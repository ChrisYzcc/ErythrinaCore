package erythrina.backend

import chisel3._
import chisel3.util._
import erythrina.ErythBundle
import erythrina.frontend.{FuType, FuOpType, InstInfo}
import erythrina.backend.rob.ROBPtr
import erythrina.memblock.lsq.{LQPtr, SQPtr}

class InstrState extends Bundle {
    val fetched = Bool()
    val renamed = Bool()
    val issued  = Bool()
    val finished = Bool()
}

class InstExInfo extends ErythBundle {
    val instr   = UInt(XLEN.W)
    val pc      = UInt(XLEN.W)
    
    // Execution Unit
    val fuType  = FuType.apply()

    // Execution opcode
    val fuOpType = FuOpType.apply()

    // architecture regs
    val a_rs1 = UInt(ArchRegAddrBits.W)
    val a_rs2 = UInt(ArchRegAddrBits.W)
    val a_rd  = UInt(ArchRegAddrBits.W)

    val rs1_need_rename = Bool()
    val rs2_need_rename = Bool()
    val rd_need_rename  = Bool()

    // physical regs
    val p_rs1 = UInt(PhyRegAddrBits.W)
    val p_rs2 = UInt(PhyRegAddrBits.W)
    val p_rd  = UInt(PhyRegAddrBits.W)
    val origin_preg = UInt(PhyRegAddrBits.W)

    val src1 = UInt(XLEN.W) // src1 value
    val src2 = UInt(XLEN.W) // src2 value
    val src1_ready = Bool()
    val src2_ready = Bool()

    val imm = UInt(XLEN.W)

    val rf_wen = Bool() 

    // instruction state
    val state = new InstrState

    // result
    val res = UInt(XLEN.W)

    // idx
    val robPtr = new ROBPtr
    val lqPtr = new LQPtr
    val sqPtr = new SQPtr

    // branch prediction
    val bpu_taken = Bool()      // BPU prediction result
    val bpu_target = UInt(XLEN.W)

    val real_taken = Bool()     // real branch result
    val real_target = UInt(XLEN.W)

    def fromInstInfo(inst_info: InstInfo) = {
        this := 0.U.asTypeOf(new InstExInfo)

        this.instr := inst_info.instr
        this.pc := inst_info.pc

        this.bpu_taken := inst_info.bpu_taken
    }
}