package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import bus.axi4._
import erythrina.{ErythModule, ErythBundle}
import erythrina.frontend.InstFetchBlock
import erythrina.frontend.icache.ICacheParams.get_cacheline_blk_offset

object ICacheParams {
    val CacheableRange = (0xa0000000L, 0xbfffffffL)

    val CachelineSize = 16  // 16 Bytes

    def get_cacheline_offset(addr: UInt): UInt = {
        val offset = addr(log2Ceil(CachelineSize) - 1, 0)
        offset
    }

    def get_cacheline_blk_offset(addr: UInt): UInt = {
        val offset = addr(log2Ceil(CachelineSize) - 1, 2)
        offset
    }
}

class ICacheDummy extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val req_num = RegInit(0.U(4.W))
    val req_addr = RegInit(0.U(XLEN.W))
    val rsp_data_idx = RegInit(0.U(log2Ceil(CachelineSize / 4).W))
    val rsp_data_vec = RegInit(VecInit(Seq.fill(CachelineSize / 4)(0.U(XLEN.W))))

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
        req_num := (CachelineSize / 4).U - get_cacheline_blk_offset(req.bits)
    }.elsewhen(axi.ar.fire) {
        req_num := req_num - 1.U
    }

    // rsp_data_idx
    when (req.fire) {
        rsp_data_idx := get_cacheline_blk_offset(req.bits)
    }.elsewhen(axi.r.fire) {
        rsp_data_idx := rsp_data_idx + 1.U
    }

    // rsp_data_vec
    when (axi.r.fire) {
        rsp_data_vec(rsp_data_idx) := axi.r.bits.data
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
    axi.b <> DontCare
    axi.aw <> DontCare
}