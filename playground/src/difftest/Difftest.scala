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

    val mem_en = Bool()
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
            val diff_infos = Vec(CommitWidth, Flipped(ValidIO(new DifftestInfos)))
        })

        val portString = io.diff_infos.zipWithIndex.map{
            case (info, i) =>
                s"""
                |   input   diff_infos_${i}_valid,
                |   input   [${XLEN-1}:0] diff_infos_${i}_bits_pc,
                |   input   [${XLEN-1}:0] diff_infos_${i}_bits_inst,
                |   input   diff_infos_${i}_bits_rf_wen,
                |   input   [${ArchRegAddrBits-1}:0] diff_infos_${i}_bits_rf_waddr,
                |   input   [${XLEN-1}:0] diff_infos_${i}_bits_rf_wdata,
                |   input   diff_infos_${i}_bits_mem_en,
                |   input   [${XLEN-1}:0] diff_infos_${i}_bits_mem_addr,
                |   input   [${XLEN-1}:0] diff_infos_${i}_bits_mem_data,
                |   input   [${MASKLEN-1}:0] diff_infos_${i}_bits_mem_mask
                """.stripMargin
        }

        val logicDefString = s"""
        |   logic valids [${CommitWidth-1}:0];
        |   logic [${XLEN-1}:0] pcs [${CommitWidth-1}:0];
        |   logic [${XLEN-1}:0] insts [${CommitWidth-1}:0];
        |   logic rf_wens [${CommitWidth-1}:0];
        |   logic [${ArchRegAddrBits-1}:0] rf_waddrs [${CommitWidth-1}:0];
        |   logic [${XLEN-1}:0] rf_wdatas [${CommitWidth-1}:0];
        |   logic mem_ens [${CommitWidth-1}:0];
        |   logic [${XLEN-1}:0] mem_addrs [${CommitWidth-1}:0];
        |   logic [${XLEN-1}:0] mem_datas [${CommitWidth-1}:0];
        |   logic [${MASKLEN-1}:0] mem_masks [${CommitWidth-1}:0];
        """.stripMargin

        val logicAssignString = (0 until CommitWidth).map{i =>
            s"""
            |   assign valids[${i}] = diff_infos_${i}_valid;
            |   assign pcs[${i}] = diff_infos_${i}_bits_pc;
            |   assign insts[${i}] = diff_infos_${i}_bits_inst;
            |   assign rf_wens[${i}] = diff_infos_${i}_bits_rf_wen;
            |   assign rf_waddrs[${i}] = diff_infos_${i}_bits_rf_waddr;
            |   assign rf_wdatas[${i}] = diff_infos_${i}_bits_rf_wdata;
            |   assign mem_ens[${i}] = diff_infos_${i}_bits_mem_en;
            |   assign mem_addrs[${i}] = diff_infos_${i}_bits_mem_addr;
            |   assign mem_datas[${i}] = diff_infos_${i}_bits_mem_data;
            |   assign mem_masks[${i}] = diff_infos_${i}_bits_mem_mask;
            """.stripMargin
        }

        val verilogString = s"""
        |module Messager (
        |   ${portString.mkString(",\n")}
        |);
        |   ${logicDefString}
        |   ${logicAssignString.mkString("\n")}
        |
        |   export "DPI-C" task set_diff_idx;
        |   export "DPI-C" task get_diff_info;
        |   
        |   logic [${log2Ceil(CommitWidth)-1}:0] diff_idx;
        |   task set_diff_idx(input logic [${log2Ceil(CommitWidth)-1}:0] idx);
        |       diff_idx = idx;
        |   endtask
        |
        |   task get_diff_info(
        |       output logic valid,
        |       output logic [${XLEN-1}:0] pc,
        |       output logic [${XLEN-1}:0] inst,
        |       output logic rf_wen,
        |       output logic [${ArchRegAddrBits-1}:0] rf_waddr,
        |       output logic [${XLEN-1}:0] rf_wdata,
        |       output logic mem_en,
        |       output logic [${XLEN-1}:0] mem_addr,
        |       output logic [${XLEN-1}:0] mem_data,
        |       output logic [${MASKLEN-1}:0] mem_mask
        |   );
        |       valid = valids[diff_idx];
        |       pc = pcs[diff_idx];
        |       inst = insts[diff_idx];
        |       rf_wen = rf_wens[diff_idx];
        |       rf_waddr = rf_waddrs[diff_idx];
        |       rf_wdata = rf_wdatas[diff_idx];
        |       mem_en = mem_ens[diff_idx];
        |       mem_addr = mem_addrs[diff_idx];
        |       mem_data = mem_datas[diff_idx];
        |       mem_mask = mem_masks[diff_idx];
        |   endtask
        |endmodule
        """.stripMargin

        setInline("Messager.v", verilogString)
        
    }

    val messager = Module(new Messager)
    messager.io.diff_infos <> RegNext(diff_infos)
}