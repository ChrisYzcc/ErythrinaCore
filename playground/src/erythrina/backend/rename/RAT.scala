package erythrina.backend.rename

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.Redirect
import erythrina.HasErythCoreParams

class ArchRATPeeker extends BlackBox with HasBlackBoxInline with HasErythCoreParams {
    val io = IO(new Bundle {
        val arch_rat_value = Vec(ArchRegNum, Input(UInt(PhyRegAddrBits.W)))
    })
    
    val portString = io.arch_rat_value.zipWithIndex.map{
        case (phy_reg, i) =>
            s"""
            |   input   [${PhyRegAddrBits-1}:0] arch_rat_value_${i}
            """.stripMargin
    }

    val logicDefString = s"""
        |   logic [${PhyRegAddrBits-1}:0] phy_regs [${ArchRegNum-1}:0];
        """.stripMargin

    val logicAssignString = (0 until ArchRegNum).map{
        i => 
            s"""
            |   assign phy_regs[${i}] = arch_rat_value_${i};
            """.stripMargin
    }

    val verilogString = s"""
    |module ArchRATPeeker(
    |   ${portString.mkString(",\n")}
    |);
    |   ${logicDefString}
    |   ${logicAssignString.mkString("\n")}
    |
    |   export "DPI-C" task set_arch_reg;
    |   export "DPI-C" task get_phy_reg;
    |
    |   logic [${ArchRegAddrBits-1}:0] arch_reg;
    |   task set_arch_reg(input logic [${ArchRegAddrBits-1}:0] idx);
    |       arch_reg = idx;
    |   endtask
    |
    |   task get_phy_reg(output logic [${PhyRegAddrBits-1}:0] value);
    |       value = phy_regs[arch_reg];
    |   endtask
    |endmodule
    """.stripMargin
    setInline("ArchRATPeeker.sv", verilogString)
}

class RAT extends ErythModule {
    val io = IO(new Bundle {
        // Read Ports (From Rename)
        val rs1 = Vec(RenameWidth, Input(UInt(ArchRegAddrBits.W)))
        val rs2 = Vec(RenameWidth, Input(UInt(ArchRegAddrBits.W)))
        val rd = Vec(RenameWidth, Input(UInt(ArchRegAddrBits.W)))

        val rs1_phy = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))
        val rs2_phy = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))
        val rd_phy = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))

        // Physical Write Ports (From Rename)
        val wr_phy = Vec(RenameWidth, Flipped(ValidIO(new Bundle {
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        })))

        // commit (From ROB)
        val wr_cmt = Vec(CommitWidth, Flipped(ValidIO(new Bundle {
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        })))

        // redirect
        val redirect = Flipped(ValidIO(new Redirect))
    })

    val arch_rat = Reg(Vec(ArchRegNum, UInt(PhyRegAddrBits.W)))
    val phy_rat = Reg(Vec(ArchRegNum, UInt(PhyRegAddrBits.W)))

    when (reset.asBool) {
        for (i <- 0 until ArchRegNum) {
            arch_rat(i) := i.U
            phy_rat(i) := i.U
        }
    }

    // Physical RAT
    val (rs1, rs2, rd) = (io.rs1, io.rs2, io.rd)
    val (rs1_phy, rs2_phy, rd_phy) = (io.rs1_phy, io.rs2_phy, io.rd_phy)
    for (i <- 0 until RenameWidth) {
        rs1_phy(i)  := phy_rat(rs1(i))
        rs2_phy(i)  := phy_rat(rs2(i))
        rd_phy(i)   := phy_rat(rd(i))
    }

    val wr_phy = io.wr_phy
    for (i <- 0 until RenameWidth) {
        when(wr_phy(i).valid) {
            phy_rat(wr_phy(i).bits.a_reg) := wr_phy(i).bits.p_reg
        }
    }

    // Commit
    val wr_cmt = io.wr_cmt
    for (i <- 0 until CommitWidth) {
        when(wr_cmt(i).valid) {
            arch_rat(wr_cmt(i).bits.a_reg) := wr_cmt(i).bits.p_reg
        }
    }

    // Redirect
    val redirect = io.redirect
    when(redirect.valid) {
        for (i <- 0 until ArchRegNum) {
            phy_rat(i) := arch_rat(i)
        }
    }

    // Peeker for difftest
    val peeker = Module(new ArchRATPeeker)
    for (i <- 0 until ArchRegNum) {
        peeker.io.arch_rat_value(i) := arch_rat(i)
    }
    
}