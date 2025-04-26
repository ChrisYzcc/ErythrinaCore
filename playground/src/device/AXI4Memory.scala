/**
  * Reference: OpenXiangShan
  */

package device

import chisel3._
import chisel3.util._
import chisel3.experimental.{ExtModule, prefix}
import bus.axi4._

class MemReqHelper extends BlackBox with HasBlackBoxInline {
	val clock = IO(Input(Clock()))
	val reset = IO(Input(Reset()))
	val io = IO(new Bundle {
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
			|   input io_req_valid,
			|   input [31:0] io_req_bits_addr,
			|   input [3:0] io_req_bits_id,
			|   input io_req_bits_is_write,
			|	output reg io_rsp
			|);
			|	import "DPI-C" function bit mem_req(
			|		input int address,
			|		input int id,
			|		input bit is_write
			|	);
			|
			|	always @(posedge clock or posedge reset) begin
			|		if (reset) begin
			|			io_rsp <= 1'b0;
			|		end
			|		else begin
			|			if (io_req_valid) begin
			|				io_rsp <= mem_req(io_req_bits_addr, io_req_bits_id, io_req_bits_is_write);	
			|			end
			|			else begin
			|				io_rsp <= 1'b0;
			|			end
			|		end
			|	end
			endmodule""".stripMargin)
}

class MemRspHelper extends BlackBox with HasBlackBoxInline {
	val clock = IO(Input(Clock()))
	val reset = IO(Input(Reset()))
	val io = IO(new Bundle {
		val enable = Input(Bool())
		val is_write = Input(Bool())
		val response = Output(UInt(64.W))
	})

	setInline("MemRspHelper.v",
		s"""module MemRspHelper(
			|	input clock,
			|	input reset,
			|   input io_enable,
			|   input io_is_write,
			|   output reg [63:0] io_response
			|);
			|	
			|	import "DPI-C" function longint mem_rsp(input bit is_write);
			|	
			|	always @(posedge clock or posedge reset) begin
			|		if (reset) begin
			|			io_response <= 64'b0;
			|		end
			|		else begin
			|			if (!reset && io_enable) begin
			|				io_response <= mem_rsp(io_is_write);
			|			end
			|			else begin
			|				io_response <= 64'b0;
			|			end
			|		end
			|	end
			endmodule""".stripMargin)
}


class AXI4Memory extends Module {
	val mem_req_helper = Module(new MemReqHelper)
	val mem_rsp_helper = Module(new MemRspHelper)
	val mem_rd_helper = Module(new MemReadHelpler)
	val mem_wr_helper = Module(new MemWriteHelper)

	def readRequest(valid:Bool, addr:UInt, id:UInt): Bool = {
		mem_req_helper.clock := clock
		mem_req_helper.reset := reset
		mem_req_helper.io.req.valid := valid
		mem_req_helper.io.req.bits.addr := addr
		mem_req_helper.io.req.bits.id := id
		mem_req_helper.io.req.bits.is_write := false.B
		mem_req_helper.io.rsp
	}

	def writeRequest(valid:Bool, addr:UInt, id:UInt): Bool = {
		mem_req_helper.clock := clock
		mem_req_helper.reset := reset
		mem_req_helper.io.req.valid := valid
		mem_req_helper.io.req.bits.addr := addr
		mem_req_helper.io.req.bits.id := id
		mem_req_helper.io.req.bits.is_write := true.B
		mem_req_helper.io.rsp
	}

	def readResponse(enable:Bool): (Bool, UInt) = {
		mem_rsp_helper.clock := clock
		mem_rsp_helper.reset := reset
		mem_rsp_helper.io.enable := enable
		mem_rsp_helper.io.is_write := false.B
		val response = mem_rsp_helper.io.response
		(response(32), response(31, 0))
	}

	def writeResponse(enable:Bool): (Bool, UInt) = {
		mem_rsp_helper.clock := clock
		mem_rsp_helper.reset := reset
		mem_rsp_helper.io.enable := enable
		mem_rsp_helper.io.is_write := true.B
		val response = mem_rsp_helper.io.response
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

	val axi = IO(new AXI4)

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
				rState := rIDLE
			}
		}
	}

	val rd_task = RegInit(0.U.asTypeOf(axi.ar.bits))
	when (axi.ar.fire) {
		rd_task := axi.ar.bits
	}

	// AR
	axi.ar.ready := rState === rIDLE

	// DDR
	ddr_rd_req_ready := readRequest(rState === rWAIT_DRAM, rd_task.addr, rd_task.id)
	ddr_rd_rsp_valid := readResponse(rState === rREQ)._1

	// Get Data
	val rd_data = readData(rState === rREQ && ddr_rd_rsp_valid, rd_task.addr)

	// R
	axi.r.valid := rState === rRESP
	axi.r.bits := 0.U.asTypeOf(axi.r.bits)
	axi.r.bits.id := rd_task.id
	axi.r.bits.data := rd_data

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