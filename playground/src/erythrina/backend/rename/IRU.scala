package erythrina.backend.rename

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.{InstExInfo, Redirect}
import erythrina.backend.rob.ROBPtr
import utils.StageConnect

/**
  * IRU Stage 1: alloc physical register, update RAT and BusyTable
  */
class IRUStage1 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo))))

        // To RAT
        val rs1 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))         // query
        val rs2 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))
        val rd = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))

        val rs1_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))       // result
        val rs2_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))
        val rd_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))

        val wr_phy = Vec(RenameWidth, ValidIO(new Bundle {                  // update
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        }))

        // To FreeList
        val fl_req = Vec(RenameWidth, DecoupledIO())
        val fl_rsp = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))

        // To BusyTable
        val bt_alloc = Vec(RenameWidth, ValidIO(UInt(PhyRegAddrBits.W)))

        // Redirect
        val redirect = Flipped(ValidIO(new Redirect))

        val out = DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo)))
    })
    
    val (in, out) = (io.in, io.out)

    val (rs1, rs2, rd) = (io.rs1, io.rs2, io.rd)
    val (rs1_phy, rs2_phy, rd_phy) = (io.rs1_phy, io.rs2_phy, io.rd_phy)
    val wr_phy = io.wr_phy
    val (fl_req, fl_rsp) = (io.fl_req, io.fl_rsp)
    val bt_alloc = io.bt_alloc
    val redirect = io.redirect

    val slot_ready = Wire(Vec(RenameWidth, Bool()))

    // Req to RAT
    for (i <- 0 until RenameWidth) {
        rs1(i)  := in.bits(i).bits.a_rs1
        rs2(i)  := in.bits(i).bits.a_rs2
        rd(i)   := in.bits(i).bits.a_rd
    }

    // Req to FreeList
    val new_pdst = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    for (i <- 0 until RenameWidth) {
        val has_req_fl = RegInit(false.B)
        when (in.fire) {
            has_req_fl := false.B
        }.elsewhen(fl_req(i).fire) {
            has_req_fl := true.B
        }

        fl_req(i).valid := in.bits(i).valid && in.bits(i).bits.rd_need_rename && !redirect.valid && !has_req_fl
        val fl_rsp_reg = RegEnable(fl_rsp(i), 0.U.asTypeOf(fl_rsp(i)), fl_req(i).fire)
        new_pdst(i) := Mux(has_req_fl, fl_rsp_reg, fl_rsp(i))

        slot_ready(i) := in.bits(i).bits.rd_need_rename && (fl_req(i).fire || has_req_fl) || !in.bits(i).bits.rd_need_rename || !in.bits(i).valid
    }

    // origin phy reg
    val old_pdst = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    for (i <- 0 until RenameWidth) {
        if (i == 0) {
            old_pdst(i) := rd_phy(i)
        } else{
            val prev_v = in.bits.slice(0, i).map(_.valid)
            val prev_rd = in.bits.slice(0, i).map(_.bits.a_rd)
            val prev_wen = in.bits.slice(0, i).map(_.bits.rf_wen)

            val prev_has_same_rd_vec = prev_v.zip(prev_rd).zip(prev_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === in.bits(i).bits.a_rd)
            }
            val prev_has_same_rd_idx = (i - 1).U - PriorityEncoder(prev_has_same_rd_vec.reverse)
            val prev_has_same_rd = prev_has_same_rd_vec.reduce(_ || _)
            old_pdst(i) := Mux(prev_has_same_rd, new_pdst(prev_has_same_rd_idx), rd_phy(i))
        }
    }

    // Check for WAW
    val hasWAW = Wire(Vec(RenameWidth, Bool()))
    for (i <- 0 until RenameWidth) {
        if (i == RenameWidth - 1) {
            hasWAW(i) := false.B
        } else {
            val aft_v = in.bits.slice(i + 1, RenameWidth).map(_.valid)
            val aft_rd = in.bits.slice(i + 1, RenameWidth).map(_.bits.a_rd)
            val aft_wen = in.bits.slice(i + 1, RenameWidth).map(_.bits.rf_wen)

            hasWAW(i) := aft_v.zip(aft_rd).zip(aft_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === in.bits(i).bits.a_rd)
            }.reduce(_ || _)
        }

        wr_phy(i).valid := in.bits(i).valid && in.bits(i).bits.rd_need_rename && !hasWAW(i) && !redirect.valid && out.fire
        wr_phy(i).bits.a_reg := in.bits(i).bits.a_rd
        wr_phy(i).bits.p_reg := new_pdst(i)
    }

    // Check for RAW
    val final_rs1 = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    val final_rs2 = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    for (i <- 0 until RenameWidth) {
        if (i == 0) {
            final_rs1(i) := rs1_phy(i)
            final_rs2(i) := rs2_phy(i)
        } else {
            val prev_v = in.bits.slice(0, i).map(_.valid)
            val prev_rd = in.bits.slice(0, i).map(_.bits.a_rd)
            val prev_wen = in.bits.slice(0, i).map(_.bits.rf_wen)

            val rs1_hasRAW_vec = prev_v.zip(prev_rd).zip(prev_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === in.bits(i).bits.a_rs1)
            }
            val rs1_hasRAW_idx = (i - 1).U - PriorityEncoder(rs1_hasRAW_vec.reverse)
            val rs1_hasRAW = rs1_hasRAW_vec.reduce(_ || _)

            val rs2_hasRAW_vec = prev_v.zip(prev_rd).zip(prev_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === in.bits(i).bits.a_rs2)
            }
            val rs2_hasRAW_idx = (i - 1).U - PriorityEncoder(rs2_hasRAW_vec.reverse)
            val rs2_hasRAW = rs2_hasRAW_vec.reduce(_ || _)

            final_rs1(i) := Mux(rs1_hasRAW, new_pdst(rs1_hasRAW_idx), rs1_phy(i))
            final_rs2(i) := Mux(rs2_hasRAW, new_pdst(rs2_hasRAW_idx), rs2_phy(i))
        }
    }

    // BusyTable
    for (i <- 0 until RenameWidth) {
        bt_alloc(i).valid := in.bits(i).valid && in.bits(i).bits.rf_wen && !redirect.valid && out.fire
        bt_alloc(i).bits := new_pdst(i)
    }

    val out_task = WireInit(in.bits)
    for (i <- 0 until RenameWidth) {
        out_task(i).bits.p_rs1 := final_rs1(i)
        out_task(i).bits.p_rs2 := final_rs2(i)
        out_task(i).bits.p_rd := new_pdst(i)
        out_task(i).bits.origin_preg := old_pdst(i)
    }

    out.valid := slot_ready.reduce(_ && _) && !redirect.valid
    out.bits := out_task

    in.ready := out.valid && out.ready || redirect.valid
}

/**
  * IRU Stage 2: alloc ROB entry
  */

class IRUStage2 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo))))

        // To ROB
        val rob_alloc_req = Vec(RenameWidth, DecoupledIO(new InstExInfo))
        val rob_alloc_rsp = Vec(RenameWidth, Input(new ROBPtr))

        // Redirect
        val redirect = Flipped(ValidIO(new Redirect))

        val out = DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo)))
    })

    val (in, out) = (io.in, io.out)
    val (rob_alloc_req, rob_alloc_rsp) = (io.rob_alloc_req, io.rob_alloc_rsp)
    val redirect = io.redirect

    val slot_ready = Wire(Vec(RenameWidth, Bool()))

    // Req To ROB
    val rob_ptr_vec = Wire(Vec(RenameWidth, new ROBPtr))
    for (i <- 0 until RenameWidth) {
        val has_req_rob = RegInit(false.B)
        when (in.fire) {
            has_req_rob := false.B
        }.elsewhen(rob_alloc_req(i).fire) {
            has_req_rob := true.B
        }
        rob_alloc_req(i).valid := in.bits(i).valid && !redirect.valid && !has_req_rob
        rob_alloc_req(i).bits := in.bits(i).bits
        rob_ptr_vec(i) := Mux(has_req_rob, rob_alloc_rsp(i), rob_alloc_rsp(i))

        slot_ready(i) := rob_alloc_req(i).fire || has_req_rob || !in.bits(i).valid
    }

    val out_task = WireInit(in.bits)
    for (i <- 0 until RenameWidth) {
        out_task(i).bits.robPtr := rob_ptr_vec(i)
    }

    out.valid := slot_ready.reduce(_ && _) && !redirect.valid
    out.bits := out_task

    in.ready := out.valid && out.ready || redirect.valid
}

class IRU extends ErythModule {
    val io = IO(new Bundle {
        val rename_req = Flipped(DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo))))

        // To RAT
        val rs1 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))         // query
        val rs2 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))
        val rd = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))

        val rs1_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))       // result
        val rs2_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))
        val rd_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))

        val wr_phy = Vec(RenameWidth, ValidIO(new Bundle {                  // update
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        }))

        // To FreeList
        val fl_req = Vec(RenameWidth, DecoupledIO())
        val fl_rsp = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))

        // To BusyTable
        val bt_alloc = Vec(RenameWidth, ValidIO(UInt(PhyRegAddrBits.W)))

        // To ROB
        val rob_alloc_req = Vec(RenameWidth, DecoupledIO(new InstExInfo))
        val rob_alloc_rsp = Vec(RenameWidth, Input(new ROBPtr))

        // Redirect
        val redirect = Flipped(ValidIO(new Redirect))

        // To DispatchPool
        val enq_req = DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo)))
    })

    val stage1 = Module(new IRUStage1)
    val stage2 = Module(new IRUStage2)

    StageConnect(io.rename_req, stage1.io.in)
    StageConnect(stage1.io.out, stage2.io.in)

    stage1.rs1 <> io.rs1
    stage1.rs2 <> io.rs2
    stage1.rd <> io.rd
    stage1.rs1_phy <> io.rs1_phy
    stage1.rs2_phy <> io.rs2_phy
    stage1.rd_phy <> io.rd_phy
    stage1.wr_phy <> io.wr_phy
    stage1.fl_req <> io.fl_req
    stage1.fl_rsp <> io.fl_rsp
    stage1.bt_alloc <> io.bt_alloc
    stage1.redirect <> io.redirect

    stage2.rob_alloc_req <> io.rob_alloc_req
    stage2.rob_alloc_rsp <> io.rob_alloc_rsp
    stage2.redirect <> io.redirect
    stage2.out <> io.enq_req
    
}