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
import erythrina.backend.fu.EXUInfo
import erythrina.backend.rob.ROBPtr
import difftest.DifftestInfos
import erythrina.backend.issue.BypassInfo

class Backend extends ErythModule {
    val io = IO(new Bundle {
        val to_frontend = new Bundle {
            val flush = Output(Bool())
            val redirect = ValidIO(new Redirect)
        }

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

            val redirect = ValidIO(new Redirect)
        }

        val from_memblock = new Bundle {
            val ldu_info = Input(new EXUInfo)
            val ldu_cmt = Flipped(ValidIO(new InstExInfo))
            val stu_info = Input(new EXUInfo)
            val stu_cmt = Flipped(ValidIO(new InstExInfo))

            val lq_except_infos = Vec(LoadQueSize, Flipped(ValidIO(new ROBPtr)))
        }

        val difftest = Vec(CommitWidth, ValidIO(new DifftestInfos))
    })

    val isq_int = Module(new IssueQueue(2, "IntIssueQueue", 8))
    val isq_ld = Module(new IssueQueue(1, "LdIssueQueue", 8))
    val isq_st = Module(new IssueQueue(1, "StIssueQueue", 8))

    val exu0 = Module(new EXU0())
    val exu1 = Module(new EXU1())

    val rdu = Module(new RDU)
    val rob = Module(new ROB)
    val rat = Module(new RAT)
    val regfile = Module(new RegFile(DispatchWidth * 2, CommitWidth))
    val busyTable = Module(new BusyTable)
    val freelist = Module(new FreeList)

    /* --------------  Bypass ------------- */
    val bypass = Wire(Vec(BypassWidth, ValidIO(new BypassInfo)))
    bypass(0).valid := exu0.cmt.valid && exu0.cmt.bits.rf_wen
    bypass(0).bits.bypass_prd := exu0.cmt.bits.p_rd
    bypass(0).bits.bypass_data := exu0.cmt.bits.res

    bypass(1).valid := exu1.cmt.valid && exu1.cmt.bits.rf_wen
    bypass(1).bits.bypass_prd := exu1.cmt.bits.p_rd
    bypass(1).bits.bypass_data := exu1.cmt.bits.res

    bypass(2).valid := io.from_memblock.ldu_cmt.valid && io.from_memblock.ldu_cmt.bits.rf_wen
    bypass(2).bits.bypass_prd := io.from_memblock.ldu_cmt.bits.p_rd
    bypass(2).bits.bypass_data := io.from_memblock.ldu_cmt.bits.res

    bypass(3).valid := false.B      // STU don't write back
    bypass(3).bits := DontCare

    isq_int.io.bypass <> bypass
    isq_ld.io.bypass <> bypass
    isq_st.io.bypass <> bypass
    rdu.io.bypass <> bypass


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
    rdu.io.int_issue_req <> isq_int.io.enq
    rdu.io.ld_issue_req <> isq_ld.io.enq
    rdu.io.st_issue_req <> isq_st.io.enq
    

    /* --------------- EXU -----------------*/
    exu0.io.req <> isq_int.io.deq(0)
    exu0.io.exu_info <> isq_int.io.exu_info(0)
    exu0.io.cmt <> rob.io.fu_commit(0)
    exu1.io.req <> isq_int.io.deq(1)
    exu1.io.exu_info <> isq_int.io.exu_info(1)
    exu1.io.cmt <> rob.io.fu_commit(1)

    io.to_memblock.ldu_req <> isq_ld.io.deq(0)
    io.from_memblock.ldu_cmt <> rob.io.fu_commit(2)
    io.from_memblock.ldu_info <> isq_ld.io.exu_info(0)
    io.to_memblock.stu_req <> isq_st.io.deq(0)
    io.from_memblock.stu_cmt <> rob.io.fu_commit(3)
    io.from_memblock.stu_info <> isq_st.io.exu_info(0)

    /* --------------- RAT -----------------*/
    rat.io.wr_cmt <> rob.io.upt_arch_rat

    /* --------------- ROB -----------------*/
    rob.io.reg_write <> regfile.io.writePorts
    rob.io.rob_commit <> io.to_memblock.rob_commits
    rob.io.bt_free_req <> busyTable.io.free
    rob.io.fl_free_req <> freelist.io.free_req
    rob.io.difftest <> io.difftest
    rob.io.lq_except_infos <> io.from_memblock.lq_except_infos

    // for Speculative
    isq_int.io.last_robPtr.valid := true.B
    isq_int.io.last_robPtr.bits := rob.io.last_robPtr
    isq_ld.io.last_robPtr <> DontCare
    isq_st.io.last_robPtr <> DontCare

    val backend_redirect = rob.io.redirect
    /* --------------- Flush & Redirect -------------- */
    io.to_frontend.flush := rob.io.flush
    io.to_frontend.redirect <> backend_redirect
    io.to_memblock.redirect <> backend_redirect

    rdu.io.redirect <> backend_redirect
    isq_int.io.redirect <> backend_redirect
    isq_ld.io.redirect <> backend_redirect
    isq_st.io.redirect <> backend_redirect
    exu0.io.redirect <> backend_redirect
    exu1.io.redirect <> backend_redirect
    rat.io.redirect <> RegNext(backend_redirect)
    busyTable.io.redirect <> RegNext(backend_redirect)
    freelist.io.redirect <> RegNext(backend_redirect)
}