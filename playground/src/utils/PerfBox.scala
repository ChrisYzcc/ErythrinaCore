package utils

import chisel3._
import chisel3.util._
import scala.collection.mutable.ListBuffer
import chisel3.util.experimental.BoringUtils._

class PerfCtrlIO extends Bundle {
    val clean = Bool()
}

object PerfCount {
    private val perfEvents = ListBuffer.empty[(String, UInt)]
    private val perfCounters = ListBuffer.empty[(String, UInt)]
    def apply(event_name:String, event:UInt): Unit = {
        perfEvents += ((event_name, event))
    }
    def collect(perf_ctrl: PerfCtrlIO): Unit = {
        perfEvents.foreach{
            case (name, event) =>
                val perf_event = tapAndRead(event)
                if (perfCounters.exists(_._1 == name)) {
                    val value = perfCounters.find(_._1 == name).get._2
                    value := Mux(perf_ctrl.clean, 0.U, value + perf_event)
                }
                else {
                    val value = RegInit(0.U(64.W))
                    perfCounters += ((name, value))
                    value := Mux(perf_ctrl.clean, 0.U, value + perf_event)
                }
        }
    }
    def print = {
        perfCounters.foreach{
            case (name, value) =>
                printf(p"$name: ${value}\n")
        }
    }
}

object PerfDumpTrigger {
    private val perfDumpTriggers = ListBuffer.empty[(String, UInt)]
    def apply(trigger_name:String, trigger:UInt): Unit = {
        perfDumpTriggers += ((trigger_name, trigger))
    }
    def is_triggered = {
        val trigger_vec = perfDumpTriggers.map{
            case (name, trigger) =>
                tapAndRead(trigger).asBool
        }
        if (trigger_vec.isEmpty) {
            false.B
        } else {
            trigger_vec.reduce(_||_)
        }
    }
}

class PerfCtrlHelper extends BlackBox with HasBlackBoxInline {
    val io = IO(new Bundle {
        val perf_ctrl = Output(new PerfCtrlIO)
    })

    setInline("PerfCtrlHelper.v",
        s"""
        |module PerfCtrlHelper(
        |    output logic perf_ctrl_clean
        |);
        |   export "DPI-C" task set_perf_ctrl_clean;
        |
        |   task set_perf_ctrl_clean(input logic clean);
        |          perf_ctrl_clean = clean;
        |   endtask
        |
        |endmodule
        """.stripMargin)
}

class PerfBox extends Module {
    val perf_ctrl_helper = Module(new PerfCtrlHelper)

    val perf_ctrl = perf_ctrl_helper.io.perf_ctrl
    PerfCount.collect(perf_ctrl)

    when (PerfDumpTrigger.is_triggered) {
        PerfCount.print
    }
}