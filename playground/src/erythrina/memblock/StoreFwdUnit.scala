package erythrina.memblock

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class StoreFwdUnit extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(ValidIO(new StoreFwdBundle))
        val resp = Output(new StoreFwdBundle)
        val sq_fwd = Vec(StoreQueSize, Flipped(ValidIO(new StoreFwdBundle)))
        val stu_fwd = Vec(2, Flipped(ValidIO(new StoreFwdBundle)))
    })

    val (req, resp) = (io.req, io.resp)
    val sq_fwd = io.sq_fwd
    val stu_fwd = io.stu_fwd

    val stu_hit_vec = stu_fwd.map{
        case fwd =>
            fwd.valid && fwd.bits.addr === req.bits.addr && fwd.bits.robPtr < req.bits.robPtr
    }

    val sq_hit_vec = sq_fwd.map{
        case fwd =>
            fwd.valid && fwd.bits.addr === req.bits.addr && (fwd.bits.robPtr < req.bits.robPtr || fwd.bits.committed)
    }

    val final_mask_vec = Wire(Vec(MASKLEN, Bool()))
    val final_byte_res_vec = Wire(Vec(MASKLEN, UInt((XLEN / MASKLEN).W)))

    def get_newest(vec: Seq[Valid[StoreFwdBundle]]): (Bool, UInt) = {
        val age_relation_matrix = Wire(Vec(vec.length, Vec(vec.length, Bool())))

        val hit_cnt = PopCount(vec.map(_.valid))
        val is_newest_vec = Wire(Vec(vec.length, Bool()))
        for (i <- 0 until vec.length) {
            for (j <- 0 until vec.length) {
                if (i == j) {
                    age_relation_matrix(i)(j) := vec(i).valid
                }
                else {
                    age_relation_matrix(i)(j) := vec(i).valid && vec(j).valid && vec(i).bits.robPtr > vec(j).bits.robPtr
                }
            }
            val age_relation = age_relation_matrix(i)
            is_newest_vec(i) := PopCount(age_relation) === hit_cnt && vec(i).valid
        }
        val newest_idx = PriorityEncoder(is_newest_vec)
        assert(PopCount(is_newest_vec) <= 1.U, "More than one newest store forward detected")

        (VecInit(vec)(newest_idx).valid, newest_idx)
    }

    for (mask_idx <- 0 until MASKLEN) {
        // stu mask hit
        val stu_mask_hit_vec = stu_hit_vec.zipWithIndex.map {
            case (hit, idx) =>
                hit && stu_fwd(idx).bits.mask(mask_idx) && req.bits.mask(mask_idx)
        }

        val stu_query_vec = Wire(Vec(stu_hit_vec.length, Valid(new StoreFwdBundle)))
        for (i <- 0 until stu_hit_vec.length) {
            stu_query_vec(i).valid := stu_mask_hit_vec(i)
            stu_query_vec(i).bits := stu_fwd(i).bits
        }
        val (stu_v, stu_newest_idx) = get_newest(stu_query_vec)

        // sq uncommitted mask hit
        val sq_uncommitted_mask_hit_vec = sq_hit_vec.zipWithIndex.map {
            case (hit, idx) =>
                hit && !sq_fwd(idx).bits.committed && sq_fwd(idx).bits.mask(mask_idx) && req.bits.mask(mask_idx)
        }

        val sq_uncommitted_query_vec = Wire(Vec(sq_hit_vec.length, Valid(new StoreFwdBundle)))
        for (i <- 0 until sq_hit_vec.length) {
            sq_uncommitted_query_vec(i).valid := sq_uncommitted_mask_hit_vec(i)
            sq_uncommitted_query_vec(i).bits := sq_fwd(i).bits
        }
        val (sq_uncommitted_v, sq_uncommitted_newest_idx) = get_newest(sq_uncommitted_query_vec)

        // sq committed mask hit
        val sq_committed_mask_hit_vec = sq_hit_vec.zipWithIndex.map {
            case (hit, idx) =>
                hit && sq_fwd(idx).bits.committed && sq_fwd(idx).bits.mask(mask_idx) && req.bits.mask(mask_idx)
        }

        val sq_committed_query_vec = Wire(Vec(sq_hit_vec.length, Valid(new StoreFwdBundle)))
        for (i <- 0 until sq_hit_vec.length) {
            sq_committed_query_vec(i).valid := sq_committed_mask_hit_vec(i)
            sq_committed_query_vec(i).bits := sq_fwd(i).bits
        }
        val (sq_committed_v, sq_committed_newest_idx) = get_newest(sq_committed_query_vec)

        // get the newest mask
        final_mask_vec(mask_idx) := Mux(
            sq_committed_v,
            sq_fwd(sq_committed_newest_idx).bits.mask(mask_idx),
            Mux(
                sq_uncommitted_v && stu_v,
                Mux(
                    sq_fwd(sq_uncommitted_newest_idx).bits.robPtr > stu_fwd(stu_newest_idx).bits.robPtr,
                    sq_fwd(sq_uncommitted_newest_idx).bits.mask(mask_idx),
                    stu_fwd(stu_newest_idx).bits.mask(mask_idx)
                ),
                Mux(
                    sq_uncommitted_v,
                    sq_fwd(sq_uncommitted_newest_idx).bits.mask(mask_idx),
                    Mux(
                        stu_v,
                        stu_fwd(stu_newest_idx).bits.mask(mask_idx),
                        false.B
                    )
                )
            )
        )

        // get the newest data
        final_byte_res_vec(mask_idx) := Mux(
            sq_committed_v,
            sq_fwd(sq_committed_newest_idx).bits.data((mask_idx + 1) * (XLEN / MASKLEN) - 1, mask_idx * (XLEN / MASKLEN)),
            Mux(
                sq_uncommitted_v && stu_v,
                Mux(
                    sq_fwd(sq_uncommitted_newest_idx).bits.robPtr > stu_fwd(stu_newest_idx).bits.robPtr,
                    sq_fwd(sq_uncommitted_newest_idx).bits.data((mask_idx + 1) * (XLEN / MASKLEN) - 1, mask_idx * (XLEN / MASKLEN)),
                    stu_fwd(stu_newest_idx).bits.data((mask_idx + 1) * (XLEN / MASKLEN) - 1, mask_idx * (XLEN / MASKLEN))
                ),
                Mux(
                    sq_uncommitted_v,
                    sq_fwd(sq_uncommitted_newest_idx).bits.data((mask_idx + 1) * (XLEN / MASKLEN) - 1, mask_idx * (XLEN / MASKLEN)),
                    Mux(
                        stu_v,
                        stu_fwd(stu_newest_idx).bits.data((mask_idx + 1) * (XLEN / MASKLEN) - 1, mask_idx * (XLEN / MASKLEN)),
                        req.bits.data((mask_idx + 1) * (XLEN / MASKLEN) - 1, mask_idx * (XLEN / MASKLEN))
                    )
                )
            )
        )
    }

    resp := DontCare
    resp.mask := final_mask_vec.asUInt
    resp.data := Cat(final_byte_res_vec.reverse)
}