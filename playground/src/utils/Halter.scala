package utils

import chisel3._
import chisel3.util._

object HaltOp{  // ref to RISC-V exception code
    val ebreak = 3.U(32.W)
    
    val load_af = 5.U(32.W)
    val store_af = 7.U(32.W)
}

class Halter extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val trigger = Input(Bool())
        val reason = Input(UInt(32.W))
    })
    setInline(s"Halter.v",
    s"""module Halter(
    |   input wire trigger,
    |   input wire [31:0] reason
    |);
    |   import "DPI-C" function void halt(input int reason);
    |   
    |   always @(*) begin
    |       if (trigger) begin
    |           halt(reason);
    |       end
    |   end
    |endmodule
    """.stripMargin)
}