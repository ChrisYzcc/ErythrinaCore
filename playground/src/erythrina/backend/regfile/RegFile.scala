package erythrina.backend.regfile

import chisel3._
import chisel3.util._
import erythrina.ErythModule
import erythrina.HasErythCoreParams
import top.Config

class RegFilePeeker extends BlackBox with HasBlackBoxInline with HasErythCoreParams {
    val io = IO(new Bundle {
        val rf_value_vec = Vec(PhyRegNum, Input(UInt(XLEN.W)))
    })

    val portString = io.rf_value_vec.zipWithIndex.map{
        case (rf_value, i) =>
            s"""
            |   input   [${XLEN-1}:0] rf_value_vec_${i}
            """.stripMargin
    }

    val logicDefString = s"""
        |   logic [${XLEN-1}:0] rf_values [${PhyRegNum-1}:0];
        """.stripMargin

    val logicAssignString = (0 until PhyRegNum).map{
        i => 
            s"""
            |   assign rf_values[${i}] = rf_value_vec_${i};
            """.stripMargin
    }

    val verilogString = s"""
    |module RegFilePeeker(
    |   ${portString.mkString(",\n")}
    |);
    |   ${logicDefString}
    |   ${logicAssignString.mkString("\n")}
    |
    |   export "DPI-C" task set_rf_idx;
    |   export "DPI-C" task get_rf_value;
    |   
    |   logic [${PhyRegAddrBits-1}:0] rf_idx;
    |   task set_rf_idx(input logic [${PhyRegAddrBits-1}:0] idx);
    |       rf_idx = idx;
    |   endtask
    |
    |   task get_rf_value(output logic [${XLEN-1}:0] value);
    |       value = rf_values[rf_idx];
    |   endtask
    |endmodule
    """.stripMargin

    setInline("RegFilePeeker.sv", verilogString)
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
    if (!Config.isTiming) {
        val peeker = Module(new RegFilePeeker)
        for (i <- 0 until PhyRegNum) {
            peeker.io.rf_value_vec(i) := regFile(i)
        }
    }   
}