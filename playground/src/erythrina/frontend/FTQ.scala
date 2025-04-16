package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import utils.CircularQueuePtr

class FTQ extends ErythModule {
    val io = IO(new Bundle {
        val enq_req  = Flipped(DecoupledIO(new InstFetchBlock))        // from BPU, enq
        val pred_req = ValidIO(new InstFetchBlock)      // req to BPU
        val pred_rsp = Flipped(ValidIO(new InstFetchBlock))      // rsp from BPU

        val fetch_req   = DecoupledIO(new InstFetchBlock)               // to IFU
        val fetch_rsp  = Flipped(ValidIO(new InstFetchBlock))      // from IFU

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
    val predicted = RegInit(VecInit(Seq.fill(FTQSize)(false.B)))
    val valids = RegInit(VecInit(Seq.fill(FTQSize)(false.B)))

    // ptr
    val enqPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val deqPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val fetchPtrExt = RegInit(0.U.asTypeOf(new Ptr))
    val predPtrExt = RegInit(0.U.asTypeOf(new Ptr))

    // enq
    val enq_req = io.enq_req
    enq_req.ready := enqPtrExt >= deqPtrExt

    val enq_entry = WireInit(enq_req.bits)
    enq_entry.ftqIdx := enqPtrExt.value
    when (enq_req.fire) {
        entries(enqPtrExt.value) := enq_entry
        valids(enqPtrExt.value) := true.B
        fetched(enqPtrExt.value) := false.B
        predicted(enqPtrExt.value) := false.B
        enqPtrExt := enqPtrExt + 1.U
    }
    
    // deq
    val decode_req = io.decode_req
    decode_req.valid := predicted(deqPtrExt.value) && fetched(deqPtrExt.value) && !io.flush && valids(deqPtrExt.value)
    decode_req.bits := entries(deqPtrExt.value)
    when (decode_req.fire) {
        deqPtrExt := deqPtrExt + 1.U
    }

    // fetch req
    val fetch_req = io.fetch_req
    fetch_req.valid := valids(fetchPtrExt.value) && !fetched(fetchPtrExt.value) && !io.flush
    fetch_req.bits := entries(fetchPtrExt.value)

    when (fetch_req.fire) {
        fetchPtrExt := fetchPtrExt + 1.U
    }

    // fetch resp
    val fetch_resp = io.fetch_rsp

    val fetch_entry = fetch_resp.bits
    when (fetch_resp.valid) {
        entries(fetch_entry.ftqIdx) := fetch_entry
        fetched(fetch_entry.ftqIdx) := true.B
    }

    // predict
    val pred_req = io.pred_req
    val pred_rsp = io.pred_rsp
    
    pred_req.valid := valids(predPtrExt.value) && fetched(predPtrExt.value) && !io.flush
    pred_req.bits := entries(predPtrExt.value)

    when (pred_rsp.valid) {
        val pred_entry = pred_rsp.bits
        entries(pred_entry.ftqIdx) := pred_entry
        predicted(pred_entry.ftqIdx) := true.B
        predPtrExt := predPtrExt + 1.U
    }

    // flush
    when (io.flush) {
        enqPtrExt := 0.U.asTypeOf(new Ptr)
        deqPtrExt := 0.U.asTypeOf(new Ptr)
        fetchPtrExt := 0.U.asTypeOf(new Ptr)
        predPtrExt := 0.U.asTypeOf(new Ptr)
        for (i <- 0 until FTQSize) {
            fetched(i) := false.B
            predicted(i) := false.B
            valids(i) := false.B
        }
    }
}

