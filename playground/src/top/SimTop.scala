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

    val axi_simpleRam_i = Module(new AX4ISimpleRam(new AXI4Lite))
    val axi_simpleRam_d = Module(new AX4ISimpleRam(new AXI4Lite))

    core.io.i_axi <> axi_simpleRam_i.io.axi
    core.io.d_axi <> axi_simpleRam_d.io.axi
    
    val difftest = Module(new DifftestBox)
    difftest.io.diff_infos <> core.io.difftest

    if (Config.enablePerf) {
        val perfBox = Module(new PerfBox)
    }
}