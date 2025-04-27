package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.frontend.icache.ICacheParams.get_cacheline_blk_offset

class IBuffer extends ErythModule {
    val io = IO(new Bundle {
        val req = DecoupledIO(UInt(XLEN.W))
        val rsp = Flipped(ValidIO(UInt((CachelineSize * 8).W)))

        val fetch_req = Flipped(DecoupledIO(new InstFetchBlock)) // from FTQ
        val fetch_rsp = ValidIO(new InstFetchBlock) // to FTQ

        val flush = Input(Bool())
    })

    val (req, rsp) = (io.req, io.rsp)
    val (fetch_req, fetch_rsp) = (io.fetch_req, io.fetch_rsp)

    val base_pc = RegInit(0.U(XLEN.W))
    when (req.fire) {
        base_pc := req.bits(XLEN - 1, log2Ceil(CachelineSize))
    }

    def in_range(addr: UInt): Bool = {
        addr(XLEN - 1, log2Ceil(CachelineSize)) === base_pc
    }

    val buffer = RegInit(VecInit(Seq.fill(CachelineSize / 4)(0.U(XLEN.W))))

    /* ----------------- Stage Control ----------------- */
    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())

    /* ----------------- Stage 0 ----------------- */
    s0_valid := fetch_req.valid
    s0_ready := s1_ready || io.flush

    val s0_task = fetch_req.bits
    val s0_inrange = fetch_req.bits.instVec.map{
        case inst => 
            in_range(inst.pc) || !inst.valid
    }.reduce(_ && _)

    fetch_req.ready := s0_ready || io.flush

    /* ----------------- Stage 1 ----------------- */
    val has_req = RegInit(false.B)
    val s1_task = RegInit(0.U.asTypeOf(new InstFetchBlock))
    val s1_inrange = RegInit(false.B)

    when (s0_valid && s1_ready) {
        s1_valid := s0_valid && !io.flush
        s1_task := s0_task
        s1_inrange := s0_inrange
        has_req := false.B
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_task := 0.U.asTypeOf(new InstFetchBlock)
        s1_inrange := false.B
        has_req := false.B
    }

    s1_ready := !s1_valid || io.flush

    // req for data
    when (req.fire) {
        has_req := true.B
    }

    req.valid := s1_valid && !io.flush && !s1_inrange && !has_req
    req.bits := s1_task.instVec(0).pc

    when (rsp.valid) {
        for (i <- 0 until CachelineSize / 4) {
            buffer(i) := rsp.bits((i + 1) * XLEN - 1, i * XLEN)
        }
    }

    val rsp_vec = Wire(Vec(CachelineSize / 4, UInt(XLEN.W)))
    for (i <- 0 until CachelineSize / 4) {
        rsp_vec(i) := rsp.bits((i + 1) * XLEN - 1, i * XLEN)
    }

    val rsp_blk = WireInit(s1_task)
    for (i <- 0 until FetchWidth) {
        val pc = rsp_blk.instVec(i).pc
        val idx = get_cacheline_blk_offset(pc)

        when (rsp_blk.instVec(i).valid) {
            rsp_blk.instVec(i).instr := Mux(s1_inrange, buffer(idx), rsp_vec(idx))
        }.otherwise {
            rsp_blk.instVec(i).instr := 0.U
        }
    }

    fetch_rsp.valid := s1_valid && (s1_inrange || rsp.valid) && !io.flush
    fetch_rsp.bits := rsp_blk
}