package erythrina.frontend.icache

/**
  * Prefetch Pipeline
*/

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class PrefetchPipe extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))

        // Req to Meta Array
        val meta_req = DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
        })

        val meta_rsp = Input(Vec(ICacheParams.ways, new ICacheMeta))

        // Req to Fetcher
        val fetcher_req = DecoupledIO(UInt(XLEN.W))
        val fetcher_rsp = Flipped(ValidIO(UInt((ICacheParams.CachelineSize * 8).W)))

        // Forward Info to Main Pipe
        val fwd_info = ValidIO(UInt(XLEN.W))
    })

    val req = io.req
    val (meta_req, meta_rsp) = (io.meta_req, io.meta_rsp)
    val (fetcher_req, fetcher_rsp) = (io.fetcher_req, io.fetcher_rsp)
    val fwd_info = io.fwd_info

    /* ------------------- Stage Control ------------------- */
    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())

    /* ------------------- Stage 0 ------------------- */
    s0_valid := req.valid
    s0_ready := s1_ready && meta_req.ready
    req.ready := s0_ready

    val s0_addr = req.bits
    val s0_inrange = (s0_addr >= ICacheParams.CacheableRange._1.U) && (s0_addr < ICacheParams.CacheableRange._2.U)

    val s0_idx = ICacheParams.get_idx(s0_addr)
    val s0_tag = ICacheParams.get_tag(s0_addr)

    // send req to meta array
    meta_req.valid := s0_valid && s0_inrange && s0_ready
    meta_req.bits.idx := s0_idx

    /* ------------------- Stage 1 ------------------- */
    val s1_idx = RegInit(0.U(log2Ceil(ICacheParams.sets).W))
    val s1_tag = RegInit(0.U(ICacheParams.TagLen.W))
    val s1_inrange = RegInit(false.B)

    when (s0_valid && s1_ready) {
        s1_valid := s0_valid
        s1_inrange := s0_inrange
        s1_idx := s0_idx
        s1_tag := s0_tag
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_inrange := false.B
        s1_idx := 0.U
        s1_tag := 0.U
    }

    val hit_vec = meta_rsp.map(m => m.valid && (m.tag === s1_tag))
    val hit = hit_vec.reduce(_ || _) && s1_valid && s1_inrange

    // fetcher
    fetcher_req.valid := s1_valid && (s1_inrange && !hit || !s1_inrange)
    fetcher_req.bits := Cat(s1_tag, s1_idx, 0.U(log2Ceil(ICacheParams.CachelineSize).W))

    s1_ready := (!s1_valid || (s1_valid && hit) || fetcher_rsp.valid) && meta_req.ready

    // forward
    fwd_info.valid := s1_valid
    fwd_info.bits := Cat(s1_tag, s1_idx, 0.U(log2Ceil(ICacheParams.CachelineSize).W))
}