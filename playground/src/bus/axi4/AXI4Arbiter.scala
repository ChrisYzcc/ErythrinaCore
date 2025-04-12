package bus.axi4

import chisel3._
import chisel3.util._

object AXI4MuxDummy {
    def apply[T <: AXI4Lite](slave: T = new AXI4, master: T = new AXI4Lite, cond: Bool)={
        // ar
        master.ar.ready := Mux(cond, slave.ar.ready, 0.B)
        
        // aw
        master.aw.ready := Mux(cond, slave.aw.ready, 0.B)

        // r
        master.r.valid  := Mux(cond, slave.r.valid, 0.B)
        master.r.bits   <> slave.r.bits

        // w
        master.w.ready  := Mux(cond, slave.w.ready, 0.B)

        // b
        master.b.valid  := Mux(cond, slave.b.valid, 0.B)
        master.b.bits   <> slave.b.bits
    }
}

class AXI4ArbiterNto1[T <: AXI4Lite](n:Int, _type: T = new AXI4) extends Module{
    val io = IO(new Bundle {
        val in  = Vec(n, Flipped(_type))
        val out = _type
    })

    /*---------- Read Channel ----------*/
    val rd_inflight = RegInit(0.B)
    when (io.out.ar.valid){
        rd_inflight := 1.B
    }.elsewhen(io.out.r.fire){
        rd_inflight := 0.B
    }

    val rd_hitvec   = VecInit(io.in.map(p => p.ar.valid))
    val rd_hitvec_r = RegEnable(rd_hitvec, io.out.ar.valid & ~rd_inflight)

    val rd_selvec_0 = VecInit(PriorityEncoderOH(rd_hitvec))
    val rd_selvec_1 = VecInit(PriorityEncoderOH(rd_hitvec_r))
    val rd_selvec   = Mux(rd_inflight, rd_selvec_1, rd_selvec_0)

    val ar_has_done = RegInit(0.B)
    when (io.out.ar.fire){
        ar_has_done := 1.B
    }.elsewhen(io.out.r.fire){
        ar_has_done := 0.B
    }

    val r_stage     = ar_has_done

    // AR
    io.out.ar.valid := Mux1H(rd_selvec, io.in.map(_.ar.valid))
    io.out.ar.bits  := Mux1H(rd_selvec, io.in.map(_.ar.bits))
    for (i <- 0 until io.in.length){
        io.in(i).ar.ready := rd_selvec(i) & io.out.ar.ready
    }

    // R
    io.out.r.ready := Mux1H(rd_selvec, io.in.map(_.r.ready)) & r_stage
    for (i <- 0 until io.in.length){
        io.in(i).r.valid := rd_selvec(i) & io.out.r.valid & r_stage
        io.in(i).r.bits  := io.out.r.bits
    }

    /*---------- Write Channel ----------*/
    val aw_has_done = RegInit(0.B)
    val w_has_done  = RegInit(0.B)

    when (io.out.aw.fire){
        aw_has_done := 1.B
    }.elsewhen(io.out.b.fire){
        aw_has_done := 0.B
    }

    when (io.out.w.fire){
        w_has_done := 1.B
    }.elsewhen(io.out.b.fire){
        w_has_done := 0.B
    }

    val b_stage = w_has_done & aw_has_done

    val wr_inflight = RegInit(0.B)
    when (io.out.aw.valid){
        wr_inflight := 1.B
    }.elsewhen(io.out.b.fire){
        wr_inflight := 0.B
    }

    val wr_hitvec   = VecInit(io.in.map(p => p.aw.valid))
    val wr_hitvec_r = RegEnable(wr_hitvec, io.out.aw.valid & ~wr_inflight)

    val wr_selvec_0 = VecInit(PriorityEncoderOH(wr_hitvec))
    val wr_selvec_1 = VecInit(PriorityEncoderOH(wr_hitvec_r))
    val wr_selvec   = Mux(wr_inflight, wr_selvec_1, wr_selvec_0)

    // AW
    io.out.aw.valid := Mux1H(wr_selvec, io.in.map(_.aw.valid))
    io.out.aw.bits  := Mux1H(wr_selvec, io.in.map(_.aw.bits))
    for (i <- 0 until io.in.length){
        io.in(i).aw.ready := wr_selvec(i) & io.out.aw.ready
    }

    // W
    io.out.w.valid := Mux1H(wr_selvec, io.in.map(_.w.valid))
    io.out.w.bits  := Mux1H(wr_selvec, io.in.map(_.w.bits))
    for (i <- 0 until io.in.length){
        io.in(i).w.ready := wr_selvec(i) & io.out.w.ready
    }

    // B
    io.out.b.ready := Mux1H(wr_selvec, io.in.map(_.b.ready)) & b_stage
    for (i <- 0 until io.in.length){
        io.in(i).b.valid := wr_selvec(i) & io.out.b.valid & b_stage
        io.in(i).b.bits  := io.out.b.bits
    }
}