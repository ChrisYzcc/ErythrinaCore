package erythrina.frontend.isa

import chisel3._
import chisel3.util._
import erythrina.frontend._
import erythrina.backend.fu.CSRop

object Privileged extends InstrType {
    def EBREAK  = BitPat("b0000000_00001_00000_000_00000_11100_11")
    def ECALL   = BitPat("b0000000_00000_00000_000_00000_11100_11")

    // Mache Level
    def MRET    = BitPat("b0011000_00010_00000_000_00000_11100_11")

    val table = Array(
        EBREAK -> List(TypeN, FuType.csr, CSRop.jmp),
        ECALL  -> List(TypeN, FuType.csr, CSRop.jmp),
        MRET   -> List(TypeN, FuType.csr, CSRop.jmp)
    )
}