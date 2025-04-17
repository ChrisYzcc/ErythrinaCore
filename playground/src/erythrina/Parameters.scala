package erythrina

import top.Config
import chisel3.util._

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

    val CachelineSize = 64
    
    val FuOpTypeBits = 7

    val PhyRegNum = 64
    val PhyRegAddrBits = log2Up(PhyRegNum)
    
    val ArchRegNum = 32
    val ArchRegAddrBits = log2Up(ArchRegNum)

    val ROBSize = 64
    val LoadQueSize = 8
    val StoreQueSize = 8

    val addr_space = (0x80000000L, 0xFFFFFFFFL)
}