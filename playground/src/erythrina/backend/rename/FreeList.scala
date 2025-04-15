package erythrina.backend.rename

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import utils.CircularQueuePtr
import erythrina.backend.Redirect

class FreeList extends ErythModule {
    val io = IO(new Bundle {
        val redirect = Flipped(ValidIO(new Redirect))
        val alloc_req = Vec(RenameWidth, Flipped(DecoupledIO()))
        val alloc_rsp = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))
        val free_req = Vec(CommitWidth, Flipped(ValidIO(UInt(PhyRegAddrBits.W))))
    })

    val FLSize = PhyRegNum

    val free_list = Reg(Vec(FLSize, UInt(PhyRegAddrBits.W)))
    val arch_free_list = Reg(Vec(FLSize, UInt(PhyRegAddrBits.W)))

    when (reset.asBool) {
        for (i <- 0 until FLSize) {
            if (i < ArchRegNum) {
                free_list(i) := (i + ArchRegNum).U
                arch_free_list(i) := (i + ArchRegNum).U
            }
            else {
                free_list(i) := 0.U
                arch_free_list(i) := 0.U
            }
        }
    }

    class Ptr extends CircularQueuePtr[Ptr](FLSize) {
    }

    object Ptr {
        def apply(f: Bool, v: UInt): Ptr = {
            val ptr = Wire(new Ptr)
            ptr.flag := f
            ptr.value := v
            ptr
        }
    }

    // ptr
    val deqPtrExt = Reg(Vec(RenameWidth, new Ptr))  // alloc
    val enqPtrExt = Reg(Vec(CommitWidth, new Ptr))  // free

    val arch_deqPtrExt = Reg(Vec(RenameWidth, new Ptr))  // alloc
    val arch_enqPtrExt = Reg(Vec(CommitWidth, new Ptr))  // free
    

    for (i <- 0 until RenameWidth) {
        when (reset.asBool) {
            deqPtrExt(i) := i.U.asTypeOf(new Ptr)
            arch_deqPtrExt(i) := i.U.asTypeOf(new Ptr)
        }
    }

    for (i <- 0 until CommitWidth) {
        when (reset.asBool) {
            enqPtrExt(i) := (i + ArchRegNum).U.asTypeOf(new Ptr)
            arch_enqPtrExt(i) := (i + ArchRegNum).U.asTypeOf(new Ptr)
        }
    }

    

    /* --------------- Real FreeList ---------------- */
    // free (enq)
    val free_req = io.free_req
    val needAlloc = Wire(Vec(CommitWidth, Bool()))
    val canAlloc = Wire(Vec(CommitWidth, Bool()))

    for (i <- 0 until CommitWidth) {
        val v = free_req(i).valid
        val id = free_req(i).bits
        
        val index = PopCount(needAlloc.take(i))
        val allocPtr = enqPtrExt(index)

        needAlloc(i) := v
        canAlloc(i) := needAlloc(i) && allocPtr >= deqPtrExt(0)
        when (canAlloc(i)) {
            free_list(allocPtr.value) := id
        }
    }
    val allocNum = PopCount(canAlloc)
    enqPtrExt.foreach{case x => when (canAlloc.asUInt.orR) {x := x + allocNum}}

    // alloc (deq)
    val alloc_req = io.alloc_req
    val alloc_rsp = io.alloc_rsp
    
    val needDeq = Wire(Vec(RenameWidth, Bool()))
    val canDeq = Wire(Vec(RenameWidth, Bool()))

    for (i <- 0 until RenameWidth) {
        val v = alloc_req(i).valid

        val index = PopCount(needDeq.take(i))
        val deqPtr = deqPtrExt(index)

        needDeq(i) := v
        canDeq(i) := needDeq(i) && deqPtr < enqPtrExt(0)
        
        alloc_rsp(i) := free_list(deqPtr.value)
    }
    val deqNum = PopCount(canDeq)
    val all_canDeq = canDeq.zip(alloc_req).map{
        case (a, b) => a || !b.valid
    }.reduce(_ && _) && !reset.asBool && !io.redirect.valid
    deqPtrExt.foreach{case x => when (all_canDeq) {x := x + deqNum}}
    alloc_req.foreach{case x => x.ready := all_canDeq}

    /* ------------------ Arch FreeList ----------------- */
    // enq
    val arch_needAlloc = Wire(Vec(CommitWidth, Bool()))
    val arch_canAlloc = Wire(Vec(CommitWidth, Bool()))
    for (i <- 0 until CommitWidth) {
        val v = free_req(i).valid
        val id = free_req(i).bits
        val index = PopCount(needAlloc.take(i))

        val enq_ptr = arch_enqPtrExt(index)
        arch_needAlloc(i) := v
        arch_canAlloc(i) := arch_needAlloc(i) && enq_ptr >= arch_deqPtrExt(0)
        when (arch_canAlloc(i)) {
            arch_free_list(enq_ptr.value) := id
        }
    }
    val arch_allocNum = PopCount(arch_canAlloc)
    arch_enqPtrExt.foreach{case x => when (arch_canAlloc.asUInt.orR) {x := x + arch_allocNum}}

    // deq
    val arch_needDeq = Wire(Vec(CommitWidth, Bool()))
    val arch_canDeq = Wire(Vec(CommitWidth, Bool()))
    for (i <- 0 until CommitWidth) {
        val v = free_req(i).valid
        val index = PopCount(arch_needDeq.take(i))

        val deq_ptr = arch_deqPtrExt(index)
        arch_needDeq(i) := v
        arch_canDeq(i) := arch_needDeq(i) && deq_ptr < arch_enqPtrExt(0)
    }
    val arch_deqNum = PopCount(arch_canDeq)
    arch_deqPtrExt.foreach{case x => when (arch_canDeq.asUInt.orR) {x := x + arch_deqNum}}

    // Redirect
    val redirect = io.redirect
    when (redirect.valid) {
        for (i <- 0 until RenameWidth) {
            deqPtrExt(i) := arch_deqPtrExt(i)
        }
        for (i <- 0 until CommitWidth) {
            enqPtrExt(i) := arch_enqPtrExt(i)
        }
        for (i <- 0 until FLSize) {
            free_list(i) := arch_free_list(i)
        }
    }
}