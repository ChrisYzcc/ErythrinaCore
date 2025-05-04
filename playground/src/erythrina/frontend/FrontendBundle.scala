package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythBundle

class InstInfo extends ErythBundle {
    val valid   = Bool()
    val instr   = UInt(XLEN.W)
    val pc      = UInt(XLEN.W)

    val bpu_hit = Bool()      // BPU says: this is a branch instruction
    val bpu_taken = Bool()      // BPU says: let's take the branch!
    val bpu_target = UInt(XLEN.W) // BPU says: branch target address
}

class InstFetchBlock extends ErythBundle {
    val instVec = Vec(FetchWidth, new InstInfo)
    val ftqIdx = UInt(log2Ceil(FTQSize).W)

    def fromInstInfoVec(instVec: Seq[InstInfo]) = {
        require(instVec.length == FetchWidth, s"InstFetchBlock.fromInstInfoVec: expected ${FetchWidth} elements, got ${instVec.length}")
        this := 0.U.asTypeOf(new InstFetchBlock)
        this.instVec := VecInit(instVec)
    }
}
