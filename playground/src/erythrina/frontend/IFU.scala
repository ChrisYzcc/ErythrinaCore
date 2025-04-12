package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._

class IFU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())

        val fetch_req = Flipped(DecoupledIO(new InstFetchBlock)) // from FTQ
        val fetch_rsp = ValidIO(new InstFetchBlock) // to FTQ

        // AXI4-lite
        val axi = new Bundle {
            val ar = DecoupledIO(new AXI4LiteBundleA)
            val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = 64)))
        }
    })

    val (fetch_req, fetch_rsp) = (io.fetch_req, io.fetch_rsp)
    val axi = io.axi

    // TODO: use pipeline
    val sIDLE :: sREQ :: sRECV :: Nil = Enum(3)
    val state = RegInit(sIDLE)
    switch (state) {
        is (sIDLE) {
            when (!reset.asBool) {
                state := sREQ
            }
        }
        is (sREQ) {
            when (axi.ar.fire) {
                state := sRECV
            }
        }
        is (sRECV) {
            when (axi.r.fire) {
                state := sREQ
            }
        }
    }

    axi.ar.valid        := fetch_req.valid && state === sREQ && !io.flush
    axi.ar.bits         := 0.U.asTypeOf(new AXI4LiteBundleA)
    axi.ar.bits.addr    := Mux(fetch_req.bits.instVec(0).valid, fetch_req.bits.instVec(0).pc, fetch_req.bits.instVec(1).pc)     // TODO: alignment?
    
    axi.r.ready     := state === sRECV

    // response to FTQ
    fetch_req.ready := axi.ar.ready && state === sREQ
    
    val inflight_block = RegInit(0.U.asTypeOf(new InstFetchBlock))
    when (fetch_req.fire) {
        inflight_block  := fetch_req.bits
    }
    val rsp_block = inflight_block
    rsp_block.instVec(0).instr  := axi.r.bits.data(XLEN - 1, 0)
    rsp_block.instVec(1).instr  := axi.r.bits.data(2 * XLEN - 1, XLEN)

    fetch_rsp.valid := axi.r.fire && state === sRECV
    fetch_rsp.bits  := rsp_block
}