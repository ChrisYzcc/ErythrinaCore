package erythrina

import chisel3._
import chisel3.util._
import bus.axi4._
import erythrina.frontend.Frontend
import erythrina.backend.Backend
import erythrina.memblock.Memblock
import difftest.DifftestInfos
import erythrina.frontend.icache._
import device._
import erythrina.AddrSpace.addr_space

abstract class ErythBundle extends Bundle with HasErythCoreParams

abstract class ErythModule extends Module with HasErythCoreParams

class ErythrinaCore extends ErythModule {
    val io = IO(new Bundle {
        val master_axi = new AXI4
        val difftest = Vec(CommitWidth, ValidIO(new DifftestInfos))
    })

    val i_axi = WireInit(0.U.asTypeOf(new AXI4))
    val d_axi = WireInit(0.U.asTypeOf(new AXI4))

    val axi_arb = Module(new AXI4Arbiter(2))
    axi_arb.io.in(0) <> d_axi
    axi_arb.io.in(1) <> i_axi

    val axi_xbar = Module(new AXI4XBar(addr_space))
    val axi_clint = Module(new AXI4CLINT)

    axi_xbar.io.in <> axi_arb.io.out
    axi_xbar.io.out(1) <> axi_clint.io.axi
    axi_xbar.io.out(0) <> io.master_axi

    val frontend = Module(new Frontend)
    val backend = Module(new Backend)
    val memblock = Module(new Memblock)

    val icache = Module(new ICache)
    icache.io.req <> frontend.io.icache_req
    icache.io.rsp <> frontend.io.icache_rsp
    icache.io.axi <> i_axi

    memblock.io.axi <> d_axi

    frontend.io.to_backend <> backend.io.from_frontend
    frontend.io.from_backend <> backend.io.to_frontend
    
    backend.io.to_memblock <> memblock.io.from_backend
    backend.io.difftest <> io.difftest
    
    memblock.io.to_backend <> backend.io.from_memblock
}