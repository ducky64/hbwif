package hbwif2

import chisel3._
import chisel3.util._
import chisel3.experimental.withClockAndReset

case class BertConfig(
    bertSampleCounterWidth: Int = 32,
    bertErrorCounterWidth: Int = 32
)

class BertDebugIO(prbs: Seq[(Int, Int)])(implicit c: SerDesConfig, implicit val b: BertConfig) extends DebugIO {
    val enable = Input(Bool())
    val clear = Input(Bool())
    val prbsLoad = Input(UInt(prbs.map(_._1).max.W))
    val prbsModeTx = Input(UInt(2.W))
    val prbsModeRx = Input(Vec(c.numWays, UInt(2.W)))
    val prbsSelect = Input(UInt(log2Ceil(prbs.length).W))
    val prbsSeedGoods = Output(Vec(c.numWays, Bool()))
    val sampleCount = Input(UInt(b.bertSampleCounterWidth.W))
    val sampleCountOut = Output(UInt(b.bertSampleCounterWidth.W))
    val errorCounts = Output(Vec(c.numWays, UInt(b.bertErrorCounterWidth.W)))
    val berMode = Input(Bool()) // 1 = track BER, 0 = track 1s
}

class BertDebug()(implicit c: SerDesConfig, implicit val b: BertConfig) extends Debug with HasPRBS {

    require((c.dataWidth % c.numWays) == 0)

    val io = IO(new BertDebugIO(prbs()))

    // TODO deal with rx valid and tx ready
    withClockAndReset(io.txClock, io.txReset) {
        val prbsModulesTx = prbs().map(PRBS(_, c.dataWidth))

        io.txOut.bits := Mux(io.enable, MuxLookup(io.prbsSelect, 0.U, prbsModulesTx.zipWithIndex.map { x => (x._2.U, x._1.io.out.asUInt) }), io.txIn.bits)
        io.txIn.ready := Mux(io.enable, false.B, io.txOut.ready)

        prbsModulesTx.foreach { p =>
            p.io.seed := 0.U
            p.io.load := io.prbsLoad
            p.io.mode := io.prbsModeTx
        }
    }

    // XXX TODO clock crossings for controls here
    withClockAndReset(io.rxClock, io.rxReset) {
        val prbsModulesRx = Seq.fill(c.numWays) { prbs().map(PRBS(_, c.dataWidth/c.numWays)) }
        val errorCounts = RegInit(Vec(c.numWays, 0.U(b.bertErrorCounterWidth.W)))
        val sampleCount = RegInit(0.U(b.bertSampleCounterWidth.W))
        val wayData = Seq.fill(c.numWays) { Wire(Vec(c.dataWidth/c.numWays, Bool())) }

        (0 until c.dataWidth) foreach { i => wayData(i % c.numWays)(i / c.numWays) := io.rxIn.bits(i) }

        io.sampleCountOut := sampleCount
        val done = io.sampleCount === io.sampleCountOut

        io.prbsSeedGoods.zip(prbsModulesRx).foreach { case (p, m) => p := MuxLookup(io.prbsSelect, false.B, m.zipWithIndex.map { x => (x._2.U, x._1.io.seedGood) }) }
        io.errorCounts := errorCounts

        val prbsRxData = prbsModulesRx.zip(wayData).zip(io.prbsModeRx).map { case ((w, d), m) =>
            w.foreach { p =>
                p.io.seed := d.asUInt
                p.io.load := io.prbsLoad
                p.io.mode := m
            }
            MuxLookup(io.prbsSelect, 0.U, w.zipWithIndex.map { x => (x._2.U, x._1.io.out.asUInt ) })
        }

        val wayErrors = prbsRxData.zip(wayData).map { case (p, d) => PopCount(Mux(io.berMode, p ^ d.asUInt, d.asUInt)) }

        io.rxOut <> io.rxIn

        when (io.clear) {
            sampleCount := 0.U
            errorCounts.foreach(_ := 0.U)
        } .otherwise {
            when (!done && io.enable) {
                sampleCount := sampleCount + 1.U
                errorCounts.zip(wayErrors).foreach { case (e, w) => e := e + w }
            }
        }
    }

    def connectController(builder: ControllerBuilder) {
        builder.w("bert_enable", io.enable)
        builder.w("bert_clear", io.clear)
        builder.w("bert_prbs_load", io.prbsLoad)
        builder.w("bert_mode_tx", io.prbsModeTx)
        builder.w("bert_prbs_mode_rx", io.prbsModeRx)
        builder.w("bert_prbs_select", io.prbsSelect)
        builder.w("bert_prbs_seed_goods", io.prbsSeedGoods.asUInt)
        builder.w("bert_sample_count", io.sampleCount)
        builder.r("bert_sample_count_out", io.sampleCountOut)
        builder.r("bert_error_counts", io.errorCounts)
        builder.w("bert_ber_mode", io.berMode)
    }

}

object PRBS {
    def apply(prbs: (Int, Int), parallel: Int): PRBS = Module(new PRBS(prbs._1, prbs._2, parallel))
}

class PRBS(prbsWidth: Int, polynomial: Int, parallel: Int) extends Module {

    val io = IO(new Bundle {
        val out = Output(Vec(parallel, Bool()))
        val seedGood = Output(Bool())
        val seed = Input(UInt(parallel.W))
        val load = Input(UInt(prbsWidth.W))
        val mode = Input(UInt(2.W))
    })

    val sLoad :: sSeed :: sRun :: sStop :: Nil = Enum(4)

    val lfsr = RegInit(1.U(prbsWidth.W))

    val nextLfsr = (0 until parallel).foldLeft(lfsr)({ (stage, i) =>
        io.out(i) := stage(prbsWidth-1)
        Cat(stage(prbsWidth-2,0),(stage & polynomial.U).xorR)
    })

    io.seedGood := lfsr.orR

    switch (io.mode) {
        is (sLoad) {
            lfsr := io.load
        }
        is (sSeed) {
            if (parallel >= prbsWidth) {
                lfsr := io.seed(parallel-1, parallel-prbsWidth)
            } else {
                lfsr := Cat(lfsr(prbsWidth-parallel-1,0), io.seed)
            }
        }
        is (sRun) {
            lfsr := nextLfsr
        }
    }

}

trait HasPRBS {
    // each entry is a tuple containing (width, polynomial)
    def prbs(): Seq[(Int, Int)] = Seq()
    require(prbs.length > 0, "must override prbs!")
}

trait HasPRBS7 extends HasPRBS {
    abstract override def prbs() = Seq((7, 0x60)) ++ super.prbs()
}

trait HasPRBS15 extends HasPRBS {
    abstract override def prbs() = Seq((15, 0x6000)) ++ super.prbs()
}

trait HasPRBS31 extends HasPRBS {
    abstract override def prbs() = Seq((31, 0x48000000)) ++ super.prbs()
}

trait HasAllPRBS extends HasPRBS7 with HasPRBS15 with HasPRBS31

trait HasBertDebug extends HasDebug {
    implicit val c: SerDesConfig
    implicit val b: BertConfig
    abstract override def genDebug() = Seq(new BertDebug with HasAllPRBS) ++ super.genDebug()
}