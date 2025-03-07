/***************************************************************************************
* Copyright (c) 2024 Beijing Institute of Open Source Chip (BOSC)
* Copyright (c) 2020-2024 Institute of Computing Technology, Chinese Academy of Sciences
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

package xiangshan.frontend.icache

import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.ClientMetadata
import freechips.rocketchip.tilelink.TLPermissions
import org.chipsalliance.cde.config.Parameters
import utility._
import utils._
import xiangshan._

class ICacheReadBundle(implicit p: Parameters) extends ICacheBundle {
  val vSetIdx      = Vec(2, UInt(log2Ceil(nSets).W))
  val wayMask      = Vec(2, Vec(nWays, Bool()))
  val blkOffset    = UInt(log2Ceil(blockBytes).W)
  val isDoubleLine = Bool()
}

class ICacheMetaWriteBundle(implicit p: Parameters) extends ICacheBundle {
  val virIdx  = UInt(idxBits.W)
  val phyTag  = UInt(tagBits.W)
  val waymask = UInt(nWays.W)
  val bankIdx = Bool()

  def generate(tag: UInt, idx: UInt, waymask: UInt, bankIdx: Bool): Unit = {
    this.virIdx  := idx
    this.phyTag  := tag
    this.waymask := waymask
    this.bankIdx := bankIdx
  }

}

class ICacheDataWriteBundle(implicit p: Parameters) extends ICacheBundle {
  val virIdx  = UInt(idxBits.W)
  val data    = UInt(blockBits.W)
  val waymask = UInt(nWays.W)
  val bankIdx = Bool()

  def generate(data: UInt, idx: UInt, waymask: UInt, bankIdx: Bool): Unit = {
    this.virIdx  := idx
    this.data    := data
    this.waymask := waymask
    this.bankIdx := bankIdx
  }

}

class ICacheMetaRespBundle(implicit p: Parameters) extends ICacheBundle {
  val metas      = Vec(PortNumber, Vec(nWays, new ICacheMetadata))
  val codes      = Vec(PortNumber, Vec(nWays, UInt(ICacheMetaCodeBits.W)))
  val entryValid = Vec(PortNumber, Vec(nWays, Bool()))

  // for compatibility
  def tags = VecInit(metas.map(port => VecInit(port.map(way => way.tag))))
}

class ICacheDataRespBundle(implicit p: Parameters) extends ICacheBundle {
  val datas = Vec(ICacheDataBanks, UInt(ICacheDataBits.W))
  val codes = Vec(ICacheDataBanks, UInt(ICacheDataCodeBits.W))
}

class ICacheMetaReadBundle(implicit p: Parameters) extends ICacheBundle {
  val req  = Flipped(DecoupledIO(new ICacheReadBundle))
  val resp = Output(new ICacheMetaRespBundle)
}

class ReplacerTouch(implicit p: Parameters) extends ICacheBundle {
  val vSetIdx = UInt(log2Ceil(nSets).W)
  val way     = UInt(log2Ceil(nWays).W)
}

class ReplacerVictim(implicit p: Parameters) extends ICacheBundle {
  val vSetIdx = ValidIO(UInt(log2Ceil(nSets).W))
  val way     = Input(UInt(log2Ceil(nWays).W))
}
