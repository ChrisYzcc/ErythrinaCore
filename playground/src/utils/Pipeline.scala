package utils

import chisel3._
import chisel3.util._

object StageConnect{
  def apply[T <: Data](left: DecoupledIO[T], right: DecoupledIO[T]) = {
    right.valid := left.valid
    left.ready := right.ready
    right.bits := RegEnable(Mux(right.fire, left.bits, 0.U.asTypeOf(left.bits)), right.fire | (right.ready & ~left.valid))
  }
}