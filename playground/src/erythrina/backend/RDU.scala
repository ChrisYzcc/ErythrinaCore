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

class RDU extends ErythModule {
    val io = IO(new Bundle {
        // from FrontEnd
        val req = Vec(RenameWidth, Flipped(DecoupledIO(new InstExInfo)))

        // redirect?

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
    })

    val renameModule = Module(new RenameModule)
    val dispatchModule = Module(new DispatchModule)

    val s1_ready = Wire(Bool())
    val s2_ready = Wire(Bool())

    val req = io.req
    /* -------------- Stage 1 -------------- */
    val s1IDLE :: s1RENAME ::s1WAIT :: Nil = Enum(3)
    val s1_state = RegInit(s1IDLE)
    switch (s1_state) {
        is (s1IDLE) {
            when (req.map(_.fire).reduce(_||_)) {
                s1_state := s1RENAME
            }
        }
        is (s1RENAME) {
            when (s1_ready && s2_ready) {
                s1_state := s1IDLE
            }.elsewhen(s1_ready && !s2_ready) {
                s1_state := s1WAIT
            }
        }
        is (s1WAIT) {
            when (s2_ready) {
                s1_state := s1IDLE
            }
        }
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

    val s1_req_vec = RegInit(VecInit(Seq.fill(RenameWidth)(0.U.asTypeOf(new InstExInfo))))
    val s1_valid_vec = RegInit(VecInit(Seq.fill(RenameWidth)(false.B)))
    when (s1_state === s1IDLE && req.map(_.fire).reduce(_||_)) {
        for (i <- 0 until RenameWidth) {
            s1_req_vec(i) := req(i).bits
            s1_valid_vec(i) := req(i).valid
        }
    }

    for (i <- 0 until RenameWidth) {
        renameModule.io.rename_req(i).valid := s1_state === s1RENAME && s1_valid_vec(i)
        renameModule.io.rename_req(i).bits := s1_req_vec(i)

        req(i).ready := s1_state === s1IDLE
    }

    val rename_rsp = renameModule.io.rename_rsp
    val renamed_blk_vec = WireInit(VecInit(Seq.fill(RenameWidth)(0.U.asTypeOf(new InstExInfo))))
    val renamed_blk_vec_reg = RegInit(VecInit(Seq.fill(RenameWidth)(0.U.asTypeOf(new InstExInfo))))
    val rename_all_ready = rename_rsp.map(_.valid).reduce(_ && _)

    for (i <- 0 until RenameWidth) {
        renamed_blk_vec(i) := rename_rsp(i).bits
    }

    s1_ready := s1_state === s1WAIT || rename_all_ready
    when (s1_state === s1RENAME && rename_all_ready) {
        for (i <- 0 until RenameWidth) {
            renamed_blk_vec_reg(i) := renamed_blk_vec(i)
        }
    }

    val s1_to_s2_req = Mux(s1_state === s1RENAME, renamed_blk_vec, renamed_blk_vec_reg)

    /* -------------- Stage 2 -------------- */
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

    s2_ready := dispatch_req.map(_.ready).reduce(_ && _)
    for (i <- 0 until DispatchWidth) {
        dispatch_req(i).valid := s1_valid_vec(i)
        dispatch_req(i).bits := s1_to_s2_req(i)
    }
    
}