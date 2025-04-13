package erythrina.memblock.lsq

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import bus.axi4._
import erythrina.memblock.StoreFwdBundle
import erythrina.backend.Redirect

class StoreQueue extends ErythModule {
    val io = IO(new Bundle {
        val alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        val alloc_rsp = Vec(DispatchWidth, Output(new SQPtr))

        val alloc_upt = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))    // update ROBPtr

        // rob commit
        val rob_commit = Vec(CommitWidth, Flipped(ValidIO(new SQPtr)))

        // from STU res
        val stu_cmt = Flipped(ValidIO(new InstExInfo))

        // Forward Store Res
        val sq_fwd = Vec(StoreQueSize, ValidIO(new StoreFwdBundle))

        // to mem/D$
        val axi = new Bundle {
            val aw = DecoupledIO(new AXI4LiteBundleA)
            val w = DecoupledIO(new AXI4LiteBundleW(dataBits = XLEN))
            val b = Flipped(DecoupledIO(new AXI4LiteBundleB))
        }

        // redirect
        val redirect = Flipped(ValidIO(new Redirect))
    })

    val entries = RegInit(VecInit(Seq.fill(StoreQueSize)(0.U.asTypeOf(new InstExInfo))))
    val valids = RegInit(VecInit(Seq.fill(StoreQueSize)(false.B)))
    val stu_finished = RegInit(VecInit(Seq.fill(StoreQueSize)(false.B)))
    val rob_commited = RegInit(VecInit(Seq.fill(StoreQueSize)(false.B)))

    // ptr
    val allocPtrExt = RegInit(VecInit((0 until DispatchWidth).map(_.U.asTypeOf(new SQPtr))))
    val deqPtrExt = RegInit(0.U.asTypeOf(new SQPtr))
    val uncommitedPtrExt = RegInit(VecInit((0 until CommitWidth).map(_.U.asTypeOf(new SQPtr))))

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
            stu_finished(ptr.value) := false.B
            rob_commited(ptr.value) := false.B
        }
        alloc_rsp(i) := ptr
        alloc_req(i).ready := ptr >= deqPtrExt
    }
    val allocNum = PopCount(canAlloc)
    allocPtrExt.foreach{case x => when (all_canAlloc) {x := x + allocNum}}

    val alloc_upt = io.alloc_upt
    for (i <- 0 until DispatchWidth) {
        when (alloc_upt(i).valid) {
            val ptr = alloc_upt(i).bits.sqPtr
            entries(ptr.value) := alloc_upt(i).bits
        }
    }

    // stu cmt
    val stu_cmt = io.stu_cmt
    when (stu_cmt.valid) {
        val ptr = stu_cmt.bits.sqPtr
        entries(ptr.value) := stu_cmt.bits
        stu_finished(ptr.value) := true.B
    }

    // rob commit
    val rob_commit = io.rob_commit
    for (i <- 0 until CommitWidth) {
        when (rob_commit(i).valid) {
            val ptr = rob_commit(i).bits
            rob_commited(ptr.value) := true.B
        }
    }
    val rob_commitNum = PopCount(rob_commit.map(_.valid))
    uncommitedPtrExt.foreach{case x => when (rob_commitNum > 0.U) {x := x + rob_commitNum}}

    // StoreQueue Forward
    val sq_fwd = io.sq_fwd
    for (i <- 0 until StoreQueSize) {
        sq_fwd(i).valid := valids(i) && stu_finished(i)
        sq_fwd(i).bits.data := entries(i).res
        sq_fwd(i).bits.mask := entries(i).mask
        sq_fwd(i).bits.addr := entries(i).addr
        sq_fwd(i).bits.robPtr := entries(i).robPtr
    }

    // AXI Req
    val axi = io.axi

    val sREQ :: sACK :: Nil = Enum(2)
    val state = RegInit(sREQ)

    val aw_has_acked = RegInit(false.B)
    val w_has_acked = RegInit(false.B)

    when (axi.aw.fire) {
        aw_has_acked := true.B
    }
    when (axi.w.fire) {
        w_has_acked := true.B
    }
    when (axi.b.fire) {
        aw_has_acked := false.B
        w_has_acked := false.B
    }

    val aw_acked = aw_has_acked || axi.aw.fire
    val w_acked = w_has_acked || axi.w.fire

    switch (state) {
        is (sREQ) {
            when (aw_acked && w_acked) {
                state := sACK
            }
        }
        is (sACK) {
            when (axi.b.fire) {
                state := sREQ
            }
        }
    }

    axi.aw.valid := state === sREQ && valids(deqPtrExt.value) && stu_finished(deqPtrExt.value) && rob_commited(deqPtrExt.value)
    axi.aw.bits := 0.U.asTypeOf(new AXI4LiteBundleA)
    axi.aw.bits.addr := entries(deqPtrExt.value).addr

    axi.w.valid := state === sREQ && valids(deqPtrExt.value) && stu_finished(deqPtrExt.value) && rob_commited(deqPtrExt.value)
    axi.w.bits := 0.U.asTypeOf(new AXI4LiteBundleW(dataBits = XLEN))
    axi.w.bits.data := entries(deqPtrExt.value).res
    axi.w.bits.strb := entries(deqPtrExt.value).mask

    axi.b.ready := state === sACK

    // deq
    when (axi.b.fire) {
        valids(deqPtrExt.value) := false.B
        stu_finished(deqPtrExt.value) := false.B
        rob_commited(deqPtrExt.value) := false.B
    }

    when (deqPtrExt < allocPtrExt(0) && !valids(deqPtrExt.value)) {
        deqPtrExt := deqPtrExt + 1.U
    }

    // redirect
    when (io.redirect.valid) {
        for (i <- 0 until StoreQueSize) {
            when (!rob_commited(i)) {
                entries(i) := 0.U.asTypeOf(new InstExInfo)
                valids(i) := false.B
                stu_finished(i) := false.B
                rob_commited(i) := false.B
            }
        }
        for (i <- 0 until DispatchWidth) {
            allocPtrExt(i) := uncommitedPtrExt(i)
        }
    }
}