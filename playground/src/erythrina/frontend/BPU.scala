package erythrina.frontend

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.Redirect

class BPU extends ErythModule {
    val io = IO(new Bundle {
        val flush = Input(Bool())
        val redirect = Flipped(ValidIO(new Redirect))

        val ftq_enq_req = DecoupledIO(new InstFetchBlock)        // to FTQ, enq
        
        // from FTQ
        val ftq_pred_req = Flipped(ValidIO(new InstFetchBlock))
        val ftq_pred_rsp = ValidIO(new InstFetchBlock)
    })

    // init InstFetchBlock
    val init_block = WireInit(0.U.asTypeOf(new InstFetchBlock))
    init_block.instVec(0).valid := true.B
    init_block.instVec(0).pc   := RESETVEC.U
    init_block.instVec(1).valid := true.B
    init_block.instVec(1).pc   := RESETVEC.U + 4.U

    // response prediction result
    // TODO: dynamic prediction
    val pred_req = io.ftq_pred_req
    val base_block = pred_req.bits
    val base_pc = Mux(base_block.instVec(1).valid, base_block.instVec(1).pc, base_block.instVec(0).pc) + 4.U

    val pred_rsp = io.ftq_pred_rsp
    val resp_block = WireInit(base_block)
    for (i <- 0 until FetchWidth) {
        resp_block.instVec(i).bpu_taken := false.B
    }

    pred_rsp.valid := RegNext(pred_req.valid)
    pred_rsp.bits := RegNext(resp_block)
    
    // generage new block
    val new_block = WireInit(0.U.asTypeOf(new InstFetchBlock))
    for (i <- 0 until FetchWidth) {
        new_block.instVec(i).valid := true.B && !io.flush
        new_block.instVec(i).pc := base_pc + (i.U << 2)
    }

    // redirect block
    val redirect_block = WireInit(0.U.asTypeOf(new InstFetchBlock))
    for (i <- 0 until FetchWidth) {
        redirect_block.instVec(i).valid := true.B
        redirect_block.instVec(i).pc := io.redirect.bits.npc + (i.U << 2)
    }
    
    val ftq_enq_req = io.ftq_enq_req
    ftq_enq_req.valid   := RegNext(pred_req.valid) || RegNext(reset.asBool) || io.redirect.valid
    ftq_enq_req.bits    := Mux(io.redirect.valid,
                                redirect_block,
                                Mux(RegNext(pred_req.valid),
                                    RegNext(new_block),
                                    init_block)
                            )
}