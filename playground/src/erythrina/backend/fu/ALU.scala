package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.FuOpType
import utils.{LookupTreeDefault, ZeroExt, SignExt}

class ALU extends ErythModule {
    val io = IO(new Bundle {
        val src1 = Input(UInt(XLEN.W))
        val src2 = Input(UInt(XLEN.W))
        val aluop = Input(FuOpType.apply())
        val res = Output(UInt(XLEN.W))
    })

    val (src1, src2, aluop) = (io.src1, io.src2, io.aluop)

    val usesub = ALUop.usesub(aluop)
    val shamt = if (useRV64) src2(5, 0) else src2(4, 0)
    val shamt_w = src2(4, 0)
    val src2in = src2 ^ Fill(XLEN, usesub)

    val add_sub_res = (src1 +& src2in) + usesub
    val sltu_res = !add_sub_res(XLEN)
    val overflow  = (src1(XLEN-1) & src2in(XLEN-1) & ~add_sub_res(XLEN-1)) | (~src1(XLEN-1) & ~src2in(XLEN-1) & add_sub_res(XLEN-1))
    val slt_res   = overflow ^ add_sub_res(XLEN-1)

    val res_list_32 =  List(
        ALUop.dir   -> src1,
        ALUop.slt   -> ZeroExt(slt_res, XLEN),
        ALUop.sltu  -> ZeroExt(sltu_res, XLEN),
        ALUop.and   -> (src1 & src2),
        ALUop.xor   -> (src1 ^ src2),
        ALUop.or    -> (src1 | src2),
        ALUop.srl   -> (src1 >> shamt),
        ALUop.sra   -> (SignExt(src1, 2 * XLEN) >> shamt)(XLEN - 1, 0),
        ALUop.sll   -> (src1 << shamt)(XLEN-1, 0)
    )

    val res_list_64 = List(
        ALUop.addw  -> SignExt(add_sub_res(31, 0), XLEN),
        ALUop.subw  -> SignExt(add_sub_res(31, 0), XLEN),
        ALUop.sllw  -> SignExt((src1 << shamt_w)(31, 0), XLEN),
        ALUop.srlw  -> SignExt(src1(31, 0) >> shamt_w, XLEN),
        ALUop.sraw  -> SignExt((SignExt(src1(31, 0), 64) >> shamt_w)(31, 0), XLEN)
    )

    val res_list = if (useRV64) res_list_32 ++ res_list_64 else res_list_32

    val res = LookupTreeDefault(aluop, add_sub_res, res_list)

    io.res  := res
}