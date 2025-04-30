package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import top.Config._

object ICacheParams {
    val CacheableRange = ICacheRange

    def get_cacheline_offset(addr: UInt): UInt = {
        val offset = addr(log2Ceil(CachelineSize) - 1, 0)
        offset
    }

    def get_cacheline_blk_offset(addr: UInt): UInt = {
        val offset = addr(log2Ceil(CachelineSize) - 1, 2)
        offset
    }

    /*  
        ------------------------------------------
        way0
        |   v   |   tag   |    data    |
        ------------------------------------------
        way1
        ...
        ------------------------------------------
        way2
        ...
        ------------------------------------------
        way3
        ...
        ------------------------------------------

        Total: $sets * $ways * $cachelineSize

        Index = log2(sets)
        Offset = log2(data)
        Tag = XLEN - Index - Offset
    */

    // cache params
    var ways = 4
    var sets = 16
    var CachelineSize = 16

    def TagLen = XLEN - log2Ceil(sets) - log2Ceil(CachelineSize)

    def get_idx(addr: UInt): UInt = {
        val idx = addr(log2Ceil(sets) + log2Ceil(CachelineSize) - 1, log2Ceil(CachelineSize))
        idx
    }

    def get_tag(addr: UInt): UInt = {
        val tag = addr(XLEN - 1, log2Ceil(sets) + log2Ceil(CachelineSize))
        tag
    }
}
