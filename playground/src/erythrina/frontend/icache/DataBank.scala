package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class MetaArray extends ErythModule {
    val io = IO(new Bundle {
        val rd_req = Flipped(Vec(2, DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
        })))
        val rd_rsp = Output(Vec(2, Vec(ICacheParams.ways, new ICacheMeta)))

        val wr_req = Flipped(DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
            val way = UInt(log2Ceil(ICacheParams.ways).W)
            val meta = new ICacheMeta
        }))
    })

    val (rd_req, rd_rsp) = (io.rd_req, io.rd_rsp)
    val wr_req = io.wr_req

    // Reset Logic
    val rst_idx = RegInit(0.U(log2Ceil(ICacheParams.sets).W))
    when (rst_idx =/= (ICacheParams.sets - 1).U) {
        rst_idx := rst_idx + 1.U
    }

    val meta_array_seq = Seq.fill(ICacheParams.ways)(SyncReadMem(ICacheParams.sets, new ICacheMeta))

    // Read
    for (i <- 0 until 2) {
        rd_req(i).ready := !reset.asBool && (rst_idx === (ICacheParams.sets - 1).U)

        val rd_req_idx_reg = RegEnable(rd_req(i).bits.idx, 0.U, rd_req(i).fire)
        val rd_data = meta_array_seq.map(_.read(Mux(rd_req(i).fire, rd_req(i).bits.idx, rd_req_idx_reg)))

        rd_rsp(i) := rd_data
    }

    // Write
    wr_req.ready := !reset.asBool && (rst_idx === (ICacheParams.sets - 1).U)

    for (i <- 0 until ICacheParams.ways) {
        val need_write = !reset.asBool && (rst_idx =/= (ICacheParams.sets - 1).U) || (wr_req.fire && wr_req.bits.way === i.U)

        val wr_addr = Mux(wr_req.fire, wr_req.bits.idx, rst_idx)
        val wr_data = Mux(wr_req.fire, wr_req.bits.meta, 0.U.asTypeOf(new ICacheMeta))

        when (need_write) {
            meta_array_seq(i).write(wr_addr, wr_data)
        }
    }
}

class DataArray extends ErythModule {
    val io = IO(new Bundle {
        val rd_req = Flipped(DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
        }))

        val rd_rsp = Output(Vec(ICacheParams.ways, UInt((ICacheParams.CachelineSize * 8).W)))

        val wr_req = Flipped(DecoupledIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
            val way = UInt(log2Ceil(ICacheParams.ways).W)
            val data = UInt((ICacheParams.CachelineSize * 8).W)
        }))
    })

    val (rd_req, rd_rsp) = (io.rd_req, io.rd_rsp)
    val wr_req = io.wr_req

    // Reset Logic
    val rst_idx = RegInit(0.U(log2Ceil(ICacheParams.sets).W))
    when (rst_idx =/= (ICacheParams.sets - 1).U) {
        rst_idx := rst_idx + 1.U
    }

    val data_array_seq = Seq.fill(ICacheParams.ways)(SyncReadMem(ICacheParams.sets, UInt((ICacheParams.CachelineSize * 8).W)))

    // Read
    rd_req.ready := !reset.asBool && (rst_idx === (ICacheParams.sets - 1).U)
    
    val rd_req_idx_reg = RegEnable(rd_req.bits.idx, 0.U, rd_req.fire)
    val rd_data = data_array_seq.map(_.read(Mux(rd_req.fire, rd_req.bits.idx, rd_req_idx_reg)))

    rd_rsp := rd_data

    // Write
    wr_req.ready := !reset.asBool && (rst_idx === (ICacheParams.sets - 1).U)

    for (i <- 0 until ICacheParams.ways) {
        val need_write = !reset.asBool && (rst_idx =/= (ICacheParams.sets - 1).U) || (wr_req.fire && wr_req.bits.way === i.U)

        val wr_addr = Mux(wr_req.fire, wr_req.bits.idx, rst_idx)
        val wr_data = Mux(wr_req.fire, wr_req.bits.data, 0.U((ICacheParams.CachelineSize * 8).W))

        when (need_write) {
            data_array_seq(i).write(wr_addr, wr_data)
        }
    }
}