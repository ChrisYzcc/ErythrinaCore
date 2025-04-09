package erythrina.backend.regfile

import chisel3._
import chisel3.util._
import erythrina.ErythModule

class RegFile(numReadPorts: Int, numWritePorts: Int) extends ErythModule {
    val io = IO(new Bundle {
        val readPorts = Vec(numReadPorts, new Bundle {
            val addr = Input(UInt(PhyRegAddrBits.W))
            val data = Output(UInt(XLEN.W))
        })
        val writePorts = Vec(numWritePorts, Flipped(ValidIO(new Bundle {
            val addr = UInt(PhyRegAddrBits.W)
            val data = UInt(XLEN.W)
        })))
    })

    val regFile = RegInit(VecInit(Seq.fill(PhyRegNum)(0.U(XLEN.W))))

    // Read Ports
    for (i <- 0 until numReadPorts) {
        io.readPorts(i).data := regFile(io.readPorts(i).addr)
    }

    // Write Ports
    for (i <- 0 until numWritePorts) {
        when(io.writePorts(i).valid) {
            regFile(io.writePorts(i).bits.addr) := io.writePorts(i).bits.data
        }
    }
    
    for (i <- 0 until numWritePorts) {
        val pre_v = io.writePorts.take(i).map(_.valid)
        val pre_addr = io.writePorts.take(i).map(_.bits.addr)

        val same_addr = pre_v.zip(pre_addr).map{
            case (v, addr) =>
                v && io.writePorts(i).valid && (addr === io.writePorts(i).bits.addr)
        }
        assert(!same_addr.reduce(_ || _), s"Write port $i has same address as previous write ports")
    }
}