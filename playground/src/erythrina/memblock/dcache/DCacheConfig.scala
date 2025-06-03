package erythrina.memblock.dcache

import chisel3._
import chisel3.util._
import top.Config._

object DCacheParams {
    val CacheableRange = DCacheRange
    val CmdBits = 2

    def get_cacheline_offset(addr: UInt): UInt = {
        val offset = addr(log2Ceil(CachelineSize) - 1, 0)
        offset
    }

    def get_cacheline_blk_offset(addr: UInt): UInt = {
        val offset = addr(log2Ceil(CachelineSize) - 1, 2)
        offset
    }

    def get_cacheline_addr(addr:UInt): UInt = {
        Cat(addr(XLEN - 1, log2Ceil(CachelineSize)), 0.U(log2Ceil(CachelineSize).W))
    }

    var ways = 4
    var sets = 256
    var CachelineSize = 64
    
    def TagLen = XLEN - log2Ceil(sets) - log2Ceil(CachelineSize)

    def get_idx(addr: UInt): UInt = {
        val idx = addr(log2Ceil(sets) + log2Ceil(CachelineSize) - 1, log2Ceil(CachelineSize))
        idx
    }

    def get_tag(addr: UInt): UInt = {
        val tag = addr(XLEN - 1, log2Ceil(sets) + log2Ceil(CachelineSize))
        tag
    }

    def is_cacheable(addr: UInt): Bool = {
        addr >= CacheableRange._1.U && addr <= CacheableRange._2.U
    }
}