package erythrina

import chisel3._
import chisel3.util._

abstract class ErythBundle extends Bundle with HasErythCoreParams

abstract class ErythModule extends Module with HasErythCoreParams