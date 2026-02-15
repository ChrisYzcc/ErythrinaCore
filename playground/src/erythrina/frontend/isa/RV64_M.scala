package erythrina.frontend.isa

import chisel3._
import chisel3.util._
import erythrina.frontend._
import erythrina.backend.fu.MDUop

object RV64_M extends InstrType {
    def MULW    = BitPat("b0000001_?????_?????_000_?????_01110_11")
    def DIVW    = BitPat("b0000001_?????_?????_100_?????_01110_11")
    def DIVUW   = BitPat("b0000001_?????_?????_101_?????_01110_11")
    def REMW    = BitPat("b0000001_?????_?????_110_?????_01110_11")
    def REMUW   = BitPat("b0000001_?????_?????_111_?????_01110_11")

    val table = Array(
        MULW    -> List(TypeR, FuType.mul, MDUop.mulw),
        DIVW    -> List(TypeR, FuType.div, MDUop.divw),
        DIVUW   -> List(TypeR, FuType.div, MDUop.divuw),
        REMW    -> List(TypeR, FuType.div, MDUop.remw),
        REMUW   -> List(TypeR, FuType.div, MDUop.remuw)
    )
}