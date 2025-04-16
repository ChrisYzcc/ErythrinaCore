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
import erythrina.backend.issue.BypassInfo

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

        val bypass = Vec(BypassWidth, Flipped(ValidIO(new BypassInfo)))
    })

    val dispatch_req = io.dispatch_req
    val redirect = io.redirect
    val (rob_alloc_req, rob_alloc_rsp, rob_alloc_upt) = (io.rob_alloc_req, io.rob_alloc_rsp, io.rob_alloc_upt)
    val (lq_alloc_req, lq_alloc_rsp, lq_alloc_upt) = (io.lq_alloc_req, io.lq_alloc_rsp, io.lq_alloc_upt)
    val (sq_alloc_req, sq_alloc_rsp, sq_alloc_upt) = (io.sq_alloc_req, io.sq_alloc_rsp, io.sq_alloc_upt)
    val (int_issue_req, ld_issue_req, st_issue_req) = (io.int_issue_req, io.ld_issue_req, io.st_issue_req)

    val slot_done = Wire(Vec(DispatchWidth, Bool()))
    val valid = dispatch_req.map(_.valid).reduce(_||_)
    val ready = !valid || slot_done.reduce(_&&_) || redirect.valid
    dispatch_req.foreach { req =>
        req.ready := ready
    }

    for (i <- 0 until DispatchWidth) {
        val issues = Wire(Vec(3, Bool()))
        issues(0) := int_issue_req(i).fire
        issues(1) := ld_issue_req(i).fire
        issues(2) := st_issue_req(i).fire
        assert(PopCount(issues) <= 1.U, "Dispatch can only issue one instruction at a time")

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

        when (dispatch_req(i).fire || redirect.valid || issues.reduce(_||_)) {
            robPtr_has_alloc := false.B
            lqPtr_has_alloc := false.B
            sqPtr_has_alloc := false.B
        }.otherwise {
            robPtr_has_alloc := Mux(rob_alloc_req(i).fire, true.B, robPtr_has_alloc)
            lqPtr_has_alloc := Mux(lq_alloc_req(i).fire, true.B, lqPtr_has_alloc)
            sqPtr_has_alloc := Mux(sq_alloc_req(i).fire, true.B, sqPtr_has_alloc)
        }

        val instExBlk = dispatch_req(i).bits

        // Alloc Ptr
        val need_lq_ptr = instExBlk.fuType === FuType.ldu
        val need_sq_ptr = instExBlk.fuType === FuType.stu

        rob_alloc_req(i).valid := dispatch_req(i).valid && !robPtr_has_alloc && !redirect.valid
        rob_alloc_req(i).bits := instExBlk

        lq_alloc_req(i).valid := dispatch_req(i).valid && need_lq_ptr && !lqPtr_has_alloc && !redirect.valid
        lq_alloc_req(i).bits := instExBlk

        sq_alloc_req(i).valid := dispatch_req(i).valid && need_sq_ptr && !sqPtr_has_alloc && !redirect.valid
        sq_alloc_req(i).bits := instExBlk

        val ptr_ready = robPtr_ready && (!need_lq_ptr || lqPtr_ready) && (!need_sq_ptr || sqPtr_ready)

        // Update
        val updateBlk = WireInit(instExBlk)
        updateBlk.robPtr := robPtr
        updateBlk.lqPtr := lqPtr
        updateBlk.sqPtr := sqPtr

        rob_alloc_upt(i).valid := ptr_ready && !redirect.valid
        rob_alloc_upt(i).bits := updateBlk

        lq_alloc_upt(i).valid := ptr_ready && need_lq_ptr && !redirect.valid
        lq_alloc_upt(i).bits := updateBlk

        sq_alloc_upt(i).valid := ptr_ready && need_sq_ptr && !redirect.valid
        sq_alloc_upt(i).bits := updateBlk

        // bypass
        val bypass = io.bypass
        val bp_src1_ready_vec = bypass.map{
            case b =>
                b.valid && b.bits.bypass_prd === instExBlk.p_rs1
        }
        val bp_src1_ready = bp_src1_ready_vec.reduce(_||_)
        val bp_src2_ready_vec = bypass.map{
            case b =>
                b.valid && b.bits.bypass_prd === instExBlk.p_rs2
        }
        val bp_src2_ready = bp_src2_ready_vec.reduce(_||_)
        val bp_src1_data = VecInit(bypass.map(_.bits.bypass_data))(PriorityEncoder(bp_src1_ready_vec))
        val bp_src2_data = VecInit(bypass.map(_.bits.bypass_data))(PriorityEncoder(bp_src2_ready_vec))

        val frm_bp_src1_ready = bp_src1_ready
        val frm_bp_src2_ready = bp_src2_ready
        val frm_bp_src1_data = bp_src1_data
        val frm_bp_src2_data = bp_src2_data

        // Dispatch
        val issueBlk = WireInit(instExBlk)
        issueBlk.robPtr := robPtr
        issueBlk.lqPtr := lqPtr
        issueBlk.sqPtr := sqPtr

        io.rs1(i).addr := issueBlk.p_rs1
        io.rs2(i).addr := issueBlk.p_rs2
        io.bt_res(i).rs1 := issueBlk.p_rs1
        io.bt_res(i).rs2 := issueBlk.p_rs2

        issueBlk.src1 := Mux(instExBlk.src1_ready, instExBlk.src1, Mux(frm_bp_src1_ready, frm_bp_src1_data, io.rs1(i).data))
        issueBlk.src2 := Mux(instExBlk.src2_ready, instExBlk.src2, Mux(frm_bp_src2_ready, frm_bp_src2_data, io.rs2(i).data))
        issueBlk.src1_ready := instExBlk.src1_ready || !io.bt_res(i).rs1_busy || frm_bp_src1_ready
        issueBlk.src2_ready := instExBlk.src2_ready || !io.bt_res(i).rs2_busy || frm_bp_src2_ready

        val is_ldu = instExBlk.fuType === FuType.ldu
        val is_stu = instExBlk.fuType === FuType.stu
        val is_int = !is_ldu && !is_stu

        int_issue_req(i).valid := dispatch_req(i).valid && is_int && !redirect.valid && ptr_ready
        int_issue_req(i).bits := issueBlk
        ld_issue_req(i).valid := dispatch_req(i).valid && is_ldu && !redirect.valid && ptr_ready
        ld_issue_req(i).bits := issueBlk
        st_issue_req(i).valid := dispatch_req(i).valid && is_stu && !redirect.valid && ptr_ready
        st_issue_req(i).bits := issueBlk

        slot_done(i) := issues.reduce(_||_) || redirect.valid
    }
}