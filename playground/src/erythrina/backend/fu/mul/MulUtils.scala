package erythrina.backend.fu.mul

/*
    * Utility functions for MUL
    * Author: yzcc
    * Credited to XiangShan project (https://github.com/OpenXiangShan/XiangShan)
*/

import chisel3._
import chisel3.util._

// CSA

// compressor: 2 bits -> 2 bits
class C22 extends Module{
    val io = IO(new Bundle{
        val in = Input(UInt(2.W))
        val out = Output(UInt(2.W))
    })

    io.out  := io.in(0) +& io.in(1)
}

// compressor: 3 bits -> 2 bits
class C32 extends Module{
    val io = IO(new Bundle{
        val in = Input(UInt(3.W))
        val out = Output(UInt(2.W))
    })

    io.out  := io.in(0) +& io.in(1) +& io.in(2)
}