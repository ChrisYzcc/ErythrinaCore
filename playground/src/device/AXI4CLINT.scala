package device

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4.AXI4Lite
import utils.LookupTree

object AXI4CLINTAddr {
    def rtc_l = 0xa0000048L.U
    def rtc_h = 0xa000004cL.U
}

class AXI4CLINT extends ErythModule {
    val io = IO(new Bundle {
        val axi = Flipped(new AXI4Lite)
    })

    val axi = io.axi
    assert(!(axi.aw.valid || axi.w.valid), "AXI4CLINT does not support write operations")

    val sREQ :: sRSP :: Nil = Enum(2)
    val state = RegInit(sREQ)
    switch (state) {
        is (sREQ) {
            when (axi.ar.fire) {
                state := sRSP
            }
        }
        is (sRSP) {
            when (axi.r.fire) {
                state := sREQ
            }
        }
    }

    // AR
    axi.ar.ready := state === sREQ
    val addr_r = RegEnable(axi.ar.bits.addr, 0.U, axi.ar.fire)

    // R
    val mtime = RegInit(0.U(64.W))
    mtime := mtime + 1.U

    axi.r.valid := state === sRSP
    axi.r.bits := 0.U.asTypeOf(axi.r.bits)
    axi.r.bits.data := LookupTree(addr_r, Seq(
        AXI4CLINTAddr.rtc_l -> Cat(Fill(XLEN, 0.B), mtime(31,0)),
        AXI4CLINTAddr.rtc_h -> Cat(Fill(XLEN, 0.B), mtime(63,32))
    ))
}