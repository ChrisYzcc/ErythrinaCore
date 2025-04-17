package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._
import utils.StageConnect
import erythrina.backend.{InstExInfo, Redirect}

// Frontend Top
class Frontend extends ErythModule {
    val io = IO(new Bundle {
        val from_backend = new Bundle {
            val flush = Input(Bool())
            val redirect = Flipped(ValidIO(new Redirect))
        }
        val to_backend = Vec(DecodeWidth, Decoupled(new InstExInfo))

        // AXI4-Lite
        val ar = DecoupledIO(new AXI4LiteBundleA)
        val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = 64)))
    })

    val (flush, redirect) = (io.from_backend.flush, io.from_backend.redirect)

    val ftq = Module(new FTQ)
    val idu = Module(new IDU)
    val ifu = Module(new IFU)
    val bpu = Module(new BPU)

    ftq.io.flush := flush || io.from_backend.redirect.valid
    idu.io.flush := flush || io.from_backend.redirect.valid
    ifu.io.flush := flush || io.from_backend.redirect.valid
    bpu.io.flush := flush
    bpu.io.redirect <> redirect

    ftq.io.fetch_req    <> ifu.io.fetch_req
    ftq.io.fetch_rsp    <> ifu.io.fetch_rsp
    ftq.io.pred_req     <> bpu.io.ftq_pred_req
    ftq.io.enq_req      <> bpu.io.ftq_enq_req
    
    ftq.io.decode_req   <> idu.io.decode_req
    
    ifu.io.axi.ar       <> io.ar
    ifu.io.axi.r        <> io.r

    idu.io.decode_res   <> io.to_backend
}