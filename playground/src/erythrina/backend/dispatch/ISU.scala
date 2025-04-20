package erythrina.backend.dispatch

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.memblock.lsq.{LQPtr, SQPtr}
import erythrina.backend.Redirect
import erythrina.backend.issue.BypassInfo
import erythrina.frontend.FuType

class ISU extends ErythModule {
    val io = IO(new Bundle {
        val in = new Bundle {
            val valid = Output(Bool())
            val ready = Input(Bool())
            val bits = Input(new InstExInfo)
        }

        // to ROB
        val rob_alloc_upt = ValidIO(new InstExInfo)    // update LQPtr, SQPtr

        // to LoadQueue
        val lq_alloc_req = DecoupledIO(new InstExInfo)
        val lq_alloc_rsp = Input(new LQPtr)

        // to StoreQueue
        val sq_alloc_req = DecoupledIO(new InstExInfo)
        val sq_alloc_rsp = Input(new SQPtr)

        // to RegFile
        val rs1 = new Bundle {
            val addr = Output(UInt(PhyRegAddrBits.W))
            val data = Input(UInt(XLEN.W))
        }
        val rs2 = new Bundle {
            val addr = Output(UInt(PhyRegAddrBits.W))
            val data = Input(UInt(XLEN.W))
        }

        // to BusyTable
        val bt_res = new Bundle {
            val rs1 = Output(UInt(PhyRegAddrBits.W))
            val rs2 = Output(UInt(PhyRegAddrBits.W))
            val rs1_busy = Input(Bool())
            val rs2_busy = Input(Bool())
        }

        // to IntIssueQueue
        val int_issue_req = DecoupledIO(new InstExInfo)
        
        // to LdIssueQueue
        val ld_issue_req = DecoupledIO(new InstExInfo)

        // to StIssueQueue
        val st_issue_req = DecoupledIO(new InstExInfo)

        // Redirect
        val redirect = Flipped(ValidIO(new Redirect))

        // bypass
        val bypass = Vec(BypassWidth, Flipped(ValidIO(new BypassInfo)))
    })

    val in = io.in
    val rob_alloc_upt = io.rob_alloc_upt
    val (lq_alloc_req, lq_alloc_rsp) = (io.lq_alloc_req, io.lq_alloc_rsp)
    val (sq_alloc_req, sq_alloc_rsp) = (io.sq_alloc_req, io.sq_alloc_rsp)
    val (int_issue_req, ld_issue_req, st_issue_req) = (io.int_issue_req, io.ld_issue_req, io.st_issue_req)
    val redirect = io.redirect

    /* ------------- Stage Ctrl ------------- */
    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)

    val s0_task = WireInit(in.bits)
    val s1_task = RegInit(0.U.asTypeOf(new InstExInfo))

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())

    /* ------------- Stage 0 ------------- */
    in.valid := s0_ready     // req a instr from InstrPool

    s0_valid := in.valid && in.ready
    s0_ready := s1_ready || redirect.valid

    /* ------------- Stage 1 ------------- */
    when (s0_valid && s1_ready) {
        s1_valid := s0_valid && !redirect.valid
        s1_task := s0_task
    }.elsewhen (!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_task := 0.U.asTypeOf(new InstExInfo)
    }

    // Alloc LqPtr and SqPtr
    val lqPtr_has_alloc = RegInit(false.B)
    val lqPtr_reg = RegEnable(lq_alloc_rsp, 0.U.asTypeOf(new LQPtr), lq_alloc_req.fire)
    val lqPtr_ready = lqPtr_has_alloc || lq_alloc_req.fire
    val lqPtr = Mux(lqPtr_has_alloc, lqPtr_reg, lq_alloc_rsp)

    val sqPtr_has_alloc = RegInit(false.B)
    val sqPtr_reg = RegEnable(sq_alloc_rsp, 0.U.asTypeOf(new SQPtr), sq_alloc_req.fire)
    val sqPtr_ready = sqPtr_has_alloc || sq_alloc_req.fire
    val sqPtr = Mux(sqPtr_has_alloc, sqPtr_reg, sq_alloc_rsp)

    when (s1_ready || redirect.valid) {
        lqPtr_has_alloc := false.B
        sqPtr_has_alloc := false.B
    }.otherwise {
        lqPtr_has_alloc := Mux(lq_alloc_req.fire, true.B, lqPtr_has_alloc)
        sqPtr_has_alloc := Mux(sq_alloc_req.fire, true.B, sqPtr_has_alloc)
    }

    val need_lq_ptr = s1_task.fuType === FuType.ldu
    val need_sq_ptr = s1_task.fuType === FuType.stu

    lq_alloc_req.valid := s1_valid && need_lq_ptr && !lqPtr_has_alloc && !redirect.valid
    lq_alloc_req.bits := s1_task

    sq_alloc_req.valid := s1_valid && need_sq_ptr && !sqPtr_has_alloc && !redirect.valid
    sq_alloc_req.bits := s1_task


    // Update
    val upt_task = WireInit(s1_task)
    upt_task.lqPtr := lqPtr
    upt_task.sqPtr := sqPtr

    rob_alloc_upt.valid := (need_lq_ptr && lqPtr_ready || need_sq_ptr && sqPtr_ready) && !redirect.valid && s1_valid
    rob_alloc_upt.bits := upt_task

    // bypass
    val bypass = io.bypass
    val bp_src1_ready_vec = bypass.map {
        case b =>
            b.valid && b.bits.bypass_prd === s1_task.p_rs1
    }
    val bp_src2_ready_vec = bypass.map {
        case b =>
            b.valid && b.bits.bypass_prd === s1_task.p_rs2
    }
    val bp_src1_ready = bp_src1_ready_vec.reduce(_ || _)
    val bp_src2_ready = bp_src2_ready_vec.reduce(_ || _)
    val bp_src1_data = VecInit(bypass.map(_.bits.bypass_data))(PriorityEncoder(bp_src1_ready_vec))
    val bp_src2_data = VecInit(bypass.map(_.bits.bypass_data))(PriorityEncoder(bp_src2_ready_vec))

    // out
    val out_task = WireInit(s1_task)

    out_task.lqPtr := lqPtr
    out_task.sqPtr := sqPtr

    io.rs1.addr := out_task.p_rs1
    io.rs2.addr := out_task.p_rs2
    io.bt_res.rs1 := out_task.p_rs1
    io.bt_res.rs2 := out_task.p_rs2

    out_task.src1 := Mux(s1_task.src1_ready, s1_task.src1, Mux(bp_src1_ready, bp_src1_data, io.rs1.data))
    out_task.src2 := Mux(s1_task.src2_ready, s1_task.src2, Mux(bp_src2_ready, bp_src2_data, io.rs2.data))
    out_task.src1_ready := s1_task.src1_ready || bp_src1_ready || !io.bt_res.rs1_busy
    out_task.src2_ready := s1_task.src2_ready || bp_src2_ready || !io.bt_res.rs2_busy

    val is_ldu = out_task.fuType === FuType.ldu
    val is_stu = out_task.fuType === FuType.stu
    val is_int = !is_ldu && !is_stu

    val ptr_ready = (!need_lq_ptr || lqPtr_ready) && (!need_sq_ptr || sqPtr_ready)
    int_issue_req.valid := s1_valid && is_int && ptr_ready && !redirect.valid
    int_issue_req.bits := out_task
    ld_issue_req.valid := s1_valid && is_ldu && ptr_ready && !redirect.valid
    ld_issue_req.bits := out_task
    st_issue_req.valid := s1_valid && is_stu && ptr_ready && !redirect.valid
    st_issue_req.bits := out_task

    val int_issue_ready = !is_int || int_issue_req.ready
    val ld_issue_ready = !is_ldu || ld_issue_req.ready
    val st_issue_ready = !is_stu || st_issue_req.ready

    s1_ready := !s1_valid || s1_valid && (int_issue_ready && ld_issue_ready && st_issue_ready) && ptr_ready || redirect.valid
}