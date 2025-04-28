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

    val fwds = VecInit(Seq(sq_fwd, stu_fwd).flatten)
    val addr_hit_vec = fwds.map{
        case fwd =>
            fwd.valid && fwd.bits.addr === req.bits.addr
    }

    val final_mask_vec = Wire(Vec(MASKLEN, Bool()))
    val final_byte_res_vec = Wire(Vec(MASKLEN, UInt((XLEN / MASKLEN).W)))

    for (mask_idx <- 0 until MASKLEN) {
        val age_relation_matrix = Wire(Vec(fwds.length, Vec(fwds.length, Bool())))
        val mask_hit = fwds.map{
            case fwd =>
                fwd.valid && fwd.bits.mask(mask_idx) && req.bits.mask(mask_idx)
        }

        val hit_vec = addr_hit_vec.zip(mask_hit).zipWithIndex.map{
            case ((addr_hit, mask_hit), idx) =>
                addr_hit && mask_hit && req.bits.robPtr > fwds(idx).bits.robPtr
        }
        val hit_cnt = PopCount(hit_vec)

        val is_newest_vec = Wire(Vec(fwds.length, Bool()))
        for (i <- 0 until fwds.length) {
            for (j <- 0 until fwds.length) {
                // rob_i is newer than rob_j
                if (i == j)
                    age_relation_matrix(i)(j) := hit_vec(i)
                else
                    age_relation_matrix(i)(j) := fwds(i).bits.robPtr > fwds(j).bits.robPtr && hit_vec(i) && hit_vec(j)
            }
            val age_relation = age_relation_matrix(i)
            is_newest_vec(i) := PopCount(age_relation) === hit_cnt && hit_vec(i)
        }
        val newest_idx = PriorityEncoder(is_newest_vec)
        assert(PopCount(is_newest_vec) <= 1.U, "More than one newest store forward detected")

        final_mask_vec(mask_idx) := hit_cnt =/= 0.U
        final_byte_res_vec(mask_idx) := fwds(newest_idx).bits.data((mask_idx + 1) * XLEN / MASKLEN - 1, mask_idx * XLEN / MASKLEN)
    }

    resp := DontCare
    resp.mask := final_mask_vec.asUInt
    resp.data := Cat(final_byte_res_vec.reverse)
}