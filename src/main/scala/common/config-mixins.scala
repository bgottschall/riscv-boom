//******************************************************************************
// Copyright (c) 2015 - 2018, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package boom.common

import chisel3._
import chisel3.util.log2Up
import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.subsystem.{RocketCrossingParams, RocketTilesKey, SystemBusKey}
import freechips.rocketchip.devices.tilelink.BootROMParams
import freechips.rocketchip.diplomacy.{AsynchronousCrossing, RationalCrossing, SynchronousCrossing}
import freechips.rocketchip.rocket._
import freechips.rocketchip.tile._
import boom.ifu._
import boom.bpu._
import boom.exu.{IssueParams, _}
import boom.lsu._

case object BoomTilesKey extends Field[Seq[BoomTileParams]](Nil)
case object BoomCrossingKey extends Field[Seq[RocketCrossingParams]](List(RocketCrossingParams()))

// ---------------------
// BOOM Configs
// ---------------------

/**
 * Enables RV32 version of the core
 */
class WithBoomRV32 extends Config((site, here, up) => {
  case XLen => 32
  case BoomTilesKey => up(BoomTilesKey, site) map { b =>
    b.copy(core = b.core.copy(
      fpu = b.core.fpu.map(_.copy(fLen = 32)),
      mulDiv = Some(MulDivParams(mulUnroll = 8))))
  }
})

/**
 * Disable support for C-extension (RVC)
 */
class WithoutBoomRVC extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b =>
    b.copy(core = b.core.copy(
      fetchWidth = b.core.fetchWidth / 2,
      useCompressed = false))
   }
})

/**
 * Remove FPU
 */
class WithoutBoomFPU extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b =>
    b.copy(core = b.core.copy(
      issueParams = b.core.issueParams.filter(_.iqType != IQT_FP.litValue),
      fpu = None))
   }
})

/**
 * Remove Fetch Monitor (should not be synthesized (although it can be))
 */
class WithoutFetchMonitor extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b =>
    b.copy(core = b.core.copy(
      useFetchMonitor = false
    ))
  }
})

/**
 * Customize the amount of perf. counters (HPMs) for the core
 */
class WithNPerfCounters(n: Int) extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(core = b.core.copy(
    nPerfCounters = n
  ))}
})

/**
 * Enable tracing
 */
class WithTrace extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(trace = true) }
})

/**
 * Create multiple copies of a BOOM tile (and thus a core).
 * Override with the default mixins to control all params of the tiles.
 * Default adds small BOOMs.
 *
 * @param n amount of tiles to duplicate
 */
class WithNBoomCores(n: Int) extends Config(
  new WithSmallBooms ++
  new Config((site, here, up) => {
    case BoomTilesKey => {
      List.tabulate(n)(i => BoomTileParams(hartId = i))
    }
  })
)

/**
 * Class to renumber BOOM + Rocket harts so that there are no overlapped harts
 * This mixin assumes Rocket tiles are numbered before BOOM tiles
 * Also makes support for multiple harts depend on Rocket + BOOM
 * Note: Must come after all harts are assigned for it to apply
 */
class WithRenumberHarts(rocketFirst: Boolean = false) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site).zipWithIndex map { case (r, i) =>
    r.copy(hartId = i + (if(rocketFirst) 0 else up(BoomTilesKey, site).length))
  }
  case BoomTilesKey => up(BoomTilesKey, site).zipWithIndex map { case (b, i) =>
    b.copy(hartId = i + (if(rocketFirst) up(RocketTilesKey, site).length else 0))
  }
  case MaxHartIdBits => log2Up(up(BoomTilesKey, site).size + up(RocketTilesKey, site).size)
})

/**
 * Add a synchronous clock crossing to the tile boundary
 */
class WithSynchronousBoomTiles extends Config((site, here, up) => {
  case BoomCrossingKey => up(BoomCrossingKey, site) map { b =>
    b.copy(crossingType = SynchronousCrossing())
  }
})

/**
 * Add an asynchronous clock crossing to the tile boundary
 */
class WithAsynchronousBoomTiles(depth: Int, sync: Int) extends Config((site, here, up) => {
  case BoomCrossingKey => up(BoomCrossingKey, site) map { b =>
    b.copy(crossingType = AsynchronousCrossing(depth, sync))
  }
})

/**
 * Add a rational clock crossing to the tile boundary (used when the clocks are related by a fraction).
 */
class WithRationalBoomTiles extends Config((site, here, up) => {
  case BoomCrossingKey => up(BoomCrossingKey, site) map { b =>
    b.copy(crossingType = RationalCrossing())
  }
})

/**
 * 1-wide BOOM.
 */
class WithSmallBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 1,
      numRobEntries = 32,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=8, iqType=IQT_MEM.litValue, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQT_INT.litValue, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQT_FP.litValue , dispatchWidth=1)
      ),
      numIntPhysRegisters = 52,
      numFpPhysRegisters = 48,
      numLdqEntries = 8,
      numStqEntries = 8,
      maxBrCount = 4,
      numFetchBufferEntries = 8,
      ftq = FtqParameters(nEntries=16),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
                              nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=11, numSets=2048)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                               nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})
class WithSmallInoBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 1,
      numRobEntries = 32,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=8, iqType=IQT_MEM.litValue, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQT_INT.litValue, dispatchWidth=1),
        IssueParams(issueWidth=1, numEntries=8, iqType=IQT_FP.litValue , dispatchWidth=1),
        IssueParams(issueWidth=3, numEntries=1, iqType=IQT_COMB.litValue, dispatchWidth=1), // combined
      ),
      numIntPhysRegisters = 52,
      numFpPhysRegisters = 48,
      numLdqEntries = 8,
      numStqEntries = 8,
      maxBrCount = 4,
      numFetchBufferEntries = 8,
      ftq = FtqParameters(nEntries=16),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
                              nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=11, numSets=2048)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      inoParams = Some(InoParams())
    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                               nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})
class WithSmallInoQueueBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 1,
      numRobEntries = 32,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_MEM.litValue, dispatchWidth=0),
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_INT.litValue, dispatchWidth=0),
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_FP.litValue , dispatchWidth=0),
        IssueParams(issueWidth=3, numEntries=0, iqType=IQT_COMB.litValue, dispatchWidth=0), // combined
      ),
      numIntPhysRegisters = 52,
      numFpPhysRegisters = 48,
      numLdqEntries = 8,
      numStqEntries = 8,
      maxBrCount = 4,
      numFetchBufferEntries = 8,
      ftq = FtqParameters(nEntries=16),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
                              nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=11, numSets=2048)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      inoParams = Some(InoParams(
        queueMode = true,
        queueSize = 1
      )),
      busyLookupParams = Some(BusyLookupParams(
        lookupAtRename = false,
        lookupAtDisWidth = 1
      ))
    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                               nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})
class WithMediumCasBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=0, iqType=IQT_INT.litValue, dispatchWidth=0), // INT
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_MEM.litValue, dispatchWidth=0), // MEM
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_FP.litValue , dispatchWidth=0), // FP
        IssueParams(issueWidth=4, numEntries=0, iqType=IQT_COMB.litValue, dispatchWidth=0), // combined
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
        nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      useAtomics = true,
      usingFPU = true,
      casParams= Some(CasParams(
        numInqEntries = 12,
        numSqEntries = 4,
        slidingOffset = 1,
        windowSize = 2,
        inqDispatches = 2
      )),
      busyLookupParams = Some(BusyLookupParams(
        lookupAtRename = false,
        lookupAtDisWidth = 4
      ))

    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
      nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})


class WithMediumDnbBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=0, iqType=IQT_INT.litValue, dispatchWidth=0), // INT
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_MEM.litValue, dispatchWidth=0), // MEM
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_FP.litValue , dispatchWidth=0), // FP
        IssueParams(issueWidth=4, numEntries=16, iqType=IQT_COMB.litValue, dispatchWidth=2), // combined
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
        nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      useAtomics = true,
      usingFPU = true,
      dnbParams = Some(DnbParams(
        numCrqEntries = 16,
        numDlqEntries = 16,
        crqDispatches = 2,
        dlqDispatches = 2,
      )),
      ibdaParams = Some(IbdaParams(
        ibdaTagType = IBDA_TAG_FULL_PC,
        rdtIstMarkWidth = 1
      )),
      busyLookupParams = Some(BusyLookupParams(
        lookupAtRename = true,
        lookupAtDisWidth = 2
      ))

    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
      nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})
class WithMediumBranchDnbBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=0, iqType=IQT_INT.litValue, dispatchWidth=0), // INT
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_MEM.litValue, dispatchWidth=0), // MEM
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_FP.litValue , dispatchWidth=0), // FP
        IssueParams(issueWidth=4, numEntries=16, iqType=IQT_COMB.litValue, dispatchWidth=2), // combined
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
        nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      useAtomics = true,
      usingFPU = true,
      dnbParams = Some(DnbParams(
        numCrqEntries = 16,
        numDlqEntries = 16,
        crqDispatches = 2,
        dlqDispatches = 2,
      )),
      ibdaParams = Some(IbdaParams(
        ibdaTagType = IBDA_TAG_FULL_PC,
        rdtIstMarkWidth = 1,
        branchIbda = true,
      )),
      busyLookupParams = Some(BusyLookupParams(
        lookupAtRename = true,
        lookupAtDisWidth = 2
      ))

    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
      nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})

/**
 * In order slice BOOM.
 */
class WithMediumSliceBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=0, iqType=IQT_INT.litValue, dispatchWidth=0), // INT
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_MEM.litValue, dispatchWidth=0), // MEM
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_FP.litValue , dispatchWidth=0), // FP
        IssueParams(issueWidth=4, numEntries=0, iqType=IQT_COMB.litValue, dispatchWidth=0), // combined
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
        nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      useAtomics = true,
      usingFPU = true,
      loadSliceCore = Some(LoadSliceCoreParams(
        numAqEntries = 16,
        numBqEntries = 16,
        unifiedIssueQueue = true,
        aDispatches = 2,
        bDispatches = 2
      )),
      ibdaParams = Some(IbdaParams(
        ibdaTagType = IBDA_TAG_FULL_PC,
        rdtIstMarkWidth = 1,
//        bloomIst = true
      )),
      busyLookupParams = Some(BusyLookupParams(
        lookupAtRename = false,
        lookupAtDisWidth = 4
      ))

    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                               nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})

/**
 * 2-wide BOOM. Try to match the Cortex-A9.
 */
class WithMediumBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=16, iqType=IQT_MEM.litValue, dispatchWidth=2), // MEM
        IssueParams(issueWidth=2, numEntries=16, iqType=IQT_INT.litValue, dispatchWidth=2), // INT
        IssueParams(issueWidth=1, numEntries=16, iqType=IQT_FP.litValue , dispatchWidth=2), // FP
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
                              nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                                 nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
    )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)

})

/**
 * 2-wide BOOM. Try to match the Cortex-A9.
 */
class WithMediumInoBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=16, iqType=IQT_MEM.litValue, dispatchWidth=2),
        IssueParams(issueWidth=2, numEntries=16, iqType=IQT_INT.litValue, dispatchWidth=2),
        IssueParams(issueWidth=1, numEntries=16, iqType=IQT_FP.litValue , dispatchWidth=2),
        IssueParams(issueWidth=4, numEntries=2, iqType=IQT_COMB.litValue, dispatchWidth=2),
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
                              nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      inoParams = Some(InoParams())
    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                                 nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
    )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)

})
class WithMediumInoQueueBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 4,
      useCompressed = true,
      decodeWidth = 2,
      numRobEntries = 64,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_MEM.litValue, dispatchWidth=0),
        IssueParams(issueWidth=2, numEntries=0, iqType=IQT_INT.litValue, dispatchWidth=0),
        IssueParams(issueWidth=1, numEntries=0, iqType=IQT_FP.litValue , dispatchWidth=0),
        IssueParams(issueWidth=4, numEntries=0, iqType=IQT_COMB.litValue, dispatchWidth=0),
      ),
      numIntPhysRegisters = 80,
      numFpPhysRegisters = 64,
      numLdqEntries = 16,
      numStqEntries = 16,
      maxBrCount = 8,
      numFetchBufferEntries = 16,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=64, nWays=2,
                              nRAS=8, tagSz=20, bypassCalls=false, rasCheckForEmpty=false),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      nPerfCounters = 12,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true)),
      inoParams = Some(InoParams(
        queueMode = true,
        queueSize = 2
      )),
      busyLookupParams = Some(BusyLookupParams(
        lookupAtRename = false,
        lookupAtDisWidth = 2
      ))
    ),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBits,
                                 nSets=64, nWays=4, nMSHRs=2, nTLBEntries=8)),
    icache = Some(ICacheParams(rowBits = site(SystemBusKey).beatBits, nSets=64, nWays=4, fetchBytes=2*4))
    )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)

})

// DOC include start: LargeBoomConfig
/**
 * 3-wide BOOM. Try to match the Cortex-A15.
 */
class WithLargeBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 8,
      useCompressed = true,
      decodeWidth = 3,
      numRobEntries = 96,
      issueParams = Seq(
        IssueParams(issueWidth=1, numEntries=24, iqType=IQT_MEM.litValue, dispatchWidth=3),
        IssueParams(issueWidth=2, numEntries=24, iqType=IQT_INT.litValue, dispatchWidth=3),
        IssueParams(issueWidth=1, numEntries=24, iqType=IQT_FP.litValue , dispatchWidth=3)),
      numIntPhysRegisters = 100,
      numFpPhysRegisters = 96,
      numLdqEntries = 24,
      numStqEntries = 24,
      maxBrCount = 12,
      numFetchBufferEntries = 24,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=512, nWays=4, nRAS=16, tagSz=20),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBytes*8,
                               nSets=64, nWays=8, nMSHRs=4, nTLBEntries=16)),
    icache = Some(ICacheParams(fetchBytes = 4*4, rowBits = site(SystemBusKey).beatBytes*8, nSets=64, nWays=8))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 16)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})
// DOC include end: LargeBoomConfig

/**
 * 4-wide BOOM.
 */
class WithMegaBooms extends Config((site, here, up) => {
  case BoomTilesKey => up(BoomTilesKey, site) map { b => b.copy(
    core = b.core.copy(
      fetchWidth = 8,
      useCompressed = true,
      decodeWidth = 4,
      numRobEntries = 128,
      issueParams = Seq(
        IssueParams(issueWidth=2, numEntries=32, iqType=IQT_MEM.litValue, dispatchWidth=4),
        IssueParams(issueWidth=3, numEntries=32, iqType=IQT_INT.litValue, dispatchWidth=4),
        IssueParams(issueWidth=2, numEntries=32, iqType=IQT_FP.litValue , dispatchWidth=4)),
      numIntPhysRegisters = 128,
      numFpPhysRegisters = 128,
      numLdqEntries = 32,
      numStqEntries = 32,
      maxBrCount = 16,
      numFetchBufferEntries = 32,
      enablePrefetching=true,
      numDCacheBanks=2,
      ftq = FtqParameters(nEntries=32),
      btb = BoomBTBParameters(btbsa=true, densebtb=false, nSets=512, nWays=4, nRAS=16, tagSz=20),
      bpdBaseOnly = None,
      gshare = Some(GShareParameters(historyLength=23, numSets=4096)),
      tage = None,
      bpdRandom = None,
      fpu = Some(freechips.rocketchip.tile.FPUParams(sfmaLatency=4, dfmaLatency=4, divSqrt=true))),
    dcache = Some(DCacheParams(rowBits = site(SystemBusKey).beatBytes*8,
                               nSets=64, nWays=8, nMSHRs=8, nTLBEntries=32)),
    icache = Some(ICacheParams(fetchBytes = 4*4, rowBits = site(SystemBusKey).beatBytes*8, nSets=64, nWays=8, prefetch=true))
  )}
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 16)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(BoomTilesKey).size)
})
