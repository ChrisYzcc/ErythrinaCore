package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.icache.ICacheParams.get_cacheline_blk_offset

class IBuffer extends ErythModule {
    val io = IO(new Bundle {
        val req = DecoupledIO(UInt(XLEN.W))
        val rsp = Flipped(ValidIO(UInt((CachelineSize * 8).W)))

        val fetch_req = Flipped(ValidIO(new InstFetchBlock)) // from FTQ
        val fetch_rsp = ValidIO(new InstFetchBlock) // to FTQ
    })

    val buffer = RegInit(VecInit(Seq.fill(CachelineSize / 4)(0.U(XLEN.W))))

    val (fetch_req, fetch_rsp) = (io.fetch_req, io.fetch_rsp)   // <-> FTQ
    val (req, rsp) = (io.req, io.rsp)   // <-> ICache

    // base pc: the address of the first byte of the cacheline
    val base_pc = RegInit(0.U(XLEN.W))
    
    val in_range = fetch_req.bits.instVec.map{
        case inst =>
            !inst.valid || (inst.pc >= base_pc && inst.pc < base_pc + CachelineSize.U)
    }.reduce(_ && _)

    fetch_rsp.valid := fetch_req.valid && in_range
    val rsp_blk = WireInit(fetch_req.bits)
    for (i <- 0 until FetchWidth) {
        rsp_blk.instVec(i).instr := Mux(rsp_blk.instVec(i).valid, buffer(get_cacheline_blk_offset(rsp_blk.instVec(i).pc)), 0.U)
    }
    fetch_rsp.bits := rsp_blk

    // req
    val has_req = RegInit(false.B)
    when (req.fire) {
        has_req := true.B
    }
    when (rsp.valid) {
        has_req := false.B
    }

    req.valid := fetch_req.valid && !has_req && !in_range
    req.bits := Cat(fetch_req.bits.instVec(0).pc(XLEN - 1, log2Ceil(CachelineSize)), 0.U(log2Ceil(CachelineSize).W))

    val addr_inflight = RegInit(0.U(XLEN.W))
    when (req.fire) {
        addr_inflight := req.bits
    }
    when (rsp.valid) {
        for (i <- 0 until CachelineSize / 4) {
            buffer(i) := rsp.bits((i + 1) * XLEN - 1, i * XLEN)
        }
        base_pc := Cat(addr_inflight(XLEN - 1, log2Ceil(CachelineSize)), 0.U(log2Ceil(CachelineSize).W))
    }
}