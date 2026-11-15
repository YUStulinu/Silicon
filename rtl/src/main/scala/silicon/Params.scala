package silicon

import chisel3._
import chisel3.util._

/** Sizes of everything, and the one switch between the out-of-order core and
  * its in-order variant (same hardware, issue restricted to program order). */
case class CoreParams(
    fetchWidth: Int = 2,
    robEntries: Int = 32,
    iqEntries: Int = 16,
    physRegs: Int = 64,
    lqEntries: Int = 8,
    sqEntries: Int = 8,
    maxBranches: Int = 8,
    fetchBufferEntries: Int = 8,
    bhtEntries: Int = 1024,
    histBits: Int = 10,
    rasEntries: Int = 8,
    icacheLines: Int = 256,
    dcacheLines: Int = 512,
    lineBytes: Int = 32,
    inOrder: Boolean = false,
    predictor: Boolean = true,
    debugPrints: Boolean = false
) {
  val xlen = 64
  val pregBits = log2Ceil(physRegs)
  val robBits = log2Ceil(robEntries)
  val iqBits = log2Ceil(iqEntries)
  val lqBits = log2Ceil(lqEntries)
  val sqBits = log2Ceil(sqEntries)
  val brBits = log2Ceil(maxBranches)
  val lineWords = lineBytes / 8
  val lineBits = lineBytes * 8
  val offBits = log2Ceil(lineBytes)
}

object Fu {
  val ALU = 0.U(3.W)
  val BRU = 1.U(3.W) // conditional branches, jal, jalr
  val MUL = 2.U(3.W)
  val DIV = 3.U(3.W)
  val LD = 4.U(3.W)
  val ST = 5.U(3.W)
  val SYS = 6.U(3.W) // CSR, mret, fence, fence.i, wfi: done at commit
  val NONE = 7.U(3.W) // ecall, ebreak, illegal: only an exception
}

object AluOp {
  val ADD = 0.U(4.W)
  val SUB = 1.U(4.W)
  val SLL = 2.U(4.W)
  val SLT = 3.U(4.W)
  val SLTU = 4.U(4.W)
  val XOR = 5.U(4.W)
  val SRL = 6.U(4.W)
  val SRA = 7.U(4.W)
  val OR = 8.U(4.W)
  val AND = 9.U(4.W)
  val COPY2 = 10.U(4.W) // lui
}

object Cause {
  val MISALIGNED_FETCH = 0
  val ILLEGAL = 2
  val BREAKPOINT = 3
  val MISALIGNED_LOAD = 4
  val MISALIGNED_STORE = 6
  val ECALL_M = 11
}

/** What a decoded instruction carries through the pipeline. */
class MicroOp(p: CoreParams) extends Bundle {
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val seq = UInt(32.W) // debug: a unique number for the pipeline viewer
  val fu = UInt(3.W)
  val aluOp = UInt(4.W)
  val word = Bool() // *W instructions: 32-bit result, sign-extended
  val src1Pc = Bool() // auipc, jal, jalr link
  val src2Imm = Bool()
  val imm = UInt(64.W)
  val rs1 = UInt(5.W)
  val rs2 = UInt(5.W)
  val rd = UInt(5.W)
  val wen = Bool()
  val useRs1 = Bool()
  val useRs2 = Bool()
  val prs1 = UInt(p.pregBits.W)
  val prs2 = UInt(p.pregBits.W)
  val pdst = UInt(p.pregBits.W)
  val stale = UInt(p.pregBits.W)
  val funct3 = UInt(3.W)
  val mulOp = UInt(2.W) // mul, mulh, mulhsu, mulhu (funct3 low bits)
  val isJal = Bool()
  val isJalr = Bool()
  val isCall = Bool()
  val isRet = Bool()
  // fetch-time prediction
  val predTaken = Bool()
  val predTarget = UInt(64.W)
  val bhtIdx = UInt(log2Ceil(p.bhtEntries).W)
  val ghist = UInt(p.histBits.W) // history before this instruction
  val rasPtr = UInt(log2Ceil(p.rasEntries).W) // RAS state before this instruction
  val rasTop = UInt(64.W)
  // bookkeeping
  val brTag = UInt(p.brBits.W)
  val brMask = UInt(p.maxBranches.W)
  val robIdx = UInt((p.robBits + 1).W)
  val lqIdx = UInt(p.lqBits.W)
  val sqIdx = UInt(p.sqBits.W)
  val exc = Bool()
  val excCause = UInt(4.W)
  val excTval = UInt(64.W)
  // system
  val isCsr = Bool()
  val isMret = Bool()
  val isFence = Bool()
  val isFenceI = Bool()
}

/** A line-sized memory transaction (both caches share the port). */
class MemReq(p: CoreParams) extends Bundle {
  val addr = UInt(64.W)
  val write = Bool()
  val data = UInt(p.lineBits.W)
}

class MemResp(p: CoreParams) extends Bundle {
  val data = UInt(p.lineBits.W)
}

/** An uncached access below 0x80000000 (the UART). */
class MmioReq extends Bundle {
  val addr = UInt(64.W)
  val write = Bool()
  val data = UInt(64.W)
  val size = UInt(2.W)
}

/** One commit-stage event, for co-simulation against the reference model. */
class CommitInfo extends Bundle {
  val valid = Bool()
  val pc = UInt(64.W)
  val inst = UInt(32.W)
  val seq = UInt(32.W)
  val rd = UInt(5.W)
  val wen = Bool()
  val wdata = UInt(64.W)
  val stSize = UInt(4.W) // bytes, 0 if no store
  val stAddr = UInt(64.W)
  val stData = UInt(64.W)
  val trap = Bool()
  val cause = UInt(64.W)
  val tval = UInt(64.W)
  val overrideRd = Bool()
}

/** Pipeline events for the viewer and the statistics. */
class DebugEvents(p: CoreParams) extends Bundle {
  val fetch = Vec(p.fetchWidth, Valid(new Bundle { val seq = UInt(32.W); val pc = UInt(64.W); val inst = UInt(32.W) }))
  val dispatch = Vec(p.fetchWidth, Valid(UInt(32.W)))
  val issue = Vec(4, Valid(UInt(32.W)))
  val complete = Vec(5, Valid(UInt(32.W)))
  val commit = Vec(p.fetchWidth, Valid(UInt(32.W)))
  val flush = Bool()
  val mispredict = Bool()
  val branchResolved = Bool()
  val condBranchResolved = Bool()
  val condMispredict = Bool()
  val icacheMiss = Bool()
  val dcacheMiss = Bool()
  val robCount = UInt((p.robBits + 1).W)
  // why slot 0 could not dispatch this cycle (one bit per reason)
  val stallRob = Bool()
  val stallIq = Bool()
  val stallLq = Bool()
  val stallSq = Bool()
  val stallRegs = Bool()
  val stallBranches = Bool()
  val fetchEmpty = Bool()
  val iqCount = UInt((p.iqBits + 1).W)
}
