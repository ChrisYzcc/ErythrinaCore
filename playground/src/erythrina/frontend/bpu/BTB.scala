package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import erythrina.{ErythModule, ErythBundle}
import BPUParmams._
import utils.MultiPortQueue

class BTBReq extends ErythBundle {
    val pc = UInt(XLEN.W)
}

class BTBRsp extends ErythBundle {
    val hit = Bool()
    val taken = Bool()
    val target = UInt(XLEN.W)
}

class BTBUpt extends ErythBundle {
    val hit = Bool()
    val pc = UInt(XLEN.W)
    val target = UInt(XLEN.W)
    val taken = Bool()
}

class SatCnt(bits:Int) extends ErythBundle {
    val cnt = UInt(bits.W)

    def inc(): Unit = {
        cnt := Mux(cnt === ((1 << bits) - 1).U, cnt, cnt + 1.U)
    }
    def dec(): Unit = {
        cnt := Mux(cnt === 0.U, cnt, cnt - 1.U)
    }

    def reset(): Unit = {
        cnt := 0.U
    }
}

class BTB extends ErythModule {
    val io = IO(new Bundle {
        val req = Vec(FetchWidth, Flipped(ValidIO(new BTBReq)))
        val rsp = Vec(FetchWidth, ValidIO(new BTBRsp))
        val upt = Vec(CommitWidth, Flipped(ValidIO(new BTBUpt)))
    })

    println(s"BTB: BTBSize = ${BTBSize}")

    val train_queue = Module(new MultiPortQueue(new BTBUpt, 8, CommitWidth, 1))
    train_queue.io.flush := false.B
    train_queue.io.enq.zipWithIndex.map{
        case (enq, i) =>
            enq.valid := io.upt(i).valid
            enq.bits := io.upt(i).bits
    }

    train_queue.io.deq(0).ready := !reset.asBool
    val train_req = train_queue.io.deq(0)
    
    /* ------------- Meta & Target & Sat Cnt ------------- */
    val targets = SyncReadMem(BTBSize, UInt(XLEN.W))
    val tags = SyncReadMem(BTBSize, UInt(TagBits.W))
    val satcnts = RegInit(VecInit(Seq.fill(BTBSize)(0.U.asTypeOf(new SatCnt(2)))))

    when (reset.asBool) {
        for (i <- 0 until BTBSize) {
            targets.write(i.U, 0.U)
            tags.write(i.U, 0.U)
        }
    }

    /* ------------- Request ------------- */
    val btb_targets = Wire(Vec(FetchWidth, UInt(XLEN.W)))
    val btb_tags = Wire(Vec(FetchWidth, UInt(TagBits.W)))
    val btb_satcnts = Wire(Vec(FetchWidth, UInt(2.W)))

    for (i <- 0 until FetchWidth) {
        val idx = get_btb_idx(io.req(i).bits.pc)
        btb_targets(i) := targets.read(idx, io.req(i).valid)
        btb_tags(i) := tags.read(idx, io.req(i).valid)
        btb_satcnts(i) := RegNext(satcnts(idx).cnt)
    }

    /* ------------- Response ------------- */
    for (i <- 0 until FetchWidth) {
        io.rsp(i).valid := RegNext(io.req(i).valid)
        io.rsp(i).bits.hit := btb_tags(i) === get_btb_tag(RegNext(io.req(i).bits.pc))
        io.rsp(i).bits.taken := btb_satcnts(i)(1) && io.rsp(i).bits.hit
        io.rsp(i).bits.target := btb_targets(i)
    }

    /* ------------- Update ------------- */
    when (train_req.valid) {
        val idx = get_btb_idx(train_req.bits.pc)
        val tag = get_btb_tag(train_req.bits.pc)
        val hit = train_req.bits.hit
        val target = train_req.bits.target
        val taken = train_req.bits.taken

        // Update SatCnt
        when (hit) {
            when (taken) {
                satcnts(idx).inc()
            }.otherwise {
                satcnts(idx).dec()
            }
        }.otherwise {
            satcnts(idx).reset()
        }

        // Update Target && Tag
        when (!hit) {
            targets.write(idx, target)
            tags.write(idx, tag)
        }
    }
}