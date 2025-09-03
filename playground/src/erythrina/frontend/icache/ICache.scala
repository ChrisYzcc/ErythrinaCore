package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import bus.axi4._
import erythrina.{ErythModule, ErythBundle}
import erythrina.frontend.InstFetchBlock
import erythrina.frontend.icache.ICacheParams._
import top.Config._
import utils.PLRU
import utils.PerfCount

object ICacheCMD {
    def READ = 0.U
    def PREFETCH = 1.U

    def apply() = UInt(2.W)
}

class ICacheReq extends ErythBundle {
    val addr = UInt(XLEN.W)
    val cmd = ICacheCMD()
}

class ICacheMeta extends ErythBundle {
    val valid = Bool()
    val tag = UInt(TagLen.W)
}

class ICacheDummy extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val req_num = RegInit(0.U(4.W))
    val req_addr = RegInit(0.U(XLEN.W))
    val rsp_data_idx = RegInit(0.U(log2Ceil(CachelineSize / 4).W))
    val rsp_data_vec = RegInit(VecInit(Seq.fill(CachelineSize / 4)(0.U(XLEN.W))))

    val sIDLE :: sREQ :: sRECV :: sRSP :: Nil = Enum(4)
    val state = RegInit(sIDLE)
    switch (state) {
        is (sIDLE) {
            when (req.fire) {
                state := sREQ
            }
        }
        is (sREQ) {
            when (axi.ar.fire) {
                state := sRECV
            }
        }
        is (sRECV) {
            when (axi.r.fire) {
                when (req_num === 0.U) {
                    state := sRSP
                }.otherwise {
                    state := sREQ
                }
            }
        }
        is (sRSP) {
            when (rsp.valid) {
                state := sIDLE
            }
        }
    }

    // req_addr
    when (req.fire) {
        req_addr := req.bits
    }.elsewhen(axi.ar.fire) {
        req_addr := req_addr + 4.U
    }

    // req_num
    when (req.fire) {
        req_num := (CachelineSize / 4).U - get_cacheline_blk_offset(req.bits)
    }.elsewhen(axi.ar.fire) {
        req_num := req_num - 1.U
    }

    // rsp_data_idx
    when (req.fire) {
        rsp_data_idx := get_cacheline_blk_offset(req.bits)
    }.elsewhen(axi.r.fire) {
        rsp_data_idx := rsp_data_idx + 1.U
    }

    // rsp_data_vec
    when (axi.r.fire) {
        rsp_data_vec(rsp_data_idx) := axi.r.bits.data
    }

    // axi
    axi.ar.valid := state === sREQ
    axi.ar.bits := 0.U.asTypeOf(axi.ar.bits)
    axi.ar.bits.addr := req_addr

    axi.r.ready := state === sRECV

    // req
    req.ready := state === sIDLE

    // rsp
    rsp.valid := state === sRSP
    rsp.bits := rsp_data_vec.asUInt

    axi.w <> DontCare
    axi.b <> DontCare
    axi.aw <> DontCare
}

class SeqICache extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    // Print ICache Config
    println(s"ICache: sets = ${ICacheParams.sets}, ways = ${ICacheParams.ways}, cacheline = ${ICacheParams.CachelineSize} bytes, tot = ${ICacheParams.sets * ICacheParams.ways * ICacheParams.CachelineSize} bytes")

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val fetcher = Module(new Fetcher)
    fetcher.io.axi <> axi

    /* ------------------- Data & Meta Array ------------------- */
    val valids = SyncReadMem(ICacheParams.sets, Vec(ICacheParams.ways, Bool()))
    when (reset.asBool) {
        for (i <- 0 until ICacheParams.sets) {
            valids.write(i.U, 0.U.asTypeOf(Vec(ICacheParams.ways, Bool())))
        }
    }
    
    val tags = SyncReadMem(ICacheParams.sets, Vec(ICacheParams.ways, UInt(ICacheParams.TagLen.W)))

    val datas = SyncReadMem(ICacheParams.sets, Vec(ICacheParams.ways, UInt((CachelineSize * 8).W)))

    val lru_seq = Seq.fill(ICacheParams.sets)(Module(new PLRU))
    val lru_oldest_vec = VecInit(lru_seq.map(_.io.oldest))

    /* ------------------- Stage Control ------------------- */
    val s0_valid = Wire(Bool())
    val s1_valid = RegInit(false.B)

    val s0_ready = Wire(Bool())
    val s1_ready = Wire(Bool())

    // TODO: Forward data from s1
    /* ------------------- Stage 0 ------------------- */
    s0_valid := req.valid
    s0_ready := s1_ready

    req.ready := s0_ready

    val s0_addr = req.bits
    val s0_inrange = s0_addr >= ICacheParams.CacheableRange._1.U && s0_addr <= ICacheParams.CacheableRange._2.U

    // req data & meta
    val s0_idx = ICacheParams.get_idx(s0_addr)
    val s0_tag = ICacheParams.get_tag(s0_addr)

    val valid_vec = valids.read(s0_idx, s0_valid && s1_ready) // read $ways valid bit
    val tag_vec = tags.read(s0_idx, s0_valid && s1_ready) // read $ways tag
    val data_vec = datas.read(s0_idx, s0_valid && s1_ready) // read $ways data

    /* ------------------- Stage 1 ------------------- */
    val s1_idx = RegInit(0.U(log2Ceil(ICacheParams.sets).W))
    val s1_tag = RegInit(0.U(ICacheParams.TagLen.W))
    val s1_inrange = RegInit(false.B)

    when (s0_valid && s1_ready) {
        s1_valid := s0_valid
        s1_inrange := s0_inrange
        s1_idx := s0_idx
        s1_tag := s0_tag
    }.elsewhen(!s0_valid && s1_ready) {
        s1_valid := false.B
        s1_inrange := false.B
        s1_idx := 0.U
        s1_tag := 0.U
    }

    val hit_vec = valid_vec.zip(tag_vec).map{case (v, t) => {v && t === s1_tag}}
    val hit_way = PriorityEncoder(hit_vec)

    val hit = s1_valid && s1_inrange && hit_vec.reduce(_ || _)

    // fetcher
    val has_req = RegInit(false.B)
    when (fetcher.io.req.fire) {
        has_req := true.B
    }
    when (fetcher.io.rsp.valid) {
        has_req := false.B
    }

    fetcher.io.req.valid := s1_valid && (s1_inrange && !hit || !s1_inrange) && !has_req
    fetcher.io.req.bits := Cat(s1_tag, s1_idx, 0.U(log2Ceil(CachelineSize).W))

    val rsp_data = Mux(hit, data_vec(hit_way), fetcher.io.rsp.bits)
    rsp.valid := s1_valid && (s1_inrange && hit || fetcher.io.rsp.valid)
    rsp.bits := rsp_data

    s1_ready := !s1_valid || (s1_valid && hit) || (fetcher.io.rsp.valid)

    val update_way = Mux(hit, hit_way, lru_oldest_vec(s1_idx))

    // update plru
    for (i <- 0 until ICacheParams.sets) {
        lru_seq(i).io.update.valid := s1_valid && s1_inrange && rsp.valid && i.U === s1_idx
        lru_seq(i).io.update.bits := update_way
    }

    // update data & meta
    when (fetcher.io.rsp.valid) {
        // valids
        val update_valid_vec = WireInit(valid_vec)
        update_valid_vec(update_way) := true.B
        valids.write(s1_idx, update_valid_vec)

        // tags
        val update_tag_vec = WireInit(tag_vec)
        update_tag_vec(update_way) := s1_tag
        tags.write(s1_idx, update_tag_vec)

        // datas
        val update_data_vec = WireInit(data_vec)
        update_data_vec(update_way) := fetcher.io.rsp.bits
        datas.write(s1_idx, update_data_vec)
    }

    /* ---------------- Performance ----------------  */
    PerfCount("icache_hit", s1_valid && hit && s1_inrange && rsp.valid)
    PerfCount("icache_miss", s1_valid && !hit && s1_inrange && rsp.valid)
    PerfCount("icache_nc", s1_valid && !s1_inrange && rsp.valid)

    for (i <- 0 until ICacheParams.sets) {
        PerfCount(s"icache_set$i/_hit", s1_valid && hit && s1_inrange && rsp.valid && s1_idx === i.U)
        PerfCount(s"icache_set$i/_miss", s1_valid && !hit && s1_inrange && rsp.valid && s1_idx === i.U)
        for (j <- 0 until ICacheParams.ways) {
            PerfCount(s"icache_set$i/_way$j/_hit", s1_valid && hit && s1_inrange && rsp.valid && s1_idx === i.U && hit_way === j.U)
        }
    }
}

class ICache extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    // Print ICache Config
    println(s"ICache: sets = ${ICacheParams.sets}, ways = ${ICacheParams.ways}, cacheline = ${ICacheParams.CachelineSize} bytes, tot = ${ICacheParams.sets * ICacheParams.ways * ICacheParams.CachelineSize} bytes")

    val axi = io.axi
    val (req, rsp) = (io.req, io.rsp)

    val fetcher = Module(new Fetcher)
    val main_pipe = Module(new MainPipe)
    val prefetcher = Module(new Prefetcher)
    
    val req_arb = Module(new Arbiter(new ICacheReq, 2))

    req_arb.io.in(0).valid := req.valid
    req_arb.io.in(0).bits.addr := req.bits
    req_arb.io.in(0).bits.cmd := ICacheCMD.READ
    req.ready := req_arb.io.in(0).ready

    req_arb.io.in(1) <> prefetcher.io.pft_req
    req_arb.io.out <> main_pipe.io.req

    main_pipe.io.rsp <> rsp
    main_pipe.io.pft_hint <> prefetcher.io.pft_hint
    main_pipe.io.fetcher_req <> fetcher.io.req
    main_pipe.io.fetcher_rsp <> fetcher.io.rsp

    fetcher.io.axi <> axi
}