package erythrina.memblock.pipeline

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import bus.axi4._
import utils.{MaskExpand, LookupTree, SignExt, ZeroExt}
import erythrina.backend.fu.{LDUop, EXUInfo}
import erythrina.frontend.FuType
import erythrina.memblock.StoreFwdBundle
import erythrina.backend.rob.ROBPtr
import erythrina.backend.Redirect

class LDU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val axi = new Bundle {
            val ar = DecoupledIO(new AXI4LiteBundleA)
            val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = XLEN)))
        }

        val sq_fwd = Vec(StoreQueSize, Flipped(ValidIO(new StoreFwdBundle)))

        val stu_fwd = Vec(2, Flipped(ValidIO(new StoreFwdBundle)))
        
        val exu_info = Output(new EXUInfo)      // to Backend
        val ldu_cmt = ValidIO(new InstExInfo)

        val redirect = Flipped(ValidIO(new Redirect))
    })

    def getNewestFwd(fwd: Seq[ValidIO[StoreFwdBundle]], addr: UInt, ptr: ROBPtr): Valid[StoreFwdBundle] = {
        val res = Wire(Valid(new StoreFwdBundle))
        if (fwd.length == 0) {
            res.valid := false.B
            res.bits := 0.U.asTypeOf(new StoreFwdBundle)
        }
        if (fwd.length == 1) {
            res.valid := fwd(0).valid && fwd(0).bits.addr === addr
            res.bits := fwd(0).bits
        }
        if (fwd.length == 2) {
            res.valid := fwd(0).valid && fwd(0).bits.addr === addr ||
                         fwd(1).valid && fwd(1).bits.addr === addr
            
            val cmp = fwd(0).bits.robPtr > fwd(1).bits.robPtr   // 0 is newer
            res.bits := Mux(cmp,
                Mux(fwd(0).valid && fwd(0).bits.addr === addr, fwd(0).bits, fwd(1).bits),
                Mux(fwd(1).valid && fwd(1).bits.addr === addr, fwd(1).bits, fwd(0).bits)
            )
        }
        if (fwd.length > 2) {
            val l_res = getNewestFwd(fwd.take(fwd.length / 2), addr, ptr)
            val r_res = getNewestFwd(fwd.drop(fwd.length / 2), addr, ptr)
            res.valid := l_res.valid || r_res.valid

            val cmp = l_res.bits.robPtr > r_res.bits.robPtr   // left is newer
            res.bits := Mux(cmp,
                Mux(l_res.valid && l_res.bits.addr === addr, l_res.bits, r_res.bits),
                Mux(r_res.valid && r_res.bits.addr === addr, r_res.bits, l_res.bits)
            )
        }
        res
    }

    val (req, axi) = (io.req, io.axi)
    val redirect = io.redirect

    val axi_req_inflight_cnt = RegInit(0.U(2.W))
    when (axi.ar.fire) {
        axi_req_inflight_cnt := axi_req_inflight_cnt + 1.U
    }
    when (axi.r.fire) {
        axi_req_inflight_cnt := axi_req_inflight_cnt - 1.U
    }

    // TODO: use pipeline
    val sREQ :: sRECV :: Nil = Enum(2)
    val state = RegInit(sREQ)
    switch (state) {
        is (sREQ) {
            when (axi.ar.fire && !redirect.valid) {
                state := sRECV
            }
        }
        is (sRECV) {
            when (axi.r.fire && axi_req_inflight_cnt === 1.U || redirect.valid) {
                state := sREQ
            }
        }
    }

    req.ready := state === sREQ

    // AXI
    val addr = req.bits.src1 + req.bits.imm
    axi.ar.valid        := req.valid && state === sREQ && !redirect.valid
    axi.ar.bits         := 0.U.asTypeOf(new AXI4LiteBundleA)
    axi.ar.bits.addr    := addr

    axi.r.ready := state === sRECV

    // Generate Data
    val req_inflight = RegEnable(req.bits, req.valid && state === sREQ)
    val addr_inflight = RegEnable(addr, req.valid && state === sREQ)

    val (sq_fwd, stu_fwd) = (io.sq_fwd, io.stu_fwd)
    val sq_newest_fwd = getNewestFwd(sq_fwd, addr_inflight, req_inflight.robPtr)
    val stu_newest_fwd = getNewestFwd(stu_fwd, addr_inflight, req_inflight.robPtr)

    val final_fwd = Wire(Valid(new StoreFwdBundle))
    final_fwd.valid := sq_newest_fwd.valid || stu_newest_fwd.valid
    val cmp = sq_newest_fwd.bits.robPtr > stu_newest_fwd.bits.robPtr   // storequeue is newer
    final_fwd.bits := Mux(cmp,
        Mux(sq_newest_fwd.valid && sq_newest_fwd.bits.addr === addr_inflight, sq_newest_fwd.bits, stu_newest_fwd.bits),
        Mux(stu_newest_fwd.valid && stu_newest_fwd.bits.addr === addr_inflight, stu_newest_fwd.bits, sq_newest_fwd.bits)
    )

    val fwd_valid = final_fwd.valid
    val fwd_data = final_fwd.bits.data
    val fwd_mask = final_fwd.bits.mask

    val axi_data = axi.r.bits.data
    val axi_mask_exp = MaskExpand(~Mux(fwd_valid, ~fwd_mask, 0.U(MASKLEN.W)))

    val fwd_mask_exp = MaskExpand(Mux(fwd_valid, fwd_mask, 0.U(MASKLEN.W)))

    val data = axi_data & axi_mask_exp | fwd_data & fwd_mask_exp

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
    cmt_instBlk.addr := addr_inflight
    cmt_instBlk.state.finished := true.B

    io.ldu_cmt.valid := (axi.r.fire && axi_req_inflight_cnt === 1.U) && state === sRECV && !redirect.valid
    io.ldu_cmt.bits := cmt_instBlk

    // EXU Info
    val exu_info = io.exu_info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.ldu) := true.B

    exu_info.busy := state === sREQ
    exu_info.fu_type_vec := handler_vec.asUInt
}