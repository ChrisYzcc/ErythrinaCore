package difftest

import chisel3._
import chisel3.util._

trait HasDiffParams extends erythrina.HasErythCoreParams{
}

abstract class DifftestBundle extends Bundle with HasDiffParams {
}

abstract class DifftestModule extends Module with HasDiffParams {
}

class DifftestInfos extends DifftestBundle {
    val pc = UInt(XLEN.W)
    val inst = UInt(XLEN.W)

    val rf_wen = Bool()
    val rf_waddr = UInt(ArchRegAddrBits.W)
    val rf_wdata = UInt(XLEN.W)

    val mem_wen = Bool()
    val mem_addr = UInt(XLEN.W)
    val mem_data = UInt(XLEN.W)
    val mem_mask = UInt(MASKLEN.W)
}

class DifftestBox extends DifftestModule {
    val io = IO(new Bundle {
        val diff_infos = Vec(CommitWidth, Flipped(ValidIO(new DifftestInfos)))
    })

    val diff_infos = io.diff_infos

    class Messager extends BlackBox with HasBlackBoxInline with HasDiffParams{
        val io = IO(new Bundle {
            val diff_idx = Output(UInt(log2Ceil(CommitWidth).W))
            val diff_info = Flipped(ValidIO(new DifftestInfos))
        })
        setInline(s"Messager.v",
        s"""module Messager(
        |   output logic [${log2Ceil(CommitWidth)-1}:0] diff_idx,
        |   input wire diff_info_valid,
        |   input wire [${XLEN-1}:0] diff_info_bits_pc,
        |   input wire [${XLEN-1}:0] diff_info_bits_inst,
        |   input wire diff_info_bits_rf_wen,
        |   input wire [${ArchRegAddrBits-1}:0] diff_info_bits_rf_waddr,
        |   input wire [${XLEN-1}:0] diff_info_bits_rf_wdata,
        |   input wire diff_info_bits_mem_wen,
        |   input wire [${XLEN-1}:0] diff_info_bits_mem_addr,
        |   input wire [${XLEN-1}:0] diff_info_bits_mem_data,
        |   input wire [${MASKLEN-1}:0] diff_info_bits_mem_mask
        |);
        |   export "DPI-C" function read_diff_info;
        |   
        |   function void read_diff_info(
        |       input logic [${log2Ceil(CommitWidth)-1}:0] idx,
        |       output logic valid,
        |       output logic [${XLEN-1}:0] pc,
        |       output logic [${XLEN-1}:0] inst,
        |       output logic rf_wen,
        |       output logic [${ArchRegAddrBits-1}:0] rf_waddr,
        |       output logic [${XLEN-1}:0] rf_wdata,
        |       output logic mem_wen,
        |       output logic [${XLEN-1}:0] mem_addr,
        |       output logic [${XLEN-1}:0] mem_data,
        |       output logic [${MASKLEN-1}:0] mem_mask
        |   );
        |       assign diff_idx = idx;
        |       valid = diff_info_valid;
        |       pc = diff_info_bits_pc;
        |       inst = diff_info_bits_inst;
        |       rf_wen = diff_info_bits_rf_wen;
        |       rf_waddr = diff_info_bits_rf_waddr;
        |       rf_wdata = diff_info_bits_rf_wdata;
        |       mem_wen = diff_info_bits_mem_wen;
        |       mem_addr = diff_info_bits_mem_addr;
        |       mem_data = diff_info_bits_mem_data;
        |       mem_mask = diff_info_bits_mem_mask;
        |   endfunction
        |endmodule
        """.stripMargin) 
    }

    val messager = Module(new Messager)

    val req_idx = messager.io.diff_idx
    messager.io.diff_info := io.diff_infos(req_idx)
}