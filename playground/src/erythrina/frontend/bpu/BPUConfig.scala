package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import top.Config._

object BPUParmams {
    /* ------------- Branch Target Buffer ------------- */
    var BTBSize = 64
    val TagBits = XLEN - log2Ceil(BTBSize) - 2
    val IdxBits = log2Ceil(BTBSize)
}