package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import erythrina.{ErythModule, ErythBundle}
import utils.PLRU

class Replacer extends ErythModule {
    val io = IO(new Bundle {
        val update_req = Vec(2, Flipped(ValidIO(new Bundle {
            val idx = UInt(log2Ceil(ICacheParams.sets).W)
            val way = UInt(log2Ceil(ICacheParams.ways).W)
        })))

        // For Fetcher
        val query_idx = Input(UInt(log2Ceil(ICacheParams.sets).W))
        val query_way = Output(UInt(log2Ceil(ICacheParams.ways).W))
    })

    val lru_seq = Seq.fill(ICacheParams.sets)(Module(new PLRU))
    val lru_oldest = VecInit(lru_seq.map(_.io.oldest))

    // update
    assert(!(io.update_req(0).valid && io.update_req(1).valid && (io.update_req(0).bits.idx === io.update_req(1).bits.idx)), "Replacer: update conflict")
    for (j <- 0 until ICacheParams.sets) {
        lru_seq(j).io.update.valid := io.update_req.map{
            case r =>
                r.valid && (r.bits.idx === j.U)
        }.reduce(_ || _)
        lru_seq(j).io.update.bits := Mux(io.update_req(0).valid && (io.update_req(0).bits.idx === j.U), io.update_req(0).bits.way, io.update_req(1).bits.way)
    }

    // query
    io.query_way := RegNext(lru_oldest(io.query_idx))
}