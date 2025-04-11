package erythrina.memblock.pipeline

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.fu.{EXUInfo, STUop}
import erythrina.frontend.FuType
import utils.LookupTree

class STU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val cmt = ValidIO(new InstExInfo)
        val exu_info = Output(new EXUInfo)
    })

    val (req, cmt) = (io.req, io.cmt)

    // EXU Info
    val handler_vec = WireInit(Vec(FuType.num, false.B))
    handler_vec(FuType.stu) := true.B

    val exu_info = io.exu_info
    exu_info.busy := false.B
    exu_info.fu_type_vec := handler_vec.asUInt

    // req
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

    // cmt
    val cmt_instBlk = WireInit(req.bits)

    cmt_instBlk.res := st_data
    cmt_instBlk.st_addr := st_addr
    cmt_instBlk.st_mask := st_mask

    cmt.valid := req.valid
    cmt.bits := cmt_instBlk
}