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

    val PAddrBits = XLEN
    val DataBits = XLEN

    val CachelineSize = 64
    
    val FuOpTypeBits = 7

    val PhyRegNum = 64
    val PhyAddrBits = log2Up(PhyRegNum)
    
    val ArchRegNum = 32
    val ArchAddrBits = log2Up(ArchRegNum)
}