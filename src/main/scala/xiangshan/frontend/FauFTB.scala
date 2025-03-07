/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.frontend

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import scala.{Tuple2 => &}
import utility._
import xiangshan._

trait FauFTBParams extends HasXSParameter with HasBPUConst {
  val numWays = 32
  val tagSize = 16

  val TAR_STAT_SZ = 2
  def TAR_FIT     = 0.U(TAR_STAT_SZ.W)
  def TAR_OVF     = 1.U(TAR_STAT_SZ.W)
  def TAR_UDF     = 2.U(TAR_STAT_SZ.W)

  def BR_OFFSET_LEN  = 12
  def JMP_OFFSET_LEN = 20

  def getTag(pc: UInt) = pc(tagSize + instOffsetBits - 1, instOffsetBits)
}

class FauFTBEntry(implicit p: Parameters) extends FTBEntry()(p) {}

class FauFTBWay(implicit p: Parameters) extends XSModule with FauFTBParams {
  val io = IO(new Bundle {
    val req_tag        = Input(UInt(tagSize.W))
    val resp           = Output(new FauFTBEntry)
    val resp_hit       = Output(Bool())
    val update_req_tag = Input(UInt(tagSize.W))
    val update_hit     = Output(Bool())
    val write_valid    = Input(Bool())
    val write_entry    = Input(new FauFTBEntry)
    val write_tag      = Input(UInt(tagSize.W))
    val tag_read       = Output(UInt(tagSize.W))
  })

  val data  = Reg(new FauFTBEntry)
  val tag   = Reg(UInt(tagSize.W))
  val valid = RegInit(false.B)

  io.resp     := data
  io.resp_hit := tag === io.req_tag && valid
  // write bypass to avoid multiple hit
  io.update_hit := ((tag === io.update_req_tag) && valid) ||
    ((io.write_tag === io.update_req_tag) && io.write_valid)
  io.tag_read := tag

  when(io.write_valid) {
    when(!valid) {
      valid := true.B
    }
    tag  := io.write_tag
    data := io.write_entry
  }
}

class FauFTB(implicit p: Parameters) extends BasePredictor with FauFTBParams {

  class FauFTBMeta(implicit p: Parameters) extends XSBundle with FauFTBParams {
    val pred_way = if (!env.FPGAPlatform) Some(UInt(log2Ceil(numWays).W)) else None
    val hit      = Bool()
  }
  val resp_meta             = Wire(new FauFTBMeta)
  override val meta_size    = resp_meta.getWidth
  override val is_fast_pred = true

  val ways = Seq.tabulate(numWays)(w => Module(new FauFTBWay))
  // numWays * numBr
  val ctrs                = Seq.tabulate(numWays)(w => Seq.tabulate(numBr)(b => RegInit(2.U(2.W))))
  val replacer            = ReplacementPolicy.fromString("plru", numWays)
  val replacer_touch_ways = Wire(Vec(2, Valid(UInt(log2Ceil(numWays).W))))

  // pred req
  ways.foreach(_.io.req_tag := getTag(s1_pc_dup(0)))

  // pred resp
  val s1_hit_oh              = VecInit(ways.map(_.io.resp_hit)).asUInt
  val s1_hit                 = s1_hit_oh.orR
  val s1_hit_way             = OHToUInt(s1_hit_oh)
  val s1_possible_full_preds = Wire(Vec(numWays, new FullBranchPrediction(isNotS3 = true)))

  val s1_all_entries = VecInit(ways.map(_.io.resp))
  for (c & fp & e <- ctrs zip s1_possible_full_preds zip s1_all_entries) {
    fp.hit      := DontCare
    fp.multiHit := false.B
    fp.fromFtbEntry(e, s1_pc_dup(0))
    for (i <- 0 until numBr) {
      fp.br_taken_mask(i) := c(i)(1) || e.strong_bias(i)
    }
  }
  val s1_hit_full_pred   = Mux1H(s1_hit_oh, s1_possible_full_preds)
  val s1_hit_fauftbentry = Mux1H(s1_hit_oh, s1_all_entries)
  XSError(PopCount(s1_hit_oh) > 1.U, "fauftb has multiple hits!\n")
  val fauftb_enable = RegNext(io.ctrl.ubtb_enable)
  io.out.s1.full_pred.map(_ := s1_hit_full_pred)
  io.out.s1.full_pred.map(_.hit := s1_hit && fauftb_enable)
  io.fauftb_entry_out     := s1_hit_fauftbentry
  io.fauftb_entry_hit_out := s1_hit && fauftb_enable

  // Illegal check for FTB entry reading
  val s1_pc_startLower = Cat(0.U(1.W), s1_pc_dup(0)(instOffsetBits + log2Ceil(PredictWidth) - 1, instOffsetBits))
  val uftb_entry_endLowerwithCarry = Cat(s1_hit_fauftbentry.carry, s1_hit_fauftbentry.pftAddr)
  val fallThroughErr               = s1_pc_startLower + PredictWidth.U >= uftb_entry_endLowerwithCarry
  when(io.s1_fire(0) && s1_hit) {
    assert(fallThroughErr, s"FauFTB read entry fallThrough address error!")
  }

  // assign metas
  io.out.last_stage_meta := resp_meta.asUInt
  resp_meta.hit          := RegEnable(RegEnable(s1_hit, io.s1_fire(0)), io.s2_fire(0))
  if (resp_meta.pred_way.isDefined) {
    resp_meta.pred_way.get := RegEnable(RegEnable(s1_hit_way, io.s1_fire(0)), io.s2_fire(0))
  }

  // pred update replacer state
  val s1_fire = io.s1_fire(0)
  replacer_touch_ways(0).valid := RegNext(s1_fire(0) && s1_hit)
  replacer_touch_ways(0).bits  := RegEnable(s1_hit_way, s1_fire(0) && s1_hit)

  /********************** update ***********************/
  // s0: update_valid, read and tag comparison
  // s1: alloc_way and write

  // s0
  val u        = io.update
  val u_meta   = u.bits.meta.asTypeOf(new FauFTBMeta)
  val u_s0_tag = getTag(u.bits.pc)
  ways.foreach(_.io.update_req_tag := u_s0_tag)
  val u_s0_hit_oh = VecInit(ways.map(_.io.update_hit)).asUInt
  val u_s0_hit    = u_s0_hit_oh.orR
  val u_s0_br_update_valids =
    VecInit((0 until numBr).map(w =>
      u.bits.ftb_entry.brValids(w) && u.valid && !u.bits.ftb_entry.strong_bias(w) &&
        !(PriorityEncoder(u.bits.br_taken_mask) < w.U)
    ))

  // s1
  val u_s1_valid            = RegNext(u.valid)
  val u_s1_tag              = RegEnable(u_s0_tag, u.valid)
  val u_s1_hit_oh           = RegEnable(u_s0_hit_oh, u.valid)
  val u_s1_hit              = RegEnable(u_s0_hit, u.valid)
  val u_s1_alloc_way        = replacer.way
  val u_s1_write_way_oh     = Mux(u_s1_hit, u_s1_hit_oh, UIntToOH(u_s1_alloc_way))
  val u_s1_ftb_entry        = RegEnable(u.bits.ftb_entry, u.valid)
  val u_s1_ways_write_valid = VecInit((0 until numWays).map(w => u_s1_write_way_oh(w).asBool && u_s1_valid))
  for (w <- 0 until numWays) {
    ways(w).io.write_valid := u_s1_ways_write_valid(w)
    ways(w).io.write_tag   := u_s1_tag
    ways(w).io.write_entry := u_s1_ftb_entry
  }

  // Illegal check for FTB entry writing
  val uftb_write_pc          = RegEnable(u.bits.pc, u.valid)
  val uftb_write_fallThrough = u_s1_ftb_entry.getFallThrough(uftb_write_pc)
  when(u_s1_valid && u_s1_hit) {
    assert(
      uftb_write_pc + (FetchWidth * 4).U >= uftb_write_fallThrough,
      s"FauFTB write entry fallThrough address error!"
    )
  }

  // update saturating counters
  val u_s1_br_update_valids = RegEnable(u_s0_br_update_valids, u.valid)
  val u_s1_br_takens        = RegEnable(u.bits.br_taken_mask, u.valid)
  for (w <- 0 until numWays) {
    when(u_s1_ways_write_valid(w)) {
      for (br <- 0 until numBr) {
        when(u_s1_br_update_valids(br)) {
          ctrs(w)(br) := satUpdate(ctrs(w)(br), 2, u_s1_br_takens(br))
        }
      }
    }
  }

  // commit update replacer state
  replacer_touch_ways(1).valid := u_s1_valid
  replacer_touch_ways(1).bits  := OHToUInt(u_s1_write_way_oh)

  /******** update replacer *********/
  replacer.access(replacer_touch_ways)

  /********************** perf counters **********************/
  val s0_fire_next_cycle = RegNext(io.s0_fire(0))
  val u_pred_hit_way_map = (0 until numWays).map(w => s0_fire_next_cycle && s1_hit && s1_hit_way === w.U)
  XSPerfAccumulate("uftb_read_hits", s0_fire_next_cycle && s1_hit)
  XSPerfAccumulate("uftb_read_misses", s0_fire_next_cycle && !s1_hit)
  XSPerfAccumulate("uftb_commit_hits", u.valid && u_meta.hit)
  XSPerfAccumulate("uftb_commit_misses", u.valid && !u_meta.hit)
  XSPerfAccumulate("uftb_commit_read_hit_pred_miss", u.valid && !u_meta.hit && u_s0_hit_oh.orR)
  for (w <- 0 until numWays) {
    XSPerfAccumulate(f"uftb_pred_hit_way_${w}", u_pred_hit_way_map(w))
    XSPerfAccumulate(f"uftb_replace_way_${w}", !u_s1_hit && u_s1_alloc_way === w.U)
  }

  if (u_meta.pred_way.isDefined) {
    val u_commit_hit_way_map = (0 until numWays).map(w => u.valid && u_meta.hit && u_meta.pred_way.get === w.U)
    for (w <- 0 until numWays) {
      XSPerfAccumulate(f"uftb_commit_hit_way_${w}", u_commit_hit_way_map(w))
    }
  }

  override val perfEvents = Seq(
    ("fauftb_commit_hit       ", u.valid && u_meta.hit),
    ("fauftb_commit_miss      ", u.valid && !u_meta.hit)
  )
  generatePerfEvent()

}
