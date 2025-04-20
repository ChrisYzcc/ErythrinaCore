package erythrina.backend.rename

import chisel3._
import chisel3.util._

import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.Redirect
import utils.CircularQueuePtr
import os.makeDir.all

class InstrPool extends ErythModule {
    val io = IO(new Bundle {
        val enq_req = Flipped(DecoupledIO(Vec(RenameWidth, Valid(new InstExInfo))))  // from IRU
        val deq_req = Vec(DispatchWidth, new Bundle {
            val valid = Input(Bool())
            val ready = Output(Bool())
            val bits = Output(new InstExInfo)
        })
        val redirect = Flipped(ValidIO(new Redirect))
    })

    def PoolSize = 4

    class Ptr extends CircularQueuePtr[Ptr](PoolSize) {
    }

    object Ptr {
        def apply(f: Bool, v: UInt): Ptr = {
            val ptr = Wire(new Ptr)
            ptr.flag := f
            ptr.value := v
            ptr
        }
    }

    val entries = RegInit(VecInit(Seq.fill(PoolSize)(0.U.asTypeOf(new InstExInfo))))
    val valids = RegInit(VecInit(Seq.fill(PoolSize)(false.B)))

    // ptr
    val enqPtrExt = RegInit(VecInit((0 until RenameWidth).map(_.U.asTypeOf(new Ptr))))
    val deqPtrExt = RegInit(VecInit((0 until DispatchWidth).map(_.U.asTypeOf(new Ptr))))

    // enq
    val enq_req = io.enq_req
    val need_enq = Wire(Vec(RenameWidth, Bool()))
    val can_enq = Wire(Vec(RenameWidth, Bool()))
    val all_can_enq = need_enq.zip(can_enq).map {
        case (need, can) => !need || can
    }.reduce(_ && _)

    enq_req.ready := all_can_enq

    for (i <- 0 until RenameWidth) {
        val index = PopCount(need_enq.take(i))
        val ptr = enqPtrExt(index)

        need_enq(i) := enq_req.bits(i).valid && enq_req.valid
        can_enq(i) := need_enq(i) && ptr >= deqPtrExt(0)
        when (can_enq(i) && all_can_enq) {
            entries(ptr.value) := enq_req.bits(i).bits
            valids(ptr.value) := true.B
        }
    }
    val enqNum = PopCount(can_enq)
    enqPtrExt.foreach{case x => when (all_can_enq) {x := x + enqNum}}

    // deq
    val deq_req = io.deq_req
    val need_deq = Wire(Vec(DispatchWidth, Bool()))
    val can_deq = Wire(Vec(DispatchWidth, Bool()))

    for (i <- 0 until DispatchWidth) {
        val index = PopCount(need_deq.take(i))
        val ptr = deqPtrExt(index)

        need_deq(i) := deq_req(i).valid
        can_deq(i) := need_deq(i) && valids(ptr.value) && ptr < enqPtrExt(0) && !io.redirect.valid
        
        deq_req(i).ready := valids(ptr.value) && ptr < enqPtrExt(0) && !io.redirect.valid
        deq_req(i).bits := entries(ptr.value)
        when (can_deq(i)) {
            valids(ptr.value) := false.B
        }
    }
    val deqNum = PopCount(can_deq)
    deqPtrExt.foreach{case x => when (can_deq.reduce(_ || _)) {x := x + deqNum}}

    // redirect
    when (io.redirect.valid) {
        for (i <- 0 until PoolSize) {
            valids(i) := false.B
        }
        for (i <- 0 until RenameWidth) {
            enqPtrExt(i) := i.U.asTypeOf(new Ptr)
        }
        for (i <- 0 until DispatchWidth) {
            deqPtrExt(i) := i.U.asTypeOf(new Ptr)
        }
    }
}