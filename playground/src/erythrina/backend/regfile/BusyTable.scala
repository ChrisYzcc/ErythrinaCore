package erythrina.backend.regfile

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.backend.Redirect

class BusyTable extends ErythModule {
    val io = IO(new Bundle {
        val readPorts = Vec(DispatchWidth, new Bundle{
            val rs1 = Input(UInt(PhyRegAddrBits.W))
            val rs2 = Input(UInt(PhyRegAddrBits.W))
            val rs1_busy = Output(Bool())
            val rs2_busy = Output(Bool())
        })

        val alloc = Vec(DispatchWidth, Flipped(ValidIO(UInt(PhyRegAddrBits.W))))
        val free  = Vec(CommitWithDataWidth, Flipped(ValidIO(UInt(PhyRegAddrBits.W))))

        val redirect = Flipped(ValidIO(new Redirect))
    })

    val busy_table = RegInit(VecInit(Seq.fill(PhyRegNum)(false.B)))

    // read
    for (i <- 0 until DispatchWidth) {
        io.readPorts(i).rs1_busy := busy_table(io.readPorts(i).rs1)
        io.readPorts(i).rs2_busy := busy_table(io.readPorts(i).rs2)
    }

    // alloc (from rename)
    for (i <- 0 until RenameWidth) {
        when(io.alloc(i).valid) {
            busy_table(io.alloc(i).bits) := true.B
        }
    }

    // free (from EXU)
    for (i <- 0 until CommitWithDataWidth) {
        when(io.free(i).valid) {
            busy_table(io.free(i).bits) := false.B
        }
    }

    // redirect
    when(io.redirect.valid) {
        for (i <- 0 until PhyRegNum) {
            busy_table(i) := false.B
        }
    }
    
}