package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.FuOpType
import utils.LookupTreeDefault

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

        val pc = Input(UInt(XLEN.W))
        val imm = Input(UInt(XLEN.W))
        val src1 = Input(UInt(XLEN.W))
        val csrop = Input(FuOpType.apply())

        val ebreak = Output(Bool())
        val ret = Output(Bool())
        val target = Output(UInt(XLEN.W))

        val res = Output(UInt(XLEN.W))
    })

    def privECALL   = 0x000.U
    def privEBREAK  = 0x001.U
    def privMRET    = 0x302.U

    // Machine
    val mstatus     = RegInit(UInt(XLEN.W), 0x1800.U)
    val mcause      = RegInit(UInt(XLEN.W), 0.U)
    val mepc        = RegInit(UInt(XLEN.W), 0.U)
    val mtvec       = RegInit(UInt(XLEN.W), 0.U)
    val mvendorid   = RegInit(UInt(XLEN.W), 0x79737978.U)
    val marchid     = RegInit(UInt(XLEN.W), 0x1d4b42.U)

    val (src1, imm, csrop) = (io.src1, io.imm, io.csrop)

    val csrnum = imm
    val is_ecall = io.valid && csrop === CSRop.jmp && csrnum === privECALL
    val is_ebreak = io.valid && csrop === CSRop.jmp && csrnum === privEBREAK
    val is_mret = io.valid && csrop === CSRop.jmp && csrnum === privMRET

    val csrval  = LookupTreeDefault(csrnum, 0.U, List(
        CSRnum.mcause       -> mcause,
        CSRnum.mepc         -> mepc,
        CSRnum.mstatus      -> mstatus,
        CSRnum.mtvec        -> mtvec,
        CSRnum.marchid      -> marchid,
        CSRnum.mvendorid    -> mvendorid
    ))

    // update
    val csr_wen = (csrop =/= CSRop.nop) && (csrop =/= CSRop.jmp) && io.valid
    val isWRT   = CSRop.iswrt(csrop)
    val isSET   = CSRop.isset(csrop)
    val isCLR   = CSRop.isclr(csrop)
    val csr_new = Mux1H(Seq(
        isWRT   -> src1,
        isSET   -> (csrval | src1),
        isCLR   -> (csrval & ~src1)
    ))
    when (csr_wen){
        switch (csrnum){
            is (CSRnum.mcause){
                mcause  := csr_new
            }
            is (CSRnum.mepc){
                mepc    := csr_new
            }
            is (CSRnum.mstatus){
                mstatus := csr_new
            }
            is (CSRnum.mtvec){
                mtvec   := csr_new
            }
        }
    }

    io.res := csrval
    io.ebreak := is_ebreak
    io.ret := is_mret
    io.target := Mux(is_mret, mepc, mtvec)

    when (is_ecall) {
        mepc := io.pc
        mcause := TrapCause.MECALL
    }
}