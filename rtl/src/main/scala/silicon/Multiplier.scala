package silicon

import chisel3._
import chisel3.util._

/** A pipelined 65 x 65-bit multiplier built from a Wallace tree.
  *
  * Both operands are 65-bit two's complement numbers (the core extends them
  * by one bit, signed or unsigned, so that mul, mulh, mulhsu and mulhu share
  * one array). The 66 partial products (one per bit of the multiplier, the
  * last one negated because bit 64 is the sign, plus the +1 of that
  * negation) are reduced with carry-save adders, three rows to two, in two
  * pipeline stages; the third stage adds the last two rows. Each stage
  * therefore holds a few full-adder levels, instead of the whole array that a
  * plain `*` puts in one cycle. */
object Wallace {
  val W = 130

  /** Three rows to two: sum and carry (shifted left by one). */
  def csa(a: UInt, b: UInt, c: UInt): (UInt, UInt) = {
    val s = a ^ b ^ c
    val carry = ((a & b) | (a & c) | (b & c)) << 1
    (s(W - 1, 0), carry(W - 1, 0))
  }

  /** One level of reduction: every full group of three rows becomes two. */
  def level(rows: Seq[UInt]): Seq[UInt] = {
    val groups = rows.grouped(3).toSeq
    groups.flatMap { g =>
      if (g.size == 3) { val (s, c) = csa(g(0), g(1), g(2)); Seq(s, c) }
      else g
    }
  }

  /** Reduce until at most `target` rows are left. */
  def reduce(rows: Seq[UInt], target: Int): Seq[UInt] = {
    var r = rows
    while (r.size > target) r = level(r)
    r
  }

  /** The partial products of a (65-bit signed) times b (65-bit signed). */
  def partialProducts(a: UInt, b: UInt): Seq[UInt] = {
    val aExt = Cat(Fill(W - 65, a(64)), a)
    val rows = (0 until 64).map(j => (Mux(b(j), aExt, 0.U(W.W)) << j)(W - 1, 0))
    // b(64) has weight -2^64: -(y << 64) = (~y << 64) + (1 << 64) modulo 2^130
    val neg = ((~Mux(b(64), aExt, 0.U(W.W))).asUInt << 64)(W - 1, 0)
    rows :+ neg :+ (BigInt(1) << 64).U(W.W)
  }
}
