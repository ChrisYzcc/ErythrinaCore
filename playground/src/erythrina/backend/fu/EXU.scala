package erythrina.backend.fu

import chisel3._
import chisel3.util._
import erythrina.ErythBundle
import erythrina.frontend.FuType

class EXUInfo extends ErythBundle {
    val fu_type_vec = UInt(FuType.num.W)        // bitmap, 1 for can handle
}