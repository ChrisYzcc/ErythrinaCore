/**
  * Rename and Dispatch Unit
  * 2 Stages: 1 for rename, 2 for dispatch
  */

package erythrina.backend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.rob.ROBPtr
import erythrina.memblock.lsq.{LQPtr, SQPtr}
import erythrina.backend.rename.RenameModule
import erythrina.backend.dispatch.DispatchModule
import erythrina.backend.issue.BypassInfo

class RDU extends ErythModule {
    val io = IO(new Bundle {
        // from FrontEnd
        val req = Vec(RenameWidth, Flipped(DecoupledIO(new InstExInfo)))

        // redirect
        val redirect = Flipped(ValidIO(new Redirect))

        /* --------------- Rename Module ---------------- */
        // to RAT
        val rat_rs1 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))
        val rat_rs2 = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))
        val rat_rd = Vec(RenameWidth, Output(UInt(ArchRegAddrBits.W)))

        val rat_rs1_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))
        val rat_rs2_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))
        val rat_rd_phy = Vec(RenameWidth, Input(UInt(PhyRegAddrBits.W)))

        val rat_wr_phy = Vec(RenameWidth, ValidIO(new Bundle {
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        }))

        // To FreeList
        val fl_req = Vec(RenameWidth, DecoupledIO())    // get new phy reg
        val fl_rsp = Vec(RenameWidth, Flipped(UInt(PhyRegAddrBits.W)))

        // To BusyTable
        val bt_alloc = Vec(DispatchWidth, ValidIO(UInt(PhyRegAddrBits.W)))

        /* --------------- Dispatch Module ---------------- */
        // to ROB
        val rob_alloc_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))
        val rob_alloc_rsp = Vec(DispatchWidth, Input(new ROBPtr))
        val rob_alloc_upt = Vec(DispatchWidth, ValidIO(new InstExInfo))    // update LQPtr, SQPtr

        // to LoadQueue
        val lq_alloc_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))
        val lq_alloc_rsp = Vec(DispatchWidth, Input(new LQPtr))
        val lq_alloc_upt = Vec(DispatchWidth, ValidIO(new InstExInfo))    // update ROBPtr

        // to StoreQueue
        val sq_alloc_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))
        val sq_alloc_rsp = Vec(DispatchWidth, Input(new SQPtr))
        val sq_alloc_upt = Vec(DispatchWidth, ValidIO(new InstExInfo))    // update ROBPtr
        
        // to RegFile
        val rf_rs1 = Vec(DispatchWidth, new Bundle {
            val addr = Output(UInt(PhyRegAddrBits.W))
            val data = Input(UInt(XLEN.W))
        })

        val rf_rs2 = Vec(DispatchWidth, new Bundle {
            val addr = Output(UInt(PhyRegAddrBits.W))
            val data = Input(UInt(XLEN.W))
        })

        // to BusyTable
        val bt_res = Vec(DispatchWidth, new Bundle {
            val rs1 = Output(UInt(PhyRegAddrBits.W))
            val rs2 = Output(UInt(PhyRegAddrBits.W))
            val rs1_busy = Input(Bool())
            val rs2_busy = Input(Bool())
        })

        // to IntIssueQueue
        val int_issue_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))

        // to LdIssueQueue
        val ld_issue_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))

        // to StIssueQueue
        val st_issue_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))

        // bypass
        val bypass = Vec(BypassWidth, Flipped(ValidIO(new BypassInfo)))
    })

    val redirect = io.redirect

    val renameModule = Module(new RenameModule)
    val dispatchModule = Module(new DispatchModule)

    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)
    val s2_valid = RegInit(false.B)

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())
    val s2_ready = Wire(Bool())

    val s0_task = Wire(Vec(RenameWidth, Valid(new InstExInfo)))
    val s1_task = RegInit(0.U.asTypeOf(Vec(RenameWidth, Valid(new InstExInfo))))
    val s2_task = RegInit(0.U.asTypeOf(Vec(DispatchWidth, Valid(new InstExInfo))))

    /* -------------- Stage 0 -------------- */
    val req = io.req
    s0_valid := req.map(_.valid).reduce(_||_) && !redirect.valid
    s0_ready := s1_ready || redirect.valid
    for (i <- 0 until RenameWidth) {
        s0_task(i).valid := req(i).valid && !redirect.valid
        s0_task(i).bits := req(i).bits
    }

    for (i <- 0 until RenameWidth) {
        req(i).ready := s0_ready
    }

    /* -------------- Stage 1 -------------- */
    when (s0_valid && s1_ready) {
        s1_valid := s0_valid && !redirect.valid
        s1_task := s0_task
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_task := 0.U.asTypeOf(s1_task)
    }

    renameModule.io.rs1 <> io.rat_rs1
    renameModule.io.rs2 <> io.rat_rs2
    renameModule.io.rd <> io.rat_rd
    renameModule.io.rs1_phy <> io.rat_rs1_phy
    renameModule.io.rs2_phy <> io.rat_rs2_phy
    renameModule.io.rd_phy <> io.rat_rd_phy
    renameModule.io.wr_phy <> io.rat_wr_phy
    renameModule.io.fl_req <> io.fl_req
    renameModule.io.fl_rsp <> io.fl_rsp
    renameModule.io.bt_alloc <> io.bt_alloc
    renameModule.io.dispatch_ready := s2_ready || redirect.valid

    for (i <- 0 until RenameWidth) {
        renameModule.io.rename_req(i).valid := s1_valid && s1_task(i).valid && !redirect.valid
        renameModule.io.rename_req(i).bits := s1_task(i).bits
    }

    val rename_rsp = renameModule.io.rename_rsp
    val s1_to_s2_task = Wire(Vec(DispatchWidth, Valid(new InstExInfo)))
    for (i <- 0 until DispatchWidth) {
        s1_to_s2_task(i).valid := rename_rsp(i).valid && !redirect.valid
        s1_to_s2_task(i).bits := rename_rsp(i).bits
    }

    val rename_all_ready = rename_rsp.map(_.valid).reduce(_ && _)
    s1_ready := !s1_valid || rename_all_ready && s2_ready || redirect.valid

    /* -------------- Stage 2 -------------- */
    when (s1_valid && s2_ready) {
        s2_valid := s1_valid && !redirect.valid
        s2_task := s1_to_s2_task
    }.elsewhen(!s1_valid && s2_ready) {
        s2_valid := false.B
        s2_task := 0.U.asTypeOf(s2_task)
    }

    val dispatch_req = dispatchModule.io.dispatch_req

    dispatchModule.io.rob_alloc_req <> io.rob_alloc_req
    dispatchModule.io.rob_alloc_rsp <> io.rob_alloc_rsp
    dispatchModule.io.rob_alloc_upt <> io.rob_alloc_upt
    dispatchModule.io.lq_alloc_req <> io.lq_alloc_req
    dispatchModule.io.lq_alloc_rsp <> io.lq_alloc_rsp
    dispatchModule.io.lq_alloc_upt <> io.lq_alloc_upt
    dispatchModule.io.sq_alloc_req <> io.sq_alloc_req
    dispatchModule.io.sq_alloc_rsp <> io.sq_alloc_rsp
    dispatchModule.io.sq_alloc_upt <> io.sq_alloc_upt
    dispatchModule.io.rs1 <> io.rf_rs1
    dispatchModule.io.rs2 <> io.rf_rs2
    dispatchModule.io.bt_res <> io.bt_res
    dispatchModule.io.int_issue_req <> io.int_issue_req
    dispatchModule.io.ld_issue_req <> io.ld_issue_req
    dispatchModule.io.st_issue_req <> io.st_issue_req
    dispatchModule.io.bypass <> io.bypass

    s2_ready := dispatch_req.map(_.ready).reduce(_ && _) || redirect.valid
    for (i <- 0 until DispatchWidth) {
        dispatch_req(i).valid := s2_valid && s2_task(i).valid && !redirect.valid
        dispatch_req(i).bits := s2_task(i).bits
    }

    /* ---------------- Redirect ---------------- */
    renameModule.io.redirect <> redirect
    dispatchModule.io.redirect <> redirect
    
}