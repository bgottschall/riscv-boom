package boom.exu

import Chisel.Valid
import boom.common.BoomModule
import chipsalliance.rocketchip.config.Parameters
import chisel3._

//This one is done
class ReleaseQueue(implicit p: Parameters) extends BoomModule {

  val io = new Bundle {
    val load_queue_index_in = Input(Vec(coreWidth, Valid(UInt(8.W))))

    val shadow_buffer_head_in = Input(UInt())
    val shadow_buffer_tail_in = Input(UInt())

    val load_queue_index_out = Output(Vec(coreWidth, Valid(UInt())))

  }

  val ShadowStampList = Reg(Vec(64, RegInit(UInt(8.W), 0.U)))
  val LoadQueueIndexList = Reg(Vec(64, RegInit(UInt(8.W), 0.U)))

  val ReleaseQueueTail = RegInit(UInt(8.W), 0.U)
  val ReleaseQueueHead = RegInit(UInt(8.W), 0.U)

  io.load_queue_index_out := LoadQueueIndexList(ReleaseQueueTail)

  for (w <- 0 until coreWidth) {
    io.load_queue_index_out(w).valid := false.B
    when(io.shadow_buffer_head_in > ShadowStampList(ReleaseQueueHead)) {
      io.load_queue_index_out(w).bits := LoadQueueIndexList(ReleaseQueueHead)
    }
  }

  for (w <- 0 until coreWidth) {
    when(io.load_queue_index_in(w).valid) {
      ShadowStampList(ReleaseQueueTail) := io.shadow_buffer_tail_in - 1.U
      LoadQueueIndexList(ReleaseQueueTail) := io.load_queue_index_in(w).bits
      ReleaseQueueTail := (ReleaseQueueTail + 1.U) % 64.U
    }
  }

}
