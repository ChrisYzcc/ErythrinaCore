package utils

import chisel3._
import chisel3.util._

class MultiPortQueue[T <: Data](gen: T, size: Int, enqPorts: Int, deqPorts: Int) extends Module {
	val io = IO(new Bundle {
		val enq = Vec(enqPorts, Flipped(Decoupled(gen)))
		val deq = Vec(deqPorts, DecoupledIO(gen))

		val flush = Input(Bool())
	})

	class Ptr extends CircularQueuePtr[Ptr](size) {
	}

	object Ptr {
		def apply(f: Bool, v: UInt): Ptr = {
			val ptr = Wire(new Ptr)
			ptr.flag := f
			ptr.value := v
			ptr
		}
	}

	val entries = RegInit(VecInit(Seq.fill(size)(0.U.asTypeOf(gen))))
	val valids = RegInit(VecInit(Seq.fill(size)(false.B)))

	// ptr
	val deqPtrExt = RegInit(VecInit((0 until deqPorts).map(_ => 0.U.asTypeOf(new Ptr))))
	val enqPtrExt = RegInit(VecInit((0 until enqPorts).map(_ => 0.U.asTypeOf(new Ptr))))

	val (enq, deq) = (io.enq, io.deq)

	// enq
	val needAlloc = Wire(Vec(enqPorts, Bool()))
	val canAlloc = Wire(Vec(enqPorts, Bool()))

	for (i <- 0 until enqPorts) {
		val index = PopCount(needAlloc.take(i))
		val allocPtr = enqPtrExt(index)

		needAlloc(i) := enq(i).valid
		canAlloc(i) := needAlloc(i) && allocPtr >= deqPtrExt(0)
		when (canAlloc(i)) {
			entries(allocPtr.value) := enq(i).bits
			valids(allocPtr.value) := true.B
		}

		enq(i).ready := allocPtr >= deqPtrExt(0) && !io.flush
	}
	val allocNum = PopCount(canAlloc)
	enqPtrExt.foreach{case x => when (canAlloc.asUInt.orR) {x := x + allocNum}}

	// deq
	val needDeq = Wire(Vec(deqPorts, Bool()))
	val canDeq = Wire(Vec(deqPorts, Bool()))

	for (i <- 0 until deqPorts) {
		val index = PopCount(needDeq.take(i))
		val deqPtr = deqPtrExt(index)

		needDeq(i) := deq(i).ready
		canDeq(i) := needDeq(i) && deqPtr < enqPtrExt(0)

		deq(i).valid := deqPtr < enqPtrExt(0) && !io.flush
		deq(i).bits := entries(deqPtr.value)
		when (canDeq(i)) {
			valids(deqPtr.value) := false.B
		}
	}
	val deqNum = PopCount(canDeq)
	deqPtrExt.foreach{case x => when (canDeq.asUInt.orR) {x := x + deqNum}}

	// flush
	when (io.flush) {
		for (i <- 0 until size) {
			valids(i) := false.B
		}
		for (i <- 0 until deqPorts) {
			deqPtrExt(i) := i.U.asTypeOf(new Ptr)
		}
		for (i <- 0 until enqPorts) {
			enqPtrExt(i) := i.U.asTypeOf(new Ptr)
		}
	}
}