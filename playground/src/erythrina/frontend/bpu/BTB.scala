package erythrina.frontend.bpu

import chisel3._
import chisel3.util._
import erythrina.{ErythModule, ErythBundle}
import BPUParmams._
import utils.MultiPortQueue
import utils.PerfCount
import top.Config

class BTBReq extends ErythBundle {
    val pc = UInt(XLEN.W)
}

class BTBRsp extends ErythBundle {
    val hit = Bool()
    val taken = Bool()
    val target = UInt(XLEN.W)
}

class BPUTrainInfo extends ErythBundle {
    val hit = Bool()
    val pc = UInt(XLEN.W)
    val target = UInt(XLEN.W)
    val taken = Bool()

    val ghr = UInt(XLEN.W)
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
        val ghr = Input(UInt(XLEN.W))
        val rsp = Vec(FetchWidth, ValidIO(new BTBRsp))
        val upt = Flipped(ValidIO(new BPUTrainInfo))
    })

    println(s"BTB: BTBSize = ${BTBSize}")

    val train_req = io.upt
    
    def get_btb_idx(pc: UInt, ghr: UInt): UInt = {
        val new_pc = pc ^ ghr
        new_pc(log2Ceil(BTBSize) + 1, 2)
    }
    def get_btb_tag(pc: UInt, ghr: UInt): UInt = {
        val new_pc = pc ^ ghr
        new_pc(XLEN - 1, XLEN - TagBits)
    }

    /* ------------- Meta & Target & Sat Cnt ------------- */
    val targets = SyncReadMem(BTBSize, UInt(XLEN.W))
    val tags = SyncReadMem(BTBSize, UInt(TagBits.W))
    val satcnts = RegInit(VecInit(Seq.fill(BTBSize)(0.U.asTypeOf(new SatCnt(2)))))
    val valids = RegInit(VecInit(Seq.fill(BTBSize)(false.B)))

    /* ------------- Request ------------- */
    val ghr = io.ghr

    val btb_targets = Wire(Vec(FetchWidth, UInt(XLEN.W)))
    val btb_tags = Wire(Vec(FetchWidth, UInt(TagBits.W)))
    val btb_satcnts = Wire(Vec(FetchWidth, UInt(2.W)))
    val btb_valids = Wire(Vec(FetchWidth, Bool()))

    for (i <- 0 until FetchWidth) {
        val idx = get_btb_idx(io.req(i).bits.pc, ghr)

        btb_targets(i) := targets.read(idx, io.req(i).valid)
        btb_tags(i) := tags.read(idx, io.req(i).valid)
        btb_satcnts(i) := RegNext(satcnts(idx).cnt)
        btb_valids(i) := RegNext(valids(idx))
    }

    /* ------------- Response ------------- */
    for (i <- 0 until FetchWidth) {
        io.rsp(i).valid := RegNext(io.req(i).valid)
        io.rsp(i).bits.hit := btb_tags(i) === get_btb_tag(RegNext(io.req(i).bits.pc), RegNext(ghr)) && btb_valids(i)
        io.rsp(i).bits.taken := btb_satcnts(i)(1) && io.rsp(i).bits.hit
        io.rsp(i).bits.target := btb_targets(i)
    }

    /* ------------- Update ------------- */
    when (train_req.valid) {
        val idx = get_btb_idx(train_req.bits.pc, train_req.bits.ghr)
        val tag = get_btb_tag(train_req.bits.pc, train_req.bits.ghr)
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
            valids(idx) := true.B
        }
    }
    
    /* ------------------ Perf ------------------ */
    for (i <- 0 until BTBSize) {
        PerfCount(s"btb_replace_$i", train_req.valid && !train_req.bits.hit && get_btb_idx(train_req.bits.pc, train_req.bits.ghr) === i.U)
    }
}