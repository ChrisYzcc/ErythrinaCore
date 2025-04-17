package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._
import utils.CircularQueuePtr

class IFU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())

        val fetch_req = Flipped(DecoupledIO(new InstFetchBlock)) // from FTQ
        val fetch_rsp = ValidIO(new InstFetchBlock) // to FTQ

        // AXI4-lite
        val axi = new Bundle {
            val ar = DecoupledIO(new AXI4LiteBundleA)
            val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = 64)))
        }
    })

    def FIFOSize = 8

    class Ptr extends CircularQueuePtr[Ptr](FIFOSize) {
    }

    object Ptr {
        def apply(f: Bool, v: UInt): Ptr = {
            val ptr = Wire(new Ptr)
            ptr.flag := f
            ptr.value := v
            ptr
        }
    }

    // ptr
    val enqPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val deqPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val ar_req_PtrExt = RegInit(0.U.asTypeOf(new Ptr))

    val entries = RegInit(VecInit(Seq.fill(FIFOSize)(0.U.asTypeOf(new InstFetchBlock))))
    val valids = RegInit(VecInit(Seq.fill(FIFOSize)(false.B)))
    val ar_has_req_vec = RegInit(VecInit(Seq.fill(FIFOSize)(false.B)))

    val (fetch_req, fetch_rsp) = (io.fetch_req, io.fetch_rsp)
    val axi = io.axi

    when (io.flush) {
        enqPtrExt := 0.U.asTypeOf(new Ptr)
        deqPtrExt := 0.U.asTypeOf(new Ptr)
        ar_req_PtrExt := 0.U.asTypeOf(new Ptr)
        valids := VecInit(Seq.fill(FIFOSize)(false.B))
        entries := VecInit(Seq.fill(FIFOSize)(0.U.asTypeOf(new InstFetchBlock)))
        ar_has_req_vec := VecInit(Seq.fill(FIFOSize)(false.B))
    }

    // enq
    fetch_req.ready := enqPtrExt >= deqPtrExt && !io.flush
    when (fetch_req.fire) {
        entries(enqPtrExt.value) := fetch_req.bits
        valids(enqPtrExt.value) := true.B
        enqPtrExt := enqPtrExt + 1.U
    }

    axi.ar.valid := valids(ar_req_PtrExt.value) && !io.flush && !ar_has_req_vec(ar_req_PtrExt.value)
    axi.ar.bits := 0.U.asTypeOf(new AXI4LiteBundleA)
    axi.ar.bits.addr := entries(ar_req_PtrExt.value).instVec(0).pc

    when (axi.ar.fire) {
        when (ar_req_PtrExt < enqPtrExt) {
            ar_req_PtrExt := ar_req_PtrExt + 1.U
        }
        ar_has_req_vec(ar_req_PtrExt.value) := true.B
    }

    // deq
    axi.r.ready := true.B

    fetch_rsp.valid := valids(deqPtrExt.value) && !io.flush && ar_has_req_vec(deqPtrExt.value) && axi.r.fire
    val rsp_blk = WireInit(entries(deqPtrExt.value))
    rsp_blk.instVec(0).instr := axi.r.bits.data(XLEN - 1, 0)
    rsp_blk.instVec(1).instr := axi.r.bits.data(2 * XLEN - 1, XLEN)
    fetch_rsp.bits := rsp_blk

    when (fetch_rsp.valid) {
        when (deqPtrExt < enqPtrExt) {
            deqPtrExt := deqPtrExt + 1.U
        }
        valids(deqPtrExt.value) := false.B
        ar_has_req_vec(deqPtrExt.value) := false.B
    }
}