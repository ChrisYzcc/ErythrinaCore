/*
    Ref: NutShell
*/

package bus.axi4

import chisel3._
import chisel3.util._
import erythrina.HasErythCoreParams

object AXI4Params extends HasErythCoreParams {
    val lenBits   = 8
    val sizeBits  = 3
    val burstBits = 2
    val cacheBits = 4
    val protBits  = 3
    val qosBits   = 4
    val respBits  = 2

    // These are not fixed:
    val idBits    = 4
    val addrBits  = PAddrBits
    val dataBits  = DataBits
    val userBits  = 1

    def CACHE_RALLOCATE  = 8.U(cacheBits.W)
    def CACHE_WALLOCATE  = 4.U(cacheBits.W)
    def CACHE_MODIFIABLE = 2.U(cacheBits.W)
    def CACHE_BUFFERABLE = 1.U(cacheBits.W)

    def PROT_PRIVILEGED = 1.U(protBits.W)
    def PROT_INSECURE    = 2.U(protBits.W)
    def PROT_INSTRUCTION = 4.U(protBits.W)

    def BURST_FIXED = 0.U(burstBits.W)
    def BURST_INCR  = 1.U(burstBits.W)
    def BURST_WRAP  = 2.U(burstBits.W)

    def RESP_OKAY   = 0.U(respBits.W)
    def RESP_EXOKAY = 1.U(respBits.W)
    def RESP_SLVERR = 2.U(respBits.W)
    def RESP_DECERR = 3.U(respBits.W)
}

trait AXI4HasUser {
  val user  = Output(UInt(AXI4Params.userBits.W))
}

trait AXI4HasData {
  def dataBits = AXI4Params.dataBits
  val data  = Output(UInt(dataBits.W))
}

trait AXI4HasId {
  def idBits = AXI4Params.idBits
  val id    = Output(UInt(idBits.W))
}

trait AXI4HasLast {
  val last = Output(Bool())
}

// AXI4-lite

class AXI4LiteBundleA extends Bundle {
  val addr  = Output(UInt(AXI4Params.addrBits.W))
  val prot  = Output(UInt(AXI4Params.protBits.W))
}

class AXI4LiteBundleW(override val dataBits: Int = AXI4Params.dataBits) extends Bundle with AXI4HasData {
  val strb = Output(UInt((dataBits/8).W))
}

class AXI4LiteBundleB extends Bundle {
  val resp = Output(UInt(AXI4Params.respBits.W))
}

class AXI4LiteBundleR(override val dataBits: Int = AXI4Params.dataBits) extends AXI4LiteBundleB with AXI4HasData


class AXI4Lite extends Bundle {
  val aw = Decoupled(new AXI4LiteBundleA)
  val w  = Decoupled(new AXI4LiteBundleW)
  val b  = Flipped(Decoupled(new AXI4LiteBundleB))
  val ar = Decoupled(new AXI4LiteBundleA)
  val r  = Flipped(Decoupled(new AXI4LiteBundleR))
}

// AXI4-full

class AXI4BundleA(override val idBits: Int) extends AXI4LiteBundleA with AXI4HasId with AXI4HasUser {
  val len   = Output(UInt(AXI4Params.lenBits.W))  // number of beats - 1
  val size  = Output(UInt(AXI4Params.sizeBits.W)) // bytes in beat = 2^size
  val burst = Output(UInt(AXI4Params.burstBits.W))
  val lock  = Output(Bool())
  val cache = Output(UInt(AXI4Params.cacheBits.W))
  val qos   = Output(UInt(AXI4Params.qosBits.W))  // 0=no QoS, bigger = higher priority
  // val region = UInt(width = 4) // optional

  override def toPrintable: Printable = p"addr = 0x${Hexadecimal(addr)}, id = ${id}, len = ${len}, size = ${size}"
}

// id ... removed in AXI4
class AXI4BundleW(override val dataBits: Int) extends AXI4LiteBundleW(dataBits) with AXI4HasLast {
  override def toPrintable: Printable = p"data = ${Hexadecimal(data)}, wmask = 0x${strb}, last = ${last}"
}
class AXI4BundleB(override val idBits: Int) extends AXI4LiteBundleB with AXI4HasId with AXI4HasUser {
  override def toPrintable: Printable = p"resp = ${resp}, id = ${id}"
}
class AXI4BundleR(override val dataBits: Int, override val idBits: Int) extends AXI4LiteBundleR(dataBits) with AXI4HasLast with AXI4HasId with AXI4HasUser {
  override def toPrintable: Printable = p"resp = ${resp}, id = ${id}, data = ${Hexadecimal(data)}, last = ${last}"
}


class AXI4(val dataBits: Int = AXI4Params.dataBits, val idBits: Int = AXI4Params.idBits) extends AXI4Lite {
  override val aw = Decoupled(new AXI4BundleA(idBits))
  override val w  = Decoupled(new AXI4BundleW(dataBits))
  override val b  = Flipped(Decoupled(new AXI4BundleB(idBits)))
  override val ar = Decoupled(new AXI4BundleA(idBits))
  override val r  = Flipped(Decoupled(new AXI4BundleR(dataBits, idBits)))
}