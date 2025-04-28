import top._
object Elaborate extends App {
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _)
  )

  val is_perf = args.contains("--perf")
  val remainArgs = args.filterNot(_ == "--perf")

  if (is_perf) {
    circt.stage.ChiselStage.emitSystemVerilogFile(new PerfTop, remainArgs, firtoolOptions)
  }
  else {
    circt.stage.ChiselStage.emitSystemVerilogFile(new SimTop, remainArgs, firtoolOptions)
  }
}