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

    val core = Module(new ErythrinaCore)

    val axi_arb = Module(new AXI4LiteArbiter(2))
    axi_arb.io.in(0) <> core.io.d_axi
    axi_arb.io.in(1) <> core.io.i_axi

    val axi_simpleRam = Module(new AX4ISimpleRam(new AXI4Lite))
    axi_simpleRam.io.axi <> axi_arb.io.out
    
    val difftest = Module(new DifftestBox)
    difftest.io.diff_infos <> core.io.difftest

    if (Config.enablePerf) {
        val perfBox = Module(new PerfBox)
    }
}

class YSYXTop extends Module {
    val io = IO(new Bundle {
        val master = new AXI4
        val slave = Flipped(new AXI4)
        val interrupt = Input(Bool())
    })

    val core = Module(new ErythrinaCore)
}