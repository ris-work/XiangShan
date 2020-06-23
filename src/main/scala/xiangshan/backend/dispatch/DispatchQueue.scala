package xiangshan.backend.dispatch

import chisel3._
import chisel3.util._
import xiangshan.utils.GTimer
import xiangshan.{XSBundle, XSModule}


class DispatchQueueIO[T <: Data](gen: T, enqnum: Int, deqnum: Int) extends XSBundle {
  val enq = Vec(enqnum, Flipped(DecoupledIO(gen)))
  val deq = Vec(deqnum, DecoupledIO(gen))
}

class DispatchQueue[T <: Data](gen: T, size: Int, enqnum: Int, deqnum: Int, name: String) extends XSModule {
  val io = IO(new DispatchQueueIO(gen, enqnum, deqnum))
  val index_width = log2Ceil(size)

  // queue data array
  val entries = Reg(Vec(size, gen))
  val entriesValid = Reg(Vec(size, Bool()))
  val head = RegInit(0.U(index_width.W))
  val tail = RegInit(0.U(index_width.W))
  val enq_index = Wire(Vec(enqnum, UInt(index_width.W)))
  val enq_count = Wire(Vec(enqnum, UInt((index_width + 1).W)))
  val deq_index = Wire(Vec(deqnum, UInt(index_width.W)))
  val head_direction = RegInit(0.U(1.W))
  val tail_direction = RegInit(0.U(1.W))

  val valid_entries = Mux(head_direction === tail_direction, tail - head, size.U + tail - head)
  val empty_entries = size.U - valid_entries

  for (i <- 0 until enqnum) {
    enq_count(i) := PopCount(io.enq.slice(0, i + 1).map(_.valid))
    enq_index(i) := (tail + enq_count(i) - 1.U) % size.U
    when (io.enq(i).fire()) {
      entries(enq_index(i)) := io.enq(i).bits
      entriesValid(enq_index(i)) := true.B
    }
  }

  for (i <- 0 until deqnum) {
    deq_index(i) := ((head + i.U) % size.U).asUInt()
    when (io.deq(i).fire()) {
      entriesValid(deq_index(i)) := false.B
    }
  }

  // enqueue
  val num_enq_try = enq_count(enqnum - 1)
  val num_enq = Mux(empty_entries > num_enq_try, num_enq_try, empty_entries)
  (0 until enqnum).map(i => io.enq(i).ready := enq_count(i) <= num_enq)
  tail := (tail + num_enq) % size.U
  tail_direction := ((Cat(0.U(1.W), tail) + num_enq) >= size.U).asUInt() ^ tail_direction

  // dequeue
  val num_deq_try = Mux(valid_entries > deqnum.U, deqnum.U, valid_entries)
  val num_deq_fire = PriorityEncoder((io.deq.zipWithIndex map { case (deq, i) =>
    !(deq.fire() || (!entriesValid(deq_index(i)) && (i.U =/= 0.U)))
  }) :+ true.B)
  val num_deq = Mux(num_deq_try > num_deq_fire, num_deq_fire, num_deq_try)
  (0 until deqnum).map(i => io.deq(i).bits := entries(deq_index(i)))
  (0 until deqnum).map(i => io.deq(i).valid := (i.U < num_deq_try) && entriesValid(deq_index(i)))
  head := (head + num_deq) % size.U
  head_direction := ((Cat(0.U(1.W), head) + num_deq) >= size.U).asUInt() ^ head_direction

  when (num_deq > 0.U) {
    printf("[Cycle:%d][" + name + "] num_deq = %d, head = (%d -> %d)\n",
      GTimer(), num_deq, head, (head + num_deq) % size.U)
  }
  when (num_enq > 0.U) {
    printf("[Cycle:%d][" + name + "] num_enq = %d, tail = (%d -> %d)\n",
      GTimer(), num_enq, tail, (tail + num_enq) % size.U)
  }
  when (valid_entries > 0.U) {
    printf("[Cycle:%d][" + name + "] valid_entries = %d, head = (%d, %d), tail = (%d, %d), \n",
      GTimer(), valid_entries, head_direction, head, tail_direction, tail)
  }
}

object DispatchQueueTop extends App {
  Driver.execute(args, () => new DispatchQueue(UInt(32.W), 16, 6, 4, "Test"))
}
