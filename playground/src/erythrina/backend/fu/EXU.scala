package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.{ErythBundle, ErythModule}
import erythrina.frontend.FuType
import erythrina.backend.InstExInfo
import utils.LookupTreeDefault
import erythrina.backend.Redirect
import utils.PerfCount
import erythrina.backend.fu.div.Divisor
import erythrina.backend.fu.mul.Multiplier

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

    val direction_wrong = bru_taken =/= req_instBlk.bpu_taken
    val target_wrong = !direction_wrong && (bru_target =/= req_instBlk.bpu_target)
    val bru_mispredict = direction_wrong || target_wrong

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
    cmt_instBlk.exception.bpu_mispredict := bru_mispredict && req_instBlk.fuType === FuType.bru
    cmt_instBlk.exception.ret := csr.io.ret && req_instBlk.fuType === FuType.csr
    cmt_instBlk.exception.exceptions.ebreak := csr.io.ebreak && req_instBlk.fuType === FuType.csr
    cmt_instBlk.exception.exceptions.ecall_m := csr.io.ecall && req_instBlk.fuType === FuType.csr
    
    cmt.valid := RegNext(req.valid) && !redirect.valid && !RegNext(redirect.valid)
    cmt.bits := RegNext(cmt_instBlk)

    /* --------------- Perf --------------- */
    PerfCount("bpu_correct_exu", cmt.valid && !cmt.bits.exception.bpu_mispredict && cmt.bits.fuType === FuType.bru)
    PerfCount("bpu_wrong_exu", cmt.valid && cmt.bits.exception.bpu_mispredict && cmt.bits.fuType === FuType.bru)
    PerfCount("bpu_wrong_br", cmt.valid && cmt.bits.exception.bpu_mispredict && cmt.bits.fuType === FuType.bru && !(cmt.bits.fuOpType === BRUop.jal || cmt.bits.fuOpType === BRUop.jalr))
    PerfCount("bpu_wrong_jal", cmt.valid && cmt.bits.exception.bpu_mispredict && cmt.bits.fuType === FuType.bru && (cmt.bits.fuOpType === BRUop.jal || cmt.bits.fuOpType === BRUop.jalr))
}

// exu1: alu mul
class EXU1 extends BaseEXU {
    val alu = Module(new ALU)
    val mul = Module(new Multiplier)

    val (req, cmt) = (io.req, io.cmt)
    val redirect = io.redirect

    // EXU Info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.alu) := true.B
    handler_vec(FuType.mul) := true.B

    val exu_info = io.exu_info
    exu_info.busy := false.B
    exu_info.fu_type_vec := handler_vec.asUInt

    // req
    req.ready := true.B
    val req_instBlk = req.bits

    /* ------------- Stage Control ------------- */
    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)
    val s2_valid = RegInit(false.B)

    val s0_task = WireInit(0.U.asTypeOf(req_instBlk))
    val s1_task = RegInit(0.U.asTypeOf(req_instBlk))
    val s2_task = RegInit(0.U.asTypeOf(req_instBlk))

    /* ------------- Stage 0 ------------- */
    s0_valid := req.valid && !redirect.valid
    s0_task := req.bits

    // ALU
    alu.io.src1 := req_instBlk.src1
    alu.io.src2 := req_instBlk.src2
    alu.io.aluop := req_instBlk.fuOpType
    val s0_alu_res = alu.io.res

    // MUL
    mul.io.in_valid := s0_valid && req.bits.fuType === FuType.mul
    mul.io.a := req_instBlk.src1
    mul.io.b := req_instBlk.src2
    mul.io.op := req_instBlk.fuOpType

    /* ------------- Stage 1 ------------- */
    s1_valid := s0_valid && !redirect.valid
    s1_task := s0_task
    
    val s1_alu_res = RegNext(s0_alu_res)

    /* ------------- Stage 2 ------------- */
    s2_valid := s1_valid && !redirect.valid
    s2_task := s1_task
    
    val s2_alu_res = RegNext(s1_alu_res)
    val s2_mul_res = mul.io.res

    // Commit
    val cmt_instBlk = WireInit(s2_task)
    cmt_instBlk.res := Mux(s2_task.fuType === FuType.mul, s2_mul_res, s2_alu_res)
    cmt_instBlk.state.finished := true.B
    
    cmt.valid := s2_valid && !redirect.valid
    cmt.bits := cmt_instBlk
}

// exu2: div
// TODO: handle div 0 exception
class EXU2 extends BaseEXU {
    val div = Module(new Divisor)

    val (req, cmt) = (io.req, io.cmt)
    val redirect = io.redirect

    // state
    val sIDLE :: sCOMPUTE :: Nil = Enum(2)
    val state = RegInit(sIDLE)

    switch (state) {
        is (sIDLE) {
            when (req.valid && req.bits.fuType === FuType.div && !redirect.valid) {
                state := sCOMPUTE
            }
        }
        is (sCOMPUTE) {
            when (div.io.res_valid || redirect.valid) {
                state := sIDLE
            }
        }
    }

    // EXU Info
    val handler_vec = WireInit(VecInit(Seq.fill(FuType.num)(false.B)))
    handler_vec(FuType.div) := true.B

    val exu_info = io.exu_info
    exu_info.busy := state =/= sIDLE
    exu_info.fu_type_vec := handler_vec.asUInt

    // req
    req.ready := state === sIDLE
    val req_instBlk = req.bits
    val req_inflight = RegEnable(req_instBlk, 0.U.asTypeOf(req_instBlk), state === sIDLE && req.valid && req.bits.fuType === FuType.div)

    // Div
    div.io.in_valid := req.valid && req.bits.fuType === FuType.div
    div.io.in_flush := redirect.valid
    div.io.a := Mux(state === sIDLE, req_instBlk.src1, req_inflight.src1)
    div.io.b := Mux(state === sIDLE, req_instBlk.src2, req_inflight.src2)
    div.io.op := Mux(state === sIDLE, req_instBlk.fuOpType, req_inflight.fuOpType)
    val div_res = div.io.res

    // Commit
    val cmt_instBlk = WireInit(req_inflight)
    cmt_instBlk.res := div_res
    cmt_instBlk.state.finished := true.B

    cmt.valid := state === sCOMPUTE && div.io.res_valid && !redirect.valid
    cmt.bits := cmt_instBlk
}