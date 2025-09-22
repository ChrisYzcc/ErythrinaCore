package erythrina.frontend

/**
  * Filter for FTQ prefetch requests.
  * Only allow prefetch requests that are not in the same cacheline.
  */

import chisel3._
import chisel3.util._
import erythrina.{ErythBundle, ErythModule}
import erythrina.frontend.icache.ICacheParams

class FTQFilter extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(UInt(XLEN.W)))
        val out = DecoupledIO(UInt(XLEN.W))
    })

    val (in, out) = (io.in, io.out)

    val last_base = RegInit(0.U((XLEN - log2Ceil(ICacheParams.CachelineSize)).W))
    val in_base = in.bits(XLEN - 1, log2Ceil(ICacheParams.CachelineSize))

    out.valid := in.valid && (in_base =/= last_base)
    out.bits := Cat(in_base, 0.U(log2Ceil(ICacheParams.CachelineSize).W))
    in.ready := out.ready || (in_base === last_base)
    
    when (out.fire) {
        last_base := in_base
    }
}