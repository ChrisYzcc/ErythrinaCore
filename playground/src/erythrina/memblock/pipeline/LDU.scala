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
import erythrina.AddrSpace
import erythrina.memblock.dcache._

class LDU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val dcache_req = DecoupledIO(new DCacheReq)
        val dcache_resp = Flipped(ValidIO(new DCacheResp))

        val st_fwd_query = ValidIO(new StoreFwdBundle)
        val st_fwd_result = Input(new StoreFwdBundle)
        
        val exu_info = Output(new EXUInfo)      // to Backend
        val ldu_cmt = ValidIO(new InstExInfo)

        val rf_write = ValidIO(new Bundle {
            val addr = UInt(PhyRegAddrBits.W)
            val data = UInt(XLEN.W)
        })
        val bt_free_req = ValidIO(UInt(PhyRegAddrBits.W))

        val redirect = Flipped(ValidIO(new Redirect))
    })

    val req = io.req
    val (dcache_req, dcache_resp) = (io.dcache_req, io.dcache_resp)
    val redirect = io.redirect

    val req_addr_err = Wire(Bool())

    val sIDLE :: sREQ :: sRECV :: sDROP :: sERR :: Nil = Enum(5)
    val state = RegInit(sIDLE)

    switch (state) {
        is (sIDLE) {
            when (req.fire && !redirect.valid) {
                state := sREQ
            }
        }
        is (sREQ) {
            when (redirect.valid) {
                state := sIDLE
            }.elsewhen(dcache_req.fire && !req_addr_err) {
                state := sRECV
            }.elsewhen(req_addr_err) {
                state := sERR
            }
        }
        is (sRECV) {
            when (dcache_resp.valid && dcache_resp.bits.cmd === DCacheCMD.READ) {
                state := sIDLE
            }.elsewhen(redirect.valid) {
                state := sDROP
            }
        }
        is (sDROP) {
            when (dcache_resp.valid && dcache_resp.bits.cmd === DCacheCMD.READ) {
                state := sIDLE
            }
        }
        is (sERR) {
            state := sIDLE
        }
    }

    req.ready := state === sIDLE

    // sREQ
    val req_task = RegEnable(req.bits, 0.U.asTypeOf(new InstExInfo), req.fire)
    val addr = (req_task.src1 + req_task.src2)

    val req_addr = Cat(addr(XLEN - 1, 2), 0.U(2.W))
    req_addr_err := !AddrSpace.in_addr_space(req_addr)

    dcache_req.valid := state === sREQ && !redirect.valid && !req_addr_err
    dcache_req.bits := 0.U.asTypeOf(new DCacheReq)
    dcache_req.bits.cmd := DCacheCMD.READ
    dcache_req.bits.addr := req_addr

    val req_out_task = WireInit(req_task)
    req_out_task.addr := addr
    req_out_task.exception.exceptions.load_access_fault := req_addr_err

    // sRECV
    val recv_task = RegEnable(req_out_task, 0.U.asTypeOf(new InstExInfo), dcache_req.fire)

    val recv_addr = recv_task.addr

    val (fwd_query, fwd_result) = (io.st_fwd_query, io.st_fwd_result)
    fwd_query.valid := state === sRECV && !redirect.valid
    fwd_query.bits := 0.U.asTypeOf(new StoreFwdBundle)
    fwd_query.bits.addr := Cat(recv_addr(XLEN - 1, 2), 0.U(2.W))
    fwd_query.bits.robPtr := recv_task.robPtr

    val mask_frm_fwd = fwd_result.mask
    val data_frm_fwd = fwd_result.data

    val axi_data = dcache_resp.bits.data
    val axi_mask_exp = MaskExpand(~mask_frm_fwd)

    val fwd_mask_exp = MaskExpand(mask_frm_fwd)

    val data = axi_data & axi_mask_exp | data_frm_fwd & fwd_mask_exp

    val byte_res = LookupTree(recv_addr(1, 0), List(
        "b00".U -> data(7, 0),
        "b01".U -> data(15, 8),
        "b10".U -> data(23, 16),
        "b11".U -> data(31, 24)
    ))

    val byte_mask = LookupTree(recv_addr(1, 0), List(
        "b00".U -> "b0001".U,
        "b01".U -> "b0010".U,
        "b10".U -> "b0100".U,
        "b11".U -> "b1000".U
    ))

    val hword_res = LookupTree(recv_addr(1), List(
        "b0".U -> data(15, 0),
        "b1".U -> data(31, 16)
    ))

    val hword_mask = LookupTree(recv_addr(1), List(
        "b0".U -> "b0011".U,
        "b1".U -> "b1100".U
    ))

    val res = LookupTree(recv_task.fuOpType, List(
        LDUop.lb -> SignExt(byte_res, XLEN),
        LDUop.lbu -> ZeroExt(byte_res, XLEN),
        LDUop.lh -> SignExt(hword_res, XLEN),
        LDUop.lhu -> ZeroExt(hword_res, XLEN),
        LDUop.lw -> data,
    ))

    val mask = LookupTree(recv_task.fuOpType, List(
        LDUop.lb -> byte_mask,
        LDUop.lbu -> byte_mask,
        LDUop.lh -> hword_mask,
        LDUop.lhu -> hword_mask,
        LDUop.lw -> "b1111".U
    ))
    fwd_query.bits.mask := mask

    val r_has_err = recv_task.exception.exceptions.load_access_fault

    val recv_res_blk = WireInit(recv_task)
    recv_res_blk.res := res
    recv_res_blk.mask := mask
    recv_res_blk.addr := Cat(recv_addr(XLEN - 1, 2), 0.U(2.W))
    recv_res_blk.state.finished := true.B

    // sERR
    val err_task = RegEnable(req_out_task, 0.U.asTypeOf(new InstExInfo), state === sREQ && req_addr_err)
    val err_res_blk = WireInit(err_task)
    err_res_blk.addr := Cat(err_task.addr(XLEN - 1, 2), 0.U(2.W))
    err_res_blk.state.finished := true.B
    err_res_blk.exception.exceptions.load_access_fault := true.B

    // Commit
    io.ldu_cmt.valid := (dcache_req.fire && state === sRECV || state === sERR) && !redirect.valid
    io.ldu_cmt.bits := Mux(state === sRECV, recv_res_blk, err_res_blk)
    
    io.rf_write.valid := io.ldu_cmt.valid && io.ldu_cmt.bits.rf_wen
    io.rf_write.bits.addr := io.ldu_cmt.bits.p_rd
    io.rf_write.bits.data := io.ldu_cmt.bits.res

    io.bt_free_req.valid := io.ldu_cmt.valid && io.ldu_cmt.bits.rf_wen
    io.bt_free_req.bits := io.ldu_cmt.bits.p_rd

    // EXU Info
    val exu_info = io.exu_info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.ldu) := true.B

    exu_info.busy := false.B
    exu_info.fu_type_vec := handler_vec.asUInt
}