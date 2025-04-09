package erythrina.backend.dispatch

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo

class Dispatch extends ErythModule {
    val io = IO(new Bundle {
        val dispatch_req = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))

        val to_int_que = Vec(DispatchWidth, DecoupledIO(new InstExInfo))
        val to_ls_que = Vec(DispatchWidth, DecoupledIO(new InstExInfo))

        // to ROB
        
        // to RegFile

        // to BusyTable
    })
}