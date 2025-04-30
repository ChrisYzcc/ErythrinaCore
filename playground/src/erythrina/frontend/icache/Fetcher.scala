package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4.AXI4
import utils.PerfCount
import erythrina.frontend.icache.ICacheParams._

class Fetcher extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val req_num = RegInit(0.U(4.W))
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
            when (axi.r.fire) {
                when (req_num === 0.U) {
                    state := sRSP
                }.otherwise {
                    state := sREQ
                }
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
    }.elsewhen(axi.ar.fire) {
        req_addr := req_addr + 4.U
    }

    // req_num
    when (req.fire) {
        req_num := (ICacheParams.CachelineSize / 4).U
    }.elsewhen(axi.ar.fire) {
        req_num := req_num - 1.U
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