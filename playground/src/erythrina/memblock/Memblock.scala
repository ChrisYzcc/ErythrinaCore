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
import erythrina.backend.Redirect

class Memblock extends ErythModule {
    val io = IO(new Bundle {
        val from_backend = new Bundle {
            val lq_alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
            val lq_alloc_rsp = Vec(DispatchWidth, Output(new LQPtr))

            val sq_alloc_req = Vec(DispatchWidth, Flipped(DecoupledIO(new InstExInfo)))
            val sq_alloc_rsp = Vec(DispatchWidth, Output(new SQPtr))

            val ldu_req = Flipped(DecoupledIO(new InstExInfo))
            val stu_req = Flipped(DecoupledIO(new InstExInfo))

            val rob_commits = Vec(CommitWidth, Flipped(ValidIO(new InstExInfo)))

            val redirect = Flipped(ValidIO(new Redirect))
        }
        
        val to_backend = new Bundle {
            val ldu_info = Output(new EXUInfo)
            val ldu_cmt = ValidIO(new InstExInfo)
            val stu_info = Output(new EXUInfo)
            val stu_cmt = ValidIO(new InstExInfo)

            val ldu_rf_write = ValidIO(new Bundle {
                val addr = UInt(PhyRegAddrBits.W)
                val data = UInt(XLEN.W)
            })

            val ldu_bt_free_req = ValidIO(UInt(PhyRegAddrBits.W))

            val lq_except_infos = Vec(LoadQueSize, ValidIO(new ROBPtr))
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
    val fwdUnit = Module(new StoreFwdUnit)

    /* ---------------- LDU ---------------- */
    ldu.io.req <> io.from_backend.ldu_req
    ldu.io.axi <> io.axi.ldu
    ldu.io.ldu_cmt <> io.to_backend.ldu_cmt
    ldu.io.ldu_cmt <> loadQueue.io.ldu_cmt
    ldu.io.exu_info <> io.to_backend.ldu_info
    ldu.io.redirect <> io.from_backend.redirect
    ldu.io.st_fwd_query <> fwdUnit.io.req
    ldu.io.st_fwd_result <> fwdUnit.io.resp

    ldu.io.bt_free_req <> io.to_backend.ldu_bt_free_req
    ldu.io.rf_write <> io.to_backend.ldu_rf_write

    /* ---------------- STU ---------------- */
    stu.io.req <> io.from_backend.stu_req
    stu.io.cmt <> io.to_backend.stu_cmt
    stu.io.cmt <> storeQueue.io.stu_cmt
    stu.io.exu_info <> io.to_backend.stu_info
    stu.io.redirect <> io.from_backend.redirect
    stu.io.stu_fwd <> fwdUnit.io.stu_fwd

    /* ---------------- Store Queue ---------------- */
    storeQueue.io.alloc_req <> io.from_backend.sq_alloc_req
    storeQueue.io.alloc_rsp <> io.from_backend.sq_alloc_rsp
    
    for (i <- 0 until CommitWidth) {
        storeQueue.io.rob_commit(i).valid := io.from_backend.rob_commits(i).valid && io.from_backend.rob_commits(i).bits.isStroe
        storeQueue.io.rob_commit(i).bits := io.from_backend.rob_commits(i).bits.sqPtr
    }

    storeQueue.io.axi <> io.axi.stu
    storeQueue.io.redirect <> io.from_backend.redirect
    storeQueue.io.sq_fwd <> fwdUnit.io.sq_fwd

    /* ---------------- Load Queue ---------------- */
    loadQueue.io.alloc_req <> io.from_backend.lq_alloc_req
    loadQueue.io.alloc_rsp <> io.from_backend.lq_alloc_rsp

    for (i <- 0 until CommitWidth) {
        loadQueue.io.rob_commit(i).valid := io.from_backend.rob_commits(i).valid && io.from_backend.rob_commits(i).bits.isLoad
        loadQueue.io.rob_commit(i).bits := io.from_backend.rob_commits(i).bits.lqPtr
    }

    loadQueue.io.st_req.valid := io.from_backend.stu_req.valid
    loadQueue.io.st_req.bits := io.from_backend.stu_req.bits
    loadQueue.io.lq_except_infos <> io.to_backend.lq_except_infos
    loadQueue.io.redirect <> io.from_backend.redirect
}