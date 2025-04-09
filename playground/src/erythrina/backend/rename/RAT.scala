package erythrina.backend.rename

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class RAT extends ErythModule {
    val io = IO(new Bundle {
        // Read Ports
        val rs1 = Vec(RenameWidth, Input(UInt(ArchRegAddrBits.W)))
        val rs2 = Vec(RenameWidth, Input(UInt(ArchRegAddrBits.W)))
        val rd = Vec(RenameWidth, Input(UInt(ArchRegAddrBits.W)))

        val rs1_phy = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))
        val rs2_phy = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))
        val rd_phy = Vec(RenameWidth, Output(UInt(PhyRegAddrBits.W)))

        // Physical Write Ports
        val wr_phy = Vec(RenameWidth, Flipped(ValidIO(new Bundle {
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        })))

        // commit?
        val wr_cmt = Vec(CommitWidth, Flipped(ValidIO(new Bundle {
            val a_reg = UInt(ArchRegAddrBits.W)
            val p_reg = UInt(PhyRegAddrBits.W)
        })))

        // redirect?
    })

    val arch_rat = Reg(Vec(ArchRegNum, UInt(PhyRegAddrBits.W)))
    val phy_rat = Reg(Vec(PhyRegNum, UInt(ArchRegAddrBits.W)))

    when (reset.asBool) {
        for (i <- 0 until ArchRegNum) {
            arch_rat(i) := i.U
        }
        for (i <- 0 until PhyRegNum) {
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

    // TODO: Commit && Redirect
}