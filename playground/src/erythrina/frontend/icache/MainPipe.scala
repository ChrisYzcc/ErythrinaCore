package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.icache.ICacheParams._
import utils._
import coursier.util.Sync
import moduload.Priority

class DataArray[T <: Data](dataType: T, len: Int, ways: Int) extends ErythModule {
    val io = IO(new Bundle {
        val r_req = Flipped(DecoupledIO(UInt(log2Ceil(len).W)))
        val r_rsp = ValidIO(Vec(ways, dataType))

        val w_req = Flipped(DecoupledIO(new Bundle {
            val addr = UInt(log2Ceil(len).W)
            val way = UInt(log2Ceil(ways).W)
        }))
        val w_data = Input(dataType)
    })

    val (r_req, r_rsp, w_req, w_data) = (io.r_req, io.r_rsp, io.w_req, io.w_data)

    // Reset Logic
    val rst_idx = RegInit(0.U(log2Ceil(len).W))
    when (rst_idx =/= (len - 1).U) {
        rst_idx := rst_idx + 1.U
    }

    // data array
    val data_array_seq = Seq.fill(ways)(SyncReadMem(len, dataType))

    // Read
    r_req.ready := !reset.asBool && (rst_idx === (len - 1).U)
    val rd_req_reg = RegEnable(r_req.bits, 0.U, r_req.fire)

    val data_rsp = data_array_seq.map(_.read(Mux(r_req.fire, r_req.bits, rd_req_reg)))
    r_rsp.valid := RegNext(r_req.fire)
    r_rsp.bits := data_rsp

    // Write
    w_req.ready := !reset.asBool && (rst_idx === (len - 1).U)
    
    for (i <- 0 until ways) {
        val need_write = !reset.asBool && (rst_idx =/= (len - 1).U) || (w_req.fire && w_req.bits.way === i.U)

        val wr_addr = Mux(w_req.fire, w_req.bits.addr, rst_idx)
        val wr_data = Mux(w_req.fire, w_data, 0.U.asTypeOf(dataType))

        when (need_write) {
            data_array_seq(i).write(wr_addr, wr_data)
        }
    }
} 

class MainPipe extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new ICacheReq))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val fetcher_req = DecoupledIO(UInt(XLEN.W))
        val fetcher_rsp = Flipped(ValidIO(UInt((CachelineSize * 8).W)))

        // Prefetcher
        val pft_hint = ValidIO(new PftHints)
    })

    val (req, rsp) = (io.req, io.rsp)
    val (fetcher_req, fetcher_rsp) = (io.fetcher_req, io.fetcher_rsp)
    val pft_hint = io.pft_hint

    /* ------------------- Data & Meta Array ------------------- */
    val metas = Module(new DataArray(new ICacheMeta, sets, ways))
    val datas = Module(new DataArray(UInt((CachelineSize * 8).W), sets, ways))

    val lru_seq = Seq.fill(sets)(Module(new PLRU))
    val lru_oldest_vec = VecInit(lru_seq.map(_.io.oldest))

    /* ------------------- Stage Control ------------------- */
    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())

    /* ------------------- Stage 0 ------------------- */
    s0_valid := req.valid
    s0_ready := s1_ready && metas.io.r_req.ready

    req.ready := s0_ready

    val s0_addr = req.bits.addr
    val s0_inrange = s0_addr >= CacheableRange._1.U && s0_addr <= CacheableRange._2.U

    // req data & meta
    val s0_idx = get_idx(s0_addr)
    val s0_tag = get_tag(s0_addr)

    val s0_cmd = req.bits.cmd

    metas.io.r_req.valid := s0_valid && s1_ready
    metas.io.r_req.bits := s0_idx

    datas.io.r_req.valid := s0_valid && s1_ready
    datas.io.r_req.bits := s0_idx

    val meta = metas.io.r_rsp.bits
    val data = datas.io.r_rsp.bits

    /* ------------------- Stage 1 ------------------- */
    val s1_idx = RegInit(0.U(log2Ceil(ICacheParams.sets).W))
    val s1_tag = RegInit(0.U(ICacheParams.TagLen.W))
    val s1_inrange = RegInit(false.B)
    val s1_cmd = RegInit(0.U.asTypeOf(req.bits.cmd))

    when (s0_valid && s1_ready) {
        s1_valid := s0_valid
        s1_inrange := s0_inrange
        s1_idx := s0_idx
        s1_tag := s0_tag
        s1_cmd := s0_cmd
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_inrange := false.B
        s1_idx := 0.U
        s1_tag := 0.U
    }

    val hit_vec = meta.map(t => t.valid && t.tag === s1_tag)
    val hit_way = PriorityEncoder(hit_vec)

    val hit = s1_valid && s1_inrange && hit_vec.reduce(_ || _)

    // fetcher
    val has_req = RegInit(false.B)
    when (fetcher_req.fire) {
        has_req := true.B
    }
    when (fetcher_rsp.valid) {
        has_req := false.B
    }

    fetcher_req.valid := s1_valid && (s1_inrange && !hit || !s1_inrange) && !has_req
    fetcher_req.bits := Cat(s1_tag, s1_idx, 0.U(log2Ceil(CachelineSize).W))

    val rsp_data = Mux(hit, data(hit_way), fetcher_rsp.bits)
    rsp.valid := s1_valid && (s1_inrange && hit || fetcher_rsp.valid) && s1_cmd === ICacheCMD.READ
    rsp.bits := rsp_data

    s1_ready := (!s1_valid || (s1_valid && hit) || (fetcher_rsp.valid)) && metas.r_req.ready

    val update_way = Mux(hit, hit_way, lru_oldest_vec(s1_idx))
    val update_meta = Wire(new ICacheMeta)
    update_meta.valid := true.B
    update_meta.tag := s1_tag

    // update plru
    for (i <- 0 until ICacheParams.sets) {
        lru_seq(i).io.update.valid := s1_valid && s1_inrange && rsp.valid && i.U === s1_idx
        lru_seq(i).io.update.bits := update_way
    }

    // update data & meta
    metas.io.w_req.valid := fetcher_rsp.valid
    metas.io.w_req.bits.addr := s1_idx
    metas.io.w_req.bits.way := update_way
    metas.io.w_data := update_meta

    datas.io.w_req.valid := fetcher_rsp.valid
    datas.io.w_req.bits.addr := s1_idx
    datas.io.w_req.bits.way := update_way
    datas.io.w_data := fetcher_rsp.bits
    
    // Prefetcher
    pft_hint.valid := s1_valid && s1_inrange && !hit && rsp.valid && s1_cmd === ICacheCMD.READ
    pft_hint.bits.addr := Cat(s1_tag, s1_idx, 0.U(log2Ceil(CachelineSize).W))

    /* ---------------- Performance ----------------  */
    PerfCount("icache_pft_req", s1_valid && s1_cmd === ICacheCMD.PREFETCH)
    PerfCount("icache_hit", s1_valid && hit && s1_inrange && rsp.valid)
    PerfCount("icache_miss", s1_valid && !hit && s1_inrange && rsp.valid)
    PerfCount("icache_nc", s1_valid && !s1_inrange && rsp.valid)
}