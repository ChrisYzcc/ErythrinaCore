package erythrina.backend.rename

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import utils.CircularQueuePtr

class FreeList extends ErythModule {
    val io = IO(new Bundle {
        val alloc_req = Vec(RenameWidth, DecoupledIO())
        val alloc_rsp = Vec(RenameWidth, UInt(PhyRegAddrBits.W))
        val free_req = Vec(CommitWidth, Flipped(ValidIO(UInt(PhyRegAddrBits.W))))
    })

    val FLSize = PhyRegNum

    val free_list = Reg(Vec(FLSize, UInt(PhyRegAddrBits.W)))

    when (reset.asBool) {
        for (i <- 0 until FLSize) {
            if (i < ArchRegNum) {
                free_list(i) := (i + ArchRegNum).U
            }
            else {
                free_list(i) := 0.U
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
    val deqPtrExt = Reg(Vec(RenameWidth, new Ptr))
    val enqPtrExt = Reg(Vec(CommitWidth, new Ptr))

    for (i <- 0 until RenameWidth) {
        when (reset.asBool) {
            deqPtrExt(i) := i.U.asTypeOf(new Ptr)
        }
    }

    for (i <- 0 until CommitWidth) {
        when (reset.asBool) {
            enqPtrExt(i) := (i + ArchRegNum).U.asTypeOf(new Ptr)
        }
    }

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
        val v = alloc_req(i)

        val index = PopCount(needDeq.take(i))
        val deqPtr = deqPtrExt(index)

        needDeq(i) := v
        canDeq(i) := needDeq(i) && deqPtr < enqPtrExt(0)
        
        alloc_req(i).ready  := canDeq(i)
        alloc_rsp(i) := free_list(deqPtr.value)
    }
    val deqNum = PopCount(canDeq)
    val all_canDeq = canDeq.zip(alloc_req).map{
        case (a, b) => a || !b.valid
    }.reduce(_ && _)
    deqPtrExt.foreach{case x => when (all_canDeq) {x := x + deqNum}}

    // TODO: Redirect?
}