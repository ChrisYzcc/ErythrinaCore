package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.FuOpType
import utils.LookupTreeDefault

class BRU extends ErythModule {
    val io = IO(new Bundle {
        val pc = Input(UInt(XLEN.W))
        val src1 = Input(UInt(XLEN.W))
        val src2 = Input(UInt(XLEN.W))
        val imm = Input(UInt(XLEN.W))
        val bruop = Input(FuOpType.apply())

        val taken = Output(Bool())
        val target = Output(UInt(XLEN.W))
        val rd_res = Output(UInt(XLEN.W))
    })

    val (pc, src1, src2, bruop, imm) = (io.pc, io.src1, io.src2, io.bruop, io.imm)

    val iseq = src1 === src2
    val isne = !iseq
    val islt = src1.asSInt < src2.asSInt
    val isge = !islt
    val isltu = src1 < src2
    val isgeu = !isltu
    val isjal = bruop === BRUop.jal
    val isjalr = bruop === BRUop.jalr

    val taken = LookupTreeDefault(bruop, false.B, List(
        BRUop.beq  -> iseq,
        BRUop.bne  -> isne,
        BRUop.blt  -> islt,
        BRUop.bge  -> isge,
        BRUop.bltu -> isltu,
        BRUop.bgeu -> isgeu,
        BRUop.jal  -> true.B,
        BRUop.jalr -> true.B
    ))

    val taken_target = Mux(isjalr, Cat((src1 + imm)(XLEN - 1, 1), 0.B), pc + imm)
    val target = Mux(taken, taken_target, pc + 4.U)
    val rd_res = pc + 4.U

    io.taken := taken
    io.target := target
    io.rd_res := rd_res
}