package hbwif2.tilelink

import hbwif2._
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink._

abstract class TLPacketizer[S <: DecodedSymbol](params: TLBundleParameters, decodedSymbolsPerCycle: Int, symbolFactory: () => S)
    extends Packetizer(decodedSymbolsPerCycle, symbolFactory, {() => TLBundle(params)}) with TLPacketizerLike {

    val tl = io.data
    val tlQueueDepth = 8 // TODO XXX

    require(symbolFactory().decodedWidth == 8, "TLPacketizer* only supports 8-bit wide symbols")

    // Just some sanity checks
    require(tl.a.bits.opcode.getWidth == 3)
    require(tl.b.bits.opcode.getWidth == 3)
    require(tl.c.bits.opcode.getWidth == 3)
    require(tl.d.bits.opcode.getWidth == 3)
    // We want the opcode to fit in the first byte with the type
    require(tlTypeWidth == 3)

}

class TLPacketizerMaster[S <: DecodedSymbol](params: TLBundleParameters, decodedSymbolsPerCycle: Int, symbolFactory: () => S)
    extends TLPacketizer(params, decodedSymbolsPerCycle, symbolFactory) {

    /************************ TX *************************/

    val txHeaderBits = tlTypeWidth + List(headerWidth(tl.a.bits), headerWidth(tl.c.bits), headerWidth(tl.e.bits)).max
    val txBufferBits = div8Ceil(txHeaderBits)*8 + params.dataBits
    val txBuffer = Reg(UInt(txBufferBits.W))

    val txData = Mux(tl.a.fire(), tl.a.bits.data, tl.c.bits.data)

    val aPadBits = txBufferBits - headerWidth(tl.a.bits) - params.dataBits
    val cPadBits = txBufferBits - headerWidth(tl.c.bits) - params.dataBits
    val ePadBits = txBufferBits - headerWidth(tl.e.bits)
    val aHeader = Cat(typeA, tl.a.bits.opcode, tl.a.bits.param, tl.a.bits.size, tl.a.bits.source, tl.a.bits.address, tl.a.bits.mask, if (aPadBits > 0) 0.U(aPadBits.W) else Wire(UInt(0.W)))
    val cHeader = Cat(typeC, tl.c.bits.opcode, tl.c.bits.param, tl.c.bits.size, tl.c.bits.source, tl.c.bits.address, tl.c.bits.error, if (cPadBits > 0) 0.U(cPadBits.W) else Wire(UInt(0.W)))
    val eHeader = Cat(typeE, tl.e.bits.sink, if (ePadBits > 0) 0.U(ePadBits.W) else Wire(UInt(0.W)))

    val txPayloadBytes = Wire(UInt())
    val txPacked = Wire(UInt(txBufferBits.W))
    when (tl.e.valid) {
        txPayloadBytes := getNumSymbols(tl.e.bits, 0.U, 0.U)
        txPacked := Cat(eHeader, txData)
    } .elsewhen (tl.c.valid) {
        txPayloadBytes := getNumSymbols(tl.c.bits, tl.c.bits.opcode, 0.U)
        txPacked := Cat(cHeader, txData)
    } .otherwise { // a
        txPayloadBytes := getNumSymbols(tl.a.bits, tl.a.bits.opcode, tl.a.bits.mask)
        txPacked := Cat(aHeader, txData)
    }

    val txFire = tl.a.fire() || tl.c.fire() || tl.e.fire()
    val txCount = RegInit(0.U(log2Ceil(txBufferBits/8 + 1).W))

    // TODO can we process more than one request at a time (e + other?)
    // Assign priorities to the downstream channels
    val txReady = ((txCount <= decodedSymbolsPerCycle.U) && io.symbolsTxReady) || (txCount === 0.U)
    io.data.a.ready := txReady && !io.data.c.valid && !io.data.e.valid
    io.data.c.ready := txReady && !io.data.e.valid
    io.data.e.ready := txReady

    val sTxReset :: sTxSync :: sTxAck :: sTxReady :: Nil = Enum(4)
    val txState = RegInit(sTxReset)

    // These come from the RX
    val ack = io.symbolsRx map { x => x.valid && x.bits === symbolFactory().ack } reduce (_||_)
    val nack = io.symbolsRx map { x => x.valid && x.bits === symbolFactory().nack } reduce (_||_)

    when (txState === sTxReset) {
        txState := sTxSync
    } .elsewhen(txState === sTxSync) {
        txState := sTxAck
    } .elsewhen(txState === sTxAck) {
        when (nack) {
            txState := sTxSync
        } .elsewhen(ack) {
            txState := sTxReady
        }
    } .elsewhen(txState === sTxReady) {
        when (nack) {
            txState := sTxSync
        }
    } .otherwise {
        // shouldn't get here
        txState := sTxSync
    }

    io.symbolsTx.reverse.zipWithIndex.foreach { case (s,i) =>
        val doSync = ((i.U === 0.U) && (txState === sTxSync))
        s.valid := ((i.U < txCount) && (txState === sTxReady)) || doSync
        s.bits := Mux(doSync, symbolFactory().sync, symbolFactory().fromData(txBuffer(txBufferBits-8*i-1,txBufferBits-8*i-8)))
    }

    when (txFire) {
        txCount := txPayloadBytes
        txBuffer := txPacked
    } .elsewhen(txCount > decodedSymbolsPerCycle.U) {
        when (io.symbolsTxReady) {
            txCount := txCount - decodedSymbolsPerCycle.U
            txBuffer := Cat(txBuffer(txBufferBits-decodedSymbolsPerCycle*8-1,0),txBuffer(decodedSymbolsPerCycle*8-1,0))
        }
    } .otherwise {
        when (io.symbolsTxReady) {
            txCount := 0.U
        }
    }

    /************************ RX *************************/

    val bBuf = Reg(new TLBundleB(params))
    val dBuf = Reg(new TLBundleD(params))

    val bQueue = Queue(new TLBundleB(params), tlQueueDepth)
    val dQueue = Queue(new TLBundleD(params), tlQueueDepth)

    val rxHeaderBits = tlTypeWidth + List(headerWidth(tl.b.bits), headerWidth(tl.d.bits)).max
    val rxBufferBytes = div8Ceil(rxHeaderBits) + params.dataBits/8
    val maxBundlesPerCycle = max(1,decodedSymbolsPerCycle/div8Ceil(tlTypeWidth + List(headerWidth(tl.b.bits), headerWidth(tl.d.bits)).min))

    val rxBuffer = Reg(Vec(rxBufferBytes, UInt(8.W)))
    val rxType = Reg(UInt(tlTypeWidth.W))
    val rxCount = RegInit(0.U(log2Ceil(rxBufferBytes + 1).W))

    val rxReversed = io.symbolsRx.reverse
    val (rxPacked, rxPackedCount) = Pack(rxReversed.map { x =>
        val v = Wire(Valid(UInt(8.W)))
        v.bits := x.bits.isData
        v.valid := x.bits.isData && x.valid
        v
    })

    val rxHeaderCounts = Wire(Vec(maxBundlesPerCycle, UInt(log2Ceil(2*rxBufferBytes + 1).W)))
    val rxTypesOpcodes = rxHeaderCounts.map { rxReversed(_) } map { x => (x(7,8-tlTypeWidth), x(7-tlTypeWidth,8-tlTypeWidth-3)) }
    val rxCountRem = rxHeaderCounts.zip(rxTypesOpcodes).foldLeft(rxCount) { case (prev, (count, (t, o))) => {
        count := prev
        prev + getNumSymbolsFromType(tl, t, o)
    }

}

class TLPacketizerSlave[S <: DecodedSymbol](params: TLBundleParameters, decodedSymbolsPerCycle: Int, symbolFactory: () => S)
    extends TLPacketizer(params, decodedSymbolsPerCycle, symbolFactory) {

    /************************ TX *************************/

    val txHeaderBits = tlTypeWidth + List(headerWidth(tl.b.bits), headerWidth(tl.d.bits)).max
    val txBufferBits = div8Ceil(txHeaderBits)*8 + params.dataBits
    val txBuffer = Reg(UInt(txBufferBits.W))

    // TODO if we process more than one request at a time, this needs to change
    val txData = Mux(tl.b.fire(), tl.b.bits.data, tl.d.bits.data)

    val bPadBits = txBufferBits - headerWidth(tl.b.bits) - params.dataBits
    val dPadBits = txBufferBits - headerWidth(tl.d.bits) - params.dataBits
    val bHeader = Cat(typeB, tl.b.bits.opcode, tl.b.bits.param, tl.b.bits.size, tl.b.bits.source, tl.b.bits.address, tl.b.bits.mask, if (bPadBits > 0) 0.U(bPadBits.W) else Wire(UInt(0.W)))
    val dHeader = Cat(typeD, tl.d.bits.opcode, tl.d.bits.param, tl.d.bits.size, tl.d.bits.source, tl.d.bits.sink, tl.d.bits.error, if (dPadBits > 0) 0.U(dPadBits.W) else Wire(UInt(0.W)))

    val txPayloadBytes = Wire(UInt())
    val txPacked = Wire(UInt(txBufferBits.W))
    when (tl.d.valid) {
        txPayloadBytes := getNumSymbols(tl.d.bits, tl.d.bits.opcode, 0.U)
        txPacked := Cat(dHeader, txData)
    } .otherwise { // b
        txPayloadBytes := getNumSymbols(tl.b.bits, tl.b.bits.opcode, tl.b.bits.mask)
        txPacked := Cat(bHeader, txData)
    }

    val txFire = tl.b.fire() || tl.d.fire()
    val txCount = RegInit(0.U(log2Ceil(txBufferBits/8 + 1).W))

    // TODO can we process more than one request at a time (e + other?)
    // Assign priorities to the downstream channels
    val txReady = ((txCount <= decodedSymbolsPerCycle.U) && io.symbolsTxReady) || (txCount === 0.U)
    io.data.b.ready := txReady && !io.data.d.valid
    io.data.d.ready := txReady

    val sTxReset :: sTxSync :: sTxAck :: sTxReady :: Nil = Enum(4)
    val txState = RegInit(sTxReset)

    // These come from the RX
    val sync = io.symbolsRx map { x => x.valid && x.bits === symbolFactory().sync } reduce (_||_)
    val nack = io.symbolsRx map { x => x.valid && x.bits === symbolFactory().nack } reduce (_||_)

    when (txState === sTxReset) {
        txState := sTxSync
    } .elsewhen(txState === sTxSync) {
        when (sync) {
            txState := sTxAck
        }
    } .elsewhen(txState === sTxAck) {
        when (nack) {
            txState := sTxSync
        } .otherwise
            txState := sTxReady
        }
    } .elsewhen(txState === sTxReady) {
        when (nack) {
            txState := sTxSync
        } .elsewhen (sync) {
            txState := sTxAck
        }
    } .otherwise {
        // shouldn't get here
        txState := sTxSync
    }

    io.symbolsTx.reverse.zipWithIndex.foreach { case (s,i) =>
        val doAck = ((i.U === 0.U) && (txState === sTxAck))
        s.valid := ((i.U < txCount) && (txState === sTxReady)) || doAck
        s.bits := Mux(doAck, symbolFactory().ack, symbolFactory().fromData(txBuffer(txBufferBits-8*i-1,txBufferBits-8*i-8)))
    }

    when (txFire) {
        txCount := txPayloadBytes
        txBuffer := txPacked
    } .elsewhen(txCount > decodedSymbolsPerCycle.U) {
        when (io.symbolsTxReady) {
            txCount := txCount - decodedSymbolsPerCycle.U
            txBuffer := Cat(txBuffer(txBufferBits-decodedSymbolsPerCycle*8-1,0),txBuffer(decodedSymbolsPerCycle*8-1,0))
        }
    } .otherwise {
        when (io.symbolsTxReady) {
            txCount := 0.U
        }
    }

    /************************ RX *************************/

    val aBuf = Reg(new TLBundleA(params))
    val cBuf = Reg(new TLBundleC(params))
    val dBuf = Reg(new TLBundleE(params))

    val aQueue = Queue(new TLBundleA(params), tlQueueDepth)
    val cQueue = Queue(new TLBundleC(params), tlQueueDepth)
    val eQueue = Queue(new TLBundleE(params), tlQueueDepth)

}

trait TLPacketizerLike {

    val tlTypeWidth = 3

    def headerWidth(a: TLBundleA): Int = tlTypeWidth + List(a.opcode,a.param,a.size,a.source,a.address,a.mask).map( _.getWidth).reduce(_+_)
    def headerWidth(b: TLBundleB): Int = tlTypeWidth + List(b.opcode,b.param,b.size,b.source,b.address,b.mask).map(_.getWidth).reduce(_+_)
    def headerWidth(c: TLBundleC): Int = tlTypeWidth + 1 + List(c.opcode,c.param,c.size,c.source,c.address).map(_.getWidth).reduce(_+_)
    def headerWidth(d: TLBundleD): Int = tlTypeWidth + 1 + List(d.opcode,d.param,d.size,d.source,d.sink).map(_.getWidth).reduce(_+_)
    def headerWidth(e: TLBundleE): Int = tlTypeWidth + e.sink.getWidth

    val typeA = 0.U(tlTypeWidth.W)
    val typeB = 1.U(tlTypeWidth.W)
    val typeC = 2.U(tlTypeWidth.W)
    val typeD = 3.U(tlTypeWidth.W)
    val typeE = 4.U(tlTypeWidth.W)

    def div8Ceil(x: Int) = (x + 7)/8

    // There's a vestigial mask input here that we would use for only sending the data in the mask, but this is not implemented
    def tlSymbolMap(a: TLBundleA, mask: UInt) = Map(
        (TLMessages.PutFullData    -> div8Ceil(headerWidth(a) + a.data.getWidth).U),
        (TLMessages.PutPartialData -> div8Ceil(headerWidth(a) + a.data.getWidth).U), // This would use the mask
        (TLMessages.ArithmeticData -> div8Ceil(headerWidth(a) + a.data.getWidth).U),
        (TLMessages.LogicalData    -> div8Ceil(headerWidth(a) + a.data.getWidth).U),
        (TLMessages.Get            -> div8Ceil(headerWidth(a)).U),
        (TLMessages.Hint           -> div8Ceil(headerWidth(a)).U),
        (TLMessages.AcquireBlock   -> div8Ceil(headerWidth(a)).U),
        (TLMessages.AcquirePerm    -> div8Ceil(headerWidth(a)).U))

    def tlSymbolMap(b: TLBundleB, mask: UInt) = Map(
        (TLMessages.PutFullData    -> div8Ceil(headerWidth(b) + b.data.getWidth).U),
        (TLMessages.PutPartialData -> div8Ceil(headerWidth(b) + b.data.getWidth).U), // This would use the mask
        (TLMessages.ArithmeticData -> div8Ceil(headerWidth(b) + b.data.getWidth).U),
        (TLMessages.LogicalData    -> div8Ceil(headerWidth(b) + b.data.getWidth).U),
        (TLMessages.Get            -> div8Ceil(headerWidth(b)).U),
        (TLMessages.Hint           -> div8Ceil(headerWidth(b)).U),
        (TLMessages.Probe          -> div8Ceil(headerWidth(b)).U))

    def tlSymbolMap(c: TLBundleC, mask: UInt) = Map(
        (TLMessages.AccessAck      -> div8Ceil(headerWidth(c)).U),
        (TLMessages.AccessAckData  -> div8Ceil(headerWidth(c) + c.data.getWidth).U),
        (TLMessages.HintAck        -> div8Ceil(headerWidth(c)).U),
        (TLMessages.ProbeAck       -> div8Ceil(headerWidth(c)).U),
        (TLMessages.ProbeAckData   -> div8Ceil(headerWidth(c) + c.data.getWidth).U),
        (TLMessages.Release        -> div8Ceil(headerWidth(c)).U),
        (TLMessages.ReleaseData    -> div8Ceil(headerWidth(c) + c.data.getWidth).U))

    def tlSymbolMap(d: TLBundleD, mask: UInt) = Map(
        (TLMessages.AccessAck      -> div8Ceil(headerWidth(d)).U),
        (TLMessages.AccessAckData  -> div8Ceil(headerWidth(d) + d.data.getWidth).U),
        (TLMessages.HintAck        -> div8Ceil(headerWidth(d)).U),
        (TLMessages.Grant          -> div8Ceil(headerWidth(d)).U),
        (TLMessages.GrantData      -> div8Ceil(headerWidth(d) + d.data.getWidth).U),
        (TLMessages.ReleaseAck     -> div8Ceil(headerWidth(d)).U))

    def tlSymbolMap(e: TLBundleE, mask: UInt) = Map(
        (TLMessages.GrantAck       -> div8Ceil(headerWidth(e)).U))

    def getNumSymbolsFromType(tl: TLBundle, tlType: UInt, opcode: UInt): UInt = {
        require(tlSymbolMap(tl.e, 0.U).length === 1)
        Mux((tlType === typeA),
            MuxLookup(opcode, 0.U, tlSymbolMap(tl.a, 0.U)),
        Mux((tlType === typeB),
            MuxLookup(opcode, 0.U, tlSymbolMap(tl.b, 0.U)),
        Mux((tlType === typeC),
            MuxLookup(opcode, 0.U, tlSymbolMap(tl.c, 0.U)),
        Mux((tlType === typeD),
            MuxLookup(opcode, 0.U, tlSymbolMap(tl.d, 0.U)),
            tlSymbolMap(tl.e, 0.U)(TLMessages.GrantAck)))))
    }

    // TODO can we collapse these with some type parameter voodoo?
    //def getNumSymbols[T <: TLChannel](chan: T, opcode: UInt, mask: UInt): UInt = {
    def getNumSymbols(chan: TLBundleA, opcode: UInt, mask: UInt): UInt = {
        MuxLookup(opcode, 0.U, tlSymbolMap(chan, mask).toSeq)
    }
    def getNumSymbols(chan: TLBundleB, opcode: UInt, mask: UInt): UInt = {
        MuxLookup(opcode, 0.U, tlSymbolMap(chan, mask).toSeq)
    }
    def getNumSymbols(chan: TLBundleC, opcode: UInt, mask: UInt): UInt = {
        MuxLookup(opcode, 0.U, tlSymbolMap(chan, mask).toSeq)
    }
    def getNumSymbols(chan: TLBundleD, opcode: UInt, mask: UInt): UInt = {
        MuxLookup(opcode, 0.U, tlSymbolMap(chan, mask).toSeq)
    }
    def getNumSymbols(chan: TLBundleE, opcode: UInt, mask: UInt): UInt = {
        MuxLookup(opcode, 0.U, tlSymbolMap(chan, mask).toSeq)
    }

    def packData(data: UInt, mask: UInt): UInt = {
        /* val out = Wire(Vec(mask.getWidth, UInt(8.W)))
        out.zipWithIndex foreach { (d,i) =>
            // This is broken
            //d := out.slice(mask.getWidth-1,i)(PriorityEncoder(mask(mask.getWidth-1,i)))
        }
        out.asUInt
        */
        ???
    }

}

// pack all valid bytes into the lowest-indexed slots available
// e.g.
//
// symbol valid  => symbol
// A      0         C
// B      0         D
// C      1         F
// D      1         F
// E      0         F
// F      1         F
class Packer(entries: Int, width: Int = 8) extends Module {

    val io = IO(new Bundle {
        val in = Vec(entries, Valid(UInt(width.W)))
        val out = Vec(entries, Output((UInt(width.W)))
        val count = Output(UInt())
    })

    io.out := (1 until entries) foldLeft(io.in) { case (prev, stage) =>
        Vec((0 until entries) map { i =>
            if (i >= entries-stage) {
                prev(i)
            } else {
                val next = Wire(Valid(UInt(width.W)))
                next.bits := Mux(prev(i).valid, prev(i), prev(i+1))
                next.valid := prev(i) || prev(i+1)
                next
            }
        })
    } map { _.bits }

    io.count := PopCount(io.in map {_.valid})

}

object Pack {

    def apply(vec: Vec[Valid[UInt]]): (Vec[UInt], UInt) = {
        val mod = Module(new Packer(vec.length, vec(0).bits.getWidth))
        mod.io.in := vec
        (mod.io.out, mod.io.count)
    }

    def apply(bits: UInt, mask: UInt): (Vec[UInt], UInt) = {
        val w = bits.getWidth / mask.getWidth
        require(bits.getWidth % mask.getWidth == 0)
        this.apply(Vec((0 until mask.getWidth) map { i => 
            val v = Wire(Valid(UInt(w.W)))
            v.bits := bits(w * i + w - 1, w * i)
            v.valid := mask(i)
        }))
    }

}
