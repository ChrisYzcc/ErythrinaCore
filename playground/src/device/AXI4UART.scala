package device

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4.AXI4Lite

class AXI4URAT extends ErythModule {
    val io = IO(new Bundle {
        val axi = Flipped(new AXI4Lite)
    })

    val axi = io.axi
    assert(!axi.ar.valid, "AXI4UART does not support read operations")

    val aw_fire_reg = RegInit(false.B)
    when (axi.aw.fire) {
        aw_fire_reg := true.B
    }.elsewhen (axi.b.fire) {
        aw_fire_reg := false.B
    }

    val w_fire_reg = RegInit(false.B)
    when (axi.w.fire) {
        w_fire_reg := true.B
    }.elsewhen (axi.b.fire) {
        w_fire_reg := false.B
    }

    val aw_has_fire = aw_fire_reg || axi.aw.fire
    val w_has_fire = w_fire_reg || axi.w.fire

    val sREQ :: sRSP :: Nil = Enum(2)
    val state = RegInit(sREQ)
    switch(state) {
        is (sREQ) {
            when (w_has_fire && aw_has_fire) {
                state := sRSP
            }
        }
        is (sRSP) {
            when (axi.b.fire) {
                state := sREQ
            }
        }
    }

    // AW
    axi.aw.ready := state === sREQ
    val addr_w = RegEnable(axi.aw.bits.addr, 0.U, axi.aw.fire)

    // W
    axi.w.ready := state === sREQ
    val data_w = RegEnable(axi.w.bits.data, 0.U, axi.w.fire)
    val mask_w = RegEnable(axi.w.bits.strb, 0.U, axi.w.fire)

    // B
    axi.b.valid := state === sRSP
    axi.b.bits := 0.U.asTypeOf(axi.b.bits)
    
    val uart_reg = RegInit(0.U(XLEN.W))
    when (state === sRSP) {
        uart_reg := uart_reg & ~mask_w | data_w & mask_w
    }

    when (RegNext(axi.b.fire)) {
        printf("%c", uart_reg(7, 0))
    }
}