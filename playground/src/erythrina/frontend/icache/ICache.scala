package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import bus.axi4._
import erythrina.{ErythModule, ErythBundle}
import erythrina.frontend.InstInfo

class DummyICache extends ErythModule {
    val io = IO(new Bundle {
        val axi_master = new AXI4

        val axi_slave = new Bundle {
            val ar = Flipped(new AXI4LiteBundleA)
            val r = DecoupledIO(new AXI4LiteBundleR(dataBits = 64))
        }
    })

    val (axi_master, axi_slave) = (io.axi_master, io.axi_slave)

    
}