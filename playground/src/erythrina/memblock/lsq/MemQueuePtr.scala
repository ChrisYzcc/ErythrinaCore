package erythrina.memblock.lsq

import chisel3._
import chisel3.util._
import utils.CircularQueuePtr
import erythrina.HasErythCoreParams

object LSQParmas extends HasErythCoreParams {
    val LDQueSize = 8
    val STQueSize = 8
}

class LQPtr extends CircularQueuePtr[LQPtr](LSQParmas.LDQueSize) {
}

class SQPtr extends CircularQueuePtr[SQPtr](LSQParmas.STQueSize) {
}