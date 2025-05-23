package top

import chisel3._
import chisel3.util._
import erythrina._
import bus.axi4._
import device._
import difftest.DifftestBox
import utils.PerfBox
import erythrina.memblock.dcache.DCacheParams
import erythrina.frontend.icache.ICacheParams

class SimTop extends Module {
    Config.RESETVEC = 0x80000000L
    Config.ICacheRange = (0x80000000L, 0x88000000L)
    Config.DCacheRange = (0x80000000L, 0x88000000L)

    val core = Module(new ErythrinaCore)

    val axi_memory = Module(new AXI4Memory)
    axi_memory.axi <> core.io.master_axi

    val difftest = Module(new DifftestBox)
    difftest.io.diff_infos <> core.io.difftest

    if (Config.enablePerf) {
        val perfBox = Module(new PerfBox)
    }
}

class PerfTop extends Module {
    Config.isTiming = true

    ICacheParams.sets = 1024 / (ICacheParams.ways * ICacheParams.CachelineSize)
    DCacheParams.sets = 1024 / (DCacheParams.ways * DCacheParams.CachelineSize)

    val io = IO(new AXI4)

    val core = Module(new ErythrinaCore)

    core.io.master_axi <> io
}

class YSYXTop extends Module {
    val io = IO(new Bundle {
        val master = new AXI4
        val slave = Flipped(new AXI4)
        val interrupt = Input(Bool())
    })

    val core = Module(new ErythrinaCore)
}