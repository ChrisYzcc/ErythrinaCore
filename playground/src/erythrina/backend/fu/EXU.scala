package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.{ErythBundle, ErythModule}
import erythrina.frontend.FuType
import erythrina.backend.InstExInfo
import utils.LookupTreeDefault

class EXUInfo extends ErythBundle {
    val busy = Bool()
    val fu_type_vec = UInt(FuType.num.W)        // bitmap, 1 for can handle
}

class BaseEXU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val cmt = ValidIO(new InstExInfo)
        val exu_info = Output(new EXUInfo)
    })
}

// exu0: alu, bru, csr
class EXU0 extends BaseEXU {
    val alu = Module(new ALU)
    val bru = Module(new BRU)
    // TODO: csr
    
    val (req, cmt) = (io.req, io.cmt)

    // EXU Info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.alu) := true.B
    handler_vec(FuType.bru) := true.B
    handler_vec(FuType.csr) := true.B

    val exu_info = io.exu_info
    exu_info.busy := false.B
    exu_info.fu_type_vec := handler_vec.asUInt

    // req
    req.ready := true.B
    val req_instBlk = req.bits

    // ALU
    alu.io.src1 := req_instBlk.src1
    alu.io.src2 := req_instBlk.src2
    alu.io.aluop := req_instBlk.fuOpType
    val alu_res  = alu.io.res

    // BRU
    bru.io.pc := req_instBlk.pc
    bru.io.src1 := req_instBlk.src1
    bru.io.src2 := req_instBlk.src2
    bru.io.bruop := req_instBlk.fuOpType
    val bru_taken = bru.io.taken
    val bru_target = bru.io.target
    val bru_rd_res = bru.io.rd_res

    // TODO: CSR

    // Commit
    val cmt_instBlk = WireInit(req_instBlk)
    cmt_instBlk.real_taken := bru_taken
    cmt_instBlk.real_target := bru_target
    cmt_instBlk.res := LookupTreeDefault(req_instBlk.fuOpType, 0.U, List(
        FuType.alu -> alu_res,
        FuType.bru -> bru_rd_res
    ))

    cmt.valid := req.valid
    cmt.bits := cmt_instBlk
}

// exu1: alu
class EXU1 extends BaseEXU {
    val alu = Module(new ALU)

    val (req, cmt) = (io.req, io.cmt)

    // EXU Info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.alu) := true.B

    val exu_info = io.exu_info
    exu_info.busy := false.B
    exu_info.fu_type_vec := handler_vec.asUInt

    // req
    req.ready := true.B
    val req_instBlk = req.bits
    
    // ALU
    alu.io.src1 := req_instBlk.src1
    alu.io.src2 := req_instBlk.src2
    alu.io.aluop := req_instBlk.fuOpType
    val alu_res  = alu.io.res

    // Commit
    val cmt_instBlk = WireInit(req_instBlk)
    cmt_instBlk.res := alu_res
    
    cmt.valid := req.valid
    cmt.bits := cmt_instBlk
}