package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._
import utils.StageConnect
import erythrina.backend.{InstExInfo, Redirect}
import utils.PerfCount

// Frontend Top
class Frontend extends ErythModule {
    val io = IO(new Bundle {
        val from_backend = new Bundle {
            val flush = Input(Bool())
            val redirect = Flipped(ValidIO(new Redirect))
        }
        val to_backend = new Bundle {
            val rename_req = DecoupledIO(Vec(DecodeWidth, Valid(new InstExInfo)))
        }

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
    ftq.io.enq_req      <> bpu.io.ftq_enq_req
    
    ftq.io.decode_req   <> idu.io.decode_req
    
    ifu.io.axi.ar       <> io.ar
    ifu.io.axi.r        <> io.r

    idu.io.decode_res   <> io.to_backend.rename_req

    /* -------------------- TopDown -------------------- */
    PerfCount("topdown_TotalSlots", DecodeWidth.U)
    PerfCount("topdown_SlotsIssued", Mux(io.to_backend.rename_req.fire, PopCount(io.to_backend.rename_req.bits.map(_.valid)), 0.U))
    val fetch_bubble_slots = Mux(io.to_backend.rename_req.valid, PopCount(io.to_backend.rename_req.bits.map(!_.valid)), DecodeWidth.U)
    PerfCount("topdown_FetchBubbles", Mux(io.to_backend.rename_req.ready && !(io.from_backend.flush || io.from_backend.redirect.valid), fetch_bubble_slots, 0.U))
    PerfCount("topdown_RecoveryBubbles", Mux((io.from_backend.flush || io.from_backend.redirect.valid) && io.to_backend.rename_req.ready, DecodeWidth.U, 0.U))
}