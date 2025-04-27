package erythrina

import chisel3._
import chisel3.util._
import bus.axi4._
import erythrina.frontend.Frontend
import erythrina.backend.Backend
import erythrina.memblock.Memblock
import difftest.DifftestInfos
import erythrina.frontend.icache.ICacheDummy

abstract class ErythBundle extends Bundle with HasErythCoreParams

abstract class ErythModule extends Module with HasErythCoreParams

class ErythrinaCore extends ErythModule {
    val io = IO(new Bundle {
        val i_axi = new AXI4
        val d_axi = new AXI4
        val difftest = Vec(CommitWidth, ValidIO(new DifftestInfos))
    })

    val (i_axi, d_axi) = (io.i_axi, io.d_axi)

    val frontend = Module(new Frontend)
    val backend = Module(new Backend)
    val memblock = Module(new Memblock)

    val icache = Module(new ICacheDummy)
    icache.io.req <> frontend.io.icache_req
    icache.io.rsp <> frontend.io.icache_rsp
    icache.io.axi <> i_axi

    memblock.io.axi.ldu.ar <> d_axi.ar
    memblock.io.axi.ldu.r <> d_axi.r
    memblock.io.axi.stu.aw <> d_axi.aw
    memblock.io.axi.stu.w <> d_axi.w
    memblock.io.axi.stu.b <> d_axi.b

    frontend.io.to_backend <> backend.io.from_frontend
    frontend.io.from_backend <> backend.io.to_frontend
    
    backend.io.to_memblock <> memblock.io.from_backend
    backend.io.difftest <> io.difftest
    
    memblock.io.to_backend <> backend.io.from_memblock
}