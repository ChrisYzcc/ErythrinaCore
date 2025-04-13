package erythrina.backend.dispatch

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.rob.ROBPtr
import erythrina.memblock.lsq.{LQPtr, SQPtr}
import erythrina.frontend.FuType
import erythrina.backend.dispatch
import erythrina.backend.Redirect

class DispatchModule extends ErythModule {
    val io = IO(new Bundle {
        val dispatch_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))

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
        val rs1 = Vec(DispatchWidth, new Bundle {
            val addr = Output(UInt(PhyRegAddrBits.W))
            val data = Input(UInt(XLEN.W))
        })

        val rs2 = Vec(DispatchWidth, new Bundle {
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

        // redirect
        val redirect = Flipped(ValidIO(new Redirect))
    })

    val dispatch_req = io.dispatch_req
    val redirect = io.redirect
    val (rob_alloc_req, rob_alloc_rsp, rob_alloc_upt) = (io.rob_alloc_req, io.rob_alloc_rsp, io.rob_alloc_upt)
    val (lq_alloc_req, lq_alloc_rsp, lq_alloc_upt) = (io.lq_alloc_req, io.lq_alloc_rsp, io.lq_alloc_upt)
    val (sq_alloc_req, sq_alloc_rsp, sq_alloc_upt) = (io.sq_alloc_req, io.sq_alloc_rsp, io.sq_alloc_upt)
    val (int_issue_req, ld_issue_req, st_issue_req) = (io.int_issue_req, io.ld_issue_req, io.st_issue_req)

    val sIDLE :: sAlloc :: sDispatch :: Nil = Enum(3)
    val states = RegInit(VecInit(Seq.fill(DispatchWidth)(sIDLE)))
    for (i <- 0 until DispatchWidth) {
        dispatch_req(i).ready := states.map(_ === sIDLE).reduce(_ && _)

        val robPtr_has_alloc = RegInit(false.B)
        val robPtr_reg = RegEnable(rob_alloc_rsp(i), 0.U.asTypeOf(new ROBPtr), rob_alloc_req(i).fire)
        val robPtr_ready = robPtr_has_alloc || rob_alloc_req(i).fire
        val robPtr = Mux(robPtr_has_alloc, robPtr_reg, rob_alloc_rsp(i))

        val lqPtr_has_alloc = RegInit(false.B)
        val lqPtr_reg = RegEnable(lq_alloc_rsp(i), 0.U.asTypeOf(new LQPtr), lq_alloc_req(i).fire)
        val lqPtr_ready = lqPtr_has_alloc || lq_alloc_req(i).fire
        val lqPtr = Mux(lqPtr_has_alloc, lqPtr_reg, lq_alloc_rsp(i))
        
        val sqPtr_has_alloc = RegInit(false.B)
        val sqPtr_reg = RegEnable(sq_alloc_rsp(i), 0.U.asTypeOf(new SQPtr), sq_alloc_req(i).fire)
        val sqPtr_ready = sqPtr_has_alloc || sq_alloc_req(i).fire
        val sqPtr = Mux(sqPtr_has_alloc, sqPtr_reg, sq_alloc_rsp(i))

        val need_lqPtr = RegEnable(dispatch_req(i).bits.fuType === FuType.ldu, false.B, dispatch_req(i).fire)
        val need_sqPtr = RegEnable(dispatch_req(i).bits.fuType === FuType.stu, false.B, dispatch_req(i).fire)

        val instExBlk = RegEnable(dispatch_req(i).bits, 0.U.asTypeOf(new InstExInfo), dispatch_req(i).fire)

        when (dispatch_req(i).fire) {
            robPtr_has_alloc := false.B
            lqPtr_has_alloc := false.B
            sqPtr_has_alloc := false.B
        }

        when (rob_alloc_req(i).fire) {
            robPtr_has_alloc := true.B
        }
        when (lq_alloc_req(i).fire) {
            lqPtr_has_alloc := true.B
        }
        when (sq_alloc_req(i).fire) {
            sqPtr_has_alloc := true.B
        }

        switch (states(i)) {
            is (sIDLE) {
                when (dispatch_req(i).fire && !redirect.valid) {
                    states(i) := sAlloc
                }
            }
            is (sAlloc) {
                when (redirect.valid) {
                    states(i) := sIDLE
                }.elsewhen(robPtr_ready && (!need_lqPtr || lqPtr_ready) && (!need_sqPtr || sqPtr_ready)) {
                    states(i) := sDispatch
                }
            }
            is (sDispatch) {
                when (int_issue_req(i).fire || ld_issue_req(i).fire || st_issue_req(i).fire || redirect.valid) {
                    states(i) := sIDLE
                }
            }
        }

        val issues = Wire(Vec(3, Bool()))
        issues(0) := int_issue_req(i).fire
        issues(1) := ld_issue_req(i).fire
        issues(2) := st_issue_req(i).fire
        assert(PopCount(issues) <= 1.U, "Dispatch can only issue one instruction at a time")

        // Alloc
        rob_alloc_req(i).valid := states(i) === sAlloc && !robPtr_has_alloc && !redirect.valid
        rob_alloc_req(i).bits := instExBlk

        lq_alloc_req(i).valid := states(i) === sAlloc && need_lqPtr && !lqPtr_has_alloc && !redirect.valid
        lq_alloc_req(i).bits := instExBlk

        sq_alloc_req(i).valid := states(i) === sAlloc && need_sqPtr && !sqPtr_has_alloc && !redirect.valid
        sq_alloc_req(i).bits := instExBlk

        // Dispatch
        val issueBlk = WireInit(instExBlk)
        issueBlk.robPtr := robPtr
        issueBlk.lqPtr := lqPtr
        issueBlk.sqPtr := sqPtr
        
        io.rs1(i).addr := issueBlk.p_rs1
        io.rs2(i).addr := issueBlk.p_rs2
        io.bt_res(i).rs1 := issueBlk.p_rs1
        io.bt_res(i).rs2 := issueBlk.p_rs2

        issueBlk.src1 := Mux(instExBlk.src1_ready, instExBlk.src1, io.rs1(i).data)
        issueBlk.src2 := Mux(instExBlk.src2_ready, instExBlk.src2, io.rs2(i).data)
        issueBlk.src1_ready := instExBlk.src1_ready || !io.bt_res(i).rs1_busy
        issueBlk.src2_ready := instExBlk.src2_ready || !io.bt_res(i).rs2_busy

        val is_ldu = instExBlk.fuType === FuType.ldu
        val is_stu = instExBlk.fuType === FuType.stu
        val is_int = !is_ldu && !is_stu
        int_issue_req(i).valid := states(i) === sDispatch && is_int && !redirect.valid
        int_issue_req(i).bits := issueBlk
        ld_issue_req(i).valid := states(i) === sDispatch && is_ldu && !redirect.valid
        ld_issue_req(i).bits := issueBlk
        st_issue_req(i).valid := states(i) === sDispatch && is_stu && !redirect.valid
        st_issue_req(i).bits := issueBlk

        // update
        rob_alloc_upt(i).valid := states(i) === sDispatch && !redirect.valid
        rob_alloc_upt(i).bits := issueBlk
        lq_alloc_upt(i).valid := states(i) === sDispatch && need_lqPtr && !redirect.valid
        lq_alloc_upt(i).bits := issueBlk
        sq_alloc_upt(i).valid := states(i) === sDispatch && need_sqPtr && !redirect.valid
        sq_alloc_upt(i).bits := issueBlk
    }
    
}