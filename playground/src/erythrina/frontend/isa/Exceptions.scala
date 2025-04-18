package erythrina.frontend.isa

import chisel3._
import chisel3.util._
import erythrina.ErythBundle

class Exceptions extends ErythBundle {
    val ebreak = Bool()
    val ecall_m = Bool()

    val instr_addr_fault = Bool()
    val illegal_instr = Bool()

    val load_access_fault = Bool()

    val store_access_fault = Bool()

    def has_exception = {
        ebreak || ecall_m || instr_addr_fault || illegal_instr ||
        load_access_fault || store_access_fault
    }

    def can_commit = {
        ecall_m
    }
}