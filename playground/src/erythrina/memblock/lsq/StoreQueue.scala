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

        // rob commit
        val rob_commit = Vec(CommitWidth, Flipped(ValidIO(new SQPtr)))

        // from STU res
        val stu_cmt = Flipped(ValidIO(new InstExInfo))

        // Forward Store Res
        val sq_fwd = Vec(StoreQueSize, ValidIO(new StoreFwdBundle))

        // to mem/D$
        val axi = new Bundle {
            val aw = DecoupledIO(new AXI4BundleA(AXI4Params.idBits))
            val w = DecoupledIO(new AXI4BundleW(AXI4Params.dataBits))
            val b = Flipped(DecoupledIO(new AXI4BundleB(AXI4Params.idBits)))
        }

        // redirect
        val redirect = Flipped(ValidIO(new Redirect))
    })

    def reorder(in: Seq[Valid[InstExInfo]]): Seq[Valid[InstExInfo]] = {
        require(in.length == 2)
        val sorted_meta = Wire(Vec(in.length, Valid(new InstExInfo)))

        sorted_meta(0).valid := in(0).valid || in(1).valid
        sorted_meta(0).bits := Mux(in(0).valid && in(1).valid,
            Mux(in(0).bits.robPtr < in(1).bits.robPtr, in(0).bits, in(1).bits),
            Mux(in(0).valid, in(0).bits, in(1).bits)
        )

        sorted_meta(1).valid := in(0).valid && in(1).valid
        sorted_meta(1).bits := Mux(in(0).valid && in(1).valid,
            Mux(in(0).bits.robPtr < in(1).bits.robPtr, in(1).bits, in(0).bits),
            0.U.asTypeOf(new InstExInfo)
        )

        sorted_meta
    }

    val entries = RegInit(VecInit(Seq.fill(StoreQueSize)(0.U.asTypeOf(new InstExInfo))))
    val valids = RegInit(VecInit(Seq.fill(StoreQueSize)(false.B)))
    val stu_finished = RegInit(VecInit(Seq.fill(StoreQueSize)(false.B)))
    val rob_commited = RegInit(VecInit(Seq.fill(StoreQueSize)(false.B)))

    // ptr
    val allocPtrExt = RegInit(VecInit((0 until DispatchWidth).map(_.U.asTypeOf(new SQPtr))))
    val deqPtrExt = RegInit(0.U.asTypeOf(new SQPtr))
    val uncommitedPtrExt = RegInit(VecInit((0 until CommitWidth).map(_.U.asTypeOf(new SQPtr))))

    // store queue state
    val sqWORK :: sqFLUSH :: Nil = Enum(2)
    val sq_state = RegInit(sqWORK)
    switch (sq_state) {
        is (sqWORK) {
            when (io.redirect.valid) {
                sq_state := sqFLUSH
            }
        }
        is (sqFLUSH) {
            when (deqPtrExt >= allocPtrExt(0)) {
                sq_state := sqWORK
            }
        }
    }

    // alloc (enq)
    val (alloc_req, alloc_rsp) = (io.alloc_req, io.alloc_rsp)
    val needAlloc = Wire(Vec(DispatchWidth, Bool()))
    val canAlloc = Wire(Vec(DispatchWidth, Bool()))

    val all_canAlloc = needAlloc.zip(canAlloc).map {        // TODO: any improvement?
        case (need, can) => !need || can
    }.reduce(_ && _) && (sq_state === sqWORK)

    val reorder_req = reorder(alloc_req.map{
        case x => 
            val meta = Wire(Valid(new InstExInfo))
            meta.valid := x.valid
            meta.bits := x.bits
            meta
    })

    for (i <- 0 until DispatchWidth) {
        alloc_req(i).ready := false.B
        alloc_rsp(i) := 0.U.asTypeOf(new SQPtr)
    }

    for (i <- 0 until DispatchWidth) {
        val index = PopCount(needAlloc.take(i))
        val ptr = allocPtrExt(index)

        needAlloc(i) := reorder_req(i).valid
        canAlloc(i) := needAlloc(i) && ptr >= deqPtrExt
        when (all_canAlloc && canAlloc(i)) {
            entries(ptr.value) := reorder_req(i).bits
            valids(ptr.value) := true.B
            stu_finished(ptr.value) := false.B
            rob_commited(ptr.value) := false.B
        }

        val origin_hit_vec = alloc_req.map{
            case x =>
                x.valid && x.bits.robPtr === reorder_req(i).bits.robPtr && reorder_req(i).valid
        }
        assert(PopCount(origin_hit_vec) <= 1.U, "StoreQueue: multiple allocs with same robPtr")
        val origin_hit = origin_hit_vec.reduce(_ || _)
        val origin_idx = PriorityEncoder(origin_hit_vec)

        when (origin_hit) {
            alloc_req(origin_idx).ready := canAlloc(i) && all_canAlloc
            alloc_rsp(origin_idx) := ptr
        }
    }
    val allocNum = PopCount(canAlloc)
    allocPtrExt.foreach{case x => when (all_canAlloc) {x := x + allocNum}}

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
        sq_fwd(i).bits.committed := rob_commited(i)
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
    axi.aw.bits := 0.U.asTypeOf(axi.aw.bits)
    axi.aw.bits.addr := entries(deqPtrExt.value).addr

    axi.w.valid := state === sREQ && valids(deqPtrExt.value) && stu_finished(deqPtrExt.value) && rob_commited(deqPtrExt.value)
    axi.w.bits := 0.U.asTypeOf(axi.w.bits)
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