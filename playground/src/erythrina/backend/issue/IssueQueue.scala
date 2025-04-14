package erythrina.backend.issue

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.frontend.FuType
import erythrina.backend.fu.EXUInfo
import erythrina.backend.rob.ROBPtr
import erythrina.backend.Redirect

class IssueQueue(exu_num:Int, name:String, size:Int) extends ErythModule {
    val io = IO(new Bundle {
        val enq = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
        val deq = Vec(exu_num, DecoupledIO(new InstExInfo))

        val exu_info = Vec(exu_num, Input(new EXUInfo))

        val bypass = Vec(BypassWidth, Flipped(ValidIO(new BypassInfo)))

        val redirect = Flipped(ValidIO(new Redirect))

        val last_robPtr = Flipped(ValidIO(new ROBPtr))  // for non-speculative
    })

    val entries = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(new InstExInfo))))
    val valids = RegInit(VecInit(Seq.fill(size)(false.B)))
    val age_matrix = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(Vec(size, Bool())))))

    val (enq, deq) = (io.enq, io.deq)

    // Enq
    val free_vec = valids.map(!_)
    val free_cnt = PopCount(free_vec)
    val enq_cnt = PopCount(enq.map(_.valid))
    val alloc_idx = Wire(Vec(DispatchWidth, UInt(log2Ceil(size).W)))
    for (i <- 0 until DispatchWidth) {
        val cur_free_vec = if (i == 0) free_vec else free_vec.zipWithIndex.map{
            case (v, idx) => {
                val prev_match = alloc_idx.take(i).map(_ === idx.U)
                v && !prev_match.reduce(_||_)
            }
        }
        alloc_idx(i) := PriorityEncoder(cur_free_vec)

        enq(i).ready := free_cnt >= enq_cnt
        when (enq(i).fire) {
            entries(alloc_idx(i)) := enq(i).bits
            valids(alloc_idx(i)) := true.B
            
            for (j <- 0 until size) {
                age_matrix(alloc_idx(i))(j) := false.B
                age_matrix(j)(alloc_idx(i)) := true.B
            }
        }
    }

    for (i <- 0 until DispatchWidth) {
        for (j <- i until DispatchWidth) {
            when (enq(i).fire && enq(j).fire) {
                age_matrix(alloc_idx(i))(alloc_idx(j)) := true.B
            }
        }
    }

    // Deq
    val exu_info = io.exu_info
    val deq_idx = Wire(Vec(exu_num, UInt(log2Ceil(size).W)))
    val deq_valid = Wire(Vec(exu_num, Bool()))

    val ready_vec = entries.zip(valids).map{
        case (e, v) =>
            v && e.src1_ready && e.src2_ready && (e.speculative || io.last_robPtr.valid && e.robPtr === io.last_robPtr.bits)
    }
    val tot_ready_cnt = PopCount(ready_vec)

    for (i <- 0 until exu_num) {
        val relations = Wire(Vec(size, Bool()))
        for (j <- 0 until size) {
            val age_vec = age_matrix(j)
            val age_cnt = PopCount(age_vec.zip(ready_vec).map{
                case (a, r) => a && r
            })
            relations(j) := age_cnt === (tot_ready_cnt - i.U) && tot_ready_cnt > i.U && ready_vec(j)
            dontTouch(age_cnt)
        }

        assert(PopCount(relations) <= 1.U)
        deq_idx(i) := PriorityEncoder(relations)
        deq_valid(i) := relations.reduce(_||_)
    }

    val handle_exu_idx = Wire(Vec(exu_num, UInt(log2Ceil(exu_num).W)))
    for (i <- 0 until exu_num) {    // init
        deq(i).valid := false.B
        deq(i).bits := DontCare
    }
    for (i <- 0 until exu_num) {    // for the oldest $exu_num instructions
        val entry = entries(deq_idx(i))
        val valid = valids(deq_idx(i))

        val available_exu_list = 
            if (i == 0) {
                exu_info.map{
                    case e => !e.busy && e.fu_type_vec(entry.fuType)
                }
            } else {
                exu_info.zipWithIndex.map {
                    case (e, idx) => {
                        val prev_match = handle_exu_idx.take(i).map(_ === idx.U)
                        !e.busy && e.fu_type_vec(entry.fuType) && !prev_match.reduce(_||_)
                    }
                }
            }

        handle_exu_idx(i) := PriorityEncoder(available_exu_list)
        
        val canHandle = available_exu_list.reduce(_||_)
        
        deq(handle_exu_idx(i)).valid := valid && canHandle
        deq(handle_exu_idx(i)).bits := entry

        when (deq(handle_exu_idx(i)).fire) {
            valids(deq_idx(i)) := false.B
            for (j <- 0 until size) {
                age_matrix(deq_idx(i))(j) := false.B
                age_matrix(j)(deq_idx(i)) := false.B
            }
        }
    }

    // handle bypass
    val bypass = io.bypass
    for (i <- 0 until BypassWidth) {
        when (bypass(i).valid) {
            for (j <- 0 until size) {
                entries(j).src1_ready := entries(j).src1_ready || entries(j).p_rs1 === bypass(i).bits.bypass_prd
                entries(j).src1 := Mux(entries(j).src1_ready, entries(j).src1, bypass(i).bits.bypass_data)

                entries(j).src2_ready := entries(j).src2_ready || entries(j).p_rs2 === bypass(i).bits.bypass_prd
                entries(j).src2 := Mux(entries(j).src2_ready, entries(j).src2, bypass(i).bits.bypass_data)
            }   
        }
    }

    // handle redirect
    when (io.redirect.valid) {
        for (i <- 0 until size) {
            valids(i) := false.B
            for (j <- 0 until size) {
                age_matrix(i)(j) := false.B
                age_matrix(j)(i) := false.B
            }
        }
    }
}
