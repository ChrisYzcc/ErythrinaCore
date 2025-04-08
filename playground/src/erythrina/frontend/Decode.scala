package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.HasErythCoreParams
import erythrina.backend.fu.ALUop
import erythrina.frontend.isa._
import erythrina.ErythModule
import utils.SignExt
import utils.LookupTree

trait InstrType {
	def TypeI   = "b000".U
	def TypeR   = "b001".U
	def TypeS   = "b010".U
	def TypeU   = "b011".U
	def TypeJ   = "b100".U
	def TypeB   = "b101".U
	def TypeN   = "b110".U
	def TypeER  = "b111".U    // error
}

object SrcType {
	def reg   = "b00".U
	def pc    = "b01".U
	def const = "b10".U
	def imm   = "b11".U
}

object FuType {
    def num = 5
    def alu = "b000".U
    def stu = "b001".U  // store unit
    def ldu = "b010".U  // load unit
    def bru = "b011".U
    def csr = "b100".U

    def apply() = UInt(log2Up(num).W)
}

object FuOpType extends HasErythCoreParams {
    def apply() = UInt(FuOpTypeBits.W)
}

object Instructions extends InstrType {
    val decodeDefault = List(TypeER, FuType.alu, ALUop.nop)
    def decode_table = RVI.table ++ Privileged.table
}

class Decoder extends ErythModule with InstrType{
	val io = IO(new Bundle {
		val in = Input(new InstInfo)
		val out = Flipped(ValidIO(new InstExInfo))
	})

	val (in, out) = (io.in, io.out)

	val instr = io.in.instr
	val pc = io.in.pc

	// Decode Instr
	val decodelist = ListLookup(instr, Instructions.decodeDefault, Instructions.decode_table)
	val instr_type :: fuType :: fuOpType :: Nil = decodelist
	val rs2 = instr(24, 20)
	val rs1 = instr(19, 15)
	val rd = instr(11, 7)

	// get imm
	val immj = Mux(instr(3, 3) === 1.B, SignExt(Cat(instr(31), instr(19, 12), instr(20), instr(30, 21), 0.U(1.W)), XLEN), SignExt(instr(31, 20), XLEN))
    val imm = LookupTree(instr_type, List(
        TypeN   -> SignExt(instr(31, 20), XLEN),
        TypeI   -> SignExt(instr(31, 20), XLEN),
        TypeS   -> SignExt(Cat(instr(31, 25), instr(11, 7)), XLEN),
        TypeB   -> SignExt(Cat(instr(31), instr(7), instr(30, 25), instr(11, 8), 0.U(1.W)), XLEN),
        TypeU   -> SignExt(Cat(instr(31, 12), 0.U(12.W)), XLEN),
        TypeJ   -> immj
    ))

	// get src type
	val srcTypeList = List(             // type -> (src1_type, src2_type)
        TypeI   -> (SrcType.reg, SrcType.imm),
        TypeB   -> (SrcType.reg, SrcType.reg),
        TypeJ   -> (SrcType.pc, SrcType.const),
        TypeR   -> (SrcType.reg, SrcType.reg),
        TypeS   -> (SrcType.reg, SrcType.imm),
        TypeU   -> (SrcType.imm, SrcType.pc)
    )

    val src1_type = LookupTree(instr_type, srcTypeList.map(p => (p._1, p._2._1)))
    val src2_type = LookupTree(instr_type, srcTypeList.map(p => (p._1, p._2._2)))

	val src1 = LookupTree(src1_type, List(
        SrcType.imm     -> imm,
        SrcType.pc      -> pc,
        SrcType.reg     -> 0.U
    ))
    val src2 = LookupTree(src2_type, List(
        SrcType.imm     -> imm,
        SrcType.pc      -> pc,
        SrcType.reg     -> 0.U,
        SrcType.const   -> 4.U
    ))

    val rf_wen = ~(instr_type === TypeB || instr_type === TypeS || instr_type === TypeN)

	val rsp_instExInfo = WireInit(new InstExInfo)
	rsp_instExInfo.fromInstInfo(in)

	rsp_instExInfo.a_rs1 := rs1
	rsp_instExInfo.a_rs2 := rs2
	rsp_instExInfo.a_rd  := rd
    rsp_instExInfo.rs1_need_rename  := src1_type === SrcType.reg && rs1 =/= 0.U
    rsp_instExInfo.rs2_need_rename  := src2_type === SrcType.reg && rs2 =/= 0.U
    rsp_instExInfo.rd_need_rename   := rd =/= 0.U && rf_wen

	rsp_instExInfo.fuType := fuType
	rsp_instExInfo.fuOpType := fuOpType

    rsp_instExInfo.imm  := imm
    rsp_instExInfo.src1 := src1
    rsp_instExInfo.src2 := src2
    rsp_instExInfo.src1_ready := src1_type =/= SrcType.reg || rs1 === 0.U
    rsp_instExInfo.src2_ready := src2_type =/= SrcType.reg || rs2 === 0.U
    rsp_instExInfo.rf_wen := rf_wen

    out.valid := in.valid
    out.bits := rsp_instExInfo
}