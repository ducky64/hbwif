package hbwif.tilelink

import hbwif._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy.AddressSet

case class HbwifTLConfig(
    managerAddressSets: Seq[AddressSet],
    configAddressSets: Seq[AddressSet],
    numLanes: Int = 8,
    numBanks: Int = 2,
    beatBytes: Int = 16,
    numXact: Int = 32,
    tluh: Boolean = true,
    tlc: Boolean = true,
    maxOutstanding: Int = 8,
    asyncQueueDepth: Int = 8,
    asyncQueueSync: Int = 3,
    asyncQueueSafe: Boolean = true,
    asyncQueueNarrow: Boolean = true
) {
    require(tluh || !tlc)
}

case object HbwifSerDesKey extends Field[SerDesConfig]
case object HbwifBertKey extends Field[BertConfig]
case object HbwifTLKey extends Field[HbwifTLConfig]
case object HbwifPatternMemKey extends Field[PatternMemConfig]

