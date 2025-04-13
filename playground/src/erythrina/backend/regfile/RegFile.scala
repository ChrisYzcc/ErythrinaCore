package erythrina.backend.regfile

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.HasErythCoreParams

class RegFilePeeker extends BlackBox with HasBlackBoxInline with HasErythCoreParams {
    val io = IO(new Bundle {
        val idx = Output(UInt(PhyRegAddrBits.W))
        val value = Input(UInt(XLEN.W))
    })
    setInline(s"RegFilePeeker.v",
    s"""module RegFilePeeker(
        |   output logic [${PhyRegAddrBits-1}:0] idx,
        |   input wire [${XLEN-1}:0] value
        |);
        |   export "DPI-C" function peek_regfile;
        |   
        |   function void peek_regfile(
        |       input logic [${PhyRegAddrBits-1}:0] reg_idx,
        |       output logic [${XLEN-1}:0] reg_value
        |   );
        |       assign idx = reg_idx;
        |       reg_value = value;
        |   endfunction
        |endmodule
        """.stripMargin)
}

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
        if (i != 0) assert(!same_addr.reduce(_ || _), s"Write port $i has same address as previous write ports")
    }

    // For Difftest
    val peeker = Module(new RegFilePeeker)
    peeker.io.value := regFile(peeker.io.idx)
}