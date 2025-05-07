package bus.axi4

import chisel3._
import chisel3.util._

class AXI4Arbiter(n:Int) extends Module {
    val io = IO(new Bundle {
        val in = Vec(n, Flipped(new AXI4))
        val out = new AXI4
    })

    val (in, out) = (io.in, io.out)

    val w_inflight = RegInit(false.B)

    /* ----------------- Read Channel ----------------- */
    val r_idle = RegInit(true.B)
    when (io.out.ar.fire) {
        r_idle := false.B
    }.elsewhen(io.out.r.fire && io.out.r.bits.last) {
        r_idle := true.B
    }

    val rd_arb = Module(new RRArbiter(new AXI4BundleA(AXI4Params.idBits), n))
    val rd_chosen = RegInit(0.U)
    when (io.out.ar.fire) {
        rd_chosen := rd_arb.io.chosen
    }.elsewhen(io.out.r.fire && io.out.r.bits.last) {
        rd_chosen := 0.U
    }

    // AR
    for (i <- 0 until n) {
        in(i).ar <> rd_arb.io.in(i)
    }
    out.ar.valid := rd_arb.io.out.valid && !w_inflight && r_idle
    out.ar.bits := rd_arb.io.out.bits
    rd_arb.io.out.ready := out.ar.ready && !w_inflight && r_idle

    // R
    out.r.ready := in(rd_chosen).r.ready
    for (i <- 0 until n) {
        in(i).r.valid := out.r.valid && rd_chosen === i.U
        in(i).r.bits := out.r.bits
    }

    /* ----------------- Write Channel ---------------- */
    val aw_has_fire = RegInit(false.B)
    when (io.out.aw.fire) {
        aw_has_fire := true.B
    }.elsewhen(io.out.b.fire) {
        aw_has_fire := false.B
    }

    val wr_arb = Module(new RRArbiter(new AXI4BundleA(AXI4Params.idBits), n))
    val wr_chosen = RegInit(0.U)
    when (io.out.aw.fire) {
        wr_chosen := wr_arb.io.chosen
    }.elsewhen(io.out.b.fire) {
        wr_chosen := 0.U
    }

    when ((io.out.aw.fire || aw_has_fire) && io.out.w.fire) {
        w_inflight := true.B
    }.elsewhen(io.out.b.fire) {
        w_inflight := false.B
    }

    // AW
    for (i <- 0 until n) {
        in(i).aw <> wr_arb.io.in(i)
    }
    out.aw <> wr_arb.io.out

    // W
    for (i <- 0 until n) {
        in(i).w.ready := out.w.ready && Mux(aw_has_fire, wr_chosen, wr_arb.io.chosen) === i.U
    }
    out.w.valid := in(Mux(aw_has_fire, wr_chosen, wr_arb.io.chosen)).w.valid
    out.w.bits := in(Mux(aw_has_fire, wr_chosen, wr_arb.io.chosen)).w.bits

    // B
    out.b.ready := in(wr_chosen).b.ready
    for (i <- 0 until n) {
        in(i).b.valid := out.b.valid && wr_chosen === i.U
        in(i).b.bits := out.b.bits
    }
}