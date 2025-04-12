package difftest

import chisel3._
import chisel3.util._

trait HasDiffParams extends erythrina.HasErythCoreParams{
}

abstract class DifftestBundle extends Bundle with HasDiffParams {
}

abstract class DifftestModule extends Module with HasDiffParams {
}

class DifftestInfos extends DifftestBundle {
    val pc = UInt(XLEN.W)
    val inst = UInt(XLEN.W)

    val rf_wen = Bool()
    val rf_waddr = UInt(ArchRegAddrBits.W)
    val rf_wdata = UInt(XLEN.W)

    val mem_wen = Bool()
    val mem_addr = UInt(XLEN.W)
    val mem_data = UInt(XLEN.W)
    val mem_mask = UInt(MASKLEN.W)
}