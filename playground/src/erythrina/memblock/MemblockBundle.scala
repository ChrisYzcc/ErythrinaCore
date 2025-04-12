package erythrina.memblock

import chisel3._
import chisel3.util._
import erythrina.ErythBundle
import erythrina.backend.rob.ROBPtr

class StoreFwdBundle extends ErythBundle {
    val addr = UInt(XLEN.W)
    val data = UInt(XLEN.W)
    val mask = UInt(MASKLEN.W)
    val robPtr = new ROBPtr
}