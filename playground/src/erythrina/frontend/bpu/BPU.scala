package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.Redirect
import erythrina.ErythBundle

import erythrina.frontend.icache.ICacheParams._
import erythrina.frontend.InstFetchBlock

class BPU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val redirect = Flipped(ValidIO(new Redirect))

        val ftq_enq_req = DecoupledIO(new InstFetchBlock)        // to FTQ, enq

        val btb_upt = Flipped(Vec(CommitWidth, ValidIO(new BTBUpt))) // from backend, btb update
    })

    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)
    val s1_ready = Wire(Bool())

    val s0_pc = Wire(UInt(XLEN.W))
    val s1_pc = RegInit(0.U(XLEN.W))    // to FTQ
    val s1_npc = Wire(UInt(XLEN.W))    // to s0

    val rst_issued = RegInit(false.B)
    when (s0_valid && s1_ready) {
        rst_issued := true.B
    }

    val btb = Module(new BTB)
    btb.io.upt <> io.btb_upt

    /* -------------- s0 -------------- */
    s0_valid := (s1_valid || io.redirect.valid || !rst_issued) && !io.flush && !reset.asBool

    s0_pc := Mux(!rst_issued, 
                RESETVEC.U,
                Mux(io.redirect.valid,
                    io.redirect.bits.npc,
                    s1_npc
                ))

    // request btb
    for (i <- 0 until FetchWidth) {
        btb.io.req(i).valid := s0_valid && s1_ready
        btb.io.req(i).bits.pc := s0_pc + (i.U << 2)
    }

    /* -------------- s1 -------------- */
    when (s0_valid && s1_ready) {
        s1_valid := s0_valid
        s1_pc := s0_pc
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_pc := 0.U
    }

    val base_cacheline = s1_pc(XLEN - 1, log2Ceil(CachelineSize))
    val enq_blk = WireInit(0.U.asTypeOf(new InstFetchBlock))

    val s_npc_vec = Wire(Vec(FetchWidth, UInt(XLEN.W)))
    val d_npc_vec = Wire(Vec(FetchWidth, UInt(XLEN.W)))
    val d_npc_v_vec = Wire(Vec(FetchWidth, Bool()))
    for (i <- 0 until FetchWidth) {
        s_npc_vec(i) := s1_pc + (i.U << 2) + 4.U
        d_npc_vec(i) := btb.io.rsp(i).bits.target
        d_npc_v_vec(i) := btb.io.rsp(i).valid && btb.io.rsp(i).bits.taken
    }

    for (i <- 0 until FetchWidth) {
        enq_blk.instVec(i).pc := s1_pc + (i.U << 2)
        enq_blk.instVec(i).bpu_hit := btb.io.rsp(i).bits.hit
        enq_blk.instVec(i).bpu_taken := d_npc_v_vec(i)
        enq_blk.instVec(i).bpu_target := Mux(d_npc_v_vec(i), d_npc_vec(i), s_npc_vec(i))
        
        val prev_taken = if (i == 0) false.B else d_npc_v_vec.take(i).reduce(_ || _)
        val same_cacheline = (s1_pc + (i.U << 2))(XLEN - 1, log2Ceil(CachelineSize)) === base_cacheline
        enq_blk.instVec(i).valid := same_cacheline && !prev_taken
    }

    // generate npc
    val npc_vec = Wire(Vec(FetchWidth, UInt(XLEN.W)))
    val npc_v_vec = Wire(Vec(FetchWidth, Bool()))
    for (i <- 0 until FetchWidth) {
        npc_vec(i) := Mux(d_npc_v_vec(i), d_npc_vec(i), s_npc_vec(i))
        npc_v_vec(i) := enq_blk.instVec(i).valid
    }

    val last_valid_idx = (FetchWidth - 1).U - PriorityEncoder(npc_v_vec.reverse)
    s1_npc := npc_vec(last_valid_idx)

    s1_ready := !s1_valid || io.flush || s1_valid && io.ftq_enq_req.ready || io.redirect.valid

    io.ftq_enq_req.valid := s1_valid && !io.redirect.valid && !io.flush
    io.ftq_enq_req.bits := enq_blk
}