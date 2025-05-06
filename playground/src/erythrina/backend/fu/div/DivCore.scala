package erythrina.backend.fu.div

/*
    * A simple Radix2 Divider
    * Author: yzcc
    * Reference: https://soc.ustc.edu.cn/CECS/lab8/board.assets/%E4%B9%98%E6%B3%95%E5%99%A8%E4%B8%8E%E9%99%A4%E6%B3%95%E5%99%A8%E7%9A%84%E8%AE%BE%E8%AE%A1.pdf
*/

import chisel3._
import chisel3.util._

import utils.SignExt

class DivCore(len : Int) extends Module{
    val io = IO(new Bundle {
        val in_v = Input(Bool())
        val flush = Input(Bool())
        val a = Input(UInt(len.W))
        val b = Input(UInt(len.W))

        val quot = Output(UInt(len.W))
        val rem = Output(UInt(len.W))
        val out_v = Output(Bool())
    })

    // FSM
    val compute_cnt = RegInit(0.U(6.W))
    val sIDLE :: sCOMPUTE :: sDONE :: Nil = Enum(3)
    val state = RegInit(sIDLE)
    switch (state){
        is (sIDLE){
            when (io.in_v & !io.flush){
                state := sCOMPUTE
            }
        }
        is (sCOMPUTE){
            when (compute_cnt === 0.U & !io.flush){
                state := sDONE
            }.elsewhen(io.flush){
                state := sIDLE
            }
        }
        is (sDONE){
            state := sIDLE
        }
    }

    when (state === sIDLE && io.in_v && !io.flush){
        compute_cnt := (len).U
    }.elsewhen (state === sCOMPUTE){
        compute_cnt := compute_cnt - 1.U
    }

    // Div, caculate a/b
    val a_sign = io.a(len - 1)
    val b_sign = io.b(len - 1)
    val a = Mux(a_sign, (~io.a + 1.U).asUInt, io.a)
    val b = Mux(b_sign, (~io.b + 1.U).asUInt, io.b)
    val neg_b = SignExt((~b + 1.U).asUInt, len + 1)
    dontTouch(a)
    dontTouch(b)
    dontTouch(neg_b)

    val quot_r  = Reg(UInt(len.W))
    val rem_r   = Reg(UInt((len + 1).W))
    val sub_res = rem_r + neg_b
    dontTouch(sub_res)

    // Quotient
    when (state === sIDLE && io.in_v && !io.flush){
        quot_r := a
    }
    when (state === sCOMPUTE){
        quot_r := Mux(sub_res(len), quot_r << 1, Cat(quot_r(len - 2, 0), 1.U(1.W)))
    }

    // Remainder
    when (state === sIDLE && io.in_v && !io.flush){
        rem_r := 0.U
    }
    when (state === sCOMPUTE){
        rem_r := Mux(~sub_res(len), Cat(sub_res(len - 1, 0), quot_r(len - 1)), Cat(rem_r(len - 1, 0), quot_r(len - 1)))
    }

    // Res
    val rem = rem_r(len, 1)
    io.quot := Mux(a_sign ^ b_sign, (~quot_r + 1.U).asUInt, quot_r)
    io.rem  := Mux(a_sign, (~rem + 1.U).asUInt, rem)
    io.out_v := state === sDONE
}