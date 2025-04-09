package erythrina.backend.fu

import chisel3._
import chisel3.util._

object ALUop {
    def dir   = "b01001".U     // direct, for lui. (result in a reg write :D)
    def nop   = "b01000".U
    def add   = "b00000".U
    def sub   = "b00001".U
    def slt   = "b00010".U
    def sltu  = "b00011".U
    def and   = "b00100".U
    def or    = "b00101".U
    def xor   = "b00110".U
    def srl   = "b01010".U
    def sra   = "b01011".U
    def sll   = "b01100".U
    def jal   = "b01101".U
    def jalr  = "b01110".U

    def usesub(aluop: UInt) = (aluop(3,2) === 0.U) & (aluop(1,0) =/= 0.U)
}

object BRUop {
    def beq     = "b0010".U
    def bne     = "b0011".U
    def blt     = "b0100".U
    def bge     = "b0101".U
    def bltu    = "b0110".U
    def bgeu    = "b0111".U
}

object STUop {
    def sb      = "b0101".U
    def sh      = "b0110".U
    def sw      = "b0111".U
}

object LDUop {
    def lb      = "b0000".U
    def lh      = "b0001".U
    def lw      = "b0010".U
    def lbu     = "b0011".U
    def lhu     = "b0100".U
}

object CSRop {
    def nop     = "b1000".U
    def jmp     = "b0000".U
    def wrt     = "b0001".U         // write
    def set     = "b0010".U         // set
    def clr     = "b0011".U
    def wrti    = "b0101".U
    def seti    = "b0110".U
    def clri    = "b0111".U

    def usei(csrop: UInt) = csrop(2)
    def iswrt(csrop: UInt) = ~csrop(1) & csrop(0)
    def isset(csrop: UInt) = csrop(1) & ~csrop(0)
    def isclr(csrop: UInt) = csrop(1) & csrop(1)
}