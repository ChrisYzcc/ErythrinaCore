package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.FuOpType

object TrapCause{
    def UECALL  = 8.U
    def SECALL  = 9.U
    def MECALL  = 11.U
}

object CSRnum{
    def mstatus     = 0x300.U
    def mtvec       = 0x305.U
    def mepc        = 0x341.U
    def mcause      = 0x342.U
    def mvendorid   = 0xf11.U
    def marchid     = 0xf12.U
}

class CSR extends ErythModule {
    val io = IO(new Bundle {
        val valid = Input(Bool())
        val src1 = Input(UInt(XLEN.W))
        val src2 = Input(UInt(XLEN.W))
        val csrop = Input(FuOpType.apply())

        val ebreak = Output(Bool())
    })

    def privECALL   = 0x000.U
    def privEBREAK  = 0x001.U
    def privMRET    = 0x302.U

    val (src1, src2, csrop) = (io.src1, io.src2, io.csrop)

    val csrnum = src2(11, 0)
    val is_ecall = io.valid && csrop === CSRop.jmp && csrnum === privECALL
    val is_ebreak = io.valid && csrop === CSRop.jmp && csrnum === privEBREAK
    val is_mret = io.valid && csrop === CSRop.jmp && csrnum === privMRET

    io.ebreak := is_ebreak
}