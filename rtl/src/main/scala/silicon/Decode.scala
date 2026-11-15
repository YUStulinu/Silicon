package silicon

import chisel3._
import chisel3.util._

/** RV64IM + Zicsr + Zifencei decoding into a MicroOp (rename fields left for
  * the rename stage). Anything else is an illegal instruction. */
object Decoder {
  def immI(i: UInt): UInt = Cat(Fill(52, i(31)), i(31, 20))
  def immS(i: UInt): UInt = Cat(Fill(52, i(31)), i(31, 25), i(11, 7))
  def immB(i: UInt): UInt = Cat(Fill(51, i(31)), i(31), i(7), i(30, 25), i(11, 8), 0.U(1.W))
  def immU(i: UInt): UInt = Cat(Fill(32, i(31)), i(31, 12), 0.U(12.W))
  def immJ(i: UInt): UInt = Cat(Fill(43, i(31)), i(31), i(19, 12), i(20), i(30, 21), 0.U(1.W))

  def isLink(r: UInt): Bool = r === 1.U || r === 5.U

  def apply(p: CoreParams, inst: UInt, pc: UInt, u: MicroOp): Unit = {
    val opcode = inst(6, 0)
    val rd = inst(11, 7)
    val f3 = inst(14, 12)
    val rs1 = inst(19, 15)
    val rs2 = inst(24, 20)
    val f7 = inst(31, 25)

    u.pc := pc
    u.inst := inst
    u.fu := Fu.ALU
    u.aluOp := AluOp.ADD
    u.word := false.B
    u.src1Pc := false.B
    u.src2Imm := true.B
    u.imm := immI(inst)
    u.rs1 := rs1
    u.rs2 := rs2
    u.rd := rd
    u.wen := false.B
    u.useRs1 := false.B
    u.useRs2 := false.B
    u.funct3 := f3
    u.mulOp := f3(1, 0)
    u.isJal := false.B
    u.isJalr := false.B
    u.isCall := false.B
    u.isRet := false.B
    u.exc := false.B
    u.excCause := 0.U
    u.excTval := 0.U
    u.isCsr := false.B
    u.isMret := false.B
    u.isFence := false.B
    u.isFenceI := false.B

    val illegal = WireDefault(false.B)

    switch(opcode) {
      is("b0110111".U) { // lui
        u.aluOp := AluOp.COPY2; u.imm := immU(inst); u.wen := true.B
      }
      is("b0010111".U) { // auipc
        u.src1Pc := true.B; u.imm := immU(inst); u.wen := true.B
      }
      is("b1101111".U) { // jal
        u.fu := Fu.BRU; u.isJal := true.B; u.imm := immJ(inst); u.wen := true.B
        u.isCall := isLink(rd)
      }
      is("b1100111".U) { // jalr
        u.fu := Fu.BRU; u.isJalr := true.B; u.useRs1 := true.B; u.wen := true.B
        u.isCall := isLink(rd)
        u.isRet := rd === 0.U && isLink(rs1)
        illegal := f3 =/= 0.U
      }
      is("b1100011".U) { // branches
        u.fu := Fu.BRU; u.imm := immB(inst); u.useRs1 := true.B; u.useRs2 := true.B; u.src2Imm := false.B
        illegal := f3 === 2.U || f3 === 3.U
      }
      is("b0000011".U) { // loads
        u.fu := Fu.LD; u.useRs1 := true.B; u.wen := true.B
        illegal := f3 === 7.U
      }
      is("b0100011".U) { // stores
        u.fu := Fu.ST; u.imm := immS(inst); u.useRs1 := true.B; u.useRs2 := true.B
        illegal := f3 > 3.U
      }
      is("b0010011".U) { // op-imm
        u.useRs1 := true.B; u.wen := true.B
        switch(f3) {
          is(0.U) { u.aluOp := AluOp.ADD }
          is(1.U) { u.aluOp := AluOp.SLL; illegal := inst(31, 26) =/= 0.U }
          is(2.U) { u.aluOp := AluOp.SLT }
          is(3.U) { u.aluOp := AluOp.SLTU }
          is(4.U) { u.aluOp := AluOp.XOR }
          is(5.U) {
            u.aluOp := Mux(inst(30), AluOp.SRA, AluOp.SRL)
            illegal := inst(31) =/= 0.U || inst(29, 26) =/= 0.U
          }
          is(6.U) { u.aluOp := AluOp.OR }
          is(7.U) { u.aluOp := AluOp.AND }
        }
      }
      is("b0011011".U) { // op-imm-32
        u.useRs1 := true.B; u.wen := true.B; u.word := true.B
        switch(f3) {
          is(0.U) { u.aluOp := AluOp.ADD }
          is(1.U) { u.aluOp := AluOp.SLL; illegal := f7 =/= 0.U }
          is(5.U) { u.aluOp := Mux(inst(30), AluOp.SRA, AluOp.SRL); illegal := f7 =/= 0.U && f7 =/= "h20".U }
        }
        when(f3 =/= 0.U && f3 =/= 1.U && f3 =/= 5.U) { illegal := true.B }
      }
      is("b0110011".U, "b0111011".U) { // op, op-32
        val w = opcode(3)
        u.useRs1 := true.B; u.useRs2 := true.B; u.src2Imm := false.B; u.wen := true.B; u.word := w
        when(f7 === 1.U) {
          u.fu := Mux(f3(2), Fu.DIV, Fu.MUL)
          illegal := w && (f3 === 1.U || f3 === 2.U || f3 === 3.U)
        }.elsewhen(f7 === 0.U || f7 === "h20".U) {
          val alt = f7 === "h20".U
          switch(f3) {
            is(0.U) { u.aluOp := Mux(alt, AluOp.SUB, AluOp.ADD) }
            is(1.U) { u.aluOp := AluOp.SLL; illegal := alt }
            is(2.U) { u.aluOp := AluOp.SLT; illegal := alt || w }
            is(3.U) { u.aluOp := AluOp.SLTU; illegal := alt || w }
            is(4.U) { u.aluOp := AluOp.XOR; illegal := alt || w }
            is(5.U) { u.aluOp := Mux(alt, AluOp.SRA, AluOp.SRL) }
            is(6.U) { u.aluOp := AluOp.OR; illegal := alt || w }
            is(7.U) { u.aluOp := AluOp.AND; illegal := alt || w }
          }
        }.otherwise { illegal := true.B }
      }
      is("b0001111".U) { // fence, fence.i
        u.fu := Fu.SYS
        u.isFence := f3 === 0.U
        u.isFenceI := f3 === 1.U
        illegal := f3 > 1.U
      }
      is("b1110011".U) { // system
        when(f3 === 0.U) {
          u.fu := Fu.NONE
          when(inst === "h00000073".U) {
            u.exc := true.B; u.excCause := Cause.ECALL_M.U
          }.elsewhen(inst === "h00100073".U) {
            u.exc := true.B; u.excCause := Cause.BREAKPOINT.U; u.excTval := pc
          }.elsewhen(inst === "h30200073".U) {
            u.fu := Fu.SYS; u.isMret := true.B
          }.elsewhen(inst === "h10500073".U) {
            u.fu := Fu.SYS // wfi: a no-op, there are no interrupts
          }.otherwise { illegal := true.B }
        }.elsewhen(f3 === 4.U) {
          illegal := true.B
        }.otherwise {
          u.fu := Fu.SYS; u.isCsr := true.B; u.wen := true.B
          u.useRs1 := !f3(2) // csrrw/s/c read rs1; the i-forms use the zimm in rs1
        }
      }
    }
    when(opcode(1, 0) =/= 3.U) { illegal := true.B }
    when(illegal) {
      u.fu := Fu.NONE
      u.wen := false.B
      u.useRs1 := false.B
      u.useRs2 := false.B
      u.isJal := false.B
      u.isJalr := false.B
      u.isCsr := false.B
      u.isMret := false.B
      u.isFence := false.B
      u.isFenceI := false.B
      u.exc := true.B
      u.excCause := Cause.ILLEGAL.U
      u.excTval := inst
    }
    when(rd === 0.U) { u.wen := false.B }
  }
}
