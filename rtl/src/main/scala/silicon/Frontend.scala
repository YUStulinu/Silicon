package silicon

import chisel3._
import chisel3.util._

/** An instruction leaving fetch, with the prediction made for it. */
class FetchedInst(p: CoreParams) extends Bundle {
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val seq = UInt(32.W)
  val predTaken = Bool()
  val predTarget = UInt(64.W)
  val bhtIdx = UInt(log2Ceil(p.bhtEntries).W)
  val ghist = UInt(p.histBits.W)
  val rasPtr = UInt(log2Ceil(p.rasEntries).W)
  val rasTop = UInt(64.W)
}

/** Where fetch must continue, and the predictor state to restore. */
class Redirect(p: CoreParams) extends Bundle {
  val pc = UInt(64.W)
  val ghist = UInt(p.histBits.W)
  val restoreRas = Bool()
  val rasPtr = UInt(log2Ceil(p.rasEntries).W)
  val rasTop = UInt(64.W)
  val rasPush = Bool() // the resolved instruction was a call: push rasPushAddr
  val rasPop = Bool()
  val rasPushAddr = UInt(64.W)
}

/** Training from the branch unit. */
class PredUpdate(p: CoreParams) extends Bundle {
  val pc = UInt(64.W)
  val bhtIdx = UInt(log2Ceil(p.bhtEntries).W)
  val isCond = Bool()
  val taken = Bool()
  val isIndirect = Bool() // jalr that is not a return: train the BTB
  val target = UInt(64.W)
}

/** Fetch: up to fetchWidth instructions per cycle from one I-cache line.
  * Branches are found by predecoding the fetched words: conditional branches
  * use a gshare direction predictor, jal is always taken, returns use the
  * return-address stack, other jalr use a BTB. */
class Frontend(p: CoreParams, resetPc: BigInt) extends Module {
  val idxBits = log2Ceil(p.icacheLines)
  val io = IO(new Bundle {
    val out = Vec(p.fetchWidth, Valid(new FetchedInst(p)))
    val deq = Input(UInt(log2Ceil(p.fetchWidth + 1).W))
    val redirect = Flipped(Valid(new Redirect(p)))
    val update = Flipped(Valid(new PredUpdate(p)))
    val invalidate = Input(Bool())
    val mem = Decoupled(new MemReq(p))
    val memResp = Flipped(Valid(new MemResp(p)))
    val icacheMiss = Output(Bool())
    val fetched = Output(Vec(p.fetchWidth, Valid(new FetchedInst(p))))
  })

  // ---------------- I-cache: direct mapped
  val tags = Mem(p.icacheLines, UInt((64 - idxBits - p.offBits).W))
  val data = Mem(p.icacheLines, Vec(p.lineBytes / 4, UInt(32.W)))
  val valid = RegInit(VecInit(Seq.fill(p.icacheLines)(false.B)))

  // ---------------- predictors
  val bht = RegInit(VecInit(Seq.fill(p.bhtEntries)(1.U(2.W)))) // weakly not taken
  val btbN = 16
  val btbValid = RegInit(VecInit(Seq.fill(btbN)(false.B)))
  val btbTag = Reg(Vec(btbN, UInt(20.W)))
  val btbTarget = Reg(Vec(btbN, UInt(64.W)))
  val rasN = p.rasEntries
  val ras = Reg(Vec(rasN, UInt(64.W)))
  val rasPtr = RegInit(0.U(log2Ceil(rasN).W))
  val ghist = RegInit(0.U(p.histBits.W))

  // ---------------- fetch buffer
  val fbN = p.fetchBufferEntries
  val fb = Reg(Vec(fbN, new FetchedInst(p)))
  val fbHead = RegInit(0.U(log2Ceil(fbN).W))
  val fbTail = RegInit(0.U(log2Ceil(fbN).W))
  val fbCount = RegInit(0.U((log2Ceil(fbN) + 1).W))

  val pc = RegInit(resetPc.U(64.W))
  val seq = RegInit(0.U(32.W))

  val idx = pc(p.offBits + idxBits - 1, p.offBits)
  val tag = pc(63, p.offBits + idxBits)
  val hit = valid(idx) && tags(idx) === tag
  val line = data(idx)
  val wordOff = pc(p.offBits - 1, 2)

  // ---------------- refill
  val sIdle :: sReq :: sWait :: Nil = Enum(3)
  val state = RegInit(sIdle)
  val missAddr = Reg(UInt(64.W))
  io.mem.valid := state === sReq
  io.mem.bits.addr := missAddr
  io.mem.bits.write := false.B
  io.mem.bits.data := 0.U
  io.icacheMiss := false.B
  when(state === sIdle && !hit) {
    state := sReq
    missAddr := Cat(pc(63, p.offBits), 0.U(p.offBits.W))
    io.icacheMiss := true.B
  }
  when(state === sReq && io.mem.ready) { state := sWait }
  when(state === sWait && io.memResp.valid) {
    val i = missAddr(p.offBits + idxBits - 1, p.offBits)
    tags(i) := missAddr(63, p.offBits + idxBits)
    data(i) := io.memResp.bits.data.asTypeOf(Vec(p.lineBytes / 4, UInt(32.W)))
    valid(i) := true.B
    state := sIdle
  }
  when(io.invalidate) { valid.foreach(_ := false.B) }

  // ---------------- predecode and predict each slot
  def btbIndex(a: UInt) = a(5, 2)
  def btbTagOf(a: UInt) = a(25, 6)

  val slots = Wire(Vec(p.fetchWidth, new FetchedInst(p)))
  val slotValid = Wire(Vec(p.fetchWidth, Bool()))
  val slotTaken = Wire(Vec(p.fetchWidth, Bool()))
  val slotCond = Wire(Vec(p.fetchWidth, Bool()))
  val hists = Wire(Vec(p.fetchWidth + 1, UInt(p.histBits.W)))
  hists(0) := ghist
  val rasTop = ras(rasPtr - 1.U)
  for (s <- 0 until p.fetchWidth) {
    val spc = pc + (4 * s).U
    val inst = line(wordOff + s.U)
    val opc = inst(6, 0)
    val isBr = opc === "b1100011".U
    val isJal = opc === "b1101111".U
    val isJalr = opc === "b1100111".U
    val rd = inst(11, 7)
    val rs1 = inst(19, 15)
    val isRet = isJalr && rd === 0.U && Decoder.isLink(rs1)
    val isCall = (isJal || isJalr) && Decoder.isLink(rd)
    val bIdx = (spc(log2Ceil(p.bhtEntries) + 1, 2) ^ hists(s)).asUInt
    val dirTaken = bht(bIdx)(1)
    val bi = btbIndex(spc)
    val btbHit = btbValid(bi) && btbTag(bi) === btbTagOf(spc)
    val taken = WireDefault(false.B)
    val target = WireDefault(spc + 4.U)
    if (p.predictor) {
      when(isBr) { taken := dirTaken; target := spc + Decoder.immB(inst) }
      when(isJal) { taken := true.B; target := spc + Decoder.immJ(inst) }
      when(isJalr && isRet) { taken := true.B; target := rasTop }
      when(isJalr && !isRet && btbHit) { taken := true.B; target := btbTarget(bi) }
    }
    slotValid(s) := (if (s == 0) true.B else !slotTaken(s - 1) && (wordOff +& s.U) < (p.lineBytes / 4).U)
    slotTaken(s) := slotValid(s) && taken
    slotCond(s) := slotValid(s) && isBr
    hists(s + 1) := Mux(slotCond(s) && p.predictor.B, Cat(hists(s)(p.histBits - 2, 0), taken), hists(s))
    slots(s).pc := spc
    slots(s).inst := inst
    slots(s).seq := seq + s.U
    slots(s).predTaken := taken
    slots(s).predTarget := target
    slots(s).bhtIdx := bIdx
    slots(s).ghist := hists(s)
    slots(s).rasPtr := rasPtr
    slots(s).rasTop := rasTop
  }

  val nValid = PopCount(slotValid)
  val space = fbCount <= (fbN - p.fetchWidth).U
  val fire = hit && space && !io.redirect.valid

  // next pc, history and RAS after the packet
  val lastIdx = Wire(UInt(log2Ceil(p.fetchWidth).W))
  lastIdx := PriorityEncoder(slotTaken.asUInt | (1.U << (p.fetchWidth - 1)))
  val anyTaken = slotTaken.asUInt.orR
  val npc = Mux(anyTaken, slots(lastIdx).predTarget, pc + (nValid << 2))

  // ---------------- fetch-buffer bookkeeping
  val deq = Mux(io.redirect.valid, 0.U, io.deq)
  for (i <- 0 until p.fetchWidth) {
    io.out(i).valid := fbCount > i.U
    io.out(i).bits := fb(fbHead + i.U)
    io.fetched(i).valid := fire && slotValid(i)
    io.fetched(i).bits := slots(i)
  }
  when(io.redirect.valid) {
    fbHead := 0.U
    fbTail := 0.U
    fbCount := 0.U
  }.otherwise {
    var t = fbTail
    for (i <- 0 until p.fetchWidth) {
      when(fire && slotValid(i)) { fb(fbTail + i.U) := slots(i) }
    }
    val enq = Mux(fire, nValid, 0.U)
    fbTail := fbTail + enq
    fbHead := fbHead + deq
    fbCount := fbCount + enq - deq
  }

  when(fire) {
    pc := npc
    seq := seq + nValid
    ghist := hists(p.fetchWidth)
    // the RAS: the packet ends at a taken call or return, if any
    val last = slots(lastIdx)
    val lastInst = last.inst
    val lOpc = lastInst(6, 0)
    val lJal = lOpc === "b1101111".U
    val lJalr = lOpc === "b1100111".U
    val lCall = (lJal || lJalr) && Decoder.isLink(lastInst(11, 7))
    val lRet = lJalr && lastInst(11, 7) === 0.U && Decoder.isLink(lastInst(19, 15))
    if (p.predictor) {
      when(anyTaken && lCall) {
        ras(rasPtr) := last.pc + 4.U
        rasPtr := rasPtr + 1.U
      }.elsewhen(anyTaken && lRet) {
        rasPtr := rasPtr - 1.U
      }
    }
  }

  when(io.redirect.valid) {
    val r = io.redirect.bits
    pc := r.pc
    ghist := r.ghist
    when(r.restoreRas) {
      ras(r.rasPtr - 1.U) := r.rasTop
      when(r.rasPush) {
        ras(r.rasPtr) := r.rasPushAddr
        rasPtr := r.rasPtr + 1.U
      }.elsewhen(r.rasPop) {
        rasPtr := r.rasPtr - 1.U
      }.otherwise {
        rasPtr := r.rasPtr
      }
    }
  }

  // ---------------- training
  when(io.update.valid) {
    val u = io.update.bits
    when(u.isCond) {
      val c = bht(u.bhtIdx)
      bht(u.bhtIdx) := Mux(u.taken, Mux(c === 3.U, 3.U, c + 1.U), Mux(c === 0.U, 0.U, c - 1.U))
    }
    when(u.isIndirect) {
      val i = btbIndex(u.pc)
      btbValid(i) := true.B
      btbTag(i) := btbTagOf(u.pc)
      btbTarget(i) := u.target
    }
  }
}
