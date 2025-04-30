/**
  * Reference: OpenXiangShan
  */

package device

import chisel3._
import chisel3.util._
import chisel3.experimental.{ExtModule, prefix}
import bus.axi4._

class MemReqHelper extends BlackBox with HasBlackBoxInline {
	val io = IO(new Bundle {
		val clock = Input(Clock())
		val reset = Input(Reset())
		val req = Flipped(ValidIO(new Bundle {
			val addr = UInt(AXI4Params.addrBits.W)
			val id = UInt(AXI4Params.idBits.W)
			val is_write = Bool()
		}))
		val rsp = Output(Bool())
	})

	setInline("MemReqHelper.v",
		s"""module MemReqHelper(
			|	input clock,
			|	input reset,
			|   input req_valid,
			|   input [31:0] req_bits_addr,
			|   input [3:0] req_bits_id,
			|   input req_bits_is_write,
			|	output reg rsp
			|);
			|	import "DPI-C" function bit mem_req(
			|		input int address,
			|		input int id,
			|		input bit is_write
			|	);
			|
			|	always @(posedge clock or posedge reset) begin
			|		if (reset) begin
			|			rsp <= 1'b0;
			|		end
			|		else begin
			|			if (req_valid) begin
			|				rsp <= mem_req(req_bits_addr, {28'b0, req_bits_id}, req_bits_is_write);	
			|			end
			|			else begin
			|				rsp <= 1'b0;
			|			end
			|		end
			|	end
			endmodule""".stripMargin)
}

class MemRspHelper extends BlackBox with HasBlackBoxInline {
	val io = IO(new Bundle {
		val clock = Input(Clock())
		val reset = Input(Reset())
		val enable = Input(Bool())
		val is_write = Input(Bool())
		val response = Output(UInt(64.W))
	})

	setInline("MemRspHelper.v",
		s"""module MemRspHelper(
			|	input clock,
			|	input reset,
			|   input enable,
			|   input is_write,
			|   output reg [63:0] response
			|);
			|	
			|	import "DPI-C" function longint mem_rsp(input bit is_write);
			|	
			|	always @(posedge clock or posedge reset) begin
			|		if (reset) begin
			|			response <= 64'b0;
			|		end
			|		else begin
			|			if (!reset && enable) begin
			|				response <= mem_rsp(is_write);
			|			end
			|			else begin
			|				response <= 64'b0;
			|			end
			|		end
			|	end
			endmodule""".stripMargin)
}


class AXI4Memory extends Module {
	val mem_rd_req_helper = Module(new MemReqHelper)
	val mem_rd_rsp_helper = Module(new MemRspHelper)
	val mem_wr_req_helper = Module(new MemReqHelper)
	val mem_wr_rsp_helper = Module(new MemRspHelper)
	val mem_rd_helper = Module(new MemReadHelpler)
	val mem_wr_helper = Module(new MemWriteHelper)

	def readRequest(valid:Bool, addr:UInt, id:UInt): Bool = {
		mem_rd_req_helper.io.clock := clock
		mem_rd_req_helper.io.reset := reset
		mem_rd_req_helper.io.req.valid := valid
		mem_rd_req_helper.io.req.bits.addr := addr
		mem_rd_req_helper.io.req.bits.id := id
		mem_rd_req_helper.io.req.bits.is_write := false.B
		mem_rd_req_helper.io.rsp
	}

	def writeRequest(valid:Bool, addr:UInt, id:UInt): Bool = {
		mem_wr_req_helper.io.clock := clock
		mem_wr_req_helper.io.reset := reset
		mem_wr_req_helper.io.req.valid := valid
		mem_wr_req_helper.io.req.bits.addr := addr
		mem_wr_req_helper.io.req.bits.id := id
		mem_wr_req_helper.io.req.bits.is_write := true.B
		mem_wr_req_helper.io.rsp
	}

	def readResponse(enable:Bool): (Bool, UInt) = {
		mem_rd_rsp_helper.io.clock := clock
		mem_rd_rsp_helper.io.reset := reset
		mem_rd_rsp_helper.io.enable := enable
		mem_rd_rsp_helper.io.is_write := false.B
		val response = mem_rd_rsp_helper.io.response
		(response(32), response(31, 0))
	}

	def writeResponse(enable:Bool): (Bool, UInt) = {
		mem_wr_rsp_helper.io.clock := clock
		mem_wr_rsp_helper.io.reset := reset
		mem_wr_rsp_helper.io.enable := enable
		mem_wr_rsp_helper.io.is_write := true.B
		val response = mem_wr_rsp_helper.io.response
		(response(32), response(31, 0))
	}

	def readData(enable:Bool, addr:UInt): UInt = {
		mem_rd_helper.io.clock := clock
		mem_rd_helper.io.reset := reset
		mem_rd_helper.io.port_req.valid := enable
		mem_rd_helper.io.port_req.bits := addr
		mem_rd_helper.io.port_rsp.bits
	}

	def writeData(enable:Bool, addr:UInt, data:UInt, mask:UInt) = {
		mem_wr_helper.io.clock := clock
		mem_wr_helper.io.reset := reset
		mem_wr_helper.io.port_req.valid := enable
		mem_wr_helper.io.port_req.bits.addr := addr
		mem_wr_helper.io.port_req.bits.data := data
		mem_wr_helper.io.port_req.bits.mask := mask
	}

	val axi = IO(Flipped(new AXI4))

	/* --------------------- Read --------------------- */
	val ddr_rd_req_ready = Wire(Bool())
	val ddr_rd_rsp_valid = Wire(Bool())

	val rIDLE :: rWAIT_DRAM :: rREQ :: rRESP :: Nil = Enum(4)
	val rState = RegInit(rIDLE)
	switch (rState) {
		is (rIDLE) {
			when (axi.ar.fire) {
				rState := rWAIT_DRAM
			}
		}
		is (rWAIT_DRAM) {
			when (ddr_rd_req_ready) {
				rState := rREQ
			}
		}
		is (rREQ) {
			when (ddr_rd_rsp_valid) {
				rState := rRESP
			}
		}
		is (rRESP) {
			when (axi.r.fire) {
				when (axi.r.bits.last) {
					rState := rIDLE
				} .otherwise {
					rState := rWAIT_DRAM
				}
			}
		}
	}

	val rd_addr = RegInit(0.U(AXI4Params.addrBits.W))
	val rd_id = RegInit(0.U(AXI4Params.idBits.W))

	when (axi.ar.fire) {
		rd_addr := axi.ar.bits.addr
		rd_id := axi.ar.bits.id
	}.elsewhen(rState === rREQ && ddr_rd_rsp_valid) {
		rd_addr := rd_addr + 4.U
	}

	val rd_len = RegInit(0.U(AXI4Params.lenBits.W))
	when (axi.ar.fire) {
		rd_len := axi.ar.bits.len
	}.elsewhen(axi.r.fire) {
		rd_len := rd_len - 1.U
	}

	// AR
	axi.ar.ready := rState === rIDLE

	assert(!axi.ar.valid || axi.ar.bits.len <= 7.U)
	assert(!axi.ar.valid || axi.ar.bits.size === "b010".U)

	// DDR
	ddr_rd_req_ready := readRequest(rState === rWAIT_DRAM, rd_addr, rd_id)
	ddr_rd_rsp_valid := readResponse(rState === rREQ)._1

	// Get Data
	val rd_data = readData(rState === rREQ && ddr_rd_rsp_valid, rd_addr)

	// R
	axi.r.valid := rState === rRESP
	axi.r.bits := 0.U.asTypeOf(axi.r.bits)
	axi.r.bits.id := rd_id
	axi.r.bits.data := rd_data
	axi.r.bits.last := rd_len === 0.U

	/* --------------------- Write --------------------- */
	val ddr_wr_req_ready = Wire(Bool())
	val ddr_wr_rsp_valid = Wire(Bool())

	val wIDLE :: wWAIT_DRAM :: wREQ :: wRESP :: Nil = Enum(4)
	val wState = RegInit(wIDLE)
	switch (wState) {
		is (wIDLE) {
			when (axi.aw.fire && axi.w.fire) {
				wState := wWAIT_DRAM
			}
		}
		is (wWAIT_DRAM) {
			when (ddr_wr_req_ready) {
				wState := wREQ
			}
		}
		is (wREQ) {
			when (ddr_wr_rsp_valid) {
				wState := wRESP
			}
		}
		is (wRESP) {
			when (axi.b.fire) {
				wState := wIDLE
			}
		}
	}

	val aw_task = RegInit(0.U.asTypeOf(axi.aw.bits))
	val w_task = RegInit(0.U.asTypeOf(axi.w.bits))
	when (axi.aw.fire) {
		aw_task := axi.aw.bits
	}
	when (axi.w.fire) {
		w_task := axi.w.bits
	}

	// AW & W
	axi.aw.ready := axi.aw.valid && axi.w.valid && wState === wIDLE
	axi.w.ready := axi.aw.valid && axi.w.valid && wState === wIDLE

	// DDR
	ddr_wr_req_ready := writeRequest(wState === wWAIT_DRAM, aw_task.addr, aw_task.id)
	ddr_wr_rsp_valid := writeResponse(wState === wREQ)._1

	// Write
	writeData(wState === wREQ && ddr_wr_rsp_valid, aw_task.addr, w_task.data, w_task.strb)

	// B
	axi.b.valid := wState === wRESP
	axi.b.bits := 0.U.asTypeOf(axi.b.bits)
	axi.b.bits.id := aw_task.id
}