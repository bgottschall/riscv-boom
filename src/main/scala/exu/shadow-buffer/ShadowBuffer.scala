package boom.exu

import Chisel.{PopCount, Valid, log2Ceil}
import boom.common.BoomModule
import boom.util.{WrapAdd, WrapDec}
import chipsalliance.rocketchip.config.Parameters
import chisel3._

class ShadowBuffer(implicit p: Parameters) extends BoomModule {

  val io = new Bundle {
    val flush_in = Input(Bool())
    val br_mispred_shadow_buffer_idx = Input(Valid(UInt(log2Ceil(maxBrCount).W)))
    val release_queue_tail_checkpoint = Input(UInt(log2Ceil(numLdqEntries).W))
    val br_mispredict_release_queue_idx = Output(Valid(UInt(log2Ceil(numLdqEntries).W)))

    val new_branch_op = Input(Vec(coreWidth, Bool()))
    val br_safe_in = Input(Vec(coreWidth, Valid(UInt(log2Ceil(maxBrCount).W))))

    val shadow_buffer_head_out = Output(UInt(log2Ceil(maxBrCount).W))
    val shadow_buffer_tail_out = Output(UInt(log2Ceil(maxBrCount).W))
  }

  //Remember: Head is oldest speculative op, Tail is newest speculative op
  val ShadowBufferHead = RegInit(UInt(log2Ceil(maxBrCount).W), 0.U)
  val ShadowBufferTail = RegInit(UInt(log2Ceil(maxBrCount).W), 0.U)

  val ShadowCaster = Reg(Vec(maxBrCount, Bool()))
  val ReleaseQueueIndex = Reg(Vec(maxBrCount, UInt(log2Ceil(numLdqEntries).W)))

  val update_release_queue = RegNext(io.br_mispred_shadow_buffer_idx.valid)
  val update_release_queue_idx = RegNext(ReleaseQueueIndex(io.br_mispred_shadow_buffer_idx.bits))

  io.shadow_buffer_head_out := ShadowBufferHead
  io.shadow_buffer_tail_out := ShadowBufferTail

  var allClear = true.B
  var numBranch = 0.U
  ShadowBufferTail := WrapAdd(ShadowBufferTail, PopCount(io.new_branch_op), maxBrCount)

  for (w <- 0 until coreWidth) {
    numBranch = numBranch + io.new_branch_op(w).asUInt()
    
    when(io.new_branch_op(w)) {
      ShadowCaster(WrapDec(ShadowBufferTail + numBranch, maxBrCount)) := true.B
      ReleaseQueueIndex(WrapDec(ShadowBufferTail + numBranch, maxBrCount)) := io.release_queue_tail_checkpoint
    }

    when(io.br_safe_in(w).valid) {
      ShadowCaster(io.br_safe_in(w).bits) := false.B
    }

    when(ShadowCaster(ShadowBufferHead + w.U) === false.B && WrapAdd(ShadowBufferHead, w.U, maxBrCount) =/= ShadowBufferTail && allClear) {
      ShadowBufferHead := WrapAdd(ShadowBufferHead, w.U + 1.U, maxBrCount)
    }.otherwise {
      allClear = false.B
    }
  }

  io.br_mispredict_release_queue_idx.valid := false.B
  io.br_mispredict_release_queue_idx.bits := update_release_queue_idx

  when(update_release_queue) {
    io.br_mispredict_release_queue_idx.valid := true.B
  }

  when(io.flush_in) {
    ShadowBufferHead := 0.U
    ShadowBufferTail := 0.U

    //TODO: Remove this
    ShadowCaster(0.U) := false.B
    ReleaseQueueIndex(0.U) := 0.U
  }.elsewhen(io.br_mispred_shadow_buffer_idx.valid) {
    ShadowBufferTail := io.br_mispred_shadow_buffer_idx.bits

    //TODO: Remove this
    ShadowCaster(io.br_mispred_shadow_buffer_idx.bits) := false.B
    ReleaseQueueIndex(io.br_mispred_shadow_buffer_idx.bits) := 0.U
  }

}
