package erythrina.memblock.lsq

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo

class StoreQueue extends ErythModule {
    val io = IO(new Bundle {
        val alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        val alloc_rsp = Vec(DispatchWidth, Output(new SQPtr))

        val alloc_upt = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))    // update ROBPtr

        // rob commit


        // from STU res

        // to mem/D$

        // redirect?
    })
}