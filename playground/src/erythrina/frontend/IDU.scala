package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo

class IDU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val decode_req = Flipped(Decoupled(new InstFetchBlock))
        val decode_res = DecoupledIO(Vec(DecodeWidth, Valid(new InstExInfo)))
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
}