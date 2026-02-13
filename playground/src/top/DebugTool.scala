package top

import chisel3._
import chisel3.util._

object EAssert {
    def apply(cond: Bool, msg: String = ""): Unit = {
        if (!Config.isTiming) {
            assert(cond, msg)
        }
    }
}