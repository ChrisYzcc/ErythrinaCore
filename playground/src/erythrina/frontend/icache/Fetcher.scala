package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4.AXI4
import utils.PerfCount
import erythrina.frontend.icache.ICacheParams._
import bus.axi4.AXI4Params

class Fetcher extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val req_addr = RegInit(0.U(XLEN.W))
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
        req_addr := req.bits
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

    axi.w <> DontCare
    axi.aw <> DontCare
    axi.b <> DontCare

    /* ---------------- Performance ----------------  */
    PerfCount("icache_miss_penalty_tot", (state === sREQ || state === sRECV || state === sRSP))
}