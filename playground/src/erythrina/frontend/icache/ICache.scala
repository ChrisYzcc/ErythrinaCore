package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import bus.axi4._
import erythrina.{ErythModule, ErythBundle}
import erythrina.frontend.InstFetchBlock

class ICacheParams(
    val CacheableRange : (0xa0000000L, 0xbfffffffL)
)

class DummyICache extends ErythModule {
    val io = IO(new Bundle {
        val axi = new AXI4
        val req = Flipped(ValidIO(new InstFetchBlock))
        val rsp = ValidIO(new InstFetchBlock)
    })

    
}