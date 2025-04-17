package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.Redirect
import erythrina.ErythBundle

class BPUTask extends ErythBundle {
    val base_pc = UInt(XLEN.W)
    val is_redirect = Bool()

    def fromInstFetchBlk(blk: InstFetchBlock) = {
        this.base_pc := Mux(blk.instVec(1).valid, 
                            blk.instVec(1).pc,
                            blk.instVec(0).pc)
        this.is_redirect := false.B
    }

    def fromRedirect(redirect: Redirect) = {
        this.base_pc := redirect.npc
        this.is_redirect := true.B
    }
}

class BPU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val redirect = Flipped(ValidIO(new Redirect))

        val ftq_enq_req = DecoupledIO(new InstFetchBlock)        // to FTQ, enq
        
        // from FTQ
        val ftq_pred_req = Flipped(DecoupledIO(new InstFetchBlock))
    })

    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)

    val s0_task = Wire(new BPUTask)
    val s1_task = RegInit(0.U.asTypeOf(new BPUTask))

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())

    /* ------------------- s0 ------------------- */
    s0_valid := (io.ftq_pred_req.valid || io.redirect.valid) && !io.flush
    assert(!(io.redirect.valid && io.flush), "BPU: redirect and flush should not be valid at the same time")

    val static_task = Wire(new BPUTask)
    static_task.fromInstFetchBlk(io.ftq_pred_req.bits)
    
    val redirect_task = Wire(new BPUTask)
    redirect_task.fromRedirect(io.redirect.bits)

    s0_task := Mux(io.redirect.valid, 
                    redirect_task,
                    static_task
                )
    s0_ready := s1_ready && !io.redirect.valid || reset.asBool || io.flush

    io.ftq_pred_req.ready := s0_ready

    /* ------------------- s1 ------------------- */
    when (s0_valid && s1_ready) {
        s1_valid := s0_valid && !io.flush
        s1_task := s0_task
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_task := 0.U.asTypeOf(s1_task)
    }
    s1_ready := !s1_valid || io.flush || s1_valid && io.ftq_enq_req.ready || io.redirect.valid

    // TODO: cross cacheline?
    val nxt_blk = WireInit(0.U.asTypeOf(new InstFetchBlock))
    for (i <- 0 until FetchWidth) {
        nxt_blk.instVec(i).valid := true.B
        nxt_blk.instVec(i).pc := s1_task.base_pc + (i.U << 2) + Mux(s1_task.is_redirect, 0.U, 4.U)
        nxt_blk.instVec(i).bpu_taken := false.B
    }

    io.ftq_enq_req.valid := s1_valid && !io.redirect.valid
    io.ftq_enq_req.bits := nxt_blk;

}