package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.{ErythBundle, ErythModule}
import erythrina.frontend.FuType
import erythrina.backend.InstExInfo
import utils.LookupTreeDefault
import erythrina.backend.Redirect

class EXUInfo extends ErythBundle {
    val busy = Bool()
    val fu_type_vec = UInt(FuType.num.W)        // bitmap, 1 for can handle
}

class BaseEXU extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new InstExInfo))
        val cmt = ValidIO(new InstExInfo)
        val exu_info = Output(new EXUInfo)
        val redirect = Flipped(ValidIO(new Redirect))

        val rf_write = ValidIO(new Bundle {
            val addr = UInt(PhyRegAddrBits.W)
            val data = UInt(XLEN.W)
        })

        val bt_free_req = ValidIO(UInt(PhyRegAddrBits.W))
    })

    io.rf_write.valid := io.cmt.valid && io.cmt.bits.rf_wen
    io.rf_write.bits.addr := io.cmt.bits.p_rd
    io.rf_write.bits.data := io.cmt.bits.res

    io.bt_free_req.valid := io.cmt.valid && io.cmt.bits.rf_wen
    io.bt_free_req.bits := io.cmt.bits.p_rd
}

// exu0: alu, bru, csr
class EXU0 extends BaseEXU {
    val alu = Module(new ALU)
    val bru = Module(new BRU)
    val csr = Module(new CSR)
    
    val (req, cmt) = (io.req, io.cmt)
    val redirect = io.redirect

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
    bru.io.imm := req_instBlk.imm
    val bru_taken = bru.io.taken
    val bru_target = bru.io.target
    val bru_rd_res = bru.io.rd_res

    csr.io.valid := req.valid && req.bits.fuType === FuType.csr
    csr.io.pc := req_instBlk.pc
    csr.io.imm := req_instBlk.imm
    csr.io.src1 := req_instBlk.src1
    csr.io.csrop := req_instBlk.fuOpType

    // Commit
    val cmt_instBlk = WireInit(req_instBlk)
    cmt_instBlk.real_taken := bru_taken
    cmt_instBlk.real_target := Mux(req_instBlk.fuType === FuType.csr, csr.io.target, bru_target)
    cmt_instBlk.res := LookupTreeDefault(req_instBlk.fuType, 0.U, List(
        FuType.alu -> alu_res,
        FuType.bru -> bru_rd_res,
        FuType.csr -> csr.io.res
    ))

    cmt_instBlk.state.finished := true.B
    cmt_instBlk.exception.bpu_mispredict := bru_taken =/= req_instBlk.bpu_taken && req_instBlk.fuType === FuType.bru
    cmt_instBlk.exception.ret := csr.io.ret && req_instBlk.fuType === FuType.csr
    cmt_instBlk.exception.exceptions.ebreak := csr.io.ebreak && req_instBlk.fuType === FuType.csr
    cmt_instBlk.exception.exceptions.ecall_m := csr.io.ecall && req_instBlk.fuType === FuType.csr
    
    cmt.valid := RegNext(req.valid) && !redirect.valid && !RegNext(redirect.valid)
    cmt.bits := RegNext(cmt_instBlk)

}

// exu1: alu
class EXU1 extends BaseEXU {
    val alu = Module(new ALU)

    val (req, cmt) = (io.req, io.cmt)
    val redirect = io.redirect

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
    cmt_instBlk.state.finished := true.B
    
    cmt.valid := RegNext(req.valid) && !redirect.valid && !RegNext(redirect.valid)
    cmt.bits := RegNext(cmt_instBlk)
}