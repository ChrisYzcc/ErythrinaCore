package erythrina.frontend.icache

import chisel3._
import chisel3.util._
import top.Config._
import erythrina.{ErythBundle, ErythModule}
import erythrina.frontend.icache.ICacheParams.CachelineSize
import bus.axi4._

class ICacheMeta extends ErythBundle {
    val valid = Bool()
    val tag = UInt(ICacheParams.TagLen.W)
}

class ICache extends ErythModule {
    val io = IO(new Bundle {
        val req = Flipped(DecoupledIO(UInt(XLEN.W)))
        val rsp = ValidIO(UInt((CachelineSize * 8).W))

        val axi = new AXI4
    })

    val pft_pipeline = Module(new PrefetchPipe)
    val main_pipeline = Module(new MainPipe)

    val meta_array = Module(new MetaArray)
    val data_array = Module(new DataArray)

    val fetcher = Module(new Fetcher)
    val prefetcher = Module(new Prefetcher)

    val replacer = Module(new Replacer)

    val fetcher_req_arb = Module(new Arbiter(new FetcherReq, 2))

    fetcher_req_arb.io.in(0).valid := main_pipeline.io.fetcher_req.valid
    fetcher_req_arb.io.in(0).bits.addr := main_pipeline.io.fetcher_req.bits
    fetcher_req_arb.io.in(0).bits.from_mainpipe := true.B
    main_pipeline.io.fetcher_req.ready := fetcher_req_arb.io.in(0).ready

    fetcher_req_arb.io.in(1).valid := pft_pipeline.io.fetcher_req.valid
    fetcher_req_arb.io.in(1).bits.addr := pft_pipeline.io.fetcher_req.bits
    fetcher_req_arb.io.in(1).bits.from_mainpipe := false.B
    pft_pipeline.io.fetcher_req.ready := fetcher_req_arb.io.in(1).ready

    main_pipeline.io.req <> io.req
    main_pipeline.io.rsp <> io.rsp
    main_pipeline.io.meta_req <> meta_array.io.rd_req(0)
    main_pipeline.io.meta_rsp <> meta_array.io.rd_rsp(0)
    main_pipeline.io.data_req <> data_array.io.rd_req
    main_pipeline.io.data_rsp <> data_array.io.rd_rsp
    main_pipeline.io.fetcher_rsp.valid := fetcher.io.rsp.valid && fetcher.io.rsp_to_mainpipe
    main_pipeline.io.fetcher_rsp.bits := fetcher.io.rsp.bits
    main_pipeline.io.replacer_upt_req <> replacer.io.update_req(0)
    main_pipeline.io.fwd_info <> pft_pipeline.io.fwd_info
    main_pipeline.io.pft_hint <> prefetcher.io.pft_hint

    pft_pipeline.io.req <> prefetcher.io.pft_req
    pft_pipeline.io.meta_req <> meta_array.io.rd_req(1)
    pft_pipeline.io.meta_rsp <> meta_array.io.rd_rsp(1)
    pft_pipeline.io.fetcher_rsp.valid := fetcher.io.rsp.valid && !fetcher.io.rsp_to_mainpipe
    pft_pipeline.io.fetcher_rsp.bits := fetcher.io.rsp.bits
    pft_pipeline.io.fwd_info <> main_pipeline.io.fwd_info

    fetcher.io.req <> fetcher_req_arb.io.out
    fetcher.io.data_wr_req <> data_array.io.wr_req
    fetcher.io.meta_wr_req <> meta_array.io.wr_req
    fetcher.io.replacer_req_idx <> replacer.io.query_idx
    fetcher.io.replacer_rsp_way <> replacer.io.query_way
    fetcher.io.replacer_upt_req <> replacer.io.update_req(1)
    fetcher.io.axi <> io.axi
}