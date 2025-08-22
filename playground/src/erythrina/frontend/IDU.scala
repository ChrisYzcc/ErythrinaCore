package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.Redirect
import utils.PerfCount
import erythrina.frontend.bpu.BPUTrainInfo

class IDU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val decode_req = Flipped(Decoupled(new InstFetchBlock))
        val decode_res = DecoupledIO(Vec(DecodeWidth, Valid(new InstExInfo)))   // to Rename

        val redirect = ValidIO(new Redirect)
        
        val bpu_upt = Vec(DecodeWidth, ValidIO(new BPUTrainInfo))
    })

    val decoder_seq = Seq.fill(DecodeWidth)(Module(new Decoder))

    val decode_req = io.decode_req

    for (i <- 0 until DecodeWidth) {
        val decoder = decoder_seq(i)

        val decoder_in = WireInit(decode_req.bits.instVec(i))
        decoder_in.valid := decode_req.bits.instVec(i).valid && !io.flush && io.decode_req.valid

        decoder.io.in := decoder_in


        io.decode_res.bits(i).valid := decoder.io.out.valid && !io.flush
        io.decode_res.bits(i).bits := decoder.io.out.bits
    }

    io.decode_req.ready := io.decode_res.ready

    io.decode_res.valid := io.decode_res.bits.map(_.valid).reduce(_ || _) && !io.flush

    // redirect
    val redirect_vec = VecInit(decoder_seq.map(_.io.redirect))
    val redirect_idx = PriorityEncoder(redirect_vec.map(_.valid))

    io.redirect.valid := RegNext(redirect_vec.map(_.valid).reduce(_ || _) && io.decode_res.fire && !io.flush)
    io.redirect.bits := RegNext(redirect_vec(redirect_idx).bits)

    for (i <- 0 until DecodeWidth) {
        io.bpu_upt(i) <> decoder_seq(i).io.bpu_upt
    }

    /* --------------- Perf --------------- */
    PerfCount("bpu_correct_idu", Mux(io.decode_res.fire, PopCount(redirect_vec.map(!_.valid)), 0.U))
    PerfCount("bpu_wrong_idu", Mux(io.decode_res.fire, PopCount(redirect_vec.map(_.valid)), 0.U))
}