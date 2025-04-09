package erythrina.backend.regfile

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class BusyTable extends ErythModule {
    val io = IO(new Bundle {
        val readPorts = Vec(DispatchWidth, new Bundle{
            val rs1 = Input(UInt(PhyRegAddrBits.W))
            val rs2 = Input(UInt(PhyRegAddrBits.W))
            val rs1_busy = Output(Bool())
            val rs2_busy = Output(Bool())
        })

        val alloc = Vec(RenameWidth, Flipped(ValidIO(UInt(PhyRegAddrBits.W))))
        val free  = Vec(CommitWidth, Flipped(ValidIO(UInt(PhyRegAddrBits.W))))
    })

    val busy_table = RegInit(VecInit(Seq.fill(PhyRegNum)(false.B)))

    // read
    for (i <- 0 until DispatchWidth) {
        io.readPorts(i).rs1_busy := busy_table(io.readPorts(i).rs1)
        io.readPorts(i).rs2_busy := busy_table(io.readPorts(i).rs2)
    }

    // alloc
    for (i <- 0 until RenameWidth) {
        when(io.alloc(i).valid) {
            busy_table(io.alloc(i).bits) := true.B
        }
    }

    // free
    for (i <- 0 until CommitWidth) {
        when(io.free(i).valid) {
            busy_table(io.free(i).bits) := false.B
        }
    }
    
}