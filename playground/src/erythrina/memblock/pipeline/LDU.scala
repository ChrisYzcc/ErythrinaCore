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

    val req_addr = Cat(addr(XLEN - 1, DataAlignBits), 0.U(DataAlignBits.W))
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
    fwd_query.bits.addr := Cat(recv_addr(XLEN - 1, DataAlignBits), 0.U(DataAlignBits.W))
    fwd_query.bits.robPtr := recv_task.robPtr

    val mask_frm_fwd = fwd_result.mask
    val data_frm_fwd = fwd_result.data

    val axi_data = dcache_resp.bits.data
    val axi_mask_exp = MaskExpand(~mask_frm_fwd)

    val fwd_mask_exp = MaskExpand(mask_frm_fwd)

    val data = axi_data & axi_mask_exp | data_frm_fwd & fwd_mask_exp

    val byte_res_list = (0 until WORDLEN).map(i => (i.U, data(8 * (i + 1) - 1, 8 * i)))
    val byte_res = LookupTree(recv_addr(DataAlignBits - 1, 0), byte_res_list)

    val byte_mask_list = (0 until WORDLEN).map(i => (i.U, ZeroExt((1 << i).U, WORDLEN)))
    val byte_mask = LookupTree(recv_addr(DataAlignBits - 1, 0), byte_mask_list)

    val hword_res_list = (0 until WORDLEN / 2).map(i => (i.U, data(16 * (i + 1) - 1, 16 * i)))
    val hword_res = LookupTree(recv_addr(DataAlignBits - 1, 1), hword_res_list)

    val hword_mask_list = (0 until WORDLEN / 2).map(i => (i.U, ZeroExt((3 << (2 * i)).U, WORDLEN)))
    val hword_mask = LookupTree(recv_addr(DataAlignBits - 1, 1), hword_mask_list)

    val word_res_list = (0 until WORDLEN / 4).map(i => (i.U, data(32 * (i + 1) - 1, 32 * i)))
    val word_res = LookupTree(recv_addr(DataAlignBits - 1, 2), word_res_list)

    val word_mask_list = (0 until WORDLEN / 4).map(i => (i.U, ZeroExt((15 << (4 * i)).U, WORDLEN)))
    val word_mask = LookupTree(recv_addr(DataAlignBits - 1, 2), word_mask_list)

    var res_list = List(
        LDUop.lb -> SignExt(byte_res, XLEN),
        LDUop.lbu -> ZeroExt(byte_res, XLEN),
        LDUop.lh -> SignExt(hword_res, XLEN),
        LDUop.lhu -> ZeroExt(hword_res, XLEN),
        LDUop.lw -> SignExt(word_res, XLEN)
    )

    if (useRV64) {
        res_list = res_list ++ List(
            LDUop.lwu -> ZeroExt(word_res, XLEN),
            LDUop.ld -> data
        )
    }
    val res = LookupTree(recv_task.fuOpType, res_list)

    var mask_list = List(
        LDUop.lb -> byte_mask,
        LDUop.lbu -> byte_mask,
        LDUop.lh -> hword_mask,
        LDUop.lhu -> hword_mask,
        LDUop.lw -> word_mask
    )
    if (useRV64) {
        mask_list = mask_list ++ List(
            LDUop.lwu -> word_mask,
            LDUop.ld -> Fill(WORDLEN, 1.U)
        )
    }
    val mask = LookupTree(recv_task.fuOpType, mask_list)
    fwd_query.bits.mask := mask

    val r_has_err = recv_task.exception.exceptions.load_access_fault

    val recv_res_blk = WireInit(recv_task)
    recv_res_blk.res := res
    recv_res_blk.mask := mask
    recv_res_blk.addr := Cat(recv_addr(PAddrBits - 1, DataAlignBits), 0.U(DataAlignBits.W))
    recv_res_blk.state.finished := true.B

    // sERR
    val err_task = RegEnable(req_out_task, 0.U.asTypeOf(new InstExInfo), state === sREQ && req_addr_err)
    val err_res_blk = WireInit(err_task)
    err_res_blk.addr := Cat(err_task.addr(PAddrBits - 1, DataAlignBits), 0.U(DataAlignBits.W))
    err_res_blk.state.finished := true.B
    err_res_blk.exception.exceptions.load_access_fault := true.B

    // Commit
    io.ldu_cmt.valid := (dcache_resp.valid && dcache_resp.bits.cmd === DCacheCMD.READ && state === sRECV || state === sERR) && !redirect.valid
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