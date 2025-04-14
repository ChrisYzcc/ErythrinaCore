package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo

class IDU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val decode_req = Flipped(Decoupled(new InstFetchBlock))
        val decode_res = Vec(DecodeWidth, Decoupled(new InstExInfo))
    })

    val decoder_seq = Seq.fill(DecodeWidth)(Module(new Decoder))

    val decode_req = io.decode_req
    val decode_res_vec = io.decode_res

    for (i <- 0 until DecodeWidth) {
        val decoder = decoder_seq(i)
        val decode_res = decode_res_vec(i)

        val decoder_in = WireInit(decode_req.bits.instVec(i))
        decoder_in.valid := decode_req.bits.instVec(i).valid && !io.flush && io.decode_req.valid

        decoder.io.in := decoder_in

        decode_res.valid := decoder.io.out.valid && !io.flush
        decode_res.bits := decoder.io.out.bits
    }

    decode_req.ready := decode_res_vec.map(_.ready).reduce(_ && _)
}