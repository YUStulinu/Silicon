package silicon

import chisel3._
import chisel3.util._

/** The load/store unit: a load queue, a store queue and a blocking,
  * write-back, write-allocate data cache.
  *
  * Stores write the cache only after they commit, in order, from the head of
  * the store queue. A load executes once every older store has its address;
  * it takes its data from the youngest older store that overlaps it if that
  * store covers all its bytes, waits if the overlap is partial, and otherwise
  * reads the cache. Loads from devices (below 0x80000000) wait until they are
  * the oldest instruction and no store is pending, since they cannot be
  * undone. */
class Lsu(p: CoreParams) extends Module {
  val idxBits = log2Ceil(p.dcacheLines)
  val io = IO(new Bundle {
    // allocation, in program order, at dispatch
    val alloc = Input(Vec(p.fetchWidth, Valid(new Bundle {
      val isStore = Bool()
      val robIdx = UInt((p.robBits + 1).W)
      val brMask = UInt(p.maxBranches.W)
      val pdst = UInt(p.pregBits.W)
      val size = UInt(2.W)
      val signed = Bool()
      val seq = UInt(32.W)
    })))
    val lqTail = Output(UInt((p.lqBits + 1).W))
    val sqTail = Output(UInt((p.sqBits + 1).W))
    val lqFree = Output(UInt((p.lqBits + 1).W))
    val sqFree = Output(UInt((p.sqBits + 1).W))
    // address (and store data) from the address generation stage
    val agu = Input(Valid(new Bundle {
      val isStore = Bool()
      val lqIdx = UInt(p.lqBits.W)
      val sqIdx = UInt(p.sqBits.W)
      val addr = UInt(64.W)
      val data = UInt(64.W)
    }))
    // load results
    val result = Output(Valid(new Bundle {
      val pdst = UInt(p.pregBits.W)
      val data = UInt(64.W)
      val robIdx = UInt((p.robBits + 1).W)
      val seq = UInt(32.W)
      val mmio = Bool()
    }))
    val robHead = Input(UInt((p.robBits + 1).W))
    val commitLoads = Input(UInt(2.W))
    val commitStores = Input(UInt(2.W))
    // recovery
    val kill = Input(Valid(new Bundle {
      val mask = UInt(p.maxBranches.W)
      val lqTail = UInt((p.lqBits + 1).W)
      val sqTail = UInt((p.sqBits + 1).W)
    }))
    val clearMask = Input(UInt(p.maxBranches.W))
    val flush = Input(Bool())
    val storesPending = Output(Bool())
    val cacheFlush = Input(Bool()) // fence.i: write back every dirty line
    val cacheFlushDone = Output(Bool())
    // memory
    val mem = Decoupled(new MemReq(p))
    val memResp = Flipped(Valid(new MemResp(p)))
    val mmio = Decoupled(new MmioReq)
    val mmioResp = Flipped(Valid(UInt(64.W)))
    val dcacheMiss = Output(Bool())
  })

  val nL = p.lqEntries
  val nS = p.sqEntries

  // ---------------- queues
  class LqEntry extends Bundle {
    val addrValid = Bool()
    val addr = UInt(64.W)
    val size = UInt(2.W)
    val signed = Bool()
    val pdst = UInt(p.pregBits.W)
    val robIdx = UInt((p.robBits + 1).W)
    val brMask = UInt(p.maxBranches.W)
    val done = Bool()
    val sqTail = UInt((p.sqBits + 1).W) // stores before this position are older
    val seq = UInt(32.W)
  }
  class SqEntry extends Bundle {
    val addrValid = Bool()
    val addr = UInt(64.W)
    val data = UInt(64.W)
    val size = UInt(2.W)
    val committed = Bool()
    val brMask = UInt(p.maxBranches.W)
  }
  val lq = Reg(Vec(nL, new LqEntry))
  val sq = Reg(Vec(nS, new SqEntry))
  val lqHead = RegInit(0.U((p.lqBits + 1).W))
  val lqTailR = RegInit(0.U((p.lqBits + 1).W))
  val sqHead = RegInit(0.U((p.sqBits + 1).W))
  val sqTailR = RegInit(0.U((p.sqBits + 1).W))
  val sqCommitTail = RegInit(0.U((p.sqBits + 1).W)) // first store that has not committed
  def lqI(x: UInt) = x(p.lqBits - 1, 0)
  def sqI(x: UInt) = x(p.sqBits - 1, 0)
  val lqCount = lqTailR - lqHead
  val sqCount = sqTailR - sqHead
  io.lqTail := lqTailR
  io.sqTail := sqTailR
  io.lqFree := nL.U - lqCount
  io.sqFree := nS.U - sqCount
  // committed stores still to be written (stores younger than the
  // instruction asking cannot have committed yet, so they do not count)
  val committedPending = sqHead =/= sqCommitTail
  io.storesPending := committedPending

  def inLq(i: Int): Bool = (i.U(p.lqBits.W) - lqI(lqHead)) < lqCount(p.lqBits, 0)
  def inSq(i: Int): Bool = (i.U(p.sqBits.W) - sqI(sqHead)) < sqCount(p.sqBits, 0)

  // ---------------- data cache
  val tags = Mem(p.dcacheLines, UInt((64 - idxBits - p.offBits).W))
  val lines = Mem(p.dcacheLines, Vec(p.lineWords, UInt(64.W)))
  val valid = RegInit(VecInit(Seq.fill(p.dcacheLines)(false.B)))
  val dirty = RegInit(VecInit(Seq.fill(p.dcacheLines)(false.B)))
  def lineIdx(a: UInt) = a(p.offBits + idxBits - 1, p.offBits)
  def lineTag(a: UInt) = a(63, p.offBits + idxBits)
  def hitAt(a: UInt) = valid(lineIdx(a)) && tags(lineIdx(a)) === lineTag(a)
  def isMmio(a: UInt) = a < "h80000000".U

  def byteMask(addr: UInt, size: UInt): UInt = {
    val m = MuxLookup(size, 0xff.U(8.W))(Seq(0.U -> 1.U(8.W), 1.U -> 3.U(8.W), 2.U -> 0xf.U(8.W)))
    (m << addr(2, 0))(7, 0)
  }

  // ---------------- choose a load to execute
  val sIdle :: sWbReq :: sWbWait :: sRdReq :: sRdWait :: sFlush :: sFlushWb :: sFlushWait :: Nil = Enum(8)
  val state = RegInit(sIdle)
  val refilling = state === sWbReq || state === sWbWait || state === sRdReq || state === sRdWait
  val older = Wire(Vec(nL, Vec(nS, Bool())))
  val ready = Wire(Vec(nL, Bool()))
  val fwd = Wire(Vec(nL, Bool()))
  val fwdData = Wire(Vec(nL, UInt(64.W)))
  for (l <- 0 until nL) {
    val e = lq(l)
    val lmask = byteMask(e.addr, e.size)
    val olderCount = e.sqTail - sqHead // stores [sqHead, sqTail) are older
    val unknown = Wire(Vec(nS, Bool()))
    val overlap = Wire(Vec(nS, Bool()))
    for (s <- 0 until nS) {
      older(l)(s) := inSq(s) && ((s.U(p.sqBits.W) - sqI(sqHead)) < olderCount(p.sqBits, 0)) && olderCount =/= 0.U
      unknown(s) := older(l)(s) && !sq(s).addrValid
      overlap(s) := older(l)(s) && sq(s).addrValid && sq(s).addr(63, 3) === e.addr(63, 3) &&
        (byteMask(sq(s).addr, sq(s).size) & lmask) =/= 0.U
    }
    // the youngest overlapping older store: the last one counting from the
    // head (rotate the mask so the head is bit 0, then find the top bit)
    val ov = overlap.asUInt
    val rot = (Cat(ov, ov) >> sqI(sqHead))(nS - 1, 0)
    val best = (sqI(sqHead) + (nS - 1).U - PriorityEncoder(Reverse(rot)))(p.sqBits - 1, 0)
    val st = sq(best)
    val covers = (byteMask(st.addr, st.size) & lmask) === lmask
    val anyOverlap = overlap.asUInt.orR
    val mmioOk = !isMmio(e.addr) || (e.robIdx === io.robHead && !committedPending)
    // while a line is being refilled only loads that hit can proceed
    val servable = !refilling || anyOverlap || isMmio(e.addr) || hitAt(e.addr)
    ready(l) := inLq(l) && e.addrValid && !e.done && !unknown.asUInt.orR && (!anyOverlap || covers) && mmioOk && servable
    fwd(l) := anyOverlap
    fwdData(l) := st.data << (st.addr(2, 0) << 3)
  }
  // the oldest ready load: rotate the mask so the head is bit 0
  val rdy = ready.asUInt
  val lrot = (Cat(rdy, rdy) >> lqI(lqHead))(nL - 1, 0)
  val pick = WireDefault((lqI(lqHead) + PriorityEncoder(lrot))(p.lqBits - 1, 0))
  val picked = WireDefault(rdy.orR)
  val le = lq(pick)

  // ---------------- refill / write-back state machine
  val missAddr = Reg(UInt(64.W))
  val flushIdx = Reg(UInt((idxBits + 1).W))
  val mmioBusy = RegInit(false.B)
  val mmioPending = Reg(UInt(p.lqBits.W))
  val mmioIsLoad = Reg(Bool())
  val mmioLoadLive = RegInit(false.B) // the pending device load has not been squashed

  val loadMmio = isMmio(le.addr)
  val loadHit = hitAt(le.addr)
  val cacheIdle = state === sIdle
  // hits are served while a miss is being refilled (hit under miss); the line
  // being replaced still holds the data of its old tag until the refill ends
  val doLoad = picked && !mmioBusy && Mux(fwd(pick), true.B, !loadMmio && loadHit && (cacheIdle || refilling))
  val loadMiss = picked && !mmioBusy && !fwd(pick) && !loadMmio && !loadHit && cacheIdle
  val startMmioLoad = picked && !mmioBusy && !fwd(pick) && loadMmio && io.mmio.ready

  // the result, registered (the write-back stage)
  val res = RegInit(0.U.asTypeOf(Valid(new Bundle {
    val pdst = UInt(p.pregBits.W)
    val data = UInt(64.W)
    val robIdx = UInt((p.robBits + 1).W)
    val seq = UInt(32.W)
    val mmio = Bool()
    val brMask = UInt(p.maxBranches.W)
  })))
  def extend(raw: UInt, size: UInt, signed: Bool): UInt = MuxLookup(size, raw)(
    Seq(
      0.U -> Cat(Fill(56, signed && raw(7)), raw(7, 0)),
      1.U -> Cat(Fill(48, signed && raw(15)), raw(15, 0)),
      2.U -> Cat(Fill(32, signed && raw(31)), raw(31, 0))
    )
  )
  val cacheWord = lines(lineIdx(le.addr))(le.addr(p.offBits - 1, 3))
  val raw = Mux(fwd(pick), fwdData(pick), cacheWord) >> (le.addr(2, 0) << 3)
  def squashed(mask: UInt) = io.flush || (io.kill.valid && (mask & io.kill.bits.mask) =/= 0.U)
  res.valid := false.B
  when(doLoad && !squashed(le.brMask)) {
    res.valid := true.B
    res.bits.pdst := le.pdst
    res.bits.data := extend(raw(63, 0), le.size, le.signed)
    res.bits.robIdx := le.robIdx
    res.bits.seq := le.seq
    res.bits.mmio := false.B
    res.bits.brMask := le.brMask & ~io.clearMask
  }
  when(doLoad) { lq(pick).done := true.B }
  io.dcacheMiss := false.B
  when(loadMiss) {
    state := Mux(valid(lineIdx(le.addr)) && dirty(lineIdx(le.addr)), sWbReq, sRdReq)
    missAddr := Cat(le.addr(63, p.offBits), 0.U(p.offBits.W))
    io.dcacheMiss := true.B
  }

  // ---------------- MMIO (one access at a time)
  val head = sq(sqI(sqHead))
  val headCommitted = sqCount =/= 0.U && head.committed
  val storeMmio = headCommitted && isMmio(head.addr) && !mmioBusy && !startMmioLoad
  io.mmio.valid := startMmioLoad || storeMmio
  io.mmio.bits.addr := Mux(startMmioLoad, le.addr, head.addr)
  io.mmio.bits.write := !startMmioLoad
  io.mmio.bits.data := head.data
  io.mmio.bits.size := Mux(startMmioLoad, le.size, head.size)
  when(io.mmio.fire) {
    mmioBusy := true.B
    mmioIsLoad := startMmioLoad
    mmioPending := pick
    mmioLoadLive := startMmioLoad
  }
  when(mmioBusy && mmioLoadLive && squashed(lq(mmioPending).brMask)) { mmioLoadLive := false.B }
  when(mmioBusy && io.mmioResp.valid) {
    mmioBusy := false.B
    when(mmioIsLoad && mmioLoadLive && !squashed(lq(mmioPending).brMask)) {
      val e = lq(mmioPending)
      res.valid := true.B
      res.bits.pdst := e.pdst
      res.bits.data := extend(io.mmioResp.bits >> (e.addr(2, 0) << 3), e.size, e.signed)
      res.bits.robIdx := e.robIdx
      res.bits.seq := e.seq
      res.bits.mmio := true.B
      res.bits.brMask := e.brMask & ~io.clearMask
      lq(mmioPending).done := true.B
    }
  }
  val mmioStoreDone = mmioBusy && io.mmioResp.valid && !mmioIsLoad

  // ---------------- store drain
  val storeHit = headCommitted && !isMmio(head.addr) && hitAt(head.addr)
  val doStore = storeHit && cacheIdle && !loadMiss
  val storeMiss = headCommitted && !isMmio(head.addr) && !hitAt(head.addr) && cacheIdle && !loadMiss
  when(doStore) {
    val i = lineIdx(head.addr)
    val w = head.addr(p.offBits - 1, 3)
    val old = lines(i)(w)
    val m = byteMask(head.addr, head.size)
    val bits = VecInit((0 until 8).map(b => Fill(8, m(b)))).asUInt
    val shifted = head.data << (head.addr(2, 0) << 3)
    val nl = Wire(Vec(p.lineWords, UInt(64.W)))
    nl := lines(i)
    nl(w) := (old & ~bits) | (shifted(63, 0) & bits)
    lines(i) := nl
    dirty(i) := true.B
  }
  when(storeMiss) {
    state := Mux(valid(lineIdx(head.addr)) && dirty(lineIdx(head.addr)), sWbReq, sRdReq)
    missAddr := Cat(head.addr(63, p.offBits), 0.U(p.offBits.W))
    io.dcacheMiss := true.B
  }
  val storeDone = doStore || mmioStoreDone

  // ---------------- memory port
  val victimIdx = lineIdx(missAddr)
  val victimAddr = Cat(tags(victimIdx), victimIdx, 0.U(p.offBits.W))
  val flushI = flushIdx(idxBits - 1, 0)
  io.mem.valid := state === sWbReq || state === sRdReq || state === sFlushWb
  io.mem.bits.write := state === sWbReq || state === sFlushWb
  io.mem.bits.addr := MuxCase(missAddr, Seq(
    (state === sWbReq) -> victimAddr,
    (state === sFlushWb) -> Cat(tags(flushI), flushI, 0.U(p.offBits.W))
  ))
  io.mem.bits.data := Mux(state === sFlushWb, lines(flushI).asUInt, lines(victimIdx).asUInt)
  io.cacheFlushDone := false.B
  switch(state) {
    is(sWbReq) { when(io.mem.ready) { state := sWbWait } }
    is(sWbWait) { when(io.memResp.valid) { state := sRdReq } }
    is(sRdReq) { when(io.mem.ready) { state := sRdWait } }
    is(sRdWait) {
      when(io.memResp.valid) {
        lines(victimIdx) := io.memResp.bits.data.asTypeOf(Vec(p.lineWords, UInt(64.W)))
        tags(victimIdx) := lineTag(missAddr)
        valid(victimIdx) := true.B
        dirty(victimIdx) := false.B
        state := sIdle
      }
    }
    is(sFlush) {
      when(flushIdx === p.dcacheLines.U) {
        io.cacheFlushDone := true.B
        state := sIdle
      }.elsewhen(valid(flushI) && dirty(flushI)) {
        state := sFlushWb
      }.otherwise { flushIdx := flushIdx + 1.U }
    }
    is(sFlushWb) { when(io.mem.ready) { state := sFlushWait } }
    is(sFlushWait) {
      when(io.memResp.valid) {
        dirty(flushI) := false.B
        flushIdx := flushIdx + 1.U
        state := sFlush
      }
    }
  }
  when(io.cacheFlush && state === sIdle) {
    state := sFlush
    flushIdx := 0.U
  }

  // ---------------- address generation results
  when(io.agu.valid) {
    when(io.agu.bits.isStore) {
      val e = sq(io.agu.bits.sqIdx)
      e.addrValid := true.B
      e.addr := io.agu.bits.addr
      e.data := io.agu.bits.data
    }.otherwise {
      val e = lq(io.agu.bits.lqIdx)
      e.addrValid := true.B
      e.addr := io.agu.bits.addr
    }
  }

  // ---------------- masks
  for (l <- 0 until nL) { lq(l).brMask := lq(l).brMask & ~io.clearMask }
  for (s <- 0 until nS) { sq(s).brMask := sq(s).brMask & ~io.clearMask }

  // ---------------- allocation, commit, recovery
  var lt = lqTailR
  var st = sqTailR
  for (i <- 0 until p.fetchWidth) {
    val a = io.alloc(i)
    when(a.valid && !a.bits.isStore) {
      val e = lq(lqI(lt))
      e.addrValid := false.B
      e.done := false.B
      e.size := a.bits.size
      e.signed := a.bits.signed
      e.pdst := a.bits.pdst
      e.robIdx := a.bits.robIdx
      e.brMask := a.bits.brMask & ~io.clearMask
      e.seq := a.bits.seq
      e.sqTail := st
    }
    when(a.valid && a.bits.isStore) {
      val e = sq(sqI(st))
      e.addrValid := false.B
      e.committed := false.B
      e.size := a.bits.size
      e.brMask := a.bits.brMask & ~io.clearMask
    }
    lt = lt + (a.valid && !a.bits.isStore).asUInt
    st = st + (a.valid && a.bits.isStore).asUInt
  }

  // stores are marked committed in order
  for (i <- 0 until 2) {
    when(io.commitStores > i.U) { sq(sqI(sqCommitTail + i.U)).committed := true.B }
  }
  val newCommitTail = sqCommitTail + io.commitStores
  sqCommitTail := newCommitTail
  lqHead := lqHead + io.commitLoads
  val newSqHead = sqHead + storeDone.asUInt
  sqHead := newSqHead

  when(io.flush) {
    // everything not committed disappears; committed stores still drain
    lqTailR := lqHead + io.commitLoads
    sqTailR := newCommitTail
  }.elsewhen(io.kill.valid) {
    lqTailR := io.kill.bits.lqTail
    sqTailR := io.kill.bits.sqTail
  }.otherwise {
    lqTailR := lt
    sqTailR := st
  }

  if (p.debugPrints) {
    when(sqCount =/= 0.U || state =/= sIdle) {
      printf(cf"lsu: sq $sqHead..$sqTailR commit=$sqCommitTail state=$state headC=$headCommitted addr=${head.addr}%x hit=${hitAt(head.addr)} doStore=$doStore storeMiss=$storeMiss loadMiss=$loadMiss picked=$picked mmioBusy=$mmioBusy\n")
    }
  }
  io.result.valid := res.valid && !io.flush && !(io.kill.valid && (res.bits.brMask & io.kill.bits.mask) =/= 0.U)
  io.result.bits.pdst := res.bits.pdst
  io.result.bits.data := res.bits.data
  io.result.bits.robIdx := res.bits.robIdx
  io.result.bits.seq := res.bits.seq
  io.result.bits.mmio := res.bits.mmio
}
