package erythrina

import top.Config
import chisel3._
import chisel3.util._

object AddrSpace {
    val addr_space = List(
        (0x80000000L, 0x88000000L),
        (0xa0000000L, 0xa0001000L)
    )

    def in_addr_space(addr: UInt): Bool = {
        val in_space = Wire(Vec(addr_space.length, Bool()))
        for (i <- addr_space.indices) {
            in_space(i) := addr >= addr_space(i)._1.U && addr < addr_space(i)._2.U
        }
        in_space.reduce(_ || _)
    }
}

trait HasErythCoreParams {
    val XLEN = Config.XLEN
    val MASKLEN = XLEN / 8

    val RESETVEC = Config.RESETVEC

    val FetchWidth = 2
    val FTQSize = 16
    val DecodeWidth = FetchWidth
    val RenameWidth = DecodeWidth
    val DispatchWidth = RenameWidth
    val CommitWidth = 4 // EXU0, EXU1, LDU, STU
    val CommitWithDataWidth = 3 // EXU0, EXU1, LDU
    val BypassWidth = 4

    val PAddrBits = XLEN
    val DataBits = XLEN
    
    val FuOpTypeBits = 7

    val PhyRegNum = 64
    val PhyRegAddrBits = log2Up(PhyRegNum)
    
    val ArchRegNum = 32
    val ArchRegAddrBits = log2Up(ArchRegNum)

    val ROBSize = 64
    val LoadQueSize = 8
    val StoreQueSize = 8
}