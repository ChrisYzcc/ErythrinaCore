package utils

import chisel3._
import chisel3.util._

class halter(name: String) extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val trigger = Input(Bool())
    })
    setInline(s"halter$name.v",
    s"""module halt$name(
    |   input wire trigger;
    |);
    |   import "DPI-C" function void halt_$name();
    |   
    |   always @(*) begin
    |       if (trigger) begin
    |           halt_$name();
    |       end
    |   end
    |endmodule
    """.stripMargin)
}