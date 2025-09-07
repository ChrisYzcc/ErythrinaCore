package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.{ErythBundle, ErythModule}
import bus.axi4._
import utils.PerfCount

class FetcherReq extends ErythBundle {
    val addr = UInt(XLEN.W)
    val from_mainpipe = Bool()
}

class Fetcher extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new FetcherReq))

        // Update data & meta array
        val data_wr_req = DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
            val way = UInt(log2Ceil(ICacheParams.ways).W)
            val data = UInt((ICacheParams.CachelineSize * 8).W)
        })

        val meta_wr_req = DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
            val way = UInt(log2Ceil(ICacheParams.ways).W)
            val meta = new ICacheMeta
        })

        // Query Replacer
        val replacer_req_idx = Output(UInt(log2Ceil(ICacheParams.sets).W))
        val replacer_rsp_way = Input(UInt(log2Ceil(ICacheParams.ways).W))

        // Update Replacer
        val replacer_upt_req = ValidIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
            val way = UInt(log2Ceil(ICacheParams.ways).W)
        })

        // rsp
        val rsp = ValidIO(UInt((ICacheParams.CachelineSize * 8).W))
        val rsp_to_mainpipe = Output(Bool())

        // AXI
        val axi = new AXI4
    })

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val req_addr = RegInit(0.U(XLEN.W))
    val req_from_mainpipe = RegInit(false.B)
    val rsp_data_ptr = RegInit(0.U(log2Ceil(ICacheParams.CachelineSize).W))
    val rsp_data_vec = RegInit(VecInit(Seq.fill(ICacheParams.CachelineSize / 4)(0.U(XLEN.W))))

    val sIDLE :: sREQ :: sRECV :: sRSP :: Nil = Enum(4)
    val state = RegInit(sIDLE)
    switch (state) {
        is (sIDLE) {
            when (req.fire) {
                state := sREQ
            }
        }
        is (sREQ) {
            when (axi.ar.fire) {
                state := sRECV
            }
        }
        is (sRECV) {
            when (axi.r.fire && axi.r.bits.last) {
                state := sRSP
            }
        }
        is (sRSP) {
            when (rsp.valid) {
                state := sIDLE
            }
        }
    }

    // req_addr
    when (req.fire) {
        req_addr := req.bits.addr
        req_from_mainpipe := req.bits.from_mainpipe
    }

    // rsp_data_ptr
    when (req.fire) {
        rsp_data_ptr := 0.U
    }.elsewhen(axi.r.fire) {
        rsp_data_ptr := rsp_data_ptr + 1.U
    }

    // rsp_data_vec
    when (axi.r.fire) {
        rsp_data_vec(rsp_data_ptr) := axi.r.bits.data
    }

    // axi
    axi.ar.valid := state === sREQ
    axi.ar.bits := 0.U.asTypeOf(axi.ar.bits)
    axi.ar.bits.addr := req_addr
    axi.ar.bits.size := "b010".U    // 4 bytes per transfer
    axi.ar.bits.burst := AXI4Params.BURST_INCR
    axi.ar.bits.len := ((ICacheParams.CachelineSize / 4) - 1).U

    axi.r.ready := state === sRECV

    // req
    req.ready := state === sIDLE

    // rsp
    rsp.valid := state === sRSP
    rsp.bits := rsp_data_vec.asUInt
    io.rsp_to_mainpipe := req_from_mainpipe

    // replacer
    val (replacer_req_idx, replacer_rsp_way) = (io.replacer_req_idx, io.replacer_rsp_way)
    replacer_req_idx := ICacheParams.get_idx(req_addr)

    // replacer update
    io.replacer_upt_req.valid := state === sRSP
    io.replacer_upt_req.bits.idx := ICacheParams.get_idx(req_addr)
    io.replacer_upt_req.bits.way := replacer_rsp_way

    // data & meta wr req
    val (data_wr_req, meta_wr_req) = (io.data_wr_req, io.meta_wr_req)

    data_wr_req.valid := state === sRSP
    data_wr_req.bits.idx := ICacheParams.get_idx(req_addr)
    data_wr_req.bits.way := replacer_rsp_way
    data_wr_req.bits.data := rsp_data_vec.asUInt

    meta_wr_req.valid := state === sRSP
    meta_wr_req.bits.idx := ICacheParams.get_idx(req_addr)
    meta_wr_req.bits.way := replacer_rsp_way
    meta_wr_req.bits.meta.valid := true.B
    meta_wr_req.bits.meta.tag := ICacheParams.get_tag(req_addr)

    axi.w <> DontCare
    axi.aw <> DontCare
    axi.b <> DontCare

    /* ---------------- Performance ----------------  */
    PerfCount("icache_miss_penalty_tot", (state === sREQ || state === sRECV || state === sRSP))
}