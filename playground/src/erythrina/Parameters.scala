package erythrina

import top.Config
import chisel3.util._

trait HasErythCoreParams {
    val XLEN = Config.getInt("XLEN")
    val MASKLEN = XLEN / 8

    val RESETVEC = Config.getLong("RESETVEC")

    val FetchWidth = Config.getInt("FetchWidth")
    val FTQSize = Config.getInt("FTQSize")
    val DecodeWidth = FetchWidth
    val RenameWidth = DecodeWidth
    val DispatchWidth = RenameWidth
    val CommitWidth = 4 // EXU0, EXU1, LDU, STU
    val BypassWidth = 3

    val PAddrBits = XLEN
    val DataBits = XLEN

    val CachelineSize = 64
    
    val FuOpTypeBits = 7

    val PhyRegNum = 64
    val PhyRegAddrBits = log2Up(PhyRegNum)
    
    val ArchRegNum = 32
    val ArchRegAddrBits = log2Up(ArchRegNum)

    val ROBSize = 64
}