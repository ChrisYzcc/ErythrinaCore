package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.{ErythBundle, ErythModule}
import erythrina.frontend.icache.ICacheParams.CachelineSize

class PftHints extends ErythBundle {
    val addr = UInt(XLEN.W)
}

class NxtPrefetcher extends ErythModule {
    val io = IO(new Bundle {
        val pft_hint = Flipped(ValidIO(new PftHints))
        val pft_req = DecoupledIO(new ICacheReq)
    })

    val (pft_hint, pft_req) = (io.pft_hint, io.pft_req)

    pft_req.valid := RegNext(pft_hint.valid) && ICacheParams.UsePft
    pft_req.bits.addr := RegNext(pft_hint.bits.addr + (CachelineSize).U)
    pft_req.bits.cmd := ICacheCMD.PREFETCH
}

class Prefetcher extends ErythModule {
    val io = IO(new Bundle {
        val pft_hint = Flipped(ValidIO(new PftHints))
        val pft_req = DecoupledIO(new ICacheReq)
    })

    val nxt_prefetcher = Module(new NxtPrefetcher)
    nxt_prefetcher.io.pft_hint <> io.pft_hint
    nxt_prefetcher.io.pft_req <> io.pft_req
}