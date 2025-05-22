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

}

// Stage1: Req for Meta
class Stage1 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new DCacheReq))
        val out = DecoupledIO(new SimpleDCacheTask)

        val meta_rd_req = ValidIO(UInt(log2Ceil(sets).W))
    })

    val (in, out) = (io.in, io.out)
    val meta_rd_req = io.meta_rd_req

    val task = WireInit(0.U.asTypeOf(new SimpleDCacheTask))
    task.content_valid := in.valid
    task.addr := in.bits.addr
    task.data := in.bits.data
    task.mask := in.bits.mask
    task.cmd := in.bits.cmd
    
    meta_rd_req.valid := in.valid && out.ready
    meta_rd_req.bits := get_idx(task.addr)

    in.ready := out.ready
    out.valid := in.valid
    out.bits := task
}

// Stage2: Check for Hit & Req for Data
class Stage2 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new SimpleDCacheTask))
        val out = DecoupledIO(new SimpleDCacheTask)

        val meta_rd_rsp = Flipped(ValidIO(Vec(ways, new MetaEntry)))
        val data_rd_req = ValidIO(UInt(log2Ceil(sets).W))

        val forward = Flipped(ValidIO(new Bundle {
            val fwd_meta = new MetaEntry
            val fwd_way = UInt(log2Ceil(ways).W)
        }))
    })

    val (in, out) = (io.in, io.out)
    val meta_rd_rsp = io.meta_rd_rsp
    val data_rd_req = io.data_rd_req

    val plru_seq = Seq.fill(sets)(Module(new PLRU))
    val oldest_way_vec = VecInit(plru_seq.map(_.io.oldest))

    val idx = get_idx(in.bits.addr)

    // check for hit
    val meta_hit_vec = meta_rd_rsp.bits.map{
        case m => 
            m.valid && (m.tag === get_tag(in.bits.addr))
    }
    val meta_hit = meta_hit_vec.reduce(_ || _)
    val meta_hit_way = PriorityEncoder(meta_hit_vec)
    assert(PopCount(meta_hit_vec) <= 1.U, "More than one hit in meta array")

    val fwd = io.forward
    val fwd_hit = (fwd.bits.fwd_meta.tag === get_tag(in.bits.addr))
    val fwd_hit_way = fwd.bits.fwd_way

    val hit = meta_hit || fwd_hit
    val hit_way = Mux(fwd_hit, fwd_hit_way, meta_hit_way)
    val hit_meta = Mux(meta_hit, meta_rd_rsp.bits(meta_hit_way), fwd.bits.fwd_meta)

    val cascheable = in.bits.addr >= CacheableRange._1.U && in.bits.addr <= CacheableRange._2.U

    // evict & update
    val evict_way = oldest_way_vec(idx)
    val evict_meta = meta_rd_rsp.bits(evict_way)

    for (i <- 0 until sets) {
        plru_seq(i).io.update.valid := i.U === idx && in.bits.content_valid
        plru_seq(i).io.update.bits := Mux(hit, hit_way, evict_way)
    }

    // req for data
    data_rd_req.valid := in.bits.content_valid && out.ready
    data_rd_req.bits := idx

    val task = WireInit(in.bits)
    task.hit := hit
    task.cacheable := cascheable
    task.hit_or_evict_meta := Mux(hit, hit_meta, evict_meta)
    task.hit_or_evict_way := Mux(hit, hit_way, evict_way)

    in.ready := out.ready
    out.valid := true.B
    out.bits := task
}

// Stage3: response for hit, refill for miss
class Stage3 extends ErythModule {
    val io = IO(new Bundle {
        val in = Flipped(DecoupledIO(new SimpleDCacheTask))
        val out = ValidIO(new DCacheResp)

        val data_rd_rsp = Flipped(ValidIO(Vec(ways, Vec(CachelineSize / 4, UInt(XLEN.W)))))

        val data_wr_req = ValidIO(new Bundle{
            val idx = UInt(log2Ceil(sets).W)
            val way = UInt(log2Ceil(ways).W)
            val data = Vec(CachelineSize / 4, UInt(XLEN.W))
        })
        
        val meta_wr_req = ValidIO(new Bundle{
            val idx = UInt(log2Ceil(sets).W)
            val way = UInt(log2Ceil(ways).W)
            val meta = new MetaEntry
        })

        val forward = ValidIO(new Bundle {
            val fwd_meta = new MetaEntry
            val fwd_way = UInt(log2Ceil(ways).W)
        })

        val axi = new AXI4
    })

    val (in, out) = (io.in, io.out)
    val axi = io.axi

    val is_read = in.bits.cmd === DCacheCMD.READ
    val is_write = in.bits.cmd === DCacheCMD.WRITE
    val hit = in.bits.hit
    val dirty = in.bits.hit_or_evict_meta.dirty
    val cacheable = in.bits.cacheable
    val content_valid = in.bits.content_valid

    val meta = in.bits.hit_or_evict_meta
    val way = in.bits.hit_or_evict_way
    val cacheline = io.data_rd_rsp.bits(way)

    val set_idx = get_idx(in.bits.addr)

    /* ------------- Ctrl Signals ------------- */
    val reset_done = Wire(Bool())
    
    val wr_last = Wire(Bool())
    val ready_to_write = Wire(Bool())

    /* ------------- FSM Ctrl ------------- */
    // main FSM
    val sRESET :: sWORK :: sUPDATE :: Nil = Enum(3)
    val state = RegInit(sRESET)

    switch (state) {
        is (sRESET) {
            when (reset_done) {
                state := sWORK
            }
        }
        is (sWORK) {
            when (content_valid && ready_to_write) {
                state := sUPDATE
            }
        }
        is (sUPDATE) {
            state := sWORK
        }
    }

    // Read FSM
    val sIDLE_R :: sREQ_R :: sRECV_R :: sFINISH_R :: Nil = Enum(4)
    val state_r = RegInit(sIDLE_R)

    switch (state_r) {
        is (sIDLE_R) {
            when (RegNext(in.fire) && (!hit || !cacheable && is_read) && content_valid) {
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
            when (RegNext(in.fire) && (!hit && dirty || !cacheable && is_write) && content_valid) {
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
        ar_addr := Mux(cacheable,
                    get_cacheline_addr(in.bits.addr),
                    in.bits.addr
                )
    }

    val ar_len = RegInit(0.U(AXI4Params.lenBits.W))
    when (RegNext(in.fire)) {
        ar_len := Mux(cacheable,
                    (CachelineSize / 4 - 1).U,
                    0.U
                )
    }

    val r_ptr = RegInit(0.U(log2Ceil(CachelineSize / 4).W))
    when (axi.ar.fire) {
        r_ptr := 0.U
    }.elsewhen (axi.r.fire) {
        r_ptr := r_ptr + 1.U
    }

    axi.ar.valid := state_r === sREQ_R
    axi.ar.bits := 0.U.asTypeOf(axi.ar.bits)
    axi.ar.bits.addr := ar_addr
    axi.ar.bits.len := ar_len
    axi.ar.bits.size := "b010".U

    axi.r.ready := state_r === sRECV_R
    when (axi.r.fire) {
        recv_data_vec(r_ptr) := axi.r.bits.data
    }

    /* ------------- WRITE ------------- */
    val aw_addr = RegInit(0.U(XLEN.W))
    when (RegNext(in.fire)) {
        aw_addr := Mux(cacheable,
                    get_cacheline_addr(in.bits.addr),
                    Cat(meta.tag, set_idx, 0.U(log2Ceil(CachelineSize).W))
                )
    }.elsewhen(axi.b.fire) {
        aw_addr := aw_addr + 4.U
    }

    val w_len = RegInit(0.U(AXI4Params.lenBits.W))
    when (RegNext(in.fire)) {
        w_len := Mux(cacheable,
                    (CachelineSize / 4).U,
                    0.U
                )
    }

    val w_ptr = RegInit(0.U(log2Ceil(CachelineSize / 4).W))
    when (RegNext(in.fire)) {
        w_ptr := 0.U
    }.elsewhen (axi.b.fire) {
        w_ptr := w_ptr + 1.U
    }

    axi.aw.valid := state_w === sREQ_W
    axi.aw.bits := 0.U.asTypeOf(axi.aw.bits)
    axi.aw.bits.addr := aw_addr
    axi.aw.bits.size := "b010".U

    axi.w.valid := state_w === sREQ_W
    axi.w.bits := 0.U.asTypeOf(axi.w.bits)
    axi.w.bits.data := Mux(cacheable, cacheline(w_ptr), in.bits.data)
    axi.w.bits.strb := Mux(cacheable, "b1111".U, in.bits.mask)

    axi.b.ready := state_w === sRECV_W

    wr_last := Mux(cacheable, w_ptr === (CachelineSize / 4 - 1).U, true.B)

    /* ------------- Response to Core & Stage Control ------------- */
    val offset = get_cacheline_offset(in.bits.addr)
    val word_offset = offset(log2Ceil(CachelineSize) - 1, 2)

    val hit_ready = hit
    val hit_data = cacheline(word_offset)

    val miss_ready = !hit && state_r === sFINISH_R && (!meta.dirty || state_w === sFINISH_W)
    val miss_data = recv_data_vec(word_offset)

    val mmio_ready = !cacheable && (is_read && state_r === sFINISH_R || is_write && state_w === sFINISH_W)
    val mmio_data = recv_data_vec(0)

    ready_to_write := hit_ready && is_write || miss_ready || mmio_ready
    out.valid := content_valid && (state === sWORK && hit_ready || state === sUPDATE)

    out.bits.data := MuxCase(0.U, List(
        hit_ready -> hit_data,
        miss_ready -> miss_data,
        mmio_ready -> mmio_data
    ))
    out.bits.cmd := in.bits.cmd

    in.ready := (!content_valid || out.valid) && state =/= sRESET

    /* ------------- Write Meta & Cacheline ------------- */
    val (meta_wr_req, data_wr_req) = (io.meta_wr_req, io.data_wr_req)

    // Meta
    val new_meta = WireInit(0.U.asTypeOf(new MetaEntry))
    new_meta.valid := true.B
    new_meta.dirty := Mux(is_write, true.B, meta.dirty)
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

    meta_wr_req.valid := state === sRESET || state === sWORK && ready_to_write && cacheable
    meta_wr_req.bits.idx := Mux(state === sRESET, reset_idx, get_idx(in.bits.addr))
    meta_wr_req.bits.way := Mux(state === sRESET, reset_way, in.bits.hit_or_evict_way)
    meta_wr_req.bits.meta := Mux(state === sRESET, 0.U.asTypeOf(new MetaEntry), new_meta)

    // Data
    val new_cacheline = WireInit(0.U.asTypeOf(Vec(CachelineSize / 4, UInt(XLEN.W))))
    val new_data = MaskExpand(in.bits.mask) & in.bits.data | MaskExpand(~in.bits.mask) & Mux(in.bits.hit, cacheline(word_offset), recv_data_vec(word_offset))

    for (i <- 0 until CachelineSize / 4) {
        new_cacheline(i) := Mux(i.U === word_offset, 
            new_data,
            Mux(in.bits.hit, cacheline(i), recv_data_vec(i))
        )
    }

    data_wr_req.valid := state === sWORK && ready_to_write && cacheable
    data_wr_req.bits.idx := get_idx(in.bits.addr)
    data_wr_req.bits.way := in.bits.hit_or_evict_way
    data_wr_req.bits.data := new_cacheline

    /* ------------- Forward ------------- */
    io.forward.valid := out.valid
    io.forward.bits.fwd_meta := new_meta
    io.forward.bits.fwd_way := in.bits.hit_or_evict_way
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

    val s1 = Module(new Stage1)
    val s2 = Module(new Stage2)
    val s3 = Module(new Stage3)
    val meta_array = Module(new MetaArray)
    val data_array = Module(new DataArray)

    s1.io.in <> req
    s1.io.meta_rd_req <> meta_array.io.rd_req
    StageConnect(s1.io.out, s2.io.in)

    s2.io.meta_rd_rsp <> meta_array.io.rd_rsp
    s2.io.data_rd_req <> data_array.io.rd_req
    s2.io.forward <> s3.io.forward
    StageConnect(s2.io.out, s3.io.in)

    s3.io.data_rd_rsp <> data_array.io.rd_rsp
    s3.io.meta_wr_req <> meta_array.io.wr_req
    s3.io.data_wr_req <> data_array.io.wr_req
    s3.io.out <> rsp
    s3.io.axi <> axi
}