package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import top.Config._

object BPUParmams {
    /* ------------- Branch Target Buffer ------------- */
    var BTBSize = 32
    def get_btb_idx(addr:UInt): UInt = {
        val idx = addr(log2Ceil(BTBSize), 1)
        idx
    }
}