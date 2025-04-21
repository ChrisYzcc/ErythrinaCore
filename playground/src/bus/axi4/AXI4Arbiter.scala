package bus.axi4

import chisel3._
import chisel3.util._

class AXI4LiteArbiter(n:Int) extends Module {
    val io = IO(new Bundle {
        val in = Vec(n, Flipped(new AXI4Lite))
        val out = new AXI4Lite
    })

    val (in, out) = (io.in, io.out)

    /* ----------------- Read Channel ----------------- */
    val r_arb = Module(new RRArbiter(new AXI4LiteBundleA, n))
    val r_chosen = RegInit(false.B)
    when (io.out.ar.fire) {
        r_chosen := r_arb.io.chosen
    }.elsewhen(io.out.r.fire) {
        r_chosen := 0.U
    }

    // AR
    for (i <- 0 until n) {
        in(i).ar <> r_arb.io.in(i)
    }
    out.ar <> r_arb.io.out

    //R
    out.r.ready := in(r_chosen).r.ready
    for (i <- 0 until n) {
        in(i).r.valid := out.r.valid && r_chosen === i.U
        in(i).r.bits := out.r.bits
    }


    /* ----------------- Write Channel ---------------- */
    val aw_has_fire = RegInit(false.B)
    when (io.out.aw.fire) {
        aw_has_fire := true.B
    }.elsewhen(io.out.b.fire) {
        aw_has_fire := false.B
    }

    val w_arb = Module(new RRArbiter(new AXI4LiteBundleA, n))
    val w_chosen = RegInit(false.B)
    when (io.out.aw.fire) {
        w_chosen := w_arb.io.chosen
    }.elsewhen(io.out.b.fire) {
        w_chosen := 0.U
    }

    // AW
    for (i <- 0 until n) {
        in(i).aw <> w_arb.io.in(i)
    }
    out.aw <> w_arb.io.out

    // W
    for (i <- 0 until n) {
        in(i).w.ready := out.w.ready && Mux(aw_has_fire, w_chosen, w_arb.io.chosen) === i.U
    }
    out.w.valid := in(Mux(aw_has_fire, w_chosen, w_arb.io.chosen)).w.valid
    out.w.bits := in(Mux(aw_has_fire, w_chosen, w_arb.io.chosen)).w.bits

    // B
    out.b.ready := in(w_chosen).b.ready
    for (i <- 0 until n) {
        in(i).b.valid := out.b.valid && w_chosen === i.U
        in(i).b.bits := out.b.bits
    }
}