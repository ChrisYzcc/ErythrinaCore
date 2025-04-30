package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import erythrina.{ErythModule, ErythBundle}
import BPUParmams._

class BTBReq extends ErythBundle {
    val pc = UInt(XLEN.W)
}

class BTBRsp extends ErythBundle {
    val taken = Bool()
    val target = UInt(XLEN.W)
}

class BTBUpt extends ErythBundle {
    val pc = UInt(XLEN.W)
    val target = UInt(XLEN.W)
    val taken = Bool()
}

class BTB extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(Vec(FetchWidth, Flipped(ValidIO(new BTBReq))))
        val rsp = Vec(FetchWidth, ValidIO(new BTBRsp))
        val upt = Vec(CommitWidth, Flipped(ValidIO(new BTBUpt)))
    })

    println(s"BTB: BTBSize = ${BTBSize}")
    
    /* ------------- Meta & Target & Sat Cnt ------------- */
    val targets = SyncReadMem(BTBSize, UInt(XLEN.W))
    val satcnts = RegInit(VecInit(Seq.fill(BTBSize){0.U(2.W)}))

    when (reset.asBool) {
        for (i <- 0 until BTBSize) {
            targets.write(i.U, 0.U)
        }
    }

    /* ------------- Request ------------- */
    val btb_targets = Wire(Vec(FetchWidth, UInt(XLEN.W)))
    val btb_satcnts = Wire(Vec(FetchWidth, UInt(2.W)))

    for (i <- 0 until FetchWidth) {
        val idx = get_btb_idx(io.req(i).bits.pc)
        btb_targets(i) := targets.read(idx, io.req(i).valid)
        btb_satcnts(i) := RegNext(satcnts(idx))
    }

    /* ------------- Response ------------- */
    for (i <- 0 until FetchWidth) {
        io.rsp(i).valid := RegNext(io.req(i).valid)
        io.rsp(i).bits.taken := btb_satcnts(i)(1)
        io.rsp(i).bits.target := btb_targets(i)
    }

    /* ------------- Update ------------- */
    for (i <- 0 until BTBSize) {
        val taken_vec = io.upt.map{
            case u =>
                u.valid && get_btb_idx(u.bits.pc) === i.U && u.bits.taken
        }

        val untaken_vec = io.upt.map{
            case u =>
                u.valid && get_btb_idx(u.bits.pc) === i.U && !u.bits.taken
        }

        val taken_cnt = PopCount(taken_vec)
        val untaken_cnt = PopCount(untaken_vec)

        // Update Saturation Cnt
        when (taken_cnt > untaken_cnt) {
            satcnts(i) := Mux((satcnts(i) + taken_cnt - untaken_cnt > 3.U), 3.U, satcnts(i) + taken_cnt - untaken_cnt)
        }

        when (taken_cnt < untaken_cnt) {
            satcnts(i) := Mux(satcnts(i) < untaken_cnt - taken_cnt, 0.U, satcnts(i) - taken_cnt + untaken_cnt)
        }

        // Update Target Buffer
        val newest_taken_idx = (CommitWidth - 1).U - PriorityEncoder(untaken_vec.reverse)
        when (io.upt(newest_taken_idx).valid) {
            targets.write(i.U, io.upt(newest_taken_idx).bits.target)
        }
    }
}