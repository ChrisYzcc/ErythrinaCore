package erythrina.backend.issue

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo

class BaseIssueQueue(exuNum : Int, name : String, size : Int) extends ErythModule {
    val io = IO(new Bundle {
        val enq_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        
        val exu_status = Vec(exuNum, Input(Bool()))
        val deq_req = DecoupledIO(new InstExInfo)
    })
}