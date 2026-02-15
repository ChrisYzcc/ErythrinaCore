package erythrina.backend.fu.div

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.FuOpType
import utils._

class Divisor extends ErythModule {
    val io = IO(new Bundle {
        val in_valid   = Input(Bool())
        val in_flush   = Input(Bool())
        val a   = Input(UInt(XLEN.W))
        val b   = Input(UInt(XLEN.W))
        val op  = Input(FuOpType.apply())              // 00: div, 01: divu, 10: rem, 11: remu
        val res = Output(UInt(XLEN.W))
        val res_valid = Output(Bool())
    })

    val (a, b) = (io.a, io.b)
    
    val div_inst = Module(new DivCore(len=(XLEN + 1)))

    val use_rem = io.op(1)
    val use_w   = io.op(3)

    val a_signed    = Mux(use_w, SignExt(Cat(a(XLEN - 1), a)(31, 0), XLEN + 1), Cat(a(XLEN - 1), a))
    val a_unsigned  = Mux(use_w, ZeroExt(Cat(0.U(1.W), a)(31, 0), XLEN + 1), Cat(0.U(1.W), a))
    val b_signed    = Mux(use_w, SignExt(Cat(b(XLEN - 1), b)(31, 0), XLEN + 1), Cat(b(XLEN - 1), b))
    val b_unsigned  = Mux(use_w, ZeroExt(Cat(0.U(1.W), b)(31, 0), XLEN + 1), Cat(0.U(1.W), b))

    val a_src   = Mux(io.op(0), a_unsigned, a_signed)
    val b_src   = Mux(io.op(0), b_unsigned, b_signed)

    div_inst.io.in_v := io.in_valid
    div_inst.io.flush := io.in_flush
    div_inst.io.a := a_src
    div_inst.io.b := b_src

    io.res := Mux(use_rem, 
        Mux(use_w, SignExt(div_inst.io.rem(31, 0), XLEN), div_inst.io.rem),
        Mux(use_w, SignExt(div_inst.io.quot(31, 0), XLEN), div_inst.io.quot)
    )
    io.res_valid := div_inst.io.out_v
}