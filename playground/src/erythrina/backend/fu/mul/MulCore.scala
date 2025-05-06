package erythrina.backend.fu.mul

/*
    * Multiplier
    * Author: yzcc
    * Credited to XiangShan project (https://github.com/OpenXiangShan/XiangShan)
    * Reference: 
        1. https://raw.githubusercontent.com/OpenXiangShan/XiangShan-doc/main/slides/20210626-CCC-Implementation%20of%20a%20Highly%20Configurable%20Wallace%20Tree%20Multiplier%20with%20Chisel.pdf
        2. https://zhuanlan.zhihu.com/p/148289578
*/

import chisel3._
import chisel3.util._
import utils.SignExt
import utils.LookupTreeDefault

class MulCore(len: Int) extends Module{
    val io = IO(new Bundle() {
        val a = Input(UInt(len.W))
        val b = Input(UInt(len.W))
        val regEnables = Input(Vec(2, Bool()))
        val res = Output(UInt((2*len).W))
    })

    val (a, b) = (io.a, io.b)
    
    val b_sext, bx2, neg_b, neg_bx2 = Wire(UInt((len + 1).W))        // double sign bit (protect overflow?)
    b_sext  := SignExt(b, len + 1)
    bx2     := b_sext << 1
    neg_b   := (~b_sext).asUInt
    neg_bx2 := neg_b << 1

    val columns: Array[Seq[Bool]]    = Array.fill(2*len)(Seq())

    var last_x  = WireInit(0.U(3.W))
    for (i <- Range(0, len, 2)){
        val x = if (i == 0) Cat(a(1, 0), 0.U(1.W)) else if (i + 1 == len) SignExt(a(i, i - 1), 3) else a(i + 1, i - 1)

        val pp_tmp = MuxLookup(x, 0.U)(Seq(            // partial product
            1.U -> b_sext,
            2.U -> b_sext,
            3.U -> bx2,
            4.U -> neg_bx2,
            5.U -> neg_b,
            6.U -> neg_b,
        ))

        val s = pp_tmp(len)         // sign
        val t = MuxLookup(last_x, 0.U(2.W))(Seq(            // carry in
            4.U -> 2.U(2.W),
            5.U -> 1.U(2.W),
            6.U -> 1.U(2.W),
        ))
        last_x  = x

        val (pp, weight) = i match {
            case 0 =>
                (Cat(~s, s, s, pp_tmp), 0)
            case n if (n == len - 1) || (n == len - 2) =>           // len = odd or even
                (Cat(~s, pp_tmp, t), i - 2)
            case _ =>
                (Cat(1.U(1.W), ~s, pp_tmp, t), i - 2)
        }

        for (j <- columns.indices){
            if (j >= weight && j < weight + pp.getWidth){
                columns(j) = columns(j) :+ pp(j - weight)
            }
        }
    }

    def addOneColumn(col: Seq[Bool], cin: Seq[Bool]): (Seq[Bool], Seq[Bool]) = {
        var sum     = Seq[Bool]()
        var cout    = Seq[Bool]()
        col.size match{
            case 1 =>
                sum     = col ++ cin
            case 2 =>
                val c22 = Module(new C22)
                c22.io.in       := Cat(col(1), col(0))
                sum             = c22.io.out(0).asBool +: cin
                cout            = Seq(c22.io.out(1).asBool)
            case 3 =>
                val c32 = Module(new C32)
                c32.io.in       := Cat(col(2), col(1), col(0))
                sum             = c32.io.out(0).asBool +: cin
                cout            = Seq(c32.io.out(1).asBool)
            case n =>
                val cin_1 = if (cin.nonEmpty) Seq(cin.head)      else Nil
                val cin_2 = if (cin.nonEmpty) cin.drop(1)   else Nil
                val (s_1, c_1)  = addOneColumn(col take 3, cin_1)
                val (s_2, c_2)  = addOneColumn(col drop 3, cin_2)

                sum     = s_1 ++ s_2
                cout    = c_1 ++ c_2
        }
        (sum, cout)
    }

    def max(in: Iterable[Int]): Int = in.reduce((a, b) => if(a>b) a else b)
    def addAll(cols: Array[Seq[Bool]], depth: Int): (UInt, UInt) = {        // return sum and carry, recursively
        if (max(cols.map(_.size)) <= 2){            // all collumns are less than 2 rows
            val sum = Cat(cols.map(_(0)).toIndexedSeq.reverse)

            var k = 0
            while(cols(k).size == 1) k = k+1
            val carry = Cat(cols.drop(k).map(_(1)).toIndexedSeq.reverse)

            (sum, Cat(carry, 0.U(k.W)))
        }
        else{
            val column_next = Array.fill(2*len)(Seq[Bool]())
            
            var cout = Seq[Bool]()
            for (i <- cols.indices){
                val (sum, c) = addOneColumn(cols(i), cout)
                column_next(i) = sum
                cout = c
            }

            val needReg = depth == 5
            // regEnables_1 : enable for Wallace tree encoding (depth = 4)
            val toNextLayer = if(needReg)
                column_next.map(_.map(x => RegEnable(x, io.regEnables(1))))
            else
                column_next

            addAll(toNextLayer, depth+1)
        }
    }
    
    // regEnables_0 : enable for Booth-4 encoding
    val columns_reg = columns.map(col => col.map(b => RegEnable(b, io.regEnables(0))))
    val (sum, carry) = addAll(cols = columns_reg, depth = 0)

    io.res := sum + carry
}