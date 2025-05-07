package device

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4.AXI4
import utils.LookupTree

object AXI4CLINTAddr {
    def rtc_l = 0x2000000L.U
    def rtc_h = 0x2000004L.U
}

class AXI4CLINT extends ErythModule {
    val io = IO(new Bundle {
        val axi = Flipped(new AXI4)
    })

    val axi = io.axi
    assert(!(axi.aw.valid || axi.w.valid), "AXI4CLINT does not support write operations")
    axi.aw <> DontCare
    axi.w <> DontCare
    axi.b <> DontCare

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
    axi.r.bits.data := LookupTree(addr_r, List(
        AXI4CLINTAddr.rtc_l -> (mtime(31, 0)),
        AXI4CLINTAddr.rtc_h -> (mtime(63,32))
    ))
    axi.r.bits.last := true.B
}