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

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import chisel3.util._
import xiangshan._
import utils._
import chisel3.experimental.chiselName

import scala.math.min


trait FTBParams extends HasXSParameter with HasBPUConst {
  val numEntries = 4096
  val numWays    = 4
  val numSets    = numEntries/numWays // 512
  val tagSize    = 20

  val TAR_STAT_SZ = 2
  def TAR_FIT = 0.U(TAR_STAT_SZ.W)
  def TAR_OVF = 1.U(TAR_STAT_SZ.W)
  def TAR_UDF = 2.U(TAR_STAT_SZ.W)

  def BR_OFFSET_LEN = 13
  def JMP_OFFSET_LEN = 21
}

class FTBEntry(implicit p: Parameters) extends XSBundle with FTBParams with BPUUtils {
  val valid       = Bool()

  val brOffset    = Vec(numBr, UInt(log2Up(FetchWidth*2).W))
  val brLowers    = Vec(numBr, UInt(BR_OFFSET_LEN.W))
  val brTarStats  = Vec(numBr, UInt(TAR_STAT_SZ.W))
  val brValids    = Vec(numBr, Bool())

  val jmpOffset = UInt(log2Ceil(PredictWidth).W)
  val jmpLower   = UInt(JMP_OFFSET_LEN.W)
  val jmpTarStat = UInt(TAR_STAT_SZ.W)
  val jmpValid    = Bool()

  // Partial Fall-Through Address
  val pftAddr     = UInt((log2Up(PredictWidth)+1).W)
  val carry       = Bool()

  val isCall      = Bool()
  val isRet       = Bool()
  val isJalr      = Bool()

  val oversize    = Bool()

  val last_is_rvc = Bool()

  val always_taken = Vec(numBr, Bool())

  def getTarget(offsetLen: Int)(pc: UInt, lower: UInt, stat: UInt) = {
    val higher = pc(VAddrBits-1, offsetLen)
    Cat(
      Mux(stat === TAR_OVF, higher+1.U,
        Mux(stat === TAR_UDF, higher-1.U, higher)),
      lower
    )
  }
  val getBrTarget = getTarget(BR_OFFSET_LEN)(_, _, _)

  def getBrTargets(pc: UInt) = {
    VecInit((brLowers zip brTarStats).map{
      case (lower, stat) => getBrTarget(pc, lower, stat)
    })
  }

  def getJmpTarget(pc: UInt) = getTarget(JMP_OFFSET_LEN)(pc, jmpLower, jmpTarStat)

  def getLowerStatByTarget(offsetLen: Int)(pc: UInt, target: UInt) = {
    val pc_higher = pc(VAddrBits-1, offsetLen)
    val target_higher = target(VAddrBits-1, offsetLen)
    val stat = WireInit(Mux(target_higher > pc_higher, TAR_OVF,
      Mux(target_higher < pc_higher, TAR_UDF, TAR_FIT)))
    val lower = WireInit(target(offsetLen-1, 0))
    (lower, stat)
  }
  def getBrLowerStatByTarget(pc: UInt, target: UInt) = getLowerStatByTarget(BR_OFFSET_LEN)(pc, target)
  def getJmpLowerStatByTarget(pc: UInt, target: UInt) = getLowerStatByTarget(JMP_OFFSET_LEN)(pc, target)
  def setByBrTarget(brIdx: Int, pc: UInt, target: UInt) = {
    val (lower, stat) = getBrLowerStatByTarget(pc, target)
    this.brLowers(brIdx) := lower
    this.brTarStats(brIdx) := stat
  }
  def setByJmpTarget(pc: UInt, target: UInt) = {
    val (lower, stat) = getJmpLowerStatByTarget(pc, target)
    this.jmpLower := lower
    this.jmpTarStat := stat
  }


  def getOffsetVec = VecInit(brOffset :+ jmpOffset)
  def isJal = !isJalr
  def getFallThrough(pc: UInt) = getFallThroughAddr(pc, carry, pftAddr)
  def hasBr(offset: UInt) = (brValids zip brOffset).map{
    case (v, off) => v && off <= offset
  }.reduce(_||_)

  def getBrMaskByOffset(offset: UInt) = (brValids zip brOffset).map{
    case (v, off) => v && off <= offset
  }

  def brIsSaved(offset: UInt) = (brValids zip brOffset).map{
    case (v, off) => v && off === offset
  }.reduce(_||_)
  def display(cond: Bool): Unit = {
    XSDebug(cond, p"-----------FTB entry----------- \n")
    XSDebug(cond, p"v=${valid}\n")
    for(i <- 0 until numBr) {
      XSDebug(cond, p"[br$i]: v=${brValids(i)}, offset=${brOffset(i)}, lower=${Hexadecimal(brLowers(i))}\n")
    }
    XSDebug(cond, p"[jmp]: v=${jmpValid}, offset=${jmpOffset}, lower=${Hexadecimal(jmpLower)}\n")
    XSDebug(cond, p"pftAddr=${Hexadecimal(pftAddr)}, carry=$carry\n")
    XSDebug(cond, p"isCall=$isCall, isRet=$isRet, isjalr=$isJalr\n")
    XSDebug(cond, p"oversize=$oversize, last_is_rvc=$last_is_rvc\n")
    XSDebug(cond, p"------------------------------- \n")
  }

}

class FTBEntryWithTag(implicit p: Parameters) extends XSBundle with FTBParams with BPUUtils {
  val entry = new FTBEntry
  val tag = UInt(tagSize.W)
  def display(cond: Bool): Unit = {
    XSDebug(cond, p"-----------FTB entry----------- \n")
    XSDebug(cond, p"v=${entry.valid}, tag=${Hexadecimal(tag)}\n")
    for(i <- 0 until numBr) {
      XSDebug(cond, p"[br$i]: v=${entry.brValids(i)}, offset=${entry.brOffset(i)}, lower=${Hexadecimal(entry.brLowers(i))}\n")
    }
    XSDebug(cond, p"[jmp]: v=${entry.jmpValid}, offset=${entry.jmpOffset}, lower=${Hexadecimal(entry.jmpLower)}\n")
    XSDebug(cond, p"pftAddr=${Hexadecimal(entry.pftAddr)}, carry=${entry.carry}\n")
    XSDebug(cond, p"isCall=${entry.isCall}, isRet=${entry.isRet}, isjalr=${entry.isJalr}\n")
    XSDebug(cond, p"oversize=${entry.oversize}, last_is_rvc=${entry.last_is_rvc}\n")
    XSDebug(cond, p"------------------------------- \n")
  }
}

class FTBMeta(implicit p: Parameters) extends XSBundle with FTBParams {
  val writeWay = UInt(log2Ceil(numWays).W)
  val hit = Bool()
  val pred_cycle = UInt(64.W) // TODO: Use Option
}

object FTBMeta {
  def apply(writeWay: UInt, hit: Bool, pred_cycle: UInt)(implicit p: Parameters): FTBMeta = {
    val e = Wire(new FTBMeta)
    e.writeWay := writeWay
    e.hit := hit
    e.pred_cycle := pred_cycle
    e
  }
}

class FTB(implicit p: Parameters) extends BasePredictor with FTBParams with BPUUtils {
  override val meta_size = WireInit(0.U.asTypeOf(new FTBMeta)).getWidth

  val ftbAddr = new TableAddr(log2Up(numSets), 1)

  class FTBBank(val numSets: Int, val nWays: Int) extends XSModule with BPUUtils {
    val io = IO(new Bundle {
      val req_pc = Flipped(DecoupledIO(UInt(VAddrBits.W)))
      val read_resp = Output(new FTBEntry)

      // when ftb hit, read_hits.valid is true, and read_hits.bits is OH of hit way
      // when ftb not hit, read_hits.valid is false, and read_hits is OH of allocWay
      // val read_hits = Valid(Vec(numWays, Bool()))
      val read_hits = Valid(UInt(log2Ceil(numWays).W))

      val update_pc = Input(UInt(VAddrBits.W))
      val update_write_data = Flipped(Valid(new FTBEntryWithTag))
      val update_write_mask = Input(UInt(numWays.W))
    })

    val ftb = Module(new SRAMTemplate(new FTBEntryWithTag, set = numSets, way = numWays, shouldReset = true, holdRead = true, singlePort = true))

    ftb.io.r.req.valid := io.req_pc.valid // io.s0_fire
    ftb.io.r.req.bits.setIdx := ftbAddr.getIdx(io.req_pc.bits) // s0_idx

    io.req_pc.ready := ftb.io.r.req.ready

    val req_tag = RegEnable(ftbAddr.getTag(io.req_pc.bits)(tagSize-1, 0), io.req_pc.valid)
    val req_idx = RegEnable(ftbAddr.getIdx(io.req_pc.bits), io.req_pc.valid)

    val read_entries = ftb.io.r.resp.data.map(_.entry)
    val read_tags    = ftb.io.r.resp.data.map(_.tag)

    val total_hits = VecInit((0 until numWays).map(b => read_tags(b) === req_tag && read_entries(b).valid && RegNext(io.req_pc.valid)))
    val hit = total_hits.reduce(_||_)
    // val hit_way_1h = VecInit(PriorityEncoderOH(total_hits))
    val hit_way = PriorityEncoder(total_hits)

    assert(PopCount(total_hits) === 1.U || PopCount(total_hits) === 0.U)

    val multiple_hit_recording_vec = (0 to numWays).map(i => PopCount(total_hits) === i.U)
    val multiple_hit_map = (0 to numWays).map(i =>
        f"ftb_multiple_hit_$i" -> (multiple_hit_recording_vec(i) && RegNext(io.req_pc.valid))
    ).foldLeft(Map[String, UInt]())(_+_)

    for ((key, value) <- multiple_hit_map) {
      XSPerfAccumulate(key, value)
    }

    val replacer = ReplacementPolicy.fromString(Some("setplru"), numWays, numSets)
    val allocWriteWay = replacer.way(req_idx)

    val touch_set = Seq.fill(1)(Wire(UInt(log2Ceil(numSets).W)))
    val touch_way = Seq.fill(1)(Wire(Valid(UInt(log2Ceil(numWays).W))))

    replacer.access(touch_set, touch_way)

    touch_set(0) := req_idx

    touch_way(0).valid := hit
    touch_way(0).bits := hit_way

    // def allocWay(valids: UInt, meta_tags: UInt, req_tag: UInt) = {
    //   val randomAlloc = false
    //   if (numWays > 1) {
    //     val w = Wire(UInt(log2Up(numWays).W))
    //     val valid = WireInit(valids.andR)
    //     val tags = Cat(meta_tags, req_tag)
    //     val l = log2Up(numWays)
    //     val nChunks = (tags.getWidth + l - 1) / l
    //     val chunks = (0 until nChunks).map( i =>
    //       tags(min((i+1)*l, tags.getWidth)-1, i*l)
    //     )
    //     w := Mux(valid, if (randomAlloc) {LFSR64()(log2Up(numWays)-1,0)} else {chunks.reduce(_^_)}, PriorityEncoder(~valids))
    //     w
    //   } else {
    //     val w = WireInit(0.U)
    //     w
    //   }
    // }

    // val allocWriteWay = allocWay(
    //   VecInit(read_entries.map(_.valid)).asUInt,
    //   VecInit(read_tags).asUInt,
    //   req_tag
    // )

    io.read_resp := PriorityMux(total_hits, read_entries) // Mux1H
    io.read_hits.valid := hit
    // io.read_hits.bits := Mux(hit, hit_way_1h, VecInit(UIntToOH(allocWriteWay).asBools()))
    io.read_hits.bits := Mux(hit, hit_way, allocWriteWay)

    XSDebug(!hit, "FTB not hit, alloc a way: %d\n", allocWriteWay)

    // Update logic
    val u_valid = io.update_write_data.valid
    val u_data = io.update_write_data.bits
    val u_idx = ftbAddr.getIdx(io.update_pc)
    val u_mask = io.update_write_mask

    ftb.io.w.apply(u_valid, u_data, u_idx, u_mask)
  } // FTBBank

  val ftbBank = Module(new FTBBank(numSets, numWays))

  ftbBank.io.req_pc.valid := io.s0_fire
  ftbBank.io.req_pc.bits := s0_pc

  val ftb_entry = RegEnable(ftbBank.io.read_resp, io.s1_fire)
  val s1_hit = ftbBank.io.read_hits.valid
  val s2_hit = RegEnable(s1_hit, io.s1_fire)
  val writeWay = ftbBank.io.read_hits.bits

  val fallThruAddr = getFallThroughAddr(s2_pc, ftb_entry.carry, ftb_entry.pftAddr)

  // io.out.bits.resp := RegEnable(io.in.bits.resp_in(0), 0.U.asTypeOf(new BranchPredictionResp), io.s1_fire)
  io.out.resp := io.in.bits.resp_in(0)

  val s1_latch_call_is_rvc   = DontCare // TODO: modify when add RAS

  io.out.resp.s2.preds.taken_mask    := io.in.bits.resp_in(0).s2.preds.taken_mask
  for (i <- 0 until numBr) {
    when (ftb_entry.always_taken(i)) {
      io.out.resp.s2.preds.taken_mask(i) := true.B
    }
  }

  io.out.resp.s2.preds.hit           := s2_hit
  io.out.resp.s2.pc                  := s2_pc
  io.out.resp.s2.ftb_entry           := ftb_entry
  io.out.resp.s2.preds.fromFtbEntry(ftb_entry, s2_pc)

  io.out.s3_meta                     := RegEnable(RegEnable(FTBMeta(writeWay, s1_hit, GTimer()).asUInt(), io.s1_fire), io.s2_fire)

  when(s2_hit) {
    io.out.resp.s2.ftb_entry.pftAddr := ftb_entry.pftAddr
    io.out.resp.s2.ftb_entry.carry := ftb_entry.carry
  }.otherwise {
    io.out.resp.s2.ftb_entry.pftAddr := s2_pc(instOffsetBits + log2Ceil(PredictWidth), instOffsetBits) ^ (1 << log2Ceil(PredictWidth)).U
    io.out.resp.s2.ftb_entry.carry := s2_pc(instOffsetBits + log2Ceil(PredictWidth)).asBool
    io.out.resp.s2.ftb_entry.oversize := false.B
  }

  // always taken logic
  when (s2_hit) {
    for (i <- 0 until numBr) {
      when (ftb_entry.always_taken(i)) {
        io.out.resp.s2.preds.taken_mask(i) := true.B
      }
    }
  }

  // Update logic
  val update = RegNext(io.update.bits)

  val u_meta = update.meta.asTypeOf(new FTBMeta)
  val u_valid = RegNext(io.update.valid && !io.update.bits.old_entry)

  io.s1_ready := ftbBank.io.req_pc.ready && !u_valid//  && !io.redirect.valid

  when(u_valid) { // TODO: s1_ready
   ftbBank.io.req_pc.valid := true.B
   ftbBank.io.req_pc.bits := update.pc
  }

  val u_way = Mux(ftbBank.io.read_hits.valid, ftbBank.io.read_hits.bits, RegNext(u_meta.writeWay))

  val u_way_mask = UIntToOH(u_way)

  val ftb_write = Wire(new FTBEntryWithTag)
  ftb_write.entry := update.ftb_entry
  ftb_write.tag   := ftbAddr.getTag(update.pc)(tagSize-1, 0)

  ftbBank.io.update_write_data.valid := RegNext(u_valid)
  ftbBank.io.update_write_data.bits := RegNext(ftb_write)
  ftbBank.io.update_pc := RegNext(update.pc)
  ftbBank.io.update_write_mask := u_way_mask

  XSDebug("req_v=%b, req_pc=%x, ready=%b (resp at next cycle)\n", io.s0_fire, s0_pc, ftbBank.io.req_pc.ready)
  XSDebug("s2_hit=%b, hit_way=%b\n", s2_hit, writeWay.asUInt)
  XSDebug("s2_taken_mask=%b, s2_real_taken_mask=%b\n",
    io.in.bits.resp_in(0).s2.preds.taken_mask.asUInt, io.out.resp.s2.real_taken_mask().asUInt)
  XSDebug("s2_target=%x\n", io.out.resp.s2.target)

  ftb_entry.display(true.B)

  XSDebug(u_valid, "Update from ftq\n")
  XSDebug(u_valid, "update_pc=%x, tag=%x, pred_cycle=%d\n",
    update.pc, ftbAddr.getTag(update.pc), u_meta.pred_cycle)
  XSDebug(RegNext(u_valid), "Write into FTB\n")
  XSDebug(RegNext(u_valid), "hit=%d, update_write_way=%d\n",
    ftbBank.io.read_hits.valid, u_way_mask)





  XSPerfAccumulate("ftb_read_hits", RegNext(io.s0_fire) && s1_hit)
  XSPerfAccumulate("ftb_read_misses", RegNext(io.s0_fire) && !s1_hit)

  XSPerfAccumulate("ftb_commit_hits", io.update.valid && update.preds.hit)
  XSPerfAccumulate("ftb_commit_misses", io.update.valid && !update.preds.hit)

  XSPerfAccumulate("ftb_update_req", io.update.valid)
  XSPerfAccumulate("ftb_update_ignored", io.update.valid && io.update.bits.old_entry)
  XSPerfAccumulate("ftb_updated", u_valid)
}
