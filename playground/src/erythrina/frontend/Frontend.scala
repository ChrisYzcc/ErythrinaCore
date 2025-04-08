package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._
import utils.StageConnect

// Frontend Top
class FrontEnd extends ErythModule {
    val io = IO(new Bundle {
        val to_backend = Vec(DecodeWidth, Decoupled(new InstExInfo))

        // AXI4-Lite
        val ar = DecoupledIO(new AXI4LiteBundleA)
        val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = 64)))

        // redirect?
    })

    val ftq = Module(new FTQ)
    val idu = Module(new IDU)
    val ifu = Module(new IFU)
    val bpu = Module(new BPU)

    ftq.io.fetch_req    <> ifu.io.fetch_req
    ftq.io.fetch_rsp    <> ifu.io.fetch_rsp
    ftq.io.pred_req     <> bpu.io.ftq_pred_req
    ftq.io.pred_rsp     <> bpu.io.ftq_pred_rsp
    ftq.io.enq_req      <> bpu.io.ftq_enq_req
    
    StageConnect(ftq.io.decode_req, idu.io.decode_req)
    
    ifu.io.axi.ar       <> io.ar
    ifu.io.axi.r        <> io.r

    idu.io.decode_res   <> io.to_backend
}