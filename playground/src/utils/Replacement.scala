package utils

import chisel3._
import chisel3.util._

class PLRU extends Module {
    val io = IO(new Bundle {
        val update = Flipped(ValidIO(UInt(2.W)))
        val oldest = Output(UInt(2.W))
    })

    val plru = RegInit(VecInit(Seq.fill(3)(false.B)))

    // 0: oldest is on the left
    // 1: oldest is on the right

    /* ---------------- update ---------------- */
    when (io.update.valid) {
        val idx = io.update.bits
        switch (idx) {
            is (0.U) {
                plru(0) := true.B
                plru(1) := true.B
            }
            is (1.U) {
                plru(0) := true.B
                plru(1) := false.B
            }
            is (2.U) {
                plru(0) := false.B
                plru(2) := true.B
            }
            is (3.U) {
                plru(0) := false.B
                plru(2) := false.B
            }
        }
    }

    /* ---------------- get ---------------- */
    val plru_num = plru.asUInt
    assert(plru_num === 0.U || plru_num === 6.U || plru_num === 3.U || plru_num === 5.U, "invalid plru_num")
    io.oldest := LookupTree(plru_num, Seq(
        0.U -> 0.U,
        3.U -> 2.U,
        6.U -> 1.U,
        5.U -> 3.U
    ))
}