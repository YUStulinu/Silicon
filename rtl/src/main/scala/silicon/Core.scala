package silicon

import chisel3._
import chisel3.util._

class SiliconDivider extends BlackBox with HasBlackBoxResource {
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val start = Input(Bool())
    val kill = Input(Bool())
    val a = Input(UInt(64.W))
    val b = Input(UInt(64.W))
    val is_signed = Input(Bool())
    val word = Input(Bool())
    val want_rem = Input(Bool())
    val busy = Output(Bool())
    val done = Output(Bool())
    val accept = Input(Bool())
    val result = Output(UInt(64.W))
  })
  addResource("/vsrc/SiliconDivider.sv")
}

class RobEntry(p: CoreParams) extends Bundle {
  val done = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val seq = UInt(32.W)
  val rd = UInt(5.W)
  val wen = Bool()
  val pdst = UInt(p.pregBits.W)
  val stale = UInt(p.pregBits.W)
  val prs1 = UInt(p.pregBits.W)
  val fu = UInt(3.W)
  val exc = Bool()
  val cause = UInt(4.W)
  val tval = UInt(64.W)
  val isCsr = Bool()
  val isMret = Bool()
  val isFence = Bool()
  val isFenceI = Bool()
  val override_ = Bool()
  val stAddr = UInt(64.W)
  val stData = UInt(64.W)
}

/** The out-of-order core (or its in-order variant). */
class Core(p: CoreParams, resetPc: BigInt = BigInt("80000000", 16)) extends Module {
  val W = p.fetchWidth
  val io = IO(new Bundle {
    val mem = Decoupled(new MemReq(p))
    val memResp = Flipped(Valid(new MemResp(p)))
    val mmio = Decoupled(new MmioReq)
    val mmioResp = Flipped(Valid(UInt(64.W)))
    val commit = Output(Vec(W, new CommitInfo))
    val events = Output(new DebugEvents(p))
  })

  val fe = Module(new Frontend(p, resetPc))
  val lsu = Module(new Lsu(p))
  val div = Module(new SiliconDivider)

  // ======================================================================
  // State
  // ======================================================================
  val mapTable = RegInit(VecInit((0 until 32).map(_.U(p.pregBits.W))))
  val archMap = RegInit(VecInit((0 until 32).map(_.U(p.pregBits.W))))
  val flBits = p.pregBits + 1
  // a circular buffer indexed modulo a power of two, so it may be larger
  // than the number of physical registers
  val freeList = RegInit(VecInit((0 until (1 << p.pregBits)).map(i => (if (i < p.physRegs - 32) i + 32 else 0).U(p.pregBits.W))))
  val flHead = RegInit(0.U(flBits.W))
  val flTail = RegInit((p.physRegs - 32).U(flBits.W))
  val flCommitHead = RegInit(0.U(flBits.W))
  def flAt(x: UInt) = freeList(x(p.pregBits - 1, 0))
  val readyTable = RegInit(VecInit(Seq.fill(p.physRegs)(true.B)))
  val prf = Mem(p.physRegs, UInt(64.W))
  def readPrf(r: UInt): UInt = Mux(r === 0.U, 0.U, prf(r))

  // branch checkpoints
  val nB = p.maxBranches
  val cpValid = RegInit(0.U(nB.W))
  val cpMap = Reg(Vec(nB, Vec(32, UInt(p.pregBits.W))))
  val cpFlHead = Reg(Vec(nB, UInt(flBits.W)))
  val cpRobTail = Reg(Vec(nB, UInt((p.robBits + 1).W)))
  val cpLqTail = Reg(Vec(nB, UInt((p.lqBits + 1).W)))
  val cpSqTail = Reg(Vec(nB, UInt((p.sqBits + 1).W)))
  val cpMask = Reg(Vec(nB, UInt(nB.W))) // the branches older than each one

  // reorder buffer
  val rob = Reg(Vec(p.robEntries, new RobEntry(p)))
  val robHead = RegInit(0.U((p.robBits + 1).W))
  val robTail = RegInit(0.U((p.robBits + 1).W))
  def robI(x: UInt) = x(p.robBits - 1, 0)
  val robCount = robTail - robHead

  // issue queue
  class IqEntry extends Bundle {
    val valid = Bool()
    val uop = new MicroOp(p)
    val rdy1 = Bool()
    val rdy2 = Bool()
  }
  val iq = RegInit(0.U.asTypeOf(Vec(p.iqEntries, new IqEntry)))

  // CSRs
  val mstatusMie = RegInit(false.B)
  val mstatusMpie = RegInit(false.B)
  val mtvec = RegInit(0.U(64.W))
  val mepc = RegInit(0.U(64.W))
  val mcause = RegInit(0.U(64.W))
  val mtval = RegInit(0.U(64.W))
  val mscratch = RegInit(0.U(64.W))
  val mcycle = RegInit(0.U(64.W))
  val minstret = RegInit(0.U(64.W))
  mcycle := mcycle + 1.U

  // ======================================================================
  // Branch resolution from the previous cycle, and flushes from commit
  // ======================================================================
  class BrRes extends Bundle {
    val mispredict = Bool()
    val tag = UInt(p.brBits.W)
    val redirect = new Redirect(p)
    val update = new PredUpdate(p)
  }
  val brRes = RegInit(0.U.asTypeOf(Valid(new BrRes)))
  val flush = Wire(Bool()) // set by the commit stage below
  val flushPc = Wire(UInt(64.W))
  val kill = brRes.valid && brRes.bits.mispredict && !flush
  val killBit = UIntToOH(brRes.bits.tag, nB)
  val killMask = Mux(kill, killBit, 0.U(nB.W))
  val clearMask = Mux(brRes.valid && !flush, killBit, 0.U(nB.W))
  def killed(mask: UInt): Bool = (mask & killMask).orR
  def squashed(mask: UInt): Bool = flush || killed(mask)
  val youngerTags = VecInit((0 until nB).map(t => cpValid(t) && cpMask(t)(brRes.bits.tag))).asUInt

  // ======================================================================
  // Decode and rename
  // ======================================================================
  val fbOut = fe.io.out
  val dec = Wire(Vec(W, new MicroOp(p)))
  for (i <- 0 until W) {
    dec(i) := DontCare
    Decoder(p, fbOut(i).bits.inst, fbOut(i).bits.pc, dec(i))
    val f = fbOut(i).bits
    dec(i).seq := f.seq
    dec(i).predTaken := f.predTaken
    dec(i).predTarget := f.predTarget
    dec(i).bhtIdx := f.bhtIdx
    dec(i).ghist := f.ghist
    dec(i).rasPtr := f.rasPtr
    dec(i).rasTop := f.rasTop
  }
  def needIq(u: MicroOp) = u.fu === Fu.ALU || u.fu === Fu.BRU || u.fu === Fu.MUL || u.fu === Fu.DIV || u.fu === Fu.LD || u.fu === Fu.ST
  def isLoad(u: MicroOp) = u.fu === Fu.LD
  def isStore(u: MicroOp) = u.fu === Fu.ST
  def isBr(u: MicroOp) = u.fu === Fu.BRU

  val iqFreeVec = VecInit(iq.map(!_.valid))
  val iqFree = PopCount(iqFreeVec)
  val robFree = p.robEntries.U - robCount
  val flCount = flTail - flHead
  val brFree = PopCount(~cpValid)
  val lqFree = lsu.io.lqFree
  val sqFree = lsu.io.sqFree

  val n0iq = needIq(dec(0)).asUInt
  val n0ld = isLoad(dec(0)).asUInt
  val n0st = isStore(dec(0)).asUInt
  val n0pr = dec(0).wen.asUInt
  val n0br = isBr(dec(0)).asUInt
  val n1iq = needIq(dec(1)).asUInt
  val n1ld = isLoad(dec(1)).asUInt
  val n1st = isStore(dec(1)).asUInt
  val n1pr = dec(1).wen.asUInt
  val n1br = isBr(dec(1)).asUInt
  val canDispatch = !kill && !flush
  val acc0 = canDispatch && fbOut(0).valid && robFree >= 1.U && iqFree >= n0iq && lqFree >= n0ld && sqFree >= n0st &&
    flCount >= n0pr && brFree >= n0br
  val acc1 = acc0 && fbOut(1).valid && robFree >= 2.U && iqFree >= (n0iq +& n1iq) && lqFree >= (n0ld +& n1ld) &&
    sqFree >= (n0st +& n1st) && flCount >= (n0pr +& n1pr) && brFree >= (n0br +& n1br) && !(n0br.asBool && n1br.asBool)
  val accept = VecInit(acc0, acc1)
  fe.io.deq := acc0.asUInt +& acc1.asUInt

  // wakeups (filled in by the execution stages below)
  val wakeups = Wire(Vec(5, Valid(UInt(p.pregBits.W))))
  def wokenNow(r: UInt): Bool = wakeups.map(w => w.valid && w.bits === r).reduce(_ || _)

  val freeTag0 = PriorityEncoder(~cpValid)
  val ren = Wire(Vec(W, new MicroOp(p)))
  val renRdy1 = Wire(Vec(W, Bool()))
  val renRdy2 = Wire(Vec(W, Bool()))
  val maps = Wire(Vec(W + 1, Vec(32, UInt(p.pregBits.W))))
  maps(0) := mapTable
  var flPtr = flHead
  var robPtr = robTail
  var lqPtr = lsu.io.lqTail
  var sqPtr = lsu.io.sqTail
  // resolved branches leave every checkpoint mask; this must come before the
  // allocation below, which overrides it for a new checkpoint (last connect wins)
  for (t <- 0 until nB) { cpMask(t) := cpMask(t) & ~clearMask }
  var mask = cpValid & ~clearMask
  for (i <- 0 until W) {
    val u = WireDefault(dec(i))
    val m = maps(i)
    u.prs1 := Mux(dec(i).useRs1, m(dec(i).rs1), 0.U)
    u.prs2 := Mux(dec(i).useRs2, m(dec(i).rs2), 0.U)
    u.stale := m(dec(i).rd)
    u.pdst := Mux(dec(i).wen, flAt(flPtr), 0.U)
    u.robIdx := robPtr
    u.lqIdx := lqPtr(p.lqBits - 1, 0)
    u.sqIdx := sqPtr(p.sqBits - 1, 0)
    u.brMask := mask
    u.brTag := freeTag0
    val mNext = WireDefault(m)
    when(dec(i).wen) { mNext(dec(i).rd) := u.pdst }
    maps(i + 1) := mNext
    ren(i) := u
    // sources renamed by an earlier slot of this group are never ready
    val fromEarlier1 = if (i == 0) false.B else (0 until i).map(j => dec(j).wen && dec(j).rd === dec(i).rs1 && accept(j)).reduce(_ || _)
    val fromEarlier2 = if (i == 0) false.B else (0 until i).map(j => dec(j).wen && dec(j).rd === dec(i).rs2 && accept(j)).reduce(_ || _)
    renRdy1(i) := !dec(i).useRs1 || (!fromEarlier1 && (readyTable(u.prs1) || wokenNow(u.prs1)))
    renRdy2(i) := !dec(i).useRs2 || (!fromEarlier2 && (readyTable(u.prs2) || wokenNow(u.prs2)))
    // checkpoint
    when(accept(i) && isBr(dec(i))) {
      cpMap(freeTag0) := mNext
      cpFlHead(freeTag0) := flPtr + dec(i).wen.asUInt
      cpRobTail(freeTag0) := robPtr + 1.U
      cpLqTail(freeTag0) := lqPtr
      cpSqTail(freeTag0) := sqPtr
      cpMask(freeTag0) := mask
    }
    flPtr = flPtr + (accept(i) && dec(i).wen).asUInt
    robPtr = robPtr + accept(i).asUInt
    lqPtr = lqPtr + (accept(i) && isLoad(dec(i))).asUInt
    sqPtr = sqPtr + (accept(i) && isStore(dec(i))).asUInt
    mask = mask | Mux(accept(i) && isBr(dec(i)), UIntToOH(freeTag0, nB), 0.U)
  }

  // LSU allocation
  for (i <- 0 until W) {
    lsu.io.alloc(i).valid := accept(i) && (isLoad(dec(i)) || isStore(dec(i)))
    lsu.io.alloc(i).bits.isStore := isStore(dec(i))
    lsu.io.alloc(i).bits.robIdx := ren(i).robIdx
    lsu.io.alloc(i).bits.brMask := ren(i).brMask
    lsu.io.alloc(i).bits.pdst := ren(i).pdst
    lsu.io.alloc(i).bits.size := dec(i).funct3(1, 0)
    lsu.io.alloc(i).bits.signed := !dec(i).funct3(2)
    lsu.io.alloc(i).bits.seq := dec(i).seq
  }

  // ======================================================================
  // Issue
  // ======================================================================
  // An age matrix: older(i)(j) says that entry j entered the queue before
  // entry i. The oldest of a set of candidates is the one with no older
  // candidate, which takes a few gate levels instead of a chain of
  // comparisons across the queue.
  val older = Reg(Vec(p.iqEntries, Vec(p.iqEntries, Bool())))
  def oldestOf(cand: Seq[Bool]): (Bool, UInt) = {
    val n = cand.size
    val grant = (0 until n).map(i => cand(i) && !(0 until n).filter(_ != i).map(j => cand(j) && older(i)(j)).reduce(_ || _))
    (cand.reduce(_ || _), OHToUInt(grant))
  }
  val divBusy = Wire(Bool())
  val eligible = VecInit(iq.map(e => e.valid && e.rdy1 && e.rdy2 && !killed(e.uop.brMask) && !flush))
  def fuIs(e: IqEntry, fus: UInt*) = fus.map(e.uop.fu === _).reduce(_ || _)
  val c0 = iq.indices.map(i => eligible(i) && fuIs(iq(i), Fu.ALU, Fu.BRU))
  val c2 = iq.indices.map(i => eligible(i) && (fuIs(iq(i), Fu.MUL) || (fuIs(iq(i), Fu.DIV) && !divBusy)))
  val c3 = iq.indices.map(i => eligible(i) && fuIs(iq(i), Fu.LD, Fu.ST))

  val sel = Wire(Vec(4, Valid(UInt(p.iqBits.W))))
  if (!p.inOrder) {
    val (v0, i0) = oldestOf(c0)
    val c1 = iq.indices.map(i => eligible(i) && fuIs(iq(i), Fu.ALU) && !(v0 && i0 === i.U))
    val (v1, i1) = oldestOf(c1)
    val (v2, i2) = oldestOf(c2)
    val (v3, i3) = oldestOf(c3)
    sel(0).valid := v0; sel(0).bits := i0
    sel(1).valid := v1; sel(1).bits := i1
    sel(2).valid := v2; sel(2).bits := i2
    sel(3).valid := v3; sel(3).bits := i3
  } else {
    // in order: only the oldest entry may issue, and the second oldest
    // together with it if it is ready and needs another port
    val validE = iq.map(_.valid)
    val (hasO, o) = oldestOf(validE)
    val (hasS, s) = oldestOf(iq.indices.map(i => iq(i).valid && !(hasO && o === i.U)))
    def portOf(i: UInt): UInt = {
      val e = iq(i)
      MuxCase(3.U, Seq((e.uop.fu === Fu.ALU || e.uop.fu === Fu.BRU) -> 0.U, (e.uop.fu === Fu.MUL || e.uop.fu === Fu.DIV) -> 2.U))
    }
    val okO = hasO && eligible(o) &&
      !(iq(o).uop.fu === Fu.DIV && divBusy)
    val pO = portOf(o)
    val pSraw = portOf(s)
    val sIsAlu = iq(s).uop.fu === Fu.ALU
    val pS = Mux(pSraw === 0.U && pO === 0.U && sIsAlu, 1.U, pSraw)
    val okS = okO && hasS && eligible(s) && pS =/= pO && !(iq(s).uop.fu === Fu.DIV && divBusy)
    for (k <- 0 until 4) {
      sel(k).valid := (okO && pO === k.U) || (okS && pS === k.U)
      sel(k).bits := Mux(okO && pO === k.U, o, s)
    }
  }

  // ======================================================================
  // Pipeline registers: register read, execute, write-back
  // ======================================================================
  class Stage extends Bundle {
    val uop = new MicroOp(p)
    val op1 = UInt(64.W)
    val op2 = UInt(64.W)
  }
  val rr = RegInit(0.U.asTypeOf(Vec(4, Valid(new MicroOp(p)))))
  val ex = RegInit(0.U.asTypeOf(Vec(4, Valid(new Stage))))
  class WbEntry extends Bundle {
    val uop = new MicroOp(p)
    val data = UInt(64.W)
    val exc = Bool()
    val cause = UInt(4.W)
    val tval = UInt(64.W)
    val stAddr = UInt(64.W)
    val stData = UInt(64.W)
  }
  val wb = RegInit(0.U.asTypeOf(Vec(4, Valid(new WbEntry))))

  // issue -> register read
  for (k <- 0 until 4) {
    val e = iq(sel(k).bits)
    rr(k).valid := sel(k).valid
    rr(k).bits := e.uop
    rr(k).bits.brMask := e.uop.brMask & ~clearMask
    when(sel(k).valid) { iq(sel(k).bits).valid := false.B }
  }
  // speculative wakeup of single-cycle results at selection
  for (k <- 0 until 2) {
    val u = iq(sel(k).bits).uop
    wakeups(k).valid := sel(k).valid && u.wen && (u.fu === Fu.ALU || u.fu === Fu.BRU)
    wakeups(k).bits := u.pdst
  }

  // values being written this cycle, for bypassing
  def wbWrites: Seq[(Bool, UInt, UInt)] = {
    val alu = (0 until 3).map(k => (wb(k).valid && wb(k).bits.uop.wen && !wb(k).bits.exc, wb(k).bits.uop.pdst, wb(k).bits.data))
    alu :+ ((lsu.io.result.valid, lsu.io.result.bits.pdst, lsu.io.result.bits.data))
  }
  def bypass(r: UInt, base: UInt, srcs: Seq[(Bool, UInt, UInt)]): UInt =
    srcs.foldLeft(base) { case (acc, (v, t, d)) => Mux(v && t === r && r =/= 0.U, d, acc) }

  // register read -> execute
  for (k <- 0 until 4) {
    val u = rr(k).bits
    val v1 = bypass(u.prs1, readPrf(u.prs1), wbWrites)
    val v2 = bypass(u.prs2, readPrf(u.prs2), wbWrites)
    ex(k).valid := rr(k).valid && !squashed(u.brMask)
    ex(k).bits.uop := u
    ex(k).bits.uop.brMask := u.brMask & ~clearMask
    ex(k).bits.op1 := v1
    ex(k).bits.op2 := v2
  }

  // ---------------- execute: ALUs (ports 0 and 1) and the branch unit (port 0)
  def alu(u: MicroOp, a: UInt, b: UInt): UInt = {
    val shamt = Mux(u.word, b(4, 0), b(5, 0))
    val aw = Mux(u.word, Mux(u.aluOp === AluOp.SRA, Cat(Fill(32, a(31)), a(31, 0)), Cat(0.U(32.W), a(31, 0))), a)
    val r = MuxLookup(u.aluOp, 0.U(64.W))(
      Seq(
        AluOp.ADD -> (a + b),
        AluOp.SUB -> (a - b),
        AluOp.SLL -> (a << shamt)(63, 0),
        AluOp.SLT -> (a.asSInt < b.asSInt).asUInt,
        AluOp.SLTU -> (a < b).asUInt,
        AluOp.XOR -> (a ^ b),
        AluOp.SRL -> (aw >> shamt),
        AluOp.SRA -> (aw.asSInt >> shamt).asUInt,
        AluOp.OR -> (a | b),
        AluOp.AND -> (a & b),
        AluOp.COPY2 -> b
      )
    )
    Mux(u.word, Cat(Fill(32, r(31)), r(31, 0)), r)
  }
  val exBypass = (0 until 2).map(k => (wb(k).valid && wb(k).bits.uop.wen && !wb(k).bits.exc, wb(k).bits.uop.pdst, wb(k).bits.data))
  val brResNext = Wire(Valid(new BrRes))
  brResNext := 0.U.asTypeOf(brResNext)
  for (k <- 0 until 2) {
    val s = ex(k).bits
    val u = s.uop
    val a = bypass(u.prs1, s.op1, exBypass)
    val b = bypass(u.prs2, s.op2, exBypass)
    val opA = Mux(u.src1Pc, u.pc, a)
    val opB = Mux(u.src2Imm, u.imm, b)
    val res = WireDefault(alu(u, opA, opB))
    val excW = WireDefault(false.B)
    val tvalW = WireDefault(0.U(64.W))
    if (k == 0) {
      // branches and jumps
      val isBru = u.fu === Fu.BRU
      val eq = a === b
      val lt = a.asSInt < b.asSInt
      val ltu = a < b
      val cond = MuxLookup(u.funct3, false.B)(Seq(0.U -> eq, 1.U -> !eq, 4.U -> lt, 5.U -> !lt, 6.U -> ltu, 7.U -> !ltu))
      val taken = u.isJal || u.isJalr || cond
      val target = Mux(u.isJalr, (a + u.imm) & ~1.U(64.W), u.pc + u.imm)
      val nextPc = Mux(taken, target, u.pc + 4.U)
      val predNext = Mux(u.predTaken, u.predTarget, u.pc + 4.U)
      val misaligned = taken && target(1, 0) =/= 0.U
      when(isBru) {
        res := u.pc + 4.U
        excW := misaligned
        tvalW := target
      }
      when(ex(k).valid && isBru && !squashed(u.brMask)) {
        val isCond = !u.isJal && !u.isJalr
        brResNext.valid := true.B
        brResNext.bits.mispredict := nextPc =/= predNext && !misaligned
        brResNext.bits.tag := u.brTag
        val r = brResNext.bits.redirect
        r.pc := nextPc
        r.ghist := Mux(isCond, Cat(u.ghist(p.histBits - 2, 0), taken), u.ghist)
        r.restoreRas := true.B
        r.rasPtr := u.rasPtr
        r.rasTop := u.rasTop
        r.rasPush := u.isCall
        r.rasPop := u.isRet
        r.rasPushAddr := u.pc + 4.U
        val up = brResNext.bits.update
        up.pc := u.pc
        up.bhtIdx := u.bhtIdx
        up.isCond := isCond
        up.taken := taken
        up.isIndirect := u.isJalr && !u.isRet
        up.target := target
      }
    }
    wb(k).valid := ex(k).valid && !squashed(u.brMask)
    wb(k).bits.uop := u
    wb(k).bits.uop.brMask := u.brMask & ~clearMask
    wb(k).bits.data := res
    wb(k).bits.exc := excW
    wb(k).bits.cause := Cause.MISALIGNED_FETCH.U
    wb(k).bits.tval := tvalW
    wb(k).bits.stAddr := 0.U
    wb(k).bits.stData := 0.U
  }
  brRes := brResNext
  when(flush) { brRes.valid := false.B }

  // ---------------- execute: multiply and divide (port 2)
  val e2 = ex(2).bits
  val u2 = e2.uop
  val mulPipe = RegInit(0.U.asTypeOf(Vec(2, Valid(new WbEntry))))
  val isMul = ex(2).valid && u2.fu === Fu.MUL && !squashed(u2.brMask)
  val isDiv = ex(2).valid && u2.fu === Fu.DIV && !squashed(u2.brMask)
  val ma = bypass(u2.prs1, e2.op1, exBypass)
  val mb = bypass(u2.prs2, e2.op2, exBypass)
  // mul: low 64 bits; mulh: signed x signed; mulhsu: signed x unsigned; mulhu
  val aSigned = u2.mulOp === 1.U || u2.mulOp === 2.U
  val bSigned = u2.mulOp === 1.U
  val sa = Cat(aSigned && ma(63), ma)
  val sb = Cat(bSigned && mb(63), mb)
  // the Wallace tree: partial products reduced to 10 rows here, to 2 rows in
  // the next stage, and added in the stage after that
  val rows0 = Wallace.reduce(Wallace.partialProducts(sa, sb), 10)
  val mulRows0 = Reg(Vec(10, UInt(Wallace.W.W)))
  val mulRows1 = Reg(Vec(2, UInt(Wallace.W.W)))
  when(isMul) { mulRows0 := VecInit(rows0 ++ Seq.fill(10 - rows0.size)(0.U(Wallace.W.W))) }
  mulRows1 := VecInit(Wallace.reduce(mulRows0, 2))
  val prod = (mulRows1(0) + mulRows1(1))(127, 0)
  val mulIn = Wire(Valid(new WbEntry))
  mulIn.valid := isMul
  mulIn.bits := 0.U.asTypeOf(mulIn.bits)
  mulIn.bits.uop := u2
  mulIn.bits.uop.brMask := u2.brMask & ~clearMask
  mulIn.bits.data := 0.U
  for (s <- 0 until 2) {
    val src = if (s == 0) mulIn else mulPipe(s - 1)
    mulPipe(s).valid := src.valid && !squashed(src.bits.uop.brMask)
    mulPipe(s).bits := src.bits
    mulPipe(s).bits.uop.brMask := src.bits.uop.brMask & ~clearMask
  }
  // the divider and the uop it works on
  val divUop = Reg(new MicroOp(p))
  val divLive = RegInit(false.B)
  div.io.clock := clock
  div.io.reset := reset.asBool
  div.io.start := isDiv
  div.io.a := ma
  div.io.b := mb
  div.io.is_signed := !u2.funct3(0)
  div.io.word := u2.word
  div.io.want_rem := u2.funct3(1)
  val divKill = divLive && squashed(divUop.brMask)
  div.io.kill := divKill
  when(isDiv) {
    divUop := u2
    divUop.brMask := u2.brMask & ~clearMask
    divLive := true.B
  }.otherwise {
    divUop.brMask := divUop.brMask & ~clearMask
  }
  when(divKill) { divLive := false.B }
  divBusy := div.io.busy || divLive || (rr(2).valid && rr(2).bits.fu === Fu.DIV) || (ex(2).valid && u2.fu === Fu.DIV)
  val mulOut = Wire(Valid(new WbEntry))
  mulOut := mulPipe(1)
  val mu = mulPipe(1).bits.uop
  mulOut.bits.data := Mux(mu.word, Cat(Fill(32, prod(31)), prod(31, 0)), Mux(mu.mulOp === 0.U, prod(63, 0), prod(127, 64)))
  val divReady = div.io.done && divLive && !divKill
  val takeDiv = divReady && !(mulOut.valid && !squashed(mulOut.bits.uop.brMask))
  div.io.accept := takeDiv
  when(takeDiv) { divLive := false.B }
  wb(2).valid := (mulOut.valid && !squashed(mulOut.bits.uop.brMask)) || (takeDiv && !squashed(divUop.brMask))
  wb(2).bits := Mux(mulOut.valid && !squashed(mulOut.bits.uop.brMask), mulOut.bits, {
    val w = Wire(new WbEntry); w := 0.U.asTypeOf(w); w.uop := divUop; w.data := div.io.result; w
  })
  wb(2).bits.uop.brMask := Mux(mulOut.valid, mulOut.bits.uop.brMask, divUop.brMask) & ~clearMask

  // ---------------- execute: address generation (port 3)
  val e3 = ex(3).bits
  val u3 = e3.uop
  val agA = bypass(u3.prs1, e3.op1, exBypass)
  val agB = bypass(u3.prs2, e3.op2, exBypass)
  val addr = agA + u3.imm
  val size = u3.funct3(1, 0)
  val misalignedMem = MuxLookup(size, false.B)(Seq(1.U -> addr(0), 2.U -> (addr(1, 0) =/= 0.U), 3.U -> (addr(2, 0) =/= 0.U)))
  val agValid = ex(3).valid && !squashed(u3.brMask)
  lsu.io.agu.valid := agValid && !misalignedMem
  lsu.io.agu.bits.isStore := u3.fu === Fu.ST
  lsu.io.agu.bits.lqIdx := u3.lqIdx
  lsu.io.agu.bits.sqIdx := u3.sqIdx
  lsu.io.agu.bits.addr := addr
  lsu.io.agu.bits.data := agB
  wb(3).valid := agValid && (u3.fu === Fu.ST || misalignedMem)
  wb(3).bits := 0.U.asTypeOf(wb(3).bits)
  wb(3).bits.uop := u3
  wb(3).bits.uop.brMask := u3.brMask & ~clearMask
  wb(3).bits.exc := misalignedMem
  wb(3).bits.cause := Mux(u3.fu === Fu.ST, Cause.MISALIGNED_STORE.U, Cause.MISALIGNED_LOAD.U)
  wb(3).bits.tval := addr
  wb(3).bits.stAddr := addr
  wb(3).bits.stData := agB

  // ---------------- write-back
  for (k <- 0 until 3) {
    when(wb(k).valid && wb(k).bits.uop.wen && !wb(k).bits.exc) { prf(wb(k).bits.uop.pdst) := wb(k).bits.data }
  }
  when(lsu.io.result.valid) { prf(lsu.io.result.bits.pdst) := lsu.io.result.bits.data }
  // wakeups of multi-cycle results
  // a multiplication has a fixed latency: its dependents are woken two
  // cycles before its result is written, so that they reach register read
  // as the result reaches write-back (and take it from the bypass)
  val mulWake = mulPipe(0)
  wakeups(2).valid := mulWake.valid && mulWake.bits.uop.wen && !squashed(mulWake.bits.uop.brMask)
  wakeups(2).bits := mulWake.bits.uop.pdst
  // divisions are woken when they write back
  wakeups(3).valid := wb(2).valid && wb(2).bits.uop.wen && wb(2).bits.uop.fu === Fu.DIV
  wakeups(3).bits := wb(2).bits.uop.pdst
  wakeups(4).valid := lsu.io.result.valid
  wakeups(4).bits := lsu.io.result.bits.pdst

  // ROB completion
  val wbLive = (0 until 4).map(k => wb(k).valid && !squashed(wb(k).bits.uop.brMask))
  for (k <- 0 until 4) {
    when(wbLive(k)) {
      val r = rob(robI(wb(k).bits.uop.robIdx))
      r.done := true.B
      when(wb(k).bits.exc) {
        r.exc := true.B
        r.cause := wb(k).bits.cause
        r.tval := wb(k).bits.tval
      }
      if (k == 3) {
        r.stAddr := wb(k).bits.stAddr
        r.stData := wb(k).bits.stData
      }
    }
  }
  when(lsu.io.result.valid) {
    val r = rob(robI(lsu.io.result.bits.robIdx))
    r.done := true.B
    r.override_ := lsu.io.result.bits.mmio
  }

  // ======================================================================
  // Commit
  // ======================================================================
  val h0 = rob(robI(robHead))
  val h1 = rob(robI(robHead + 1.U))
  val has0 = robCount >= 1.U && h0.done
  val has1 = robCount >= 2.U && h1.done
  val trap0 = has0 && h0.exc
  val sys0 = has0 && !h0.exc && h0.fu === Fu.SYS
  val normal0 = has0 && !h0.exc && h0.fu =/= Fu.SYS
  val normal1 = normal0 && has1 && !h1.exc && h1.fu =/= Fu.SYS

  // CSR access at commit
  val csrAddr = h0.inst(31, 20)
  val csrF3 = h0.inst(14, 12)
  val csrSrc = Mux(csrF3(2), h0.inst(19, 15), readPrf(h0.prs1))
  val csrWriteReq = csrF3(1, 0) === 1.U || h0.inst(19, 15) =/= 0.U
  val mstatusVal = Cat(0.U(51.W), 3.U(2.W), 0.U(3.W), mstatusMpie, 0.U(3.W), mstatusMie, 0.U(3.W))
  val misaVal = (BigInt(2) << 62 | (1 << ('i' - 'a')) | (1 << ('m' - 'a'))).U(64.W)
  val csrTable = Seq(
    // address, value, writable, not comparable with the model
    (0x300, mstatusVal, true, true),
    (0x301, misaVal, true, true),
    (0x304, 0.U(64.W), true, false),
    (0x305, mtvec, true, false),
    (0x340, mscratch, true, false),
    (0x341, mepc, true, false),
    (0x342, mcause, true, false),
    (0x343, mtval, true, false),
    (0x344, 0.U(64.W), true, false),
    (0xb00, mcycle, true, true),
    (0xb02, minstret, true, false),
    (0xc00, mcycle, false, true),
    (0xc02, minstret, false, false),
    (0xf11, 0.U(64.W), false, false),
    (0xf12, 0.U(64.W), false, false),
    (0xf13, 0.U(64.W), false, false),
    (0xf14, 0.U(64.W), false, false)
  )
  val csrHit = csrTable.map(c => csrAddr === c._1.U)
  val csrKnown = csrHit.reduce(_ || _)
  val csrOld = Mux1H(csrHit, csrTable.map(_._2))
  val csrWritable = Mux1H(csrHit, csrTable.map(c => c._3.B))
  val csrOverride = Mux1H(csrHit, csrTable.map(c => c._4.B))
  val csrNew = MuxLookup(csrF3(1, 0), csrSrc)(Seq(2.U -> (csrOld | csrSrc), 3.U -> (csrOld & ~csrSrc)))
  val csrIllegal = h0.isCsr && (!csrKnown || (csrWriteReq && !csrWritable))

  // fence.i: drain stores, write back the data cache, then invalidate the I-cache
  val fenceIState = RegInit(0.U(2.W)) // 0 idle, 1 flushing the D-cache, 2 done
  val storesPending = lsu.io.storesPending
  val sysWait = WireDefault(false.B)
  lsu.io.cacheFlush := false.B
  fe.io.invalidate := false.B
  when(sys0 && h0.isFenceI) {
    when(fenceIState === 0.U) {
      sysWait := true.B
      when(!storesPending) { fenceIState := 1.U }
    }.elsewhen(fenceIState === 1.U) {
      sysWait := true.B
      lsu.io.cacheFlush := true.B
      when(lsu.io.cacheFlushDone) { fenceIState := 2.U }
    }.otherwise {
      fenceIState := 0.U
      fe.io.invalidate := true.B
    }
  }
  when(sys0 && h0.isFence && storesPending) { sysWait := true.B }

  val takeTrap = trap0 || (sys0 && csrIllegal)
  val sysCommit = sys0 && !csrIllegal && !sysWait
  val commit0 = normal0 || sysCommit
  val commit1 = normal1
  val sysFlush = sysCommit && (h0.isCsr || h0.isMret || h0.isFenceI)
  flush := takeTrap || sysFlush
  flushPc := Mux(takeTrap, Cat(mtvec(63, 2), 0.U(2.W)), Mux(h0.isMret, mepc, h0.pc + 4.U))

  val trapCause = Mux(trap0, h0.cause, Cause.ILLEGAL.U)
  val trapTval = Mux(trap0, h0.tval, h0.inst)
  when(takeTrap) {
    mepc := h0.pc
    mcause := trapCause
    mtval := trapTval
    mstatusMpie := mstatusMie
    mstatusMie := false.B
  }
  when(sysCommit && h0.isMret) {
    mstatusMie := mstatusMpie
    mstatusMpie := true.B
  }
  when(sysCommit && h0.isCsr && csrWriteReq) {
    switch(csrAddr) {
      is(0x300.U) { mstatusMie := csrNew(3); mstatusMpie := csrNew(7) }
      is(0x305.U) { mtvec := Cat(csrNew(63, 2), 0.U(2.W)) }
      is(0x340.U) { mscratch := csrNew }
      is(0x341.U) { mepc := Cat(csrNew(63, 2), 0.U(2.W)) }
      is(0x342.U) { mcause := csrNew }
      is(0x343.U) { mtval := csrNew }
      is(0xb00.U) { mcycle := csrNew }
      is(0xb02.U) { minstret := csrNew }
    }
  }
  when(sysCommit && h0.isCsr && h0.wen) { prf(h0.pdst) := csrOld }
  val instretInc = commit0.asUInt +& commit1.asUInt
  when(!(sysCommit && h0.isCsr && csrWriteReq && csrAddr === 0xb02.U)) { minstret := minstret + instretInc }

  // architectural state and freeing
  val archNext = WireDefault(archMap)
  when(commit0 && h0.wen) { archNext(h0.rd) := h0.pdst }
  when(commit1 && h1.wen) { archNext(h1.rd) := h1.pdst }
  archMap := archNext
  val frees = (commit0 && h0.wen).asUInt +& (commit1 && h1.wen).asUInt
  when(commit0 && h0.wen) { flAt(flTail) := h0.stale }
  when(commit1 && h1.wen) { flAt(flTail + (commit0 && h0.wen).asUInt) := h1.stale }
  flTail := flTail + frees
  val flCommitNext = flCommitHead + frees
  flCommitHead := flCommitNext
  val robHeadNext = robHead + instretInc
  robHead := robHeadNext
  lsu.io.commitLoads := (commit0 && h0.fu === Fu.LD).asUInt +& (commit1 && h1.fu === Fu.LD).asUInt
  lsu.io.commitStores := (commit0 && h0.fu === Fu.ST).asUInt +& (commit1 && h1.fu === Fu.ST).asUInt
  lsu.io.robHead := robHead

  // commit trace
  for (i <- 0 until W) {
    val h = if (i == 0) h0 else h1
    val c = io.commit(i)
    val did = if (i == 0) commit0 else commit1
    c.valid := did || (if (i == 0) takeTrap else false.B)
    c.pc := h.pc
    c.inst := h.inst
    c.seq := h.seq
    c.rd := h.rd
    c.wen := h.wen
    c.wdata := (if (i == 0) Mux(h.isCsr, csrOld, readPrf(h.pdst)) else readPrf(h.pdst))
    c.stSize := Mux(h.fu === Fu.ST, UIntToOH(h.inst(13, 12), 4), 0.U)
    c.stAddr := h.stAddr
    c.stData := h.stData
    c.trap := (if (i == 0) takeTrap else false.B)
    c.cause := trapCause
    c.tval := trapTval
    c.overrideRd := h.override_ || (if (i == 0) h.isCsr && csrOverride else false.B)
  }

  // ======================================================================
  // State updates: dispatch, wakeups, recovery
  // ======================================================================
  // dispatch into the ROB and the issue queue
  val iqSlot0 = PriorityEncoder(iqFreeVec)
  val iqSlot1 = PriorityEncoder(iqFreeVec.asUInt & ~UIntToOH(iqSlot0, p.iqEntries))
  for (i <- 0 until W) {
    val u = ren(i)
    when(accept(i)) {
      val r = rob(robI(u.robIdx))
      r.done := u.fu === Fu.SYS || u.fu === Fu.NONE || u.exc
      r.pc := u.pc
      r.inst := u.inst
      r.seq := u.seq
      r.rd := u.rd
      r.wen := u.wen
      r.pdst := u.pdst
      r.stale := u.stale
      r.prs1 := u.prs1
      r.fu := u.fu
      r.exc := u.exc
      r.cause := u.excCause
      r.tval := u.excTval
      r.isCsr := u.isCsr
      r.isMret := u.isMret
      r.isFence := u.isFence
      r.isFenceI := u.isFenceI
      r.override_ := false.B
      r.stAddr := 0.U
      r.stData := 0.U
      when(u.wen) { readyTable(u.pdst) := false.B }
    }
  }
  val iqUseFirst = needIq(dec(0)) && accept(0)
  for (i <- 0 until W) {
    val slot = if (i == 0) iqSlot0 else Mux(iqUseFirst, iqSlot1, iqSlot0)
    when(accept(i) && needIq(dec(i))) {
      // everything already in the queue (and slot 0 of this group) is older
      for (j <- 0 until p.iqEntries) {
        older(slot)(j) := iq(j).valid || (if (i == 1) iqUseFirst && iqSlot0 === j.U else false.B)
        older(j)(slot) := false.B
      }
      iq(slot).valid := true.B
      iq(slot).uop := ren(i)
      iq(slot).uop.brMask := ren(i).brMask & ~clearMask
      iq(slot).rdy1 := renRdy1(i)
      iq(slot).rdy2 := renRdy2(i)
    }
  }
  when(accept(0)) { mapTable := Mux(accept(1), maps(2), maps(1)) }
  flHead := flHead + (accept(0) && dec(0).wen).asUInt + (accept(1) && dec(1).wen).asUInt
  robTail := robTail + accept(0).asUInt + accept(1).asUInt
  when(accept.asUInt.orR && (isBr(dec(0)) && accept(0) || isBr(dec(1)) && accept(1))) {
    cpValid := (cpValid & ~clearMask) | UIntToOH(freeTag0, nB)
  }.otherwise {
    cpValid := cpValid & ~clearMask
  }

  // wakeups: the issue queue and the ready table
  for (w <- wakeups) {
    when(w.valid) { readyTable(w.bits) := true.B }
  }
  for (e <- iq) {
    when(e.valid) {
      when(wokenNow(e.uop.prs1)) { e.rdy1 := true.B }
      when(wokenNow(e.uop.prs2)) { e.rdy2 := true.B }
      e.uop.brMask := e.uop.brMask & ~clearMask
      when(killed(e.uop.brMask)) { e.valid := false.B }
    }
  }

  // misprediction recovery
  lsu.io.kill.valid := kill
  lsu.io.kill.bits.mask := killMask
  lsu.io.kill.bits.lqTail := cpLqTail(brRes.bits.tag)
  lsu.io.kill.bits.sqTail := cpSqTail(brRes.bits.tag)
  lsu.io.clearMask := clearMask
  lsu.io.flush := flush
  when(kill) {
    mapTable := cpMap(brRes.bits.tag)
    flHead := cpFlHead(brRes.bits.tag)
    robTail := cpRobTail(brRes.bits.tag)
    cpValid := cpValid & ~killBit & ~youngerTags
  }
  fe.io.update.valid := brRes.valid && !flush
  fe.io.update.bits := brRes.bits.update

  // full flush from commit
  when(flush) {
    mapTable := archNext
    flHead := flCommitNext
    robTail := robHeadNext
    cpValid := 0.U
    readyTable.foreach(_ := true.B)
    iq.foreach(_.valid := false.B)
    rr.foreach(_.valid := false.B)
    ex.foreach(_.valid := false.B)
    wb.foreach(_.valid := false.B)
    mulPipe.foreach(_.valid := false.B)
    divLive := false.B
  }
  div.io.kill := divKill || flush

  fe.io.redirect.valid := flush || kill
  fe.io.redirect.bits := brRes.bits.redirect
  when(flush) {
    fe.io.redirect.bits.pc := flushPc
    fe.io.redirect.bits.ghist := brRes.bits.redirect.ghist
    fe.io.redirect.bits.restoreRas := false.B
  }

  // ======================================================================
  // Memory: the two caches share one port; the data cache goes first
  // ======================================================================
  val memBusy = RegInit(false.B)
  val memOwnerD = Reg(Bool())
  val pickD = lsu.io.mem.valid
  io.mem.valid := !memBusy && (lsu.io.mem.valid || fe.io.mem.valid)
  io.mem.bits := Mux(pickD, lsu.io.mem.bits, fe.io.mem.bits)
  lsu.io.mem.ready := !memBusy && io.mem.ready
  fe.io.mem.ready := !memBusy && io.mem.ready && !pickD
  when(io.mem.fire) { memBusy := true.B; memOwnerD := pickD }
  when(io.memResp.valid) { memBusy := false.B }
  lsu.io.memResp.valid := io.memResp.valid && memOwnerD
  lsu.io.memResp.bits := io.memResp.bits
  fe.io.memResp.valid := io.memResp.valid && !memOwnerD
  fe.io.memResp.bits := io.memResp.bits
  if (p.debugPrints) {
    when(memBusy || io.mem.valid || lsu.io.mem.valid || fe.io.mem.valid) {
      printf(cf"mem: busy=$memBusy ownerD=$memOwnerD valid=${io.mem.valid} ready=${io.mem.ready} lsuV=${lsu.io.mem.valid} feV=${fe.io.mem.valid} resp=${io.memResp.valid} addr=${io.mem.bits.addr}%x w=${io.mem.bits.write}\n")
    }
  }
  io.mmio <> lsu.io.mmio
  lsu.io.mmioResp := io.mmioResp

  // ======================================================================
  // Events for statistics and the pipeline viewer
  // ======================================================================
  val ev = io.events
  ev.fetch := fe.io.fetched.map { f =>
    val v = Wire(Valid(new Bundle { val seq = UInt(32.W); val pc = UInt(64.W); val inst = UInt(32.W) }))
    v.valid := f.valid; v.bits.seq := f.bits.seq; v.bits.pc := f.bits.pc; v.bits.inst := f.bits.inst; v
  }
  for (i <- 0 until W) {
    ev.dispatch(i).valid := accept(i)
    ev.dispatch(i).bits := dec(i).seq
    ev.commit(i).valid := io.commit(i).valid && !io.commit(i).trap
    ev.commit(i).bits := io.commit(i).seq
  }
  for (k <- 0 until 4) {
    ev.issue(k).valid := sel(k).valid
    ev.issue(k).bits := iq(sel(k).bits).uop.seq
    ev.complete(k).valid := wbLive(k)
    ev.complete(k).bits := wb(k).bits.uop.seq
  }
  ev.complete(4).valid := lsu.io.result.valid
  ev.complete(4).bits := lsu.io.result.bits.seq
  ev.flush := flush
  ev.mispredict := kill
  ev.branchResolved := brRes.valid && !flush
  ev.condBranchResolved := brRes.valid && !flush && brRes.bits.update.isCond
  ev.condMispredict := kill && brRes.bits.update.isCond
  ev.icacheMiss := fe.io.icacheMiss
  ev.dcacheMiss := lsu.io.dcacheMiss
  ev.robCount := robCount
  val want0 = canDispatch && fbOut(0).valid
  ev.stallRob := want0 && robFree < 1.U
  ev.stallIq := want0 && iqFree < n0iq
  ev.stallLq := want0 && lqFree < n0ld
  ev.stallSq := want0 && sqFree < n0st
  ev.stallRegs := want0 && flCount < n0pr
  ev.stallBranches := want0 && brFree < n0br
  ev.fetchEmpty := canDispatch && !fbOut(0).valid
  ev.iqCount := PopCount(iq.map(_.valid))
}
