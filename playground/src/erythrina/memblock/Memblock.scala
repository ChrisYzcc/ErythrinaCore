package erythrina.memblock

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.InstExInfo
import erythrina.memblock.lsq._
import bus.axi4._
import erythrina.memblock.pipeline._
import erythrina.backend.fu.EXUInfo
import erythrina.backend.rob.ROBPtr

class Memblock extends ErythModule {
    val io = IO(new Bundle {
        val from_backend = new Bundle {
            val lq_alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
            val lq_alloc_rsp = Vec(DispatchWidth, Output(new LQPtr))
            val lq_alloc_upt = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))    // update ROBPtr

            val sq_alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
            val sq_alloc_rsp = Vec(DispatchWidth, Output(new SQPtr))
            val sq_alloc_upt = Vec(DispatchWidth, Flipped(ValidIO(new InstExInfo)))    // update ROBPtr

            val ldu_req = Flipped(DecoupledIO(new InstExInfo))
            val stu_req = Flipped(DecoupledIO(new InstExInfo))

            val rob_commits = Vec(CommitWidth, Flipped(ValidIO(new InstExInfo)))
        }
        
        val to_backend = new Bundle {
            val ldu_info = Output(new EXUInfo)
            val ldu_cmt = ValidIO(new InstExInfo)
            val stu_info = Output(new EXUInfo)
            val stu_cmt = ValidIO(new InstExInfo)

            val lq_exc_infos = Vec(LoadQueSize, ValidIO(new ROBPtr))
        }

        val axi = new Bundle {
            val ldu = new Bundle {
                val ar = DecoupledIO(new AXI4LiteBundleA)
                val r = Flipped(DecoupledIO(new AXI4LiteBundleR(dataBits = XLEN)))
            }
            val stu = new Bundle {
                val aw = DecoupledIO(new AXI4LiteBundleA)
                val w = DecoupledIO(new AXI4LiteBundleW(dataBits = XLEN))
                val b = Flipped(DecoupledIO(new AXI4LiteBundleB))
            }
        }
    })
    
    val ldu = Module(new LDU)
    val stu = Module(new STU)
    val storeQueue = Module(new StoreQueue)
    val loadQueue = Module(new LoadQueue)

    /* ---------------- LDU ---------------- */
    ldu.io.req <> io.from_backend.ldu_req
    ldu.io.axi <> io.axi.ldu
    ldu.io.sq_fwd <> storeQueue.io.sq_fwd
    ldu.io.stu_fwd <> stu.io.stu_fwd
    ldu.io.ldu_cmt <> io.to_backend.ldu_cmt
    ldu.io.ldu_cmt <> loadQueue.io.ldu_cmt
    ldu.io.exu_info <> io.to_backend.ldu_info

    /* ---------------- STU ---------------- */
    stu.io.req <> io.from_backend.stu_req
    stu.io.cmt <> io.to_backend.stu_cmt
    stu.io.cmt <> storeQueue.io.stu_cmt
    stu.io.exu_info <> io.to_backend.stu_info

    /* ---------------- Store Queue ---------------- */
    storeQueue.io.alloc_req <> io.from_backend.sq_alloc_req
    storeQueue.io.alloc_rsp <> io.from_backend.sq_alloc_rsp
    storeQueue.io.alloc_upt <> io.from_backend.sq_alloc_upt
    
    for (i <- 0 until CommitWidth) {
        storeQueue.io.rob_commit(i).valid := io.from_backend.rob_commits(i).valid
        storeQueue.io.rob_commit(i).bits := io.from_backend.rob_commits(i).bits.sqPtr
    }

    storeQueue.io.axi <> io.axi.stu

    /* ---------------- Load Queue ---------------- */
    loadQueue.io.alloc_req <> io.from_backend.lq_alloc_req
    loadQueue.io.alloc_rsp <> io.from_backend.lq_alloc_rsp
    loadQueue.io.alloc_upt <> io.from_backend.lq_alloc_upt

    for (i <- 0 until CommitWidth) {
        loadQueue.io.rob_commit(i).valid := io.from_backend.rob_commits(i).valid
        loadQueue.io.rob_commit(i).bits := io.from_backend.rob_commits(i).bits.lqPtr
    }

    loadQueue.io.st_req <> io.from_backend.stu_req
    loadQueue.lq_exc_infos <> io.to_backend.lq_exc_infos
}