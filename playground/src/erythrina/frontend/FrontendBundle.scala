package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythBundle

class InstInfo extends ErythBundle {
    val valid   = Bool()
    val instr   = UInt(XLEN.W)
    val pc      = UInt(XLEN.W)

    val bpu_taken = Bool()      // BPU says: let's take the branch!
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

class InstExInfo extends ErythBundle {
    val instr   = UInt(XLEN.W)
    val pc      = UInt(XLEN.W)
    
    // Execution Unit
    val fuType  = FuType.apply()

    // Execution opcode
    val fuOpType = FuOpType.apply()

    // architecture regs
    val a_rs1 = UInt(ArchAddrBits.W)
    val a_rs2 = UInt(ArchAddrBits.W)
    val a_rd  = UInt(ArchAddrBits.W)

    val rs1_need_rename = Bool()
    val rs2_need_rename = Bool()
    val rd_need_rename  = Bool()

    // physical regs
    val p_rs1 = UInt(PhyAddrBits.W)
    val p_rs2 = UInt(PhyAddrBits.W)
    val p_rd  = UInt(PhyAddrBits.W)

    val src1 = UInt(XLEN.W) // src1 value
    val src2 = UInt(XLEN.W) // src2 value
    val src1_ready = Bool()
    val src2_ready = Bool()

    val imm = UInt(XLEN.W)

    val rf_wen = Bool() 

    // branch prediction
    val bpu_taken = Bool()      // BPU prediction result

    def fromInstInfo(inst_info: InstInfo) = {
        this := 0.U.asTypeOf(new InstExInfo)

        this.instr := inst_info.instr
        this.pc := inst_info.pc

        this.bpu_taken := inst_info.bpu_taken
    }
}