package erythrina.backend.issue

import chisel3._
import chisel3.util._
import erythrina.ErythBundle

class BypassInfo extends ErythBundle {
    val bypass_prd = UInt(PhyRegAddrBits.W) // physical register address
    val bypass_data = UInt(XLEN.W) // data to be bypassed
}