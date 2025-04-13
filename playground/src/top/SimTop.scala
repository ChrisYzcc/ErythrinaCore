package top

import chisel3._
import chisel3.util._
import erythrina._
import bus.axi4._
import device._
import difftest.DifftestBox

class SimTop extends Module {
    Config.RESETVEC = 0x80000000L

    val core = Module(new ErythrinaCore)
    val axi_arbiter = Module(new AXI4ArbiterNto1(2, new AXI4Lite))

    axi_arbiter.io.in(0) <> core.io.i_axi
    axi_arbiter.io.in(1) <> core.io.d_axi

    val axi_simpleRam = Module(new AX4ISimpleRam(new AXI4Lite))
    axi_simpleRam.io.axi <> axi_arbiter.io.out
    
    val difftest = Module(new DifftestBox)
    difftest.io.diff_infos <> core.io.difftest
}