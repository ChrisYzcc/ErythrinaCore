package erythrina.memblock.pipeline

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.fu.{EXUInfo, STUop}
import erythrina.frontend.FuType
import utils.LookupTree
import erythrina.memblock.StoreFwdBundle
import erythrina.backend.Redirect

class STU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val cmt = ValidIO(new InstExInfo)
        val exu_info = Output(new EXUInfo)

        val stu_fwd = Vec(2, ValidIO(new StoreFwdBundle))

        val redirect = Flipped(ValidIO(new Redirect))
    })

    val (req, cmt) = (io.req, io.cmt)
    val st_fwd = io.stu_fwd
    val redirect = io.redirect

    // EXU Info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.stu) := true.B

    val exu_info = io.exu_info
    exu_info.busy := false.B
    exu_info.fu_type_vec := handler_vec.asUInt

    /* ----------------------- s0 -------------------------- */
    val s0_valid = req.valid && !redirect.valid
    req.ready := true.B

    // Calculate
    val st_addr = req.bits.src1 + req.bits.imm
    val st_data = LookupTree(req.bits.fuOpType, List(
        STUop.sb    -> (req.bits.src2(7, 0) << (st_addr(1, 0) << 3.U)),
        STUop.sh    -> (req.bits.src2(15, 0) << ((st_addr(1, 0) & "b10".U) << 3.U)),
        STUop.sw    -> (req.bits.src2)
    ))
    val st_mask = LookupTree(req.bits.fuOpType, List(
        STUop.sb    -> ("b0001".U << st_addr(1, 0)),
        STUop.sh    -> ("b0011".U << (st_addr(1, 0) & "b10".U)),
        STUop.sw    -> ("b1111".U)
    ))

    // Forwarding
    st_fwd(0).valid := s0_valid
    st_fwd(0).bits.addr := st_addr
    st_fwd(0).bits.data := st_data
    st_fwd(0).bits.mask := st_mask
    st_fwd(0).bits.robPtr := req.bits.robPtr

    /* ----------------------- s1 -------------------------- */
    val s1_valid = RegNext(s0_valid) && !redirect.valid
    val s1_st_addr = RegNext(st_addr)
    val s1_st_data = RegNext(st_data)
    val s1_st_mask = RegNext(st_mask)

    // cmt
    val cmt_instBlk = RegNext(req.bits)

    cmt_instBlk.res := s1_st_data
    cmt_instBlk.addr := s1_st_addr
    cmt_instBlk.mask := s1_st_mask
    cmt_instBlk.state.finished := true.B

    cmt.valid := s1_valid
    cmt.bits := cmt_instBlk

    // Forwarding
    st_fwd(1).valid := s1_valid
    st_fwd(1).bits.addr := s1_st_addr
    st_fwd(1).bits.data := s1_st_data
    st_fwd(1).bits.mask := s1_st_mask
    st_fwd(1).bits.robPtr := cmt_instBlk.robPtr
}