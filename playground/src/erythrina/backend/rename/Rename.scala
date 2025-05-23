package erythrina.backend.rename

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.Redirect

class RenameModule extends ErythModule {
    val io = IO(new Bundle {
        val rename_req = Vec(RenameWidth, Flipped(ValidIO(new InstExInfo)))
        val rename_rsp = Vec(RenameWidth, ValidIO(new InstExInfo))

        // To RAT
        val rs1 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))
        val rs2 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))
        val rd = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))

        val rs1_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))
        val rs2_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))
        val rd_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))

        val wr_phy = Vec(RenameWidth, ValidIO(new Bundle {
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

        // Dispatch Ready
        val dispatch_ready = Input(Bool())
    })

    val (rs1, rs2, rd) = (io.rs1, io.rs2, io.rd)
    val (rs1_phy, rs2_phy, rd_phy) = (io.rs1_phy, io.rs2_phy, io.rd_phy)
    val wr_phy = io.wr_phy
    val (rename_req, rename_rsp) = (io.rename_req, io.rename_rsp)
    val (fl_req, fl_rsp) = (io.fl_req, io.fl_rsp)
    val bt_alloc = io.bt_alloc
    val redirect = io.redirect
    val dispatch_ready = io.dispatch_ready

    // Req to RAT
    for (i <- 0 until RenameWidth) {
        rs1(i)  := rename_req(i).bits.a_rs1
        rs2(i)  := rename_req(i).bits.a_rs2
        rd(i)   := rename_req(i).bits.a_rd
    }

    // Req to FreeList
    val new_dst = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    for (i <- 0 until RenameWidth) {
        fl_req(i).valid := rename_req(i).valid && rename_req(i).bits.rd_need_rename && !redirect.valid && dispatch_ready
        new_dst(i) := fl_rsp(i)
    }

    // origin p_reg
    val origin_p_reg = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    for (i <- 0 until RenameWidth) {
        if (i == 0) {
            origin_p_reg(i) := rd_phy(i)
        } else{
            val prev_v = rename_req.slice(0, i).map(_.valid)
            val prev_rd = rename_req.slice(0, i).map(_.bits.a_rd)
            val prev_wen = rename_req.slice(0, i).map(_.bits.rf_wen)

            val prev_has_same_rd_vec = prev_v.zip(prev_rd).zip(prev_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === rename_req(i).bits.a_rd)
            }
            val prev_has_same_rd_idx = (i - 1).U - PriorityEncoder(prev_has_same_rd_vec.reverse)
            val prev_has_same_rd = prev_has_same_rd_vec.reduce(_ || _)
            origin_p_reg(i) := Mux(prev_has_same_rd, new_dst(prev_has_same_rd_idx), rd_phy(i))
        }
    }

    // Check for WAW
    val hasWAW = Wire(Vec(RenameWidth, Bool()))
    for (i <- 0 until RenameWidth) {
        if (i == RenameWidth - 1) {
            hasWAW(i) := false.B
        } else {
            val aft_v = rename_req.slice(i + 1, RenameWidth).map(_.valid)
            val aft_rd = rename_req.slice(i + 1, RenameWidth).map(_.bits.a_rd)
            val aft_wen = rename_req.slice(i + 1, RenameWidth).map(_.bits.rf_wen)

            hasWAW(i) := aft_v.zip(aft_rd).zip(aft_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === rename_req(i).bits.a_rd)
            }.reduce(_ || _)
        }

        wr_phy(i).valid := fl_req(i).fire && !hasWAW(i) && !redirect.valid && dispatch_ready
        wr_phy(i).bits.a_reg := rd(i)
        wr_phy(i).bits.p_reg := new_dst(i)
    }

    // Check for RAW
    val final_rs1 = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    val final_rs2 = Wire(Vec(RenameWidth, UInt(PhyRegAddrBits.W)))
    for (i <- 0 until RenameWidth) {
        if (i == 0) {
            final_rs1(i) := rs1_phy(i)
            final_rs2(i) := rs2_phy(i)
        } else {
            val prev_v = rename_req.slice(0, i).map(_.valid)
            val prev_rd = rename_req.slice(0, i).map(_.bits.a_rd)
            val prev_wen = rename_req.slice(0, i).map(_.bits.rf_wen)

            val rs1_hasRAW_vec = prev_v.zip(prev_rd).zip(prev_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === rename_req(i).bits.a_rs1)
            }
            val rs2_hasRAW_vec = prev_v.zip(prev_rd).zip(prev_wen).map{
                case ((v, rd), wen) =>
                    v && wen && (rd === rename_req(i).bits.a_rs2)
            }
            
            // e.g.: vec = "011", idx = 1
            val rs1_hasRAW_idx = (i - 1).U - PriorityEncoder(rs1_hasRAW_vec.reverse)
            val rs2_hasRAW_idx = (i - 1).U - PriorityEncoder(rs2_hasRAW_vec.reverse)
            val rs1_hasRAW = rs1_hasRAW_vec.reduce(_ || _)
            val rs2_hasRAW = rs2_hasRAW_vec.reduce(_ || _)

            final_rs1(i) := Mux(rs1_hasRAW, new_dst(rs1_hasRAW_idx), rs1_phy(i))
            final_rs2(i) := Mux(rs2_hasRAW, new_dst(rs2_hasRAW_idx), rs2_phy(i))
        }
    }

    // Response
    for (i <- 0 until RenameWidth) {
        val rsp_instExInfo = WireInit(rename_req(i).bits)

        rsp_instExInfo.p_rs1 := final_rs1(i)
        rsp_instExInfo.p_rs2 := final_rs2(i)
        rsp_instExInfo.p_rd  := new_dst(i)
        rsp_instExInfo.origin_preg := origin_p_reg(i)

        rename_rsp(i).valid := (!fl_req(i).valid || fl_req(i).ready) && !redirect.valid
        rename_rsp(i).bits := rsp_instExInfo

        bt_alloc(i).valid := rename_req(i).valid && rename_req(i).bits.rf_wen && !redirect.valid && dispatch_ready
        bt_alloc(i).bits := new_dst(i)
    }
}