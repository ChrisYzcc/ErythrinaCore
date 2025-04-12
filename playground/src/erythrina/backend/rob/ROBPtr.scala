package erythrina.backend.rob

import chisel3._
import chisel3.util._
import utils.CircularQueuePtr
import erythrina.HasErythCoreParams

object ROBParams extends HasErythCoreParams{
}

class ROBPtr extends CircularQueuePtr[ROBPtr](ROBParams.ROBSize){

}

object ROBPtr{
    def apply(): ROBPtr = {
        val ptr = Wire(new ROBPtr)
        ptr := 0.U.asTypeOf(ptr)
        ptr
    }
}