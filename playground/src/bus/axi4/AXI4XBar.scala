package bus.axi4

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class AXI4XBar(addr_space: List[(UInt, UInt)]) extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(new AXI4Lite)
        val out = Vec(addr_space.size, new AXI4Lite)
    })

    val (in, out) = (io.in, io.out)

    /* ----------------- Read Channel ----------------- */
    val r_hit_vec = addr_space.map{
        case (start, end) => {
            val hit = Wire(Bool())
            hit := in.ar.bits.addr >= start && in.ar.bits.addr < end
            hit
        }
    }
    assert(!in.ar.valid || r_hit_vec.reduce(_ || _), "AXI4XBar: read address not in range")
    assert(!in.ar.valid || PopCount(r_hit_vec) <= 1.U, "AXI4XBar: read address in multiple ranges")

    val r_chosen = PriorityEncoder(r_hit_vec)
    val r_chosen_reg = RegEnable(r_chosen, 0.U, in.ar.fire)

    // AR
    in.ar.ready := out(r_chosen).ar.ready
    for (i <- 0 until addr_space.size) {
        out(i).ar.valid := in.ar.valid && r_hit_vec(i)
        out(i).ar.bits := in.ar.bits
    }

    // R
    in.r.valid := out(r_chosen_reg).r.valid
    in.r.bits := out(r_chosen_reg).r.bits
    for (i <- 0 until addr_space.size) {
        out(i).r.ready := in.r.ready && r_chosen_reg === i.U
    }

    /* ----------------- Write Channel ---------------- */
    val aw_has_fire = RegInit(false.B)
    when (in.aw.fire) {
        aw_has_fire := true.B
    }.elsewhen(in.b.fire) {
        aw_has_fire := false.B
    }

    val w_has_fire = RegInit(false.B)
    when (in.w.fire) {
        w_has_fire := true.B
    }.elsewhen(in.b.fire) {
        w_has_fire := false.B
    }

    val w_hit_vec = addr_space.map{
        case (start, end) => {
            val hit = Wire(Bool())
            hit := in.aw.bits.addr >= start && in.aw.bits.addr < end
            hit
        }
    }
    assert(!in.aw.valid || w_hit_vec.reduce(_ || _), "AXI4XBar: write address not in range")
    assert(!in.aw.valid || PopCount(w_hit_vec) <= 1.U, "AXI4XBar: write address in multiple ranges")

    val w_chosen = PriorityEncoder(w_hit_vec)
    val w_chosen_reg = RegEnable(w_chosen, 0.U, in.aw.fire)

    // AW
    in.aw.ready := out(w_chosen).aw.ready
    for (i <- 0 until addr_space.size) {
        out(i).aw.valid := in.aw.valid && w_hit_vec(i)
        out(i).aw.bits := in.aw.bits
    }

    // W
    in.w.ready := out(Mux(aw_has_fire, w_chosen, w_chosen_reg)).w.ready
    for (i <- 0 until addr_space.size) {
        out(i).w.valid := in.w.valid && Mux(aw_has_fire, w_chosen, w_chosen_reg) === i.U
        out(i).w.bits := in.w.bits
    }

    // B
    in.b.valid := out(w_chosen_reg).b.valid
    in.b.bits := out(w_chosen_reg).b.bits
    for (i <- 0 until addr_space.size) {
        out(i).b.ready := in.b.ready && w_chosen_reg === i.U
    }
}