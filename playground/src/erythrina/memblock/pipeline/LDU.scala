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

class LDU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val axi = new Bundle {
            val ar = DecoupledIO(new AXI4BundleA(AXI4Params.idBits))
            val r = Flipped(DecoupledIO(new AXI4BundleR(AXI4Params.dataBits, AXI4Params.idBits)))
        }

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

    val (req, axi) = (io.req, io.axi)
    val redirect = io.redirect

    val sIDLE :: sREQ :: sRECV :: sDROP :: Nil = Enum(4)
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
            }.elsewhen(axi.ar.fire) {
                state := sRECV
            }
        }
        is (sRECV) {
            when (axi.r.fire) {
                state := sIDLE
            }.elsewhen(redirect.valid) {
                state := sDROP
            }
        }
        is (sDROP) {
            when (axi.r.fire) {
                state := sIDLE
            }
        }
    }

    req.ready := state === sIDLE

    // sREQ
    val req_task = RegEnable(req.bits, 0.U.asTypeOf(new InstExInfo), req.fire)
    val addr = (req_task.src1 + req_task.src2)

    val req_addr = Cat(addr(XLEN - 1, 2), 0.U(2.W))
    val addr_err = !AddrSpace.in_addr_space(req_addr)

    axi.ar.valid := state === sREQ && !redirect.valid && !addr_err
    axi.ar.bits := 0.U.asTypeOf(axi.ar.bits)
    axi.ar.bits.addr := req_addr
    axi.ar.bits.size := "b010".U    // 4 bytes per transfer

    val to_recv_task = WireInit(req_task)
    to_recv_task.addr := addr
    to_recv_task.exception.exceptions.load_access_fault := addr_err

    // sRECV
    val recv_task = RegEnable(to_recv_task, 0.U.asTypeOf(new InstExInfo), axi.ar.fire)

    val recv_addr = recv_task.addr

    val (fwd_query, fwd_result) = (io.st_fwd_query, io.st_fwd_result)
    fwd_query.valid := state === sRECV && !redirect.valid
    fwd_query.bits := 0.U.asTypeOf(new StoreFwdBundle)
    fwd_query.bits.addr := Cat(recv_addr(XLEN - 1, 2), 0.U(2.W))
    fwd_query.bits.robPtr := recv_task.robPtr

    val mask_frm_fwd = fwd_result.mask
    val data_frm_fwd = fwd_result.data

    val axi_data = axi.r.bits.data
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

    axi.r.ready := state === sRECV || state === sDROP

    // Commit
    val cmt_instBlk = WireInit(recv_task)
    cmt_instBlk.res := res
    cmt_instBlk.mask := mask
    cmt_instBlk.addr := Cat(recv_addr(XLEN - 1, 2), 0.U(2.W))
    cmt_instBlk.state.finished := true.B

    io.ldu_cmt.valid := (axi.r.fire && state === sRECV || r_has_err) && !redirect.valid
    io.ldu_cmt.bits := cmt_instBlk
    
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