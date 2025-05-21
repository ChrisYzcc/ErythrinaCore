package erythrina.memblock.dcache

import chisel3._
import chisel3.util._
import erythrina.ErythBundle
import DCacheParams._

object DCacheCMD {
    val READ = 0.U(CmdBits.W)
    val WRITE = 1.U(CmdBits.W)
}

class DCacheReq extends ErythBundle {
    val addr = UInt(XLEN.W)
    val data = UInt(XLEN.W)
    val mask = UInt((XLEN / 8).W)
    val cmd = UInt(CmdBits.W)
}

class DCacheResp extends ErythBundle {
    val data = UInt(XLEN.W)
    val cmd = UInt(CmdBits.W)
}