package hbwif.tilelink

import hbwif._
import chisel3._
import chisel3.util._
import chisel3.experimental.withReset
import freechips.rocketchip.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config._
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem.{PeripheryBusKey, CacheBlockBytes}

abstract class TLLane8b10b(val clientEdge: TLEdgeOut, val managerEdge: TLEdgeIn)
    (implicit val c: SerDesConfig, implicit val b: BertConfig, implicit val m: PatternMemConfig, implicit val p: Parameters) extends Lane
    with HasEncoding8b10b
    with HasTLBidirectionalPacketizer
    with HasTLController

class GenericTLLane8b10b(clientEdge: TLEdgeOut, managerEdge: TLEdgeIn)(implicit p: Parameters)
    extends TLLane8b10b(clientEdge, managerEdge)(p(HbwifSerDesKey), p(HbwifBertKey), p(HbwifPatternMemKey), p)
    with HasGenericTransceiverSubsystem
    with HasBertDebug
    with HasPatternMemDebug
    with HasBitStufferDebug4Modes
    with HasBitReversalDebug


abstract class HbwifModule()(implicit p: Parameters) extends LazyModule {

    val lanes = p(HbwifNumLanes)
    val beatBytes = p(HbwifTLKey).beatBytes
    val cacheBlockBytes = p(CacheBlockBytes)
    val numXact = p(HbwifTLKey).numXact
    val sinkIds = p(HbwifTLKey).sinkIds
    val managerAddressSet = p(HbwifTLKey).managerAddressSet
    val configAddressSets = p(HbwifTLKey).configAddressSets
    val mtlc = p(HbwifTLKey).managerTLC
    val mtluh = p(HbwifTLKey).managerTLUH
    val ctlc = p(HbwifTLKey).clientTLC
    val clientPort = p(HbwifTLKey).clientPort

    val numClientPorts = if(clientPort) lanes else 0

    val clientNode = TLClientNode((0 until numClientPorts).map { id => TLClientPortParameters(
        Seq(TLClientParameters(
            name               = s"HbwifClient$id",
            sourceId           = IdRange(0,numXact),
            supportsGet        = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsPutFull    = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsPutPartial = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsArithmetic = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsLogical    = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsHint       = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsProbe      = if (ctlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none)),
        minLatency         = 1)
        })
    val managerNode = TLManagerNode((0 until lanes).map { id =>
        val base = managerAddressSet
        val filter = AddressSet(id * cacheBlockBytes, ~((lanes-1) * cacheBlockBytes))
        TLManagerPortParameters(Seq(TLManagerParameters(
            address            = base.intersect(filter).toList,
            resources          = (new SimpleDevice(s"HbwifManager$id",Seq())).reg("mem"),
            regionType         = if (mtlc) RegionType.CACHED else RegionType.UNCACHED,
            executable         = true,
            supportsGet        = TransferSizes(1, cacheBlockBytes),
            supportsPutFull    = TransferSizes(1, cacheBlockBytes),
            supportsPutPartial = TransferSizes(1, cacheBlockBytes),
            supportsArithmetic = if (mtluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsLogical    = if (mtluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsHint       = if (mtluh) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsAcquireT   = if (mtlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            supportsAcquireB   = if (mtlc) TransferSizes(1, cacheBlockBytes) else TransferSizes.none,
            fifoId             = Some(0))),
        beatBytes = beatBytes,
        endSinkId = sinkIds,
        minLatency = 1) })
    val registerNodes = (0 until lanes).map { id => TLRegisterNode(
        address            = List(configAddressSets(id)),
        device             = new SimpleDevice(s"HbwifConfig$id", Seq(s"ucb-bar,hbwif$id")),
        beatBytes          = p(PeripheryBusKey).beatBytes
    )}
    val configBuffers = (0 until lanes).map { id =>
        LazyModule(new TLBuffer())
    }
    val configNodes = (0 until lanes).map { id =>
        registerNodes(id) := configBuffers(id).node
        configBuffers(id).node
    }

    lazy val module = new LazyModuleImp(this) {
        val banks = p(HbwifTLKey).numBanks
        require(lanes % banks == 0)

        // These go to clock receivers
        val hbwifRefClocks = IO(Input(Vec(banks, Clock())))
        // These go to mmio registers
        val hbwifResets = IO(Input(Vec(lanes, Bool())))
        // This is top-level reset
        val resetAsync = IO(Input(Bool()))

        val tx = IO(Vec(lanes, new Differential()))
        val rx = IO(Vec(lanes, Flipped(new Differential())))

        val (laneModules, addrmaps) = (0 until lanes).map({ id =>
            val (clientOut, clientEdge) = if(clientPort) {
              clientNode.out(id)
            } else {
            (0.U(1.W), new TLEdgeOut(
              TLClientPortParameters(Seq(TLClientParameters("FakeHbwifClient"))),
              TLManagerPortParameters(Seq(
                TLManagerParameters(
                  Seq(AddressSet(0, 0xff)),
                  supportsGet = TransferSizes(1, cacheBlockBytes))), beatBytes = 8),
              p, chisel3.internal.sourceinfo.SourceLine("fake.scala", 0, 0)))
            }
            val (managerIn, managerEdge) = managerNode.in(id)
            val (registerIn, registerEdge) = registerNodes(id).in(0)
            managerIn.suggestName(s"hbwif_manager_port_$id")
            configBuffers(id).module.suggestName(s"hbwif_config_port_$id")
            registerIn.suggestName(s"hbwif_register_port_$id")
            val pipelinedReset = withReset(resetAsync) {
               AsyncResetShiftReg(reset.toBool, depth = p(HbwifPipelineResetDepth), init = 1)
            }
            val lane = Module(genLane(clientEdge, managerEdge))
            val regmap = withReset(pipelinedReset) { lane.regmap }
            val addrmap = TLController.toAddrmap(regmap)
            registerNodes(id).regmap(regmap:_*)
            if(clientPort) {
               clientOut.suggestName(s"hbwif_client_port_$id")
               clientOut <> lane.io.data.client
            } else {
              lane.io.data.client := DontCare
            }
            lane.io.data.manager <> managerIn
            tx(id) <> lane.io.tx
            lane.io.rx <> rx(id)
            lane.io.clockRef <> hbwifRefClocks(id/(lanes/banks))
            lane.io.asyncResetIn <> hbwifResets(id)
            lane.reset := pipelinedReset
            configBuffers(id).module.reset := pipelinedReset
            (lane, addrmap)
        }).unzip
    }

    def genLane(clientEdge: TLEdgeOut, managerEdge: TLEdgeIn)(implicit p: Parameters): TLLane8b10b
}

class GenericHbwifModule()(implicit p: Parameters) extends HbwifModule()(p) {

    def genLane(clientEdge: TLEdgeOut, managerEdge: TLEdgeIn)(implicit p: Parameters) = {
        new GenericTLLane8b10b(clientEdge, managerEdge)(p)
    }

}
