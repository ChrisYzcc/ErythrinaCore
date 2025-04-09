package erythrina.backend.issue

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.frontend.FuType
import erythrina.backend.fu.EXUInfo

class IssueQueue(name:String, size:Int, exu_num:Int) extends ErythModule {
    val io = IO(new Bundle {
        val enq_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        
        val exu_status = Vec(exu_num, Input(new EXUInfo))
        val deq_req = Vec(exu_num, DecoupledIO(new InstExInfo))

        // bypass Info
        val bypass = Vec(exu_num, Flipped(ValidIO(new BypassInfo)))

        // redirect
    })

    val age_matrix = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(Vec(size, Bool())))))

    val entries = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(new InstExInfo))))
    val valids =  RegInit(VecInit(Seq.fill(size)(false.B)))

    // Enq
    val free_vec = valids.map(!_)
    val free_idx_vec = Wire(Vec(DispatchWidth, UInt(log2Ceil(size).W)))
    val free_cnt = PopCount(free_vec)

    require(DispatchWidth == 2)     // TODO: Parameterize
    free_idx_vec(0) := PriorityEncoder(free_vec)
    free_idx_vec(1) := PriorityEncoder(free_vec.zipWithIndex.map {
        case (v, idx) => v && idx.U =/= free_idx_vec(0)
    })

    val enq_req = io.enq_req
    for (i <- 0 until DispatchWidth) {
        enq_req(i).ready := free_cnt >= DispatchWidth.U

        when (enq_req(i).fire) {
            val idx = free_idx_vec(i)
            entries(idx) := enq_req(i).bits
            valids(idx) := true.B

            for (j <- 0 until size) {
                age_matrix(idx)(j) := false.B
                age_matrix(j)(idx) := true.B
            }
            age_matrix(idx)(idx) := true.B
        }
    }

    for (i <- 0 until DispatchWidth - 1) {
        for (j <- i + 1 until DispatchWidth) {
            when (enq_req(i).fire && enq_req(j).fire) {
                age_matrix(free_idx_vec(i))(free_idx_vec(j)) := true.B
            }
        }
    }

    // Deq
    val candidate_idx = Vec(exu_num, Wire(UInt(log2Ceil(size).W)))  // 0: oldest, 1: second oldest, etc
    val has_candidate = Wire(Vec(exu_num, Bool()))
    val candidate_issued = Wire(Vec(exu_num, Bool()))

    val ready_vec = entries.zip(valids).map{
        case (e, v) =>
            v && e.src1_ready && e.src2_ready
    }
    val tot_ready_cnt = PopCount(ready_vec)

    for (i <- 0 until exu_num) {
        val candidate_vec = Wire(Vec(size, Bool()))
        for (j <- 0 until size) {
            val age_vec = age_matrix(j)
            val age_cnt = PopCount(age_vec.zip(ready_vec).map{
                case (a, r) => a && r
            })
            candidate_vec(j) := age_cnt === (tot_ready_cnt - i.U)
        }

        assert(PopCount(candidate_vec) <= 1.U)
        candidate_idx(i) := PriorityEncoder(candidate_vec)
        has_candidate(i) := candidate_vec.reduce(_ || _)
    }

    val deq_req = io.deq_req
    val exu_status = io.exu_status
    for (i <- 0 until DispatchWidth) {
        val entry = entries(candidate_idx(i))

        deq_req(i).valid := has_candidate(i) && exu_status(i).fu_type_vec(entry.fuType)
        deq_req(i).bits := entry

        when (deq_req(i).fire) {
            val idx = candidate_idx(i)
            valids(idx) := false.B
        }
        
        // TODO: More Accurate EXU
        assert(!has_candidate(i) || exu_status(i).fu_type_vec(entry.fuType), 
            s"EXU ${i} cannot handle the instruction")
    }

    // update by bypass
    val bypass = io.bypass
    for (i <- 0 until exu_num) {
        when (bypass(i).valid) {
            for (j <- 0 until size) {
                entries(j).src1_ready := entries(j).src1_ready || entries(j).p_rs1 === bypass(i).bits.bypass_prd
                entries(j).src1 := Mux(entries(j).src1_ready, entries(j).src1, bypass(i).bits.bypass_data)

                entries(j).src2_ready := entries(j).src2_ready || entries(j).p_rs2 === bypass(i).bits.bypass_prd
                entries(j).src2 := Mux(entries(j).src2_ready, entries(j).src2, bypass(i).bits.bypass_data)
            }
        }
    }
}