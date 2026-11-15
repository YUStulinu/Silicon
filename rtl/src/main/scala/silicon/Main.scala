package silicon

import _root_.circt.stage.ChiselStage

/** Writes the SystemVerilog of each configuration:
  *   ooo      the out-of-order core
  *   inorder  the same hardware, issuing in program order
  *   nopred   ooo without branch prediction (every branch predicted not taken)
  *   small    ooo with half-size windows (ROB 16, issue queue 8, 48 registers)
  *   debug    ooo with printf tracing
  * By default only ooo and inorder are generated. */
object Main extends App {
  val out = if (args.nonEmpty) args(0) else "generated"
  val configs = Seq(
    "ooo" -> CoreParams(),
    "inorder" -> CoreParams(inOrder = true),
    "nopred" -> CoreParams(predictor = false),
    "small" -> CoreParams(robEntries = 16, iqEntries = 8, physRegs = 48, lqEntries = 4, sqEntries = 4, maxBranches = 4),
    "debug" -> CoreParams(debugPrints = true)
  )
  val only = if (args.length > 1) args(1).split(",").toSet else Set("ooo", "inorder")
  // custom variants of ooo, e.g. "x-rob16-lq4": rob, iq, prf, lq, sq, br followed by a number
  def custom(name: String): CoreParams = {
    var p = CoreParams()
    for (part <- name.stripPrefix("x-").split("-")) {
      val (k, v) = part.span(_.isLetter)
      val n = v.toInt
      p = k match {
        case "rob" => p.copy(robEntries = n)
        case "iq"  => p.copy(iqEntries = n)
        case "prf" => p.copy(physRegs = n)
        case "lq"  => p.copy(lqEntries = n)
        case "sq"  => p.copy(sqEntries = n)
        case "br"  => p.copy(maxBranches = n)
        case _     => throw new IllegalArgumentException(s"unknown parameter $k")
      }
    }
    p
  }
  val all = configs ++ only.filter(_.startsWith("x-")).map(n => n -> custom(n))
  for ((name, p) <- all if only(name)) {
    ChiselStage.emitSystemVerilogFile(
      new Core(p),
      args = Array("--target-dir", s"$out/$name"),
      firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info", "--lowering-options=disallowLocalVariables,disallowPackedArrays")
    )
    // firtool appends the list of black-box files after the modules
    val f = new java.io.File(s"$out/$name/Core.sv")
    val text = scala.io.Source.fromFile(f).mkString
    val cut = text.indexOf("// ----- 8< ----- FILE \"firrtl_black_box_resource_files.f\"")
    if (cut >= 0) java.nio.file.Files.writeString(f.toPath, text.substring(0, cut))
    println(s"wrote $out/$name")
  }
}
