package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import top.Config._

object BPUParmams {
    /* ------------- Branch Target Buffer ------------- */
    var BTBSize = 64
    val TagBits = XLEN - log2Ceil(BTBSize) - 2
    val IdxBits = log2Ceil(BTBSize)

    var use_hash = false

    def get_btb_idx(addr:UInt): UInt = {
        if (use_hash) {
            get_btb_hashed_idx(addr)
        } else {
            get_btb_plain_idx(addr)
        }
    }

    def get_btb_plain_idx(addr:UInt): UInt = {
        val idx = addr(log2Ceil(BTBSize) + 1, 2)
        idx
    }

    def get_btb_hashed_idx(addr:UInt): UInt = {
        val valid_addr = addr(XLEN - 1, 2)
        val num_chunks = (XLEN - 2 + IdxBits - 1) / IdxBits

        val chunks = Wire(Vec(num_chunks, UInt(IdxBits.W)))
        for (i <- 0 until num_chunks) {
            val start = i * IdxBits
            val end = (i + 1) * IdxBits
            if (end > XLEN - 2) {
                chunks(i) := valid_addr(XLEN - 2 - 1, start)
            } else {
                chunks(i) := valid_addr(end - 1, start)
            }
        }

        chunks.reduce(_ ^ _)
    }
    
    def get_btb_tag(addr:UInt): UInt = {
        val tag = addr(XLEN - 1, log2Ceil(BTBSize) + 2)
        tag
    }
}