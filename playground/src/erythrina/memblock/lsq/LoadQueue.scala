package erythrina.memblock.lsq

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.backend.rob.ROBPtr

class LoadQueue extends ErythModule {
    val io = IO(new Bundle {
        val alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        val alloc_rsp = Vec(DispatchWidth, Output(new LQPtr))

        val alloc_upt = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))    // update ROBPtr

        // rob commit
        val rob_commit = Vec(CommitWidth, Flipped(ValidIO(new LQPtr)))

        // to rob
        val lq_except_infos = Vec(LoadQueSize, ValidIO(new ROBPtr))

        // store-load exception detect
        val st_req = Flipped(ValidIO(new InstExInfo))

        // from LDU res
        val ldu_cmt = Flipped(ValidIO(new InstExInfo))

        // redirect?
    })

    val entries = RegInit(VecInit(Seq.fill(LoadQueSize)(0.U.asTypeOf(new InstExInfo))))
    val valids = RegInit(VecInit(Seq.fill(LoadQueSize)(false.B)))
    val ldu_finished = RegInit(VecInit(Seq.fill(LoadQueSize)(false.B)))
    val has_st2ld = RegInit(VecInit(Seq.fill(LoadQueSize)(false.B)))

    // ptr
    val allocPtrExt = RegInit(VecInit((0 until DispatchWidth).map(_.U.asTypeOf(new LQPtr))))
    val deqPtrExt = RegInit(0.U.asTypeOf(new LQPtr))

    // alloc (enq)
    val (alloc_req, alloc_rsp) = (io.alloc_req, io.alloc_rsp)
    val needAlloc = Wire(Vec(DispatchWidth, Bool()))
    val canAlloc = Wire(Vec(DispatchWidth, Bool()))
    val all_canAlloc = alloc_req.map{
        case e => !e.valid || e.ready
    }.reduce(_ && _)

    for (i <- 0 until DispatchWidth) {
        val index = PopCount(needAlloc.take(i))
        val ptr = allocPtrExt(index)

        needAlloc(i) := alloc_req(i).valid
        canAlloc(i) := needAlloc(i) && ptr >= deqPtrExt
        when (canAlloc(i) && all_canAlloc) {
            entries(ptr.value) := alloc_req(i).bits
            valids(ptr.value) := true.B
            ldu_finished(ptr.value) := false.B
            has_st2ld(ptr.value) := false.B
        }

        alloc_rsp(i) := ptr
        alloc_req(i).ready := ptr >= deqPtrExt
    }
    val allocNum = PopCount(canAlloc)
    allocPtrExt.foreach{case x => when (all_canAlloc) {x := x + allocNum}}

    val alloc_upt = io.alloc_upt
    for (i <- 0 until DispatchWidth) {
        when (alloc_upt(i).valid) {
            val ptr = alloc_upt(i).bits.lqPtr
            entries(ptr.value) := alloc_upt(i).bits
        }
    }

    // ldu cmt
    val ldu_cmt = io.ldu_cmt
    when (ldu_cmt.valid) {
        val ptr = ldu_cmt.bits.lqPtr
        entries(ptr.value) := ldu_cmt.bits
        ldu_finished(ptr.value) := true.B
    }

    // rob commit
    val rob_commit = io.rob_commit
    for (i <- 0 until CommitWidth) {
        when (rob_commit(i).valid) {
            val ptr = rob_commit(i).bits
            entries(ptr.value) := 0.U.asTypeOf(new InstExInfo)
            valids(ptr.value) := false.B
            ldu_finished(ptr.value) := false.B
            has_st2ld(ptr.value) := false.B
        }
    }
    
    // Store-Load exception detect
    val lq_exc_infos = io.lq_except_infos
    val st_req = io.st_req
    val st_addr = st_req.bits.src1 + st_req.bits.imm
    when (st_req.valid) {
        for (i <- 0 until LoadQueSize) {
            when (valids(i) && ldu_finished(i) && entries(i).addr === st_addr) {
                has_st2ld(i) := true.B
            }
        }
    }

    for (i <- 0 until LoadQueSize) {
        lq_exc_infos(i).valid := has_st2ld(i) && valids(i) && ldu_finished(i)
        lq_exc_infos(i).bits := entries(i).robPtr
    }

    // deq (free)
    when (deqPtrExt < allocPtrExt(0) && !valids(deqPtrExt.value)) {
        deqPtrExt := deqPtrExt + 1.U
    }
}