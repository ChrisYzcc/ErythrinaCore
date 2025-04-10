package erythrina.backend.rob

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo

class ROB extends ErythModule {
    val io = IO(new Bundle {
        // from Dispatch
        val alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        val alloc_rsp = Vec(DispatchWidth, Output(new ROBPtr))

        val alloc_upt = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))    // update LQPtr, SQPtr

        // fu commit
        val fu_commit = Vec(CommitWidth, Flipped(ValidIO(new InstExInfo)))

        // rob commit
        val rob_commit = Vec(CommitWidth, ValidIO(new InstExInfo))

        // to RegFile
        val reg_write = Vec(CommitWidth, ValidIO(new Bundle{
            val addr = UInt(PhyRegAddrBits.W)
            val data = UInt(XLEN.W)
        }))
        
        // to BusyTable
        val bt_free_req = Vec(CommitWidth, ValidIO(UInt(PhyRegAddrBits.W)))

        // to FreeList
        val fl_free_req = Vec(CommitWidth, ValidIO(UInt(PhyRegAddrBits.W)))

        // to RAT
        val upt_arch_rat = Vec(CommitWidth, ValidIO(new Bundle {
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        }))

        // redirect
    })

    // entries
    val entries = RegInit(VecInit(Seq.fill(ROBSize)(0.U.asTypeOf(new InstExInfo))))

    // ptr
    val allocPtrExt = RegInit(VecInit((0 until DispatchWidth).map(_.U.asTypeOf(new ROBPtr))))
    val commitPtrExt = RegInit(VecInit((0 until CommitWidth).map(_.U.asTypeOf(new ROBPtr))))

    // fu commit
    val fu_commit = io.fu_commit
    for (i <- 0 until CommitWidth) {
        val v = fu_commit(i).valid
        val info = fu_commit(i).bits
        val ptr = info.robPtr

        when (v) {
            entries(ptr.value) := info
        }
    }

    // dispatch update
    val dispatch_update = io.alloc_upt
    for (i <- 0 until DispatchWidth) {
        val v = dispatch_update(i).valid
        val info = dispatch_update(i).bits
        val ptr = info.robPtr

        when (v) {
            entries(ptr.value) := info
        }
    }

    // Alloc (enq)
    val (alloc_req, alloc_rsp) = (io.alloc_req, io.alloc_rsp)
    val alloc_needEnq = Wire(Vec(DispatchWidth, Bool()))
    val alloc_canEnq = Wire(Vec(DispatchWidth, Bool()))

    for (i <- 0 until DispatchWidth) {
        val v = alloc_req(i).valid
        val info = alloc_req(i).bits

        val index = PopCount(alloc_needEnq.take(i))
        val ptr = allocPtrExt(index)

        alloc_needEnq(i) := v
        alloc_canEnq(i) := v && ptr >= commitPtrExt(0)
        when (alloc_canEnq(i)) {
            entries(ptr.value) := info
        }

        alloc_req(i).ready  := ptr >= commitPtrExt(0)
        alloc_rsp(i) := ptr
    }
    val allocNum = PopCount(alloc_needEnq)
    allocPtrExt.foreach{case x => when (alloc_canEnq.asUInt.orR) {x := x + allocNum}}

    // Commit (deq)
    val reg_write = io.reg_write
    val fl_free_req = io.fl_free_req
    val bt_free_req = io.bt_free_req
    val rat_req = io.upt_arch_rat

    val commit_needDeq = Wire(Vec(CommitWidth, Bool()))
    val commit_canDeq = Wire(Vec(CommitWidth, Bool()))
    for (i <- 0 until CommitWidth) {
        val ptr = commitPtrExt(i)

        val prev_has_unfinished = commit_needDeq.take(i).map(!_).reduce(_ || _)

        commit_needDeq(i) := entries(ptr.value).state.finished
        commit_canDeq(i)  := commit_needDeq(i) && ptr < allocPtrExt(0) && !prev_has_unfinished

        // RegWrite
        reg_write(i).valid := commit_canDeq(i) && entries(ptr.value).rf_wen
        reg_write(i).bits.addr := entries(ptr.value).p_rd
        reg_write(i).bits.data := entries(ptr.value).res

        // BusyTable
        bt_free_req(i).valid := commit_canDeq(i) && entries(ptr.value).rf_wen
        bt_free_req(i).bits := entries(ptr.value).p_rd

        // FreeList
        fl_free_req(i).valid := commit_canDeq(i) && entries(ptr.value).rd_need_rename
        fl_free_req(i).bits := entries(ptr.value).p_rd

        // RAT
        rat_req(i).valid := commit_canDeq(i) && entries(ptr.value).rd_need_rename
        rat_req(i).bits.a_reg := entries(ptr.value).a_rd
        rat_req(i).bits.p_reg := entries(ptr.value).p_rd
    }
    val cmtNum = PopCount(commit_canDeq)
    commitPtrExt.foreach{case x => when (commit_canDeq.asUInt.orR) {x := x + cmtNum}}

    // Rob Commit Info
    for (i <- 0 until CommitWidth) {
        io.rob_commit(i).valid := commit_canDeq(i)
        io.rob_commit(i).bits := entries(commitPtrExt(i).value)
    }
}