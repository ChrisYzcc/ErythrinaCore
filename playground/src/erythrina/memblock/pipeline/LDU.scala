package erythrina.memblock.pipeline

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import bus.axi4._

class LDU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val axi = new Bundle {
            val ar = DecoupledIO(new AXI4LiteBundleA)
            val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = XLEN)))
        }
        
        val sq_fwd_req = ValidIO(UInt(XLEN.W))
        val sq_fwd_rsp = Flipped(ValidIO(new Bundle {
            val data = UInt(XLEN.W)
            val mask = UInt(XLEN.W)
        }))
    })

    val (req, axi) = (io.req, io.axi)

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

    // AXI
    val addr = req.bits.src1 + req.bits.imm
    axi.ar.valid        := req.valid && state === sREQ
    axi.ar.bits         := 0.U.asTypeOf(new AXI4LiteBundleA)
    axi.ar.bits.addr    := addr

    axi.r.ready := state === sRECV

    // SQ fwd
    val (sq_fwd_req, sq_fwd_rsp) = (io.sq_fwd_req, io.sq_fwd_rsp)
    sq_fwd_req.valid := req.valid && state === sREQ
    sq_fwd_req.bits := addr

    val sq_fwd_valid = RegInit(false.B)
    val sq_fwd_data = RegInit(0.U(XLEN.W))
    val sq_fwd_mask = RegInit(0.U(XLEN.W))
    
    when (state === sREQ && axi.ar.fire) {
        sq_fwd_valid := sq_fwd_rsp.valid
        sq_fwd_data := sq_fwd_rsp.bits.data
        sq_fwd_mask := sq_fwd_rsp.bits.mask
    }

    // Generate Data
    val axi_data = axi.r.bits.data

    
}