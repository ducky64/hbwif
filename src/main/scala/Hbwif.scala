package hbwif

import Chisel._
import cde._
import util.ParameterizedBundle
import rocketchip._
import junctions._
import uncore.tilelink._
import diplomacy.{LazyModule, LazyModuleImp}
import coreplex._
import math.max

case object HbwifKey extends Field[HbwifParameters]

case class HbwifParameters(
  val numLanes: Int = 8,
  val maxRetransmitCycles: Int = 20000,
  val bufferDepth: Int = 16)

trait HasHbwifParameters extends HasBertParameters with HasTransceiverParameters {
  val memParams = p.alterPartial({ case TLId => "Switcher" })
  val mmioParams = p.alterPartial({ case TLId => "MMIOtoSCR" })
  val hbwifNumLanes = p(HbwifKey).numLanes
  val hbwifMaxRetransmitCycles = p(HbwifKey).maxRetransmitCycles
  val hbwifBufferDepth = p(HbwifKey).bufferDepth
  val hbwifNumBanks = if(transceiverHasIref) max(1,hbwifNumLanes / transceiverRefGenNumOutputs) else 1
  val hbwifLanesPerBank = hbwifNumLanes / hbwifNumBanks
  require(hbwifNumLanes % hbwifNumBanks == 0, "The number of lanes must be a multiple of the number of banks")
}

trait HasHbwifTileLinkParameters extends HasHbwifParameters
  with HasTileLinkParameters {
  val hbwifRawGrantBits = tlBeatAddrBits + tlClientXactIdBits + tlManagerIdBits + 1 + tlGrantTypeBits + tlDataBits
  val hbwifRawAcquireBits = tlBeatAddrBits + tlClientXactIdBits + tlManagerIdBits + 1 + tlGrantTypeBits + tlDataBits
}

trait Hbwif extends LazyModule
  with HasHbwifParameters {
  val scrDevices: ResourceManager[AddrMapEntry]

  (0 until hbwifNumLanes).foreach { i =>
    scrDevices.add(AddrMapEntry(s"hbwif_lane$i", MemSize(4096, MemAttr(AddrMapProt.RW))))
  }
}

trait HbwifBundle extends HasHbwifParameters {
  val hbwifRx      = Vec(hbwifNumLanes, new Differential).flip
  val hbwifTx      = Vec(hbwifNumLanes, new Differential)
  val hbwifIref    = if(transceiverHasIref && transceiverRefGenHasInput) Some(UInt(INPUT, width=hbwifNumBanks*transceiverNumIrefs)) else None
}

trait HbwifModule extends HasHbwifParameters {
  implicit val p: Parameters
  val io: HbwifBundle
  val scrBus: TileLinkRecursiveInterconnect
  val hbwifIO: Vec[ClientUncachedTileLinkIO]
  val hbwifFastClock = Wire(Clock())
  val clock: Clock
  val reset: Bool
  val hbwifReset = Wire(Bool())
  val hbwifResetOverride = Wire(Bool())
  val hbwifSlowClockCounter = Seq.fill(hbwifNumLanes){Wire(UInt(width=64))}

  val hbwifLanes = (0 until hbwifNumLanes).map(id => Module(new HbwifLane(id)))

  hbwifLanes.foreach { _.io.fastClk := hbwifFastClock }
  hbwifLanes.foreach { _.io.hbwifReset := hbwifReset }
  hbwifLanes.foreach { _.io.hbwifResetOverride := hbwifResetOverride }

  hbwifLanes.map(_.io.rx).zip(io.hbwifRx) map { case (lane, top) => lane <> top }
  hbwifLanes.map(_.io.tx).zip(io.hbwifTx) map { case (lane, top) => top <> lane }
  hbwifLanes.map(_.io.slowClockCounter).zip(hbwifSlowClockCounter) map { case (here, there) => there := here }

  (0 until hbwifNumLanes).foreach { i =>
    hbwifLanes(i).io.scr <> scrBus.port(s"hbwif_lane$i")
  }
  hbwifLanes.zip(hbwifIO).foreach { x => x._1.io.mem <> x._2 }

  // Instantiate and connect the reference generator if needed
  (0 until hbwifNumBanks).foreach { j =>
    (0 until transceiverNumIrefs).foreach { i =>
      val idx = i + j*transceiverNumIrefs
      val hbwifRefGen = Module(new ReferenceGenerator)
      hbwifRefGen.suggestName(s"hbwifRefGenInst$idx")

      (0 until hbwifLanesPerBank).foreach { k =>
        hbwifLanes(hbwifLanesPerBank*j + k).io.iref.get(i) := hbwifRefGen.io.irefOut(k)
      }

      if (transceiverRefGenHasInput) {
        hbwifRefGen.io.irefIn.get := io.hbwifIref.get(idx)
      }
    }
  }
}


