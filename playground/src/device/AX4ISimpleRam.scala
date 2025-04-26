package device

import chisel3._
import chisel3.util._
import bus.axi4._

class MemReadHelpler extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val reset = Input(Reset())
        val port_req = Flipped(ValidIO(UInt(AXI4Params.addrBits.W)))
        val port_rsp = ValidIO(UInt(AXI4Params.dataBits.W))
    })
    setInline("MemReadHelpler.v",
    s"""module MemReadHelpler(
        |    input clock,
        |    input reset,
        |    input port_req_valid,
        |    input [${AXI4Params.addrBits-1}:0] port_req_bits,
        |    output port_rsp_valid,
        |    output [${AXI4Params.dataBits-1}:0] port_rsp_bits
        |);
        |   import "DPI-C" function int mem_read(input int paddr);
        |   
        |   // FSM
        |   reg [1:0] cur_state, nxt_state;
        |   localparam REQ = 2'b00;
        |   localparam RESP = 2'b01;
        |
        |   always @(posedge clock) begin
        |       if (reset)
        |           cur_state <= REQ;
        |       else
        |           cur_state <= nxt_state;
        |   end
        |
        |   always @(*) begin
        |       case (cur_state)
        |           REQ: begin
        |               if (port_req_valid)
        |                   nxt_state = RESP;
        |           end
        |           RESP: begin
        |               nxt_state = REQ;
        |           end
        |           default: nxt_state = REQ;
        |       endcase
        |   end
        |
        |   assign port_rsp_valid = (cur_state == RESP);
        |
        |   reg [${AXI4Params.dataBits-1}:0] data;
        |   always @(posedge clock) begin
        |       if (port_req_valid) begin
        |           data <= mem_read(port_req_bits);
        |       end
        |   end
        |
        |   assign port_rsp_bits = data;
        |
        |endmodule
    """.stripMargin);
}

class MemWriteHelper extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val clock = Input(Clock())
        val reset = Input(Reset())
        val port_req = Flipped(ValidIO(new Bundle {
            val addr = UInt(AXI4Params.addrBits.W)
            val mask = UInt((AXI4Params.dataBits/8).W)
            val data = UInt(AXI4Params.dataBits.W)
        }))
    })
    setInline("MemWriteHelper.v",
    s"""module MemWriteHelper(
        |    input clock,
        |    input reset,
        |    input port_req_valid,
        |    input [${AXI4Params.addrBits-1}:0] port_req_bits_addr,
        |    input [${AXI4Params.dataBits/8-1}:0] port_req_bits_mask,
        |    input [${AXI4Params.dataBits-1}:0] port_req_bits_data
        |);
        |   import "DPI-C" function void mem_write(input int paddr, input bit[${AXI4Params.dataBits/8-1}:0] mask, input longint data);
        |
        |   always @(posedge clock) begin
        |       if (port_req_valid) begin
        |           mem_write(port_req_bits_addr, port_req_bits_mask, port_req_bits_data);
        |       end
        |   end
        |
        |endmodule
    """.stripMargin);
}

class AX4ISimpleRam[T <: AXI4Lite](_type: T = new AXI4) extends Module {
    val io = IO(new Bundle {
        val axi = Flipped(_type)
    })

    val memRead = Module(new MemReadHelpler)
    val memWrite = Module(new MemWriteHelper)

    memRead.io.clock := clock
    memRead.io.reset := reset.asBool
    memWrite.io.clock := clock
    memWrite.io.reset := reset.asBool

    val axi = io.axi

    /* ------------------- Read -------------------- */
    val rREQ :: rRECV :: Nil = Enum(2)
    val rState = RegInit(rREQ)
    
    switch (rState) {
        is (rREQ) {
            when (axi.ar.fire) {
                rState := rRECV
            }
        }
        is (rRECV) {
            when (axi.r.fire) {
                rState := rREQ
            }
        }
    }

    // AR
    axi.ar.ready := rState === rREQ
    memRead.io.port_req.valid := axi.ar.valid && rState === rREQ
    memRead.io.port_req.bits := axi.ar.bits.addr

    // R
    val r_has_valid = RegInit(false.B)
    val r_has_data = RegInit(0.U(AXI4Params.dataBits.W))
    when (axi.ar.fire) {
        r_has_valid := false.B
    }
    when (memRead.io.port_rsp.valid) {
        r_has_valid := true.B
        r_has_data := memRead.io.port_rsp.bits
    }
    val r_valid = memRead.io.port_rsp.valid || r_has_valid
    val r_data = Mux(r_has_valid, r_has_data, memRead.io.port_rsp.bits)

    axi.r.valid := r_valid && rState === rRECV
    axi.r.bits := DontCare
    axi.r.bits.data := r_data

    /* ------------------- Write ------------------- */
    val wREQ :: wRECV :: Nil = Enum(2)
    val wState = RegInit(wREQ)
    
    val w_has_fire_reg = RegInit(false.B)
    val aw_has_fire_reg = RegInit(false.B)

    val w_addr_reg = RegInit(0.U(AXI4Params.addrBits.W))
    val w_data_reg = RegInit(0.U(AXI4Params.dataBits.W))
    val w_mask_reg = RegInit(0.U((AXI4Params.dataBits/8).W))

    when (axi.b.fire) {
        w_has_fire_reg := false.B
        aw_has_fire_reg := false.B
    }

    when (axi.aw.fire) {
        aw_has_fire_reg := true.B
        w_addr_reg := axi.aw.bits.addr
    }

    when (axi.w.fire) {
        w_has_fire_reg := true.B
        w_data_reg := axi.w.bits.data
        w_mask_reg := axi.w.bits.strb
    }

    val w_has_fire = w_has_fire_reg || axi.w.fire
    val aw_has_fire = aw_has_fire_reg || axi.aw.fire
    val w_addr = Mux(aw_has_fire_reg, w_addr_reg, axi.aw.bits.addr)
    val w_data = Mux(w_has_fire_reg, w_data_reg, axi.w.bits.data)
    val w_mask = Mux(w_has_fire_reg, w_mask_reg, axi.w.bits.strb)

    switch (wState) {
        is (wREQ) {
            when (aw_has_fire && w_has_fire) {
                wState := wRECV
            }
        }
        is (wRECV) {
            when (axi.b.fire) {
                wState := wREQ
            }
        }
    }

    memWrite.io.port_req.valid := wState === wREQ && w_has_fire && aw_has_fire
    memWrite.io.port_req.bits.addr := w_addr
    memWrite.io.port_req.bits.data := w_data
    memWrite.io.port_req.bits.mask := w_mask

    axi.aw.ready := wState === wREQ && !aw_has_fire_reg
    axi.w.ready := wState === wREQ && !w_has_fire_reg
    axi.b.valid := wState === wRECV
    axi.b.bits := DontCare

}