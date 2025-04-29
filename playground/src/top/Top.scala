package top

import chisel3._
import chisel3.util._
import erythrina._
import bus.axi4._
import device._
import difftest.DifftestBox
import utils.PerfBox

class SimTop extends Module {
    Config.RESETVEC = 0x80000000L
    Config.ICacheRange = (0x80000000L, 0x88000000L)

    val core = Module(new ErythrinaCore)

    val axi_arb = Module(new AXI4Arbiter(2))
    axi_arb.io.in(0) <> core.io.d_axi
    axi_arb.io.in(1) <> core.io.i_axi

    val axi_memory = Module(new AXI4Memory)
    axi_memory.axi <> axi_arb.io.out

    val difftest = Module(new DifftestBox)
    difftest.io.diff_infos <> core.io.difftest

    if (Config.enablePerf) {
        val perfBox = Module(new PerfBox)
    }
}

class PerfTop extends Module {
    Config.isTiming = true
    val io = IO(new AXI4)

    val core = Module(new ErythrinaCore)

    val axi_arb = Module(new AXI4Arbiter(2))
    axi_arb.io.in(0) <> core.io.d_axi
    axi_arb.io.in(1) <> core.io.i_axi

    axi_arb.io.out <> io
}

class YSYXTop extends Module {
    val io = IO(new Bundle {
        val master = new AXI4
        val slave = Flipped(new AXI4)
        val interrupt = Input(Bool())
    })

    val core = Module(new ErythrinaCore)
}