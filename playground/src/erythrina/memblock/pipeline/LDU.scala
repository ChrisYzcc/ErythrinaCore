package erythrina.memblock.pipeline

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import bus.axi4._
import utils.{MaskExpand, LookupTree, SignExt, ZeroExt}
import erythrina.backend.fu.{LDUop, EXUInfo}
import erythrina.frontend.FuType

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
        
        val exu_info = Output(new EXUInfo)      // to Backend
        val ldu_cmt = ValidIO(new InstExInfo)
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
    val axi_mask_exp = MaskExpand(~Mux(sq_fwd_valid, ~sq_fwd_mask, 0.U(MASKLEN.W)))

    val fwd_data = sq_fwd_data
    val fwd_mask_exp = MaskExpand(Mux(sq_fwd_valid, sq_fwd_mask, 0.U(MASKLEN.W)))

    val data = axi_data & axi_mask_exp | fwd_data & fwd_mask_exp

    val req_inflight = RegEnable(req.bits, req.valid && state === sREQ)
    val addr_inflight = RegEnable(addr, req.valid && state === sREQ)

    val byte_res = LookupTree(addr_inflight(1, 0), List(
        "b00".U -> data(7, 0),
        "b01".U -> data(15, 8),
        "b10".U -> data(23, 16),
        "b11".U -> data(31, 24)
    ))

    val hword_res = LookupTree(addr_inflight(1), List(
        "b0".U -> data(15, 0),
        "b1".U -> data(31, 16)
    ))

    val res = LookupTree(req_inflight.fuOpType, List(
        LDUop.lb -> SignExt(byte_res, XLEN),
        LDUop.lbu -> ZeroExt(byte_res, XLEN),
        LDUop.lh -> SignExt(hword_res, XLEN),
        LDUop.lhu -> ZeroExt(hword_res, XLEN),
        LDUop.lw -> data,
    ))

    // Cmt
    val cmt_instBlk = WireInit(req_inflight)
    cmt_instBlk.res := res

    io.ldu_cmt.valid := axi.r.fire && state === sRECV
    io.ldu_cmt.bits := cmt_instBlk

    // EXU Info
    val exu_info = io.exu_info
    val handler_vec = WireInit(Vec(FuType.num, false.B))
    handler_vec(FuType.ldu) := true.B

    exu_info.busy := state === sREQ
    exu_info.fu_type_vec := handler_vec.asUInt
}