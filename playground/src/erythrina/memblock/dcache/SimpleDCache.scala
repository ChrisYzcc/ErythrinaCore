package erythrina.memblock.dcache

/*
    A Simple Blocking DCache
*/


import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._
import erythrina.memblock.dcache.DCacheParams._
import utils._
import erythrina.ErythBundle

class SimpleDCacheTask extends ErythBundle {
    val addr = UInt(XLEN.W)
    val data = UInt(XLEN.W)
    val mask = UInt(MASKLEN.W)
    val cmd = UInt(2.W)

    val hit = Bool()
    val valid = Bool()
    val dirty = Bool()

}

class MetaEnrty extends ErythBundle {
    val valid = Bool()
    val dirty = Bool()
    val tag = UInt(TagLen.W)
}

class MetaArray extends ErythModule {
    val io = IO(new Bundle {
        val rd_req = Flipped(ValidIO(UInt(log2Ceil(sets).W)))
        val rd_rsp = ValidIO(Vec(ways, new MetaEnrty))

        val wr_req = Flipped(ValidIO(new Bundle{
            val idx = UInt(log2Ceil(sets).W)
            val meta = Vec(ways, new MetaEnrty)
        }))
    })

    val meta_array = SyncReadMem(sets, Vec(ways, new MetaEnrty))

    // Read
    val rd_rsp = meta_array.read(io.rd_req.bits, io.rd_req.valid)

    io.rd_rsp.valid := RegNext(io.rd_req.valid)
    io.rd_rsp.bits := rd_rsp

    // Write
    when (io.wr_req.valid) {
        meta_array.write(io.wr_req.bits.idx, io.wr_req.bits.meta)
    }
}

class DataArray extends ErythModule {
    val io = IO(new Bundle {
        val rd_req = Flipped(ValidIO(UInt(log2Ceil(sets).W)))
        val rd_rsp = ValidIO(Vec(ways, Vec(CachelineSize / 4, UInt(XLEN.W))))

        val wr_req = Flipped(ValidIO(new Bundle{
            val idx = UInt(log2Ceil(sets).W)
            val data = Vec(ways, Vec(CachelineSize / 4, UInt(XLEN.W)))
        }))
    })

    val data_array = SyncReadMem(sets, Vec(ways, Vec(CachelineSize / 4, UInt(XLEN.W))))

    // Read
    val rd_rsp = data_array.read(io.rd_req.bits, io.rd_req.valid)
    io.rd_rsp.valid := RegNext(io.rd_req.valid)
    io.rd_rsp.bits := rd_rsp

    // Write
    when (io.wr_req.valid) {
        data_array.write(io.wr_req.bits.idx, io.wr_req.bits.data)
    }

}

// Stage1: Req for Meta & Data
class Stage1 extends ErythBundle {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new SimpleDCacheTask))
        val out = Flipped(DecoupledIO(new SimpleDCacheTask))
    })
}