package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import top.Config._

object BPUParmams {
    /* ------------- Branch Target Buffer ------------- */
    var BTBSize = 64
    val TagBits = XLEN - log2Ceil(BTBSize) - 1

    def get_btb_idx(addr:UInt): UInt = {
        val idx = addr(log2Ceil(BTBSize), 1)
        idx
    }
    
    def get_btb_tag(addr:UInt): UInt = {
        val tag = addr(XLEN - 1, log2Ceil(BTBSize) + 1)
        tag
    }
}