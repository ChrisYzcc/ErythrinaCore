package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import utils.CircularQueuePtr

class FTQ extends ErythModule {
    val io = IO(new Bundle {
        val enq_req  = Flipped(DecoupledIO(new InstFetchBlock))        // from BPU, enq

        val fetch_req   = ValidIO(new InstFetchBlock)               // to IBuffer
        val fetch_rsp  = Flipped(ValidIO(new InstFetchBlock))      // from IBuffer

        val decode_req  = DecoupledIO(new InstFetchBlock)               // to IDU, deq
    
        // flush
        val flush = Input(Bool())

    })

    class Ptr extends CircularQueuePtr[Ptr](FTQSize) {
    }

    object Ptr {
        def apply(f: Bool, v: UInt): Ptr = {
            val ptr = Wire(new Ptr)
            ptr.flag := f
            ptr.value := v
            ptr
        }
    }

    val entries = RegInit(VecInit(Seq.fill(FTQSize)(0.U.asTypeOf(new InstFetchBlock))))
    val fetched = RegInit(VecInit(Seq.fill(FTQSize)(false.B)))
    val valids = RegInit(VecInit(Seq.fill(FTQSize)(false.B)))

    // ptr
    val enqPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val deqPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val fetchPtrExt = RegInit(0.U.asTypeOf(new Ptr))

    // enq
    val enq_req = io.enq_req
    enq_req.ready := enqPtrExt >= deqPtrExt

    val enq_entry = WireInit(enq_req.bits)
    enq_entry.ftqIdx := enqPtrExt.value
    when (enq_req.fire) {
        entries(enqPtrExt.value) := enq_entry
        valids(enqPtrExt.value) := true.B
        fetched(enqPtrExt.value) := false.B
        enqPtrExt := enqPtrExt + 1.U
    }
    
    // deq
    val decode_req = io.decode_req
    decode_req.valid := fetched(deqPtrExt.value) && !io.flush && valids(deqPtrExt.value)
    decode_req.bits := entries(deqPtrExt.value)
    when (decode_req.fire) {
        when (deqPtrExt < enqPtrExt) {
            deqPtrExt := deqPtrExt + 1.U
        }
        valids(deqPtrExt.value) := false.B
        fetched(deqPtrExt.value) := false.B
    }

    val fetch_req = io.fetch_req
    val fetch_resp = io.fetch_rsp

    // fetch req
    fetch_req.valid := valids(fetchPtrExt.value) && !fetched(fetchPtrExt.value) && !io.flush && !reset.asBool
    fetch_req.bits := entries(fetchPtrExt.value)

    when (fetch_req.valid && fetchPtrExt < enqPtrExt && fetch_resp.valid) {
        fetchPtrExt := fetchPtrExt + 1.U
    }

    // fetch resp
    val fetch_entry = fetch_resp.bits
    when (fetch_resp.valid) {
        entries(fetch_entry.ftqIdx) := fetch_entry
        fetched(fetch_entry.ftqIdx) := true.B
    }

    // flush
    when (io.flush) {
        enqPtrExt := 0.U.asTypeOf(new Ptr)
        deqPtrExt := 0.U.asTypeOf(new Ptr)
        fetchPtrExt := 0.U.asTypeOf(new Ptr)
        for (i <- 0 until FTQSize) {
            fetched(i) := false.B
            valids(i) := false.B
        }
    }
}

