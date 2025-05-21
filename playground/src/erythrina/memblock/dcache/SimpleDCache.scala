package erythrina.memblock.dcache

/*
    A Simple Blocking DCache
    Ref: Nutshell Cache
*/


import chisel3._
import chisel3.util._
import erythrina.ErythModule
import bus.axi4._
import erythrina.memblock.dcache.DCacheParams._
import utils._
import erythrina.ErythBundle

class MetaEntry extends ErythBundle {
    val valid = Bool()
    val dirty = Bool()
    val tag = UInt(TagLen.W)
}

class MetaArray extends ErythModule {
    val io = IO(new Bundle {
        val rd_req = Flipped(ValidIO(UInt(log2Ceil(sets).W)))
        val rd_rsp = ValidIO(Vec(ways, new MetaEntry))

        val wr_req = Flipped(ValidIO(new Bundle{
            val idx = UInt(log2Ceil(sets).W)
            val way = UInt(log2Ceil(ways).W)
            val meta = new MetaEntry
        }))
    })

    val meta_array = Seq.fill(ways)(SyncReadMem(sets, new MetaEntry))

    // Read
    val rd_rsp = VecInit(meta_array.map(_.read(io.rd_req.bits, io.rd_req.valid)))
    io.rd_rsp.valid := RegNext(io.rd_req.valid)
    io.rd_rsp.bits := rd_rsp

    // Write
    when (io.wr_req.valid) {
        for (i <- 0 until ways) {
            when (io.wr_req.bits.way === i.U) {
                meta_array(i).write(io.wr_req.bits.idx, io.wr_req.bits.meta)
            }
        }
    }
}

class DataArray extends ErythModule {
    val io = IO(new Bundle {
        val rd_req = Flipped(ValidIO(UInt(log2Ceil(sets).W)))
        val rd_rsp = ValidIO(Vec(ways, Vec(CachelineSize / 4, UInt(XLEN.W))))

        val wr_req = Flipped(ValidIO(new Bundle{
            val idx = UInt(log2Ceil(sets).W)
            val way = UInt(log2Ceil(ways).W)
            val data = Vec(CachelineSize / 4, UInt(XLEN.W))
        }))
    })

    val data_array = Seq.fill(ways)(SyncReadMem(sets, Vec(CachelineSize / 4, UInt(XLEN.W))))

    // Read
    val rd_rsp = VecInit(data_array.map(_.read(io.rd_req.bits, io.rd_req.valid)))
    io.rd_rsp.valid := RegNext(io.rd_req.valid)
    io.rd_rsp.bits := rd_rsp

    // Write
    when (io.wr_req.valid) {
        for (i <- 0 until ways) {
            when (io.wr_req.bits.way === i.U) {
                data_array(i).write(io.wr_req.bits.idx, io.wr_req.bits.data)
            }
        }
    }
}

class SimpleDCacheTask extends ErythBundle {
    val content_valid = Bool()
    
    val addr = UInt(XLEN.W)
    val data = UInt(XLEN.W)
    val mask = UInt(MASKLEN.W)
    val cmd = UInt(CmdBits.W)

    val hit = Bool()
    val cacheable = Bool()
    val hit_or_evict_meta = new MetaEntry
    val hit_or_evict_way = UInt(log2Ceil(ways).W)

    val hit_or_evict_cacheline = Vec(CachelineSize / 4, UInt(XLEN.W))
}

// Stage1: Req for Meta & Data
class Stage1 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new DCacheReq))
        val out = DecoupledIO(new SimpleDCacheTask)

        val meta_req = ValidIO(UInt(log2Ceil(sets).W))
        val data_req = ValidIO(UInt(log2Ceil(sets).W))
    })

    io.in.ready := io.out.ready

    val task = WireInit(0.U.asTypeOf(new SimpleDCacheTask))
    task.content_valid := io.in.valid
    task.addr := io.in.bits.addr
    task.data := io.in.bits.data
    task.mask := io.in.bits.mask
    task.cmd := io.in.bits.cmd

    // req meta & data
    io.meta_req.valid := io.in.valid && io.out.ready
    io.meta_req.bits := get_idx(io.in.bits.addr)

    io.data_req.valid := io.in.valid && io.out.ready
    io.data_req.bits := get_idx(io.in.bits.addr)

    // out
    io.out.valid := io.in.valid
    io.out.bits := task
}

// Stage2: hit check & victim choose
class Stage2 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new SimpleDCacheTask))
        val out = DecoupledIO(new SimpleDCacheTask)

        val meta_rsp = Flipped(ValidIO(Vec(ways, new MetaEntry)))
        val data_rsp = Flipped(ValidIO(Vec(ways, Vec(CachelineSize / 4, UInt(XLEN.W)))))
 
        // forward from s3
        val forward = Flipped(ValidIO(new Bundle {
            val meta = new MetaEntry
            val data = Vec(CachelineSize / 4, UInt(XLEN.W))
        }))
    })

    val (in, out) = (io.in, io.out)

    in.ready := out.ready
    out.valid := true.B
    out.bits := in.bits

    val lru_seq = Seq.fill(sets)(Module(new PLRU))
    val lru_oldest_vec = VecInit(lru_seq.map(_.io.oldest))

    val idx = get_idx(in.bits.addr)

    // hit check
    val req_tag = get_tag(in.bits.addr)

    val (meta_rsp, data_rsp) = (io.meta_rsp.bits, io.data_rsp.bits)
    val forward = io.forward
    val hit_vec = meta_rsp.map{
        m => m.valid && (m.tag === req_tag)
    }
    val hit_way = PriorityEncoder(hit_vec)
    assert(PopCount(hit_vec) <= 1.U, "More than one hit in the same set")

    val fwd_hit = forward.valid && (forward.bits.meta.tag === req_tag)

    val hit = hit_vec.reduce(_||_) || fwd_hit
    val cacheable = in.bits.addr >= CacheableRange._1.U && in.bits.addr <= CacheableRange._2.U

    out.bits.hit := hit
    out.bits.cacheable := cacheable

    val hit_cacheline = Mux(fwd_hit, forward.bits.data, data_rsp(hit_way))
    out.bits.hit_or_evict_cacheline := Mux(hit, hit_cacheline, data_rsp(lru_oldest_vec(idx)))

    // choose victim
    out.bits.hit_or_evict_meta := meta_rsp(lru_oldest_vec(idx))
    out.bits.hit_or_evict_way := Mux(hit, hit_way, lru_oldest_vec(idx))

    // update plru
    val update_way = Mux(hit, hit_way, lru_oldest_vec(idx))
    for (i <- 0 until sets) {
        lru_seq(i).io.update.valid := out.fire && (idx === i.U) && !fwd_hit
        lru_seq(i).io.update.bits := update_way
    }
}

// Stage3: cmt for hit & refill for miss
class Stage3 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new SimpleDCacheTask))
        val out = ValidIO(new DCacheResp)

        val forward = ValidIO(new Bundle {
            val meta = new MetaEntry
            val data = Vec(CachelineSize / 4, UInt(XLEN.W))
        })

        val meta_wr_req = ValidIO(new Bundle {
            val idx = UInt(log2Ceil(sets).W)
            val way = UInt(log2Ceil(ways).W)
            val meta = new MetaEntry
        })

        val data_wr_req = ValidIO(new Bundle {
            val idx = UInt(log2Ceil(sets).W)
            val way = UInt(log2Ceil(ways).W)
            val data = Vec(CachelineSize / 4, UInt(XLEN.W))
        })

        val axi = new AXI4
    })

    val (in, out) = (io.in, io.out)
    val axi = io.axi

    val meta = io.in.bits.hit_or_evict_meta
    val cacheline = in.bits.hit_or_evict_cacheline

    val content_valid = in.bits.content_valid

    /* ------------- Ctrl Signals ------------- */
    val reset_done = Wire(Bool())

    val wr_last = Wire(Bool())

    /* ------------- FSM Ctrl ------------- */
    // main FSM
    val sRESET :: sIDLE :: sRSP :: Nil = Enum(3)
    val state = RegInit(sRESET)

    switch (state) {
        is (sRESET) {
            when (reset_done) {
                state := sIDLE
            }
        }
        is (sIDLE) {
            when (in.fire) {
                state := sRSP
            }
        }
        is (sRSP) {
            when (out.valid || !content_valid) {
                state := sIDLE
            }
        }
    }

    // Read FSM
    val sIDLE_R :: sREQ_R :: sRECV_R :: sFINISH_R :: Nil = Enum(4)
    val state_r = RegInit(sIDLE_R)

    switch (state_r) {
        is (sIDLE_R) {
            when (RegNext(in.fire) && (!in.bits.hit || !in.bits.cacheable && in.bits.cmd === DCacheCMD.READ) && content_valid) {
                state_r := sREQ_R
            }
        }
        is (sREQ_R) {
            when (axi.ar.fire) {
                state_r := sRECV_R
            }
        }
        is (sRECV_R) {
            when (axi.r.fire && axi.r.bits.last) {
                state_r := sFINISH_R
            }
        }
        is (sFINISH_R) {
            when (out.valid) {
                state_r := sIDLE_R
            }
        }
    }

    // Write FSM
    val sIDLE_W :: sREQ_W :: sRECV_W :: sFINISH_W :: Nil = Enum(4)
    val state_w = RegInit(sIDLE_W)

    switch (state_w) {
        is (sIDLE_W) {
            when (RegNext(in.fire) && (!in.bits.hit && meta.dirty || !in.bits.cacheable && in.bits.cmd === DCacheCMD.WRITE) && content_valid) {
                state_w := sREQ_W
            }
        } 
        is (sREQ_W) {
            when (axi.aw.fire && axi.w.fire) {
                state_w := sRECV_W
            }
        }
        is (sRECV_W) {
            when (axi.b.fire) {
                state_w := Mux(wr_last, sFINISH_W, sREQ_W)
            }
        }
        is (sFINISH_W) {
            when (out.valid) {
                state_w := sIDLE_W
            }
        }
    }

    /* ------------- READ ------------- */
    val recv_data_vec = RegInit(VecInit(Seq.fill(CachelineSize / 4)(0.U(XLEN.W))))

    val ar_addr = RegInit(0.U(XLEN.W))
    when (RegNext(in.fire)) {
        ar_addr := Mux(in.bits.cacheable,
                    Cat(in.bits.addr(XLEN - 1, log2Ceil(CachelineSize)), 0.U(log2Ceil(CachelineSize).W)),
                    in.bits.addr
                )
    }

    val ar_len = RegInit(0.U(AXI4Params.lenBits.W))
    when (RegNext(in.fire)) {
        ar_len := Mux(in.bits.cacheable, (CachelineSize / 4 - 1).U, 0.U)
    }

    val r_idx = RegInit(0.U(log2Ceil(CachelineSize / 4).W))
    when (axi.ar.fire) {
        r_idx := 0.U
    }.elsewhen(axi.r.fire) {
        r_idx := r_idx + 1.U
    }

    axi.ar.valid := state_r === sREQ_R
    axi.ar.bits := 0.U.asTypeOf(axi.ar.bits)
    axi.ar.bits.addr := ar_addr
    axi.ar.bits.len := ar_len
    axi.ar.bits.size := "b010".U

    axi.r.ready := state_r === sRECV_R
    when (axi.r.fire) {
        recv_data_vec(r_idx) := axi.r.bits.data
    }

    /* ------------- WRITE ------------- */
    val idx = get_idx(in.bits.addr)
    val aw_addr = RegInit(0.U(XLEN.W))
    when (RegNext(in.fire)) {
        aw_addr := Mux(in.bits.cacheable,
                    Cat(in.bits.addr(XLEN - 1, log2Ceil(CachelineSize)), 0.U(log2Ceil(CachelineSize).W)),
                    Cat(meta.tag, idx, 0.U(log2Ceil(CachelineSize).W))
                )
    }.elsewhen(axi.b.fire) {
        aw_addr := aw_addr + 4.U
    }

    val w_len = RegInit(0.U(AXI4Params.lenBits.W))
    when (RegNext(in.fire)) {
        w_len := Mux(in.bits.cacheable, (CachelineSize / 4).U, 0.U)
    }

    val w_idx = RegInit(0.U(log2Ceil(CachelineSize / 4).W))
    when (RegNext(in.fire)) {
        w_idx := 0.U
    }.elsewhen(axi.b.fire) {
        w_idx := w_idx + 1.U
    }

    axi.aw.valid := state_w === sREQ_W
    axi.aw.bits := 0.U.asTypeOf(axi.aw.bits)
    axi.aw.bits.addr := aw_addr
    axi.aw.bits.size := "b010".U

    axi.w.valid := state_w === sREQ_W
    axi.w.bits := 0.U.asTypeOf(axi.w.bits)
    axi.w.bits.data := Mux(in.bits.cacheable, cacheline(w_idx), in.bits.data)
    axi.w.bits.strb := Mux(in.bits.cacheable, "b1111".U, in.bits.mask)

    axi.b.ready := state_w === sRECV_W

    wr_last := Mux(in.bits.cacheable, w_idx === (CachelineSize / 4 - 1).U, true.B)

    /* ------------- Response to Core & Stage Control ------------- */
    val offset = get_cacheline_offset(in.bits.addr)
    val word_offset = offset(log2Ceil(CachelineSize) - 1, 2)

    val hit_ready = in.bits.hit
    val hit_data = cacheline(word_offset)

    val miss_ready = !in.bits.hit && state_r === sFINISH_R && (!meta.dirty || state_w === sFINISH_W)
    val miss_data = recv_data_vec(word_offset)

    val mmio_ready = !in.bits.cacheable && (in.bits.cmd === DCacheCMD.READ && state_r === sFINISH_R || in.bits.cmd === DCacheCMD.WRITE && state_w === sFINISH_W)
    val mmio_data = recv_data_vec(0)

    out.valid := state === sRSP && (hit_ready || miss_ready || mmio_ready) && content_valid
    out.bits.data := MuxCase(0.U, List(
        hit_ready -> hit_data,
        miss_ready -> miss_data,
        mmio_ready -> mmio_data
    ))
    out.bits.cmd := in.bits.cmd

    in.ready := state === sIDLE

    /* ------------- Write Meta & Cacheline ------------- */
    val (meta_wr_req, data_wr_req) = (io.meta_wr_req, io.data_wr_req)

    // Meta
    val new_meta = WireInit(0.U.asTypeOf(new MetaEntry))
    new_meta.valid := true.B
    new_meta.dirty := Mux(in.bits.cmd === DCacheCMD.WRITE, true.B, false.B)
    new_meta.tag := get_tag(in.bits.addr)

    // reset
    val reset_idx = RegInit(0.U(log2Ceil(sets).W))
    val reset_way = RegInit(0.U(log2Ceil(ways).W))
    when (state === sRESET) {
        reset_way := reset_way + 1.U
        when (reset_way === (ways - 1).U) {
            reset_idx := reset_idx + 1.U
            reset_way := 0.U
        }
    }
    reset_done := reset_idx === (sets - 1).U && reset_way === (ways - 1).U

    meta_wr_req.valid := state === sRESET || out.valid
    meta_wr_req.bits.idx := Mux(state === sRESET, reset_idx, get_idx(in.bits.addr))
    meta_wr_req.bits.way := Mux(state === sRESET, reset_way, in.bits.hit_or_evict_way)
    meta_wr_req.bits.meta := Mux(state === sRESET, new_meta, 0.U.asTypeOf(new MetaEntry))
    
    // Data
    val new_cacheline = WireInit(0.U.asTypeOf(Vec(CachelineSize / 4, UInt(XLEN.W))))
    val new_data = MaskExpand(in.bits.mask) & in.bits.data | MaskExpand(~in.bits.mask) & Mux(in.bits.hit, cacheline(word_offset), recv_data_vec(word_offset))
    
    for (i <- 0 until CachelineSize / 4) {
        new_cacheline(i) := Mux(i.U === word_offset, 
            new_data,
            Mux(in.bits.hit, cacheline(i), recv_data_vec(i))
        )
    }

    data_wr_req.valid := out.valid
    data_wr_req.bits.idx := get_idx(in.bits.addr)
    data_wr_req.bits.way := in.bits.hit_or_evict_way
    data_wr_req.bits.data := new_cacheline

    /* ------------- Forward ------------- */
    io.forward.valid := out.valid
    io.forward.bits.meta := new_meta
    io.forward.bits.data := new_cacheline   
}

class SimpleDCache extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(new DCacheReq))
        val rsp = ValidIO(new DCacheResp)

        val axi = new AXI4
    })

    println(s"DCache: sets = ${sets}, ways = ${ways}, cacheline = ${CachelineSize} bytes, tot = ${sets * ways * CachelineSize} bytes")

    val (req, rsp) = (io.req, io.rsp)
    val axi = io.axi

    val stage1 = Module(new Stage1)
    val stage2 = Module(new Stage2)
    val stage3 = Module(new Stage3)

    val meta_array = Module(new MetaArray)
    val data_array = Module(new DataArray)

    stage1.io.in <> req
    stage1.io.meta_req <> meta_array.io.rd_req
    stage1.io.data_req <> data_array.io.rd_req
    StageConnect(stage1.io.out, stage2.io.in)

    stage2.io.meta_rsp <> meta_array.io.rd_rsp
    stage2.io.data_rsp <> data_array.io.rd_rsp
    StageConnect(stage2.io.out, stage3.io.in)

    stage3.io.axi <> axi
    stage3.io.out <> rsp
    stage3.io.forward <> stage2.io.forward
    stage3.io.meta_wr_req <> meta_array.io.wr_req
    stage3.io.data_wr_req <> data_array.io.wr_req
}