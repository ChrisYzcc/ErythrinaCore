package erythrina.backend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.memblock.lsq.{LQPtr, SQPtr}
import erythrina.backend.issue.IssueQueue
import erythrina.backend.fu.{EXU0, EXU1}
import erythrina.backend.rob.ROB
import erythrina.backend.rename.{RAT, FreeList}
import erythrina.backend.regfile.{RegFile, BusyTable}

class BackEnd extends ErythModule {
    val io = IO(new Bundle {
        val from_frontend = Vec(RenameWidth, Flipped(DecoupledIO(new InstExInfo)))

        // TODO: redirect to frontend

        val to_memblock = new Bundle {
            val lq_alloc_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))
            val lq_alloc_rsp = Vec(DispatchWidth, Input(new LQPtr))
            val lq_alloc_upt = Vec(DispatchWidth, ValidIO(new InstExInfo))    // update ROBPtr

            val sq_alloc_req = Vec(DispatchWidth, DecoupledIO(new InstExInfo))
            val sq_alloc_rsp = Vec(DispatchWidth, Input(new SQPtr))
            val sq_alloc_upt = Vec(DispatchWidth, ValidIO(new InstExInfo))    // update ROBPtr

            val ldu_req = DecoupledIO(new InstExInfo)
            val stu_req = DecoupledIO(new InstExInfo)

            val rob_commits = Vec(CommitWidth, ValidIO(new InstExInfo))

            // TODO: redirect
        }

        val from_memblock = new Bundle {
            val ldu_cmt = Flipped(ValidIO(new InstExInfo))
            val stu_cmt = Flipped(ValidIO(new InstExInfo))
        }
    })

    val intISQ = Module(new IssueQueue(2, "IntIssueQueue", 8))
    val ldISQ = Module(new IssueQueue(1, "LdIssueQueue", 8))
    val stISQ = Module(new IssueQueue(1, "StIssueQueue", 8))

    val exu0 = Module(new EXU0())
    val exu1 = Module(new EXU1())

    val rdu = Module(new RDU)
    val rob = Module(new ROB)
    val rat = Module(new RAT)
    val regfile = Module(new RegFile(DispatchWidth * 2, CommitWidth))
    val busyTable = Module(new BusyTable)
    val freelist = Module(new FreeList)

    /* --------------- RDU -----------------*/
    rdu.io.req <> io.from_frontend
    // RDU <-> RAT
    rdu.io.rat_rs1 <> rat.io.rs1        // req
    rdu.io.rat_rs2 <> rat.io.rs2
    rdu.io.rat_rd <> rat.io.rd
    rdu.io.rat_rs1_phy <> rat.io.rs1_phy    // rsp
    rdu.io.rat_rs2_phy <> rat.io.rs2_phy
    rdu.io.rat_rd_phy <> rat.io.rd_phy
    rdu.io.rat_wr_phy <> rat.io.wr_phy      // update
    // RDU <-> FreeList
    rdu.io.fl_req <> freelist.io.alloc_req
    rdu.io.fl_rsp <> freelist.io.alloc_rsp
    // RDU <-> ROB
    rdu.io.rob_alloc_req <> rob.io.alloc_req
    rdu.io.rob_alloc_rsp <> rob.io.alloc_rsp
    rdu.io.rob_alloc_upt <> rob.io.alloc_upt
    // RDU <-> LoadQueue
    rdu.io.lq_alloc_req <> io.to_memblock.lq_alloc_req
    rdu.io.lq_alloc_rsp <> io.to_memblock.lq_alloc_rsp
    rdu.io.lq_alloc_upt <> io.to_memblock.lq_alloc_upt
    // RDU <-> StoreQueue
    rdu.io.sq_alloc_req <> io.to_memblock.sq_alloc_req
    rdu.io.sq_alloc_rsp <> io.to_memblock.sq_alloc_rsp
    rdu.io.sq_alloc_upt <> io.to_memblock.sq_alloc_upt
    // RDU <-> RegFile
    for (i <- 0 until DispatchWidth) {
        rdu.io.rf_rs1(i) <> regfile.io.readPorts(i)
        rdu.io.rf_rs2(i) <> regfile.io.readPorts(i + DispatchWidth)
    }
    // RDU <-> BusyTable
    rdu.io.bt_res <> busyTable.io.readPorts
    rdu.io.bt_alloc <> busyTable.io.alloc
    // RDU <-> IssueQueue
    rdu.io.int_issue_req <> intISQ.io.enq
    rdu.io.ld_issue_req <> ldISQ.io.enq
    rdu.io.st_issue_req <> stISQ.io.enq

    /* --------------- EXU -----------------*/
    exu0.io.req <> intISQ.io.deq(0)
    exu0.io.cmt <> rob.io.fu_commit(0)
    exu1.io.req <> intISQ.io.deq(1)
    exu1.io.cmt <> rob.io.fu_commit(1)

    io.to_memblock.ldu_req <> ldISQ.io.deq(0)
    io.from_memblock.ldu_cmt <> rob.io.fu_commit(2)
    io.to_memblock.stu_req <> stISQ.io.deq(0)
    io.from_memblock.stu_cmt <> rob.io.fu_commit(3)


    /* --------------- RAT -----------------*/
    rat.io.wr_cmt <> rob.io.upt_arch_rat

    /* --------------- ROB -----------------*/
    rob.io.reg_write <> regfile.io.writePorts
    rob.io.rob_commit <> io.to_memblock.rob_commits
    rob.io.bt_free_req <> busyTable.io.free
    rob.io.fl_free_req <> freelist.io.free_req
}