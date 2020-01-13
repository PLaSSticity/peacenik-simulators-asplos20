package simulator.mesi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import simulator.mesi.MESISim.PARSEC_PHASE;

public class Processor<Line extends MESILine> implements CacheCallbacks<Line> {

	static final Random rng = new Random();

	public final CpuId id;
	/** Machine reference is shared by all processors */
	final Machine<Line> machine;

	/** L1 caches are always present, and are private to each processor */
	public final HierarchicalCache<Line> L1cache;
	/** L2 caches are optional, and (if present) are private to each processor */
	public final HierarchicalCache<Line> L2cache;
	/** the L3 cache is optional, and (if present) is shared by all processors */
	public final HierarchicalCache<Line> L3cache;

	/**
	 * List of all the processors in the system. All processors share the same array
	 * object.
	 */
	final Processor<Line>[] allProcessors;
	final Machine.MachineParams<Line> params;
	final ProcessorStats stats = new ProcessorStats();

	/** Set this to true the first time a region performs a write. */
	boolean regionContainsWrite = false;

	final HashSet<Long> perRegionLocalTable = new HashSet<Long>();

	private short ignoreEvents = 0; // Depth counter to ignore events within a lock acquire if there's any sent from
									// the
	// frontend due to multiplexing. Only used when running with Pacifist backends.

	public long pausedCores = 0L;
	boolean inTrans = false;
	boolean hasTransRestart = false; // if the current transaction has been restarted.

	boolean reRunEvent = false;
	boolean restartRegion = false;

	// counter flags should be reset when the current region finishes without
	// pausing
	boolean hasDirtyEscape = false;
	boolean regionConflicted = false;
	boolean regionWithExceptions = false;
	boolean regionHasDirtyEvictionBeforeFRV = false;
	boolean regionHasDirtyEvictionBeforeDL = false;
	boolean hasDirtyEviction = false;
	boolean regionHasNoTimeout = false;

	double start_time = 0;
	double last_pause = 0;

	double trans_start_time = 0;

	tmpCounter tmpNormalExecBWDrivenCycleCount = new tmpCounter();
	tmpCounter tmpPauseBWDrivenCycleCount = new tmpCounter();

	class ProcessorStats {
		class CacheEventCounter {
			// counters for cache events
			Counter pc_ReadHits;
			Counter pc_ReadMisses;
			Counter pc_WriteHits;
			Counter pc_WriteMisses;
			Counter pc_LineEvictions;

			// for atomic writes
			SumCounter pc_AtomicReadHits;
			SumCounter pc_AtomicReadMisses;
			SumCounter pc_AtomicWriteHits;
			SumCounter pc_AtomicWriteMisses;

			// for lock accesses
			SumCounter pc_LockReadHits;
			SumCounter pc_LockReadMisses;
			SumCounter pc_LockWriteHits;
			SumCounter pc_LockWriteMisses;

			CacheEventCounter(String prefix) {
				pc_ReadHits = new SumCounter("pc_" + prefix + "ReadHits");
				pc_ReadMisses = new SumCounter("pc_" + prefix + "ReadMisses");
				pc_WriteHits = new SumCounter("pc_" + prefix + "WriteHits");
				pc_WriteMisses = new SumCounter("pc_" + prefix + "WriteMisses");
				pc_LineEvictions = new SumCounter("pc_" + prefix + "LineEvictions");
				pc_AtomicWriteHits = new SumCounter("pc_" + prefix + "AtomicWriteHits");
				pc_AtomicWriteMisses = new SumCounter("pc_" + prefix + "AtomicWriteMisses");
				pc_AtomicReadHits = new SumCounter("pc_" + prefix + "AtomicReadHits");
				pc_AtomicReadMisses = new SumCounter("pc_" + prefix + "AtomicReadMisses");
				pc_LockReadHits = new SumCounter("pc_" + prefix + "LockReadHits");
				pc_LockReadMisses = new SumCounter("pc_" + prefix + "LockReadMisses");
				pc_LockWriteHits = new SumCounter("pc_" + prefix + "LockWriteHits");
				pc_LockWriteMisses = new SumCounter("pc_" + prefix + "LockWriteMisses");
			}
		}

		SumCounter pc_MESIReadRemoteHits = new SumCounter("pc_MESIReadRemoteHits");
		SumCounter pc_MESIWriteRemoteHits = new SumCounter("pc_MESIWriteRemoteHits");
		SumCounter pc_MESIUpgradeMisses = new SumCounter("pc_MESIUpgradeMisses");

		/**
		 * Maybe this is not a very useful and is a confusing stat? Since it does not
		 * make sense for streaming operations.
		 */
		SumCounter pc_CoreLLCNetworkMessages = new SumCounter("pc_CoreLLCNetworkMessages");
		SumCounter pc_CoreLLCNetworkMessageSizeBytes = new SumCounter("pc_CoreLLCNetworkMessageSizeBytes");
		SumCounter pc_CoreLLCNetworkMessageSize4BytesFlits = new SumCounter("pc_CoreLLCNetworkMessageSize4BytesFlits");
		SumCounter pc_CoreLLCNetworkMessageSize8BytesFlits = new SumCounter("pc_CoreLLCNetworkMessageSize8BytesFlits");
		SumCounter pc_CoreLLCNetworkMessageSize16BytesFlits = new SumCounter(
				"pc_CoreLLCNetworkMessageSize16BytesFlits");
		SumCounter pc_CoreLLCNetworkMessageSize32BytesFlits = new SumCounter(
				"pc_CoreLLCNetworkMessageSize32BytesFlits");

		SumCounter pc_RegionBoundaries = new SumCounter("pc_RegionBoundaries");
		SumCounter pc_RegionsWithWrites = new SumCounter("pc_RegionsWithWrites");

		SumCounter pc_potentialWrRdValConflicts = new SumCounter("pc_potentialWrRdValConflicts");
		SumCounter pc_ValidationAttempts = new SumCounter("pc_ValidationAttempts");

		SumCounter pc_PreciseConflicts = new SumCounter("pc_PreciseConflicts");
		SumCounter pc_ConflictingRegions = new SumCounter("pc_ConflictingRegions");

		SumCounter pc_RegionsWithTolerableConflicts = new SumCounter("pc_RegionsWithTolerableConflicts");
		SumCounter pc_RegionsWithLongPausingCores = new SumCounter("pc_RegionsWithLongPausingCores");
		SumCounter pc_RegionsTermPausingCoresWoTimeout = new SumCounter("pc_RegionsTermPausingCoresWoTimeout");
		SumCounter pc_PotentialDeadlocks = new SumCounter("pc_PotentialDeadlocks");
		SumCounter pc_RegionsWithPotentialDeadlocks = new SumCounter("pc_RegionsWithPotentialDeadlocks");
		SumCounter pc_RegionsWithExceptions = new SumCounter("pc_RegionsWithExceptions");
		// SumCounter pc_RegionHasDirtyEvictionBeforeFRV = new
		// SumCounter("pc_RegionHasDirtyEvictionBeforeFRV");
		SumCounter pc_RegionHasDirtyEvictionBeforeDL = new SumCounter("pc_RegionHasDirtyEvictionBeforeDL");
		SumCounter pc_RegionHasDLAvoidableByEvictionOpt = new SumCounter("pc_RegionHasDLAvoidableByEvictionOpt");
		// SumCounter pc_ExceptionsByFRVs = new SumCounter("pc_ExceptionsByFRVs");
		SumCounter pc_ExceptionsByPotentialDeadlocks = new SumCounter("pc_ExceptionsByPotentialDeadlocks");
		// SumCounter pc_RegionsWithExceptionsByFRVs = new
		// SumCounter("pc_RegionsWithExceptionsByFRVs");
		SumCounter pc_RegionsWithExceptionsByPotentialDeadlocks = new SumCounter(
				"pc_RegionsWithExceptionsByPotentialDeadlocks");
		SumCounter pc_TotalRegionRestarts = new SumCounter("pc_TotalRegionRestarts");
		SumCounter pc_TotalReboots = new SumCounter("pc_TotalReboots");
		SumCounter pc_TotalRequestRestarts = new SumCounter("pc_TotalRequestRestarts");

		SumCounter pc_NumScavenges = new SumCounter("pc_NumScavenges");

		CacheEventCounter pc_l1d = new CacheEventCounter("Data_L1");
		CacheEventCounter pc_l2d = new CacheEventCounter("Data_L2");
		CacheEventCounter pc_l3d = new CacheEventCounter("Data_L3");
		CacheEventCounter pc_aim = new CacheEventCounter("AIMCache");

		SumCounter pc_TotalDataReads = new SumCounter("pc_TotalDataReads");
		SumCounter pc_TotalDataWrites = new SumCounter("pc_TotalDataWrites");
		SumCounter pc_TotalMemoryAccesses = new SumCounter("pc_TotalMemoryAccesses");
		SumCounter pc_TotalMemoryAccessesSpecialInvalidState = new SumCounter(
				"pc_TotalMemoryAccessesSpecialInvalidState");

		SumCounter pc_TotalAtomicReads = new SumCounter("pc_TotalAtomicReads");
		SumCounter pc_TotalAtomicWrites = new SumCounter("pc_TotalAtomicWrites");
		SumCounter pc_TotalAtomicAccesses = new SumCounter("pc_TotalAtomicAccesses");

		SumCounter pc_TotalLockReads = new SumCounter("pc_TotalLockReads");
		SumCounter pc_TotalLockWrites = new SumCounter("pc_TotalLockWrites");
		SumCounter pc_TotalLockAccesses = new SumCounter("pc_TotalLockAccesses");

		MaxCounter pc_ExecDrivenCycleCount = new MaxCounter("pc_ExecutionDrivenCycleCount");

		// For MESI, exec driven and bandwidth driven mean the same thing.
		DependentCounter pc_MESIMemSystemExecDrivenCycleCount = new DependentCounter(
				"pc_MESIMemSystemExecDrivenCycleCount", pc_ExecDrivenCycleCount);
		DependentCounter pc_MESICoherenceExecDrivenCycleCount = new DependentCounter(
				"pc_MESICoherenceExecDrivenCycleCount", pc_ExecDrivenCycleCount);

		MaxCounter pc_BandwidthDrivenCycleCount = new MaxCounter("pc_BandwidthDrivenCycleCount");

		DependentCounter pc_ViserNormalExecBWDrivenCycleCount = new DependentCounter(
				"pc_ViserNormalExecBWDrivenCycleCount", pc_BandwidthDrivenCycleCount);
		DependentCounter pc_ViserPauseBWDrivenCycleCount = new DependentCounter("pc_ViserPauseBWDrivenCycleCount",
				pc_BandwidthDrivenCycleCount);
		DependentCounter pc_ViserRegionRestartBWDrivenCycleCount = new DependentCounter(
				"pc_ViserRegionRestartBWDrivenCycleCount", pc_BandwidthDrivenCycleCount);
		DependentCounter pc_ViserRebootBWDrivenCycleCount = new DependentCounter("pc_ViserRebootBWDrivenCycleCount",
				pc_BandwidthDrivenCycleCount);

		DependentCounter pc_RequestRestarts = new DependentCounter("pc_RequestRestarts", pc_BandwidthDrivenCycleCount);

		// performance counter for restarting the *whole* application at intolerable
		// conflicts
		// The counter is a *sum* counter because we cannot parallel the restart of the.
		// whole prog.
		SumCounter pc_BandwidthDrivenCycleCountForReboot = new SumCounter("pc_BandwidthDrivenCycleCountForReboot");
		// Per-core request restart can be paralleled, but we want to use this counter
		// to compute Avg. cost of request
		// restarts
		SumCounter pc_BandwidthDrivenCycleCountForRequestRestart = new SumCounter(
				"pc_BandwidthDrivenCycleCountForRequestRestart");

		SumCounter pc_DirtyL2Evictions = new SumCounter("pc_DirtyL2Evictions");
		SumCounter pc_ModifiedLineFetches = new SumCounter("pc_ModifiedLineFetches");
	}

	public Processor(Machine.MachineParams<Line> args, Machine<Line> machine, CpuId cpuid, Processor<Line>[] processors,
			Map<LineAddress, Integer> varmap) {
		this.params = args;
		this.id = cpuid;
		this.machine = machine;
		this.allProcessors = processors;

		/*
		 * NB: hack to get a shared L3. Necessary because we want to have a Processor
		 * object handle L3 cache evictions, and we need to supply a CacheCallbacks
		 * object to the cache ctor. So all the cache construction has to occur inside
		 * the Processor ctor.
		 */
		if (this.allProcessors[0] == null) {
			assert cpuid.get() == 0;
			// Processor 0 (us) is being constructed, so let's build the shared L3.
			// NB: processor 0 handles L3 evictions!
			this.L3cache = new HierarchicalCache<Line>(args.l3config(), this, null, args.lineFactory(),
					(Processor<Line>) this);
		} else {
			// reuse processor 0's L3 reference
			this.L3cache = this.allProcessors[0].L3cache;
		}

		if (args.useL2()) {
			this.L2cache = new HierarchicalCache<Line>(args.l2config(), this, this.L3cache, args.lineFactory(),
					(Processor<Line>) this);
			this.L1cache = new HierarchicalCache<Line>(args.l1config(), this, this.L2cache, args.lineFactory(),
					(Processor<Line>) this);
		} else {
			assert false : "L2 is required in the current design";
			this.L2cache = null;
			this.L1cache = new HierarchicalCache<Line>(args.l1config(), this, this.L3cache, args.lineFactory(),
					(Processor<Line>) this);
		}
	}

	@Override
	public String toString() {
		return "Processor:" + id.toString();
	}

	public Epoch getCurrentEpoch() {
		assert params.conflictExceptions();
		return machine.getEpoch(id);
	}

	public void insnsExecuted(int n) {
		stats.pc_ExecDrivenCycleCount.incr(n);
		stats.pc_MESIMemSystemExecDrivenCycleCount.incr(n);
		updateBWCycles(n);
	}

	private void memoryCyclesElapsed(int n, DataMemoryAccessResult mor, boolean coherenceMessage) {
		stats.pc_ExecDrivenCycleCount.incr(n);
		if (mor != null) {
			mor.latency += n;
		}
		updateBWCycles(n);
		if (coherenceMessage) {
			stats.pc_MESICoherenceExecDrivenCycleCount.incr(n);
		} else {
			stats.pc_MESIMemSystemExecDrivenCycleCount.incr(n);
		}
	}

	/** Returns which level is the last-level cache in the system. */
	CacheLevel llc() {
		if (L3cache != null) {
			return CacheLevel.L3;
		} else if (L2cache != null) {
			return CacheLevel.L2;
		} else
			return CacheLevel.L1;
	}

	public void updateBWCycles(double cycles) {
		tmpNormalExecBWDrivenCycleCount.incr(cycles);
		stats.pc_BandwidthDrivenCycleCount.incr(cycles);
	}

	@Override
	public Line eviction(final Line incoming, final LinkedList<Line> set, final CacheLevel level, short bits) {
		Line toEvict = null;
		if (params.usePLRU()) {
			for (int i = 0; i < set.size(); i++) {
				Line line = set.get(i);
				if (!line.valid()) {
					toEvict = line;
					break;
				}
			}
			if (toEvict == null) { // need to evict a valid line
				for (int i = 0; i < set.size(); i++) {
					if ((bits & (1 << i)) == 0) {
						toEvict = set.get(i);
						break;
					}
				}
			}
		} else {
			toEvict = set.getLast(); // straight LRU

			if ((params.evictCleanLineFirst() && !hasDirtyEviction) && level == CacheLevel.L2 && toEvict.valid()) {
				List<Line> tmp = new ArrayList<Line>(set.size());
				boolean notPreferred;
				long enc;
				do {
					Line l1Line = L1cache.getLine(toEvict);

					if (l1Line != null && l1Line.valid())
						enc = l1Line.getLocalWrites() | toEvict.getLocalWrites();
					else
						enc = toEvict.getLocalWrites();
					notPreferred = enc != 0L;

					if (notPreferred && set.size() > 1) { // WAR or dirty line
						set.removeLast();
						tmp.add(toEvict);
						toEvict = set.getLast();
					} else
						break;
				} while (true);

				for (int i = tmp.size() - 1; i >= 0; i--)
					set.addLast(tmp.get(i));
				if (notPreferred) {
					// System.out.println("WAR Eviction from " + id);
					toEvict = set.getLast();
				}
			}
		}

		if (toEvict.isPrivateCacheLine()) {
			// Valid bit and MESI state should be in sync for private cache lines excepting
			// for one case.
			// A L2 line on eviction will simply invalidate the L1 line without changing its
			// state. So a line
			// can be invalid with the MESI state being valid.
			assert (toEvict.getState() == MESIState.MESI_INVALID) ? toEvict.invalid() : true;
			assert toEvict.valid() ? (toEvict.getState() != MESIState.MESI_INVALID) : true;
		}

		if (toEvict.valid()) {
			switch (level) {
			case L1: {
				stats.pc_l1d.pc_LineEvictions.incr();
				break;
			}
			case L2: {
				stats.pc_l2d.pc_LineEvictions.incr();
				// We include this cost of eviction, since MESI directory protocol requires
				// an ACK as a response to be sequentially consistent.
				// M->I, M->I, or E->I, E->I, or S->I, S->I/S
				stats.pc_ExecDrivenCycleCount.incr(MemorySystemConstants.DIRECTORY_LATENCY);
				stats.pc_MESICoherenceExecDrivenCycleCount.incr(MemorySystemConstants.DIRECTORY_LATENCY);
				updateBWCycles(MemorySystemConstants.DIRECTORY_LATENCY);

				// Round-trip between L2 cache and Directory (i.e., LLC)
				switch (toEvict.getState()) {
				case MESI_MODIFIED: { // Line is dirty
					stats.pc_DirtyL2Evictions.incr();
					break;
				}

				case MESI_EXCLUSIVE:
				case MESI_SHARED: {
					// Two control messages
					break;
				}
				default: {
					assert false;
				}
				}

				if (params.conflictExceptions()) {
					// Possibly being counted twice. L2 evictions can happen normally and due to
					// recall.
					// // Backup the access bits in memory
					// if (toEvict.isRead() || toEvict.isWritten()) {
					// int sizeBytesOutgoing = MemorySystemConstants.CONTROL_MESSAGE_SIZE_BYTES +
					// MemorySystemConstants.CE_READ_METADATA_BYTES +
					// MemorySystemConstants.CE_WRITE_METADATA_BYTES;
					// updateTrafficForLLCToMemoryMessage(sizeBytesOutgoing);
					// }
				}

				break;
			}
			case L3: {
				stats.pc_l3d.pc_LineEvictions.incr();
				// Dirty line will have to be written back to memory, but we count it from
				// request() since an
				// LLC eviction includes recall. The LLC line may not be dirty, but a dirty
				// L1/L2 line implies
				// a write back.
				break;
			}
			case MEMORY:
			default: {
				assert false : "Wrong level";
			}
			}
		} else {
			if (params.conflictExceptions() && toEvict.lineAddress() != null && toEvict.getLevel() == CacheLevel.L2) {
				// The access and the supplied bits need to be backed up in the global table
				if (toEvict.isSupplied() || toEvict.isRead() || toEvict.isWritten()) {
					CEPerLineMetadata<Line> md = machine.globalTable.get(toEvict.lineAddress().get());
					if (md == null) {
						md = new CEPerLineMetadata<Line>(params.numProcessors());
					}
					CEGlobalTableValue val = md.getPerCoreMetadata(this);
					val.localReads |= toEvict.getLocalReads();
					val.localWrites |= toEvict.getLocalWrites();
					val.setSiteInfo(toEvict.readSiteInfo, toEvict.writeSiteInfo);
					if (toEvict.isSupplied()) {
						val.supplied = true;
					}
					val.regionID = getCurrentEpoch().getRegionId();
					md.setPerCoreMetadata(id, val);
					machine.globalTable.put(toEvict.lineAddress().get(), md);
				}
			}
		}
		return toEvict;
	}

	public void printPerRegionLocalTable() {
		System.out.println("Per-region Local Table for :" + this);
		for (Long l : perRegionLocalTable) {
			System.out.println("Line address:" + l);
		}
	}

	public static class DataMemoryAccessResult {
		/** The latency of this memory operation, in cycles. */
		int latency = 0;
		/**
		 * true if remote communication happened, due to remote hit or LLC miss, false
		 * on a purely local hit
		 */
		boolean remoteCommunicatedHappened = false;

		/** Aggregate the result of another memory op into the current result. */
		void aggregate(DataMemoryAccessResult dmar) {
			this.remoteCommunicatedHappened |= dmar.remoteCommunicatedHappened;
			this.latency = Math.max(this.latency, dmar.latency);
		}
	}

	private boolean reportConflict(String preop, String curop, LineAddress addr, CpuId cpuId, int[] preSiIndex,
			int[] curSiIndex, long preenc, long curenc) {
		/*
		 * rtnCoveredSet.clear(); srcCoveredSet.clear();
		 */
		boolean preciseConflict = false;
		boolean print = params.printConflictingSites();
		long enc = preenc & curenc;

		/*
		 * boolean noSrcInfo = false; boolean noFuncName = false;
		 */
		for (int off = 0; off < MemorySystemConstants.LINE_SIZE(); off++) {
			if (((1L << off) & enc) != 0) {
				SiteInfoEntry curSi = machine.siteInfo.get(curSiIndex[off]);
				SiteInfoEntry preSi = machine.siteInfo.get(preSiIndex[off]);
				int curfno = curSi.fileIndexNo;
				int curlno = curSi.lineNo;
				int currno = curSi.routineIndexNo;
				int prefno = preSi.fileIndexNo;
				int prelno = preSi.lineNo;
				int prerno = preSi.routineIndexNo;
				/*
				 * rtnCoveredSet.add(currno); rtnCoveredSet.add(prerno);
				 * srcCoveredSet.add(curfno); srcCoveredSet.add(prefno);
				 */
				/*
				 * if (curfno == 0 || curlno == 0 || prefno == 0 || prelno == 0) { noSrcInfo =
				 * true; // break; } if (currno == 0 || prerno == 0) noFuncName = true;
				 */
				if (curlno != 0 && prelno != 0) {
					preciseConflict = true;
					if (print) {
						System.out.println("\tcurrent " + curop + " by " + id + " at " + curfno + ":" + curlno + ":"
								+ currno + ".");
						System.out.println("\tprevious " + preop + " by " + cpuId + " at " + prefno + ":" + prelno + ":"
								+ prerno + ".");
					}
				}
				machine.updateConflictCounters(curfno, curlno, currno, prefno, prelno, prerno);
			}
		}
		// reset the counters to allow counting for other lines.
		machine.resetConflictCounter();
		if (print && preciseConflict) {
			System.out.println(
					"[cesim] Above " + preop + "-" + curop + " conflict is detected at " + addr.lineAddr + ".");
		}
		/*
		 * for (int fno : srcCoveredSet) { machine.srcCoverage[fno]++; } for (int rno :
		 * rtnCoveredSet) { machine.rtnCoverage[rno]++; } if (noSrcInfo)
		 * stats.pc_ConflictsWithoutSrcInfo.incr(); if (noFuncName)
		 * stats.pc_ConflictsWithoutFuncName.incr();
		 */
		return preciseConflict;
	}

	void handleConflict(short cpuId) {
		if (params.pauseCoresAtConflicts()) {
			if (!regionConflicted) {
				regionConflicted = true;
				stats.pc_RegionsWithTolerableConflicts.incr();
			}

			machine.processors[cpuId].setPausedCores(id.get());
			short[] waitee = new short[params.numProcessors()];
			short[] ends = new short[2];
			if (!machine.detectDeadlock(waitee, ends)) {
				last_pause = stats.pc_BandwidthDrivenCycleCount.stat;
				start_time = machine.processors[cpuId].stats.pc_BandwidthDrivenCycleCount.stat;
				machine.setPausingBitsAtOffset(id.get());

				if (params.printConflictingSites()) {
					System.out.println("[Current proc: " + id + "] Pause the core " + id + " at a conflict with p"
							+ cpuId + " current: " + last_pause + " start: " + start_time);
				}
				reRunEvent = true;
			} else {
				handleDeadlock(cpuId, waitee, ends);
			}
		} else {
			if (!regionWithExceptions) {
				if (!regionConflicted) {
					regionConflicted = true;
					stats.pc_RegionsWithTolerableConflicts.incr();

					stats.pc_RegionsWithExceptions.incr();
					regionWithExceptions = true;
					if (params.isHttpd() && inTrans) { // restart transaction
						updateStatsForTransRestart();
					} else {
						updateStatsForReboot();
					}
				}
			}
		}
	}

	private boolean restartAnEligibleRegionFormAnotherCore(short[] waitee, short[] ends, short rid) {
		short i;
		short cid = id.get();
		if (cid == ends[1]) // ends[1] has no father node
			i = ends[0];
		else
			i = waitee[cid];
		while (i != cid) {
			if (!machine.processors[i].hasDirtyEviction && !machine.processors[i].hasDirtyEscape) { // eligible for
																									// restart
				// pause the current core
				machine.processors[rid].setPausedCores(cid);
				// if (last_pause != 0)
				// stats.pc_TotalIntervals.incr(stats.pc_BandwidthDrivenCycleCount.stat -
				// last_pause);
				last_pause = stats.pc_BandwidthDrivenCycleCount.stat;
				start_time = machine.processors[rid].stats.pc_BandwidthDrivenCycleCount.stat;
				machine.setPausingBitsAtOffset(cid);

				Processor<Line> eligileCore = machine.processors[i];
				// resume the core with an eligible region
				eligileCore.reRunEvent = false;
				machine.clearPausingBitsAtOffset(i);
				Processor<Line> waiteeCore;
				if (i != ends[1]) {
					waiteeCore = machine.processors[waitee[i]];
				} else
					waiteeCore = machine.processors[ends[0]];

				double end_time = waiteeCore.stats.pc_BandwidthDrivenCycleCount.stat;
				double paused_cycles = end_time - eligileCore.start_time;
				if (paused_cycles < 0) {
					throw new RuntimeException("paused_cycles < 0: " + end_time + " " + eligileCore.start_time + " "
							+ waiteeCore + " " + eligileCore);
				} else {
					// eligileCore.stats.pc_BandwidthDrivenCycleCount.incr(paused_cycles);
					// eligileCore.updatePhaseBWDrivenCycleCost(ExecutionPhase.PAUSE,
					// paused_cycles);
					eligileCore.stats.pc_BandwidthDrivenCycleCount.incr(paused_cycles);
					eligileCore.stats.pc_ViserPauseBWDrivenCycleCount.incr(paused_cycles);
				}
				waiteeCore.clearPausedCores(i);

				// restart the core with an eligible region
				eligileCore.stats.pc_TotalRegionRestarts.incr();
				eligileCore.prepareRestart();
				MESISim.pos[i] = 0;
				MESISim.totalRegionRestarts++;
				System.out.println("\t" + "p" + i + " will restart the current region. ");

				// Does the current core still need to pause?
				if ((machine.getPausingBits() & (1L << cid)) == 0)
					reRunEvent = false;
				else
					reRunEvent = true;

				return true;
			}
			if (i != ends[1])
				i = waitee[i];
			else
				i = ends[0];
		}
		return false;
	}

	public void prepareRestart() {
		/* System.out.println("Core " + id + " is preparing restart..."); */
		// Epoch currentEp = getCurrentEpoch();
		// Epoch nextEp = new Epoch(currentEp.getRegionId() + 1);

		// invalidate touched lines
		// HashSet<Line> skippedL2Lines = new HashSet<Line>();
		if (params.useL2()) {
			// performInvalidation(CacheLevel.L2, skippedL2Lines, nextEp);
			clearPrivateCacheMetadata(CacheLevel.L2);
		}
		// performInvalidation(CacheLevel.L1, skippedL2Lines, nextEp);
		clearPrivateCacheMetadata(CacheLevel.L1);

		// increase epoch to clear read/write metadata in the LLC
		machine.incrementEpoch(id);

		// rerun those cores which have been waiting for the core.
		reRunPausedCores();

		stats.pc_RegionBoundaries.incr();
		resetFlagsForRegionCounters();
	}

	private void handleDeadlock(short cpuId, short[] waitee, short[] ends) {
		if (params.printConflictingSites()) {
			System.out.println("Potential deadlocks between the current core " + id + " and P" + cpuId);
		}
		machine.processors[cpuId].clearPausedCores(id.get());
		stats.pc_PotentialDeadlocks.incr();
		if (!regionWithExceptions) {
			stats.pc_RegionsWithPotentialDeadlocks.incr();

			if (!regionHasDirtyEvictionBeforeDL && (hasDirtyEscape || hasDirtyEviction)) {
				stats.pc_RegionHasDirtyEvictionBeforeDL.incr();
				regionHasDirtyEvictionBeforeDL = true;
				/*
				 * if (!hasDirtyEscape || restartAnEligibleRegionFormAnotherCore(waitee, ends,
				 * cpuId)) { stats.pc_RegionHasDLAvoidableByEvictionOpt.incr(); }
				 */
			}
			boolean restartable = false;
			if (params.restartAtFailedValidationsOrDeadlocks()) {
				if (!hasDirtyEviction && !hasDirtyEscape) {
					restartable = true;
					stats.pc_TotalRegionRestarts.incr();
					assert !restartRegion;
					restartRegion = true;
					prepareRestart();
				} else if (restartAnEligibleRegionFormAnotherCore(waitee, ends, cpuId)) {
					// check for an eligible region from other cores involved in the deadlock
					restartable = true;
				}
			}

			if (!restartable) {
				stats.pc_ExceptionsByPotentialDeadlocks.incr();
				stats.pc_RegionsWithExceptionsByPotentialDeadlocks.incr();

				stats.pc_RegionsWithExceptions.incr();
				regionWithExceptions = true;

				if (params.isHttpd() && inTrans) { // restart transaction
					updateStatsForTransRestart();
				} else {
					updateStatsForReboot();
				}

			}
		}
	}

	// compute the performance cost of restarting the whole application at
	// intolerable conflicts.
	public void updateStatsForReboot() {
		stats.pc_TotalReboots.incr();

		if (params.isHttpd()) {
			return; // use fixed constants as restart costs
		}
		// The cost of restart should be the exec cost that the whole prog has spent so
		// far,
		// i.e., the current max_BandwidthDrivenCycleCount.
		double restartCost = 0;
		double BDcost;

		for (int i = 0; i < params.numProcessors(); i++) {
			BDcost = machine.processors[i].stats.pc_BandwidthDrivenCycleCount.get();
			if (BDcost > restartCost)
				restartCost = BDcost;
		}
		// The counter is a sum counter because we cannot parallel the costs of
		// restarting the whole prog.
		// cycles
		stats.pc_BandwidthDrivenCycleCountForReboot.incr(restartCost);
	}

	public void setTransactionStart() {
		inTrans = true;
		hasTransRestart = false;
		trans_start_time = stats.pc_BandwidthDrivenCycleCount.get();
	}

	public void updateStatsForTransRestart() {
		stats.pc_TotalRequestRestarts.incr();
		stats.pc_RequestRestarts.incr();

		// cycles
		double restartCycleCost = stats.pc_BandwidthDrivenCycleCount.get() - trans_start_time;
		stats.pc_BandwidthDrivenCycleCountForRequestRestart.incr(restartCycleCost);
	}

	public boolean checkPreciseWriteWriteConflict(Line line, long remoteWrites, long localWrites, int[] curSI) {
		if (!(!params.isHttpd() && (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
				|| MESISim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI)) { // Not in ROIs
			return false;
		}

		LineAddress address = line.lineAddress();

		long tmp = remoteWrites & localWrites;
		if (tmp != 0) {
			for (short i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
				if ((tmp & (1L << i)) != 0) {
					CEPerLineMetadata<Line> md = machine.globalTable.get(address.get());
					for (Processor<Line> p : allProcessors) {
						if (p == this) {
							continue;
						}
						MemoryResponse<Line> resp = p.L1cache.ceSearchPrivateCaches(new DataByteAddress(address.get()),
								false);
						boolean conflict = false;
						if (resp.lineHit != null && (resp.lineHit.getLocalWrites() & (1L << i)) != 0) {
							Line l = resp.lineHit;
							conflict = !params.siteTracking() || reportConflict("write", "write", address, p.id,
									l.getWriteSiteInfo(), curSI, l.getLocalWrites(), localWrites);
						} else if (resp.lineHit == null && md != null && md.getPerCoreMetadata(p) != null
								&& (md.getPerCoreMetadata(p).localWrites & (1L << i)) != 0) {
							conflict = !params.siteTracking() || reportConflict("write", "write", address, p.id,
									md.getPerCoreMetadata(p).writeSiteInfo, curSI, md.getPerCoreMetadata(p).localWrites,
									localWrites);
						}

						if (conflict) {
							handleConflict(p.id.get());
							if (params.printConflictingSites()) {
								System.out.println("A WW conflict is detected between " + p.id + " and " + id);
							}
							return true;
						}
					}
					// No conflict: the line was written by the current core before.
				}
			}
		}
		return false;

	}

	public boolean checkPreciseWriteReadConflict(Line line, long remoteWrites, long localReads, int[] curSI) {
		if (!(!params.isHttpd() && (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
				|| MESISim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI)) { // Not in ROIs
			return false;
		}

		LineAddress address = line.lineAddress();

		long tmp = remoteWrites & localReads;
		if (tmp != 0) {
			for (short i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
				if ((tmp & (1L << i)) != 0) {
					CEPerLineMetadata<Line> md = machine.globalTable.get(address.get());
					for (Processor<Line> p : allProcessors) {
						if (p == this) {
							continue;
						}
						MemoryResponse<Line> resp = p.L1cache.ceSearchPrivateCaches(new DataByteAddress(address.get()),
								false);

						boolean conflict = false;
						if (resp.lineHit != null && (resp.lineHit.getLocalWrites() & (1L << i)) != 0) {
							Line l = resp.lineHit;
							conflict = !params.siteTracking() || reportConflict("write", "read", address, p.id,
									l.getWriteSiteInfo(), curSI, l.getLocalWrites(), localReads);
						} else if (resp.lineHit == null && md != null && md.getPerCoreMetadata(p) != null
								&& (md.getPerCoreMetadata(p).localWrites & (1L << i)) != 0) {
							conflict = !params.siteTracking() || reportConflict("write", "read", address, p.id,
									md.getPerCoreMetadata(p).writeSiteInfo, curSI, md.getPerCoreMetadata(p).localWrites,
									localReads);
						}

						if (conflict) {
							handleConflict(p.id.get());
							if (params.printConflictingSites()) {
								System.out.println("A WR conflict is detected between " + p.id + " and " + id);
							}
							return true;
						}
					}
					assert params.siteTracking() : "there must be a remote core writting the line";
				}
			}
		}
		return false;
	}

	public boolean checkPreciseReadWriteConflict(Line line, long remoteReads, long localWrites, int[] curSI) {
		if (!(!params.isHttpd() && (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
				|| MESISim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI)) { // Not in ROIs
			return false;
		}

		LineAddress address = line.lineAddress();

		long tmp = remoteReads & localWrites;
		if (tmp != 0) {
			for (short i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
				if ((tmp & (1L << i)) != 0) {
					CEPerLineMetadata<Line> md = machine.globalTable.get(address.get());
					for (Processor<Line> p : allProcessors) {
						if (p == this) {
							continue;
						}
						MemoryResponse<Line> resp = p.L1cache.ceSearchPrivateCaches(new DataByteAddress(address.get()),
								false);

						boolean conflict = false;
						if (resp.lineHit != null && (resp.lineHit.getLocalReads() & (1L << i)) != 0) {
							Line l = resp.lineHit;
							conflict = !params.siteTracking() || reportConflict("read", "write", address, p.id,
									l.getReadSiteInfo(), curSI, l.getLocalReads(), localWrites);
							// System.out.println("core: " + (localWrites & l.getLocalReads()));
						} else if (resp.lineHit == null && md != null && md.getPerCoreMetadata(p) != null
								&& (md.getPerCoreMetadata(p).localReads & (1L << i)) != 0) {
							conflict = !params.siteTracking() || reportConflict("read", "write", address, p.id,
									md.getPerCoreMetadata(p).readSiteInfo, curSI, md.getPerCoreMetadata(p).localReads,
									localWrites);
							// System.out.println("global table conflict");
						}

						if (conflict) {
							handleConflict(p.id.get());
							if (params.printConflictingSites()) {
								System.out.println("A RW conflict is detected between " + p.id + " and " + id);
							}
							return true;
						}
					}
					assert false : "there must be a remote core reading the line";
				}
			}
		}
		return false;
	}

	// full bit map, we want to be precise at the byte-level
	long getEncodingForAccess(DataAccess access) {
		long tmp = 0;
		ByteAddress start = new DataByteAddress(access.addr().get());
		for (int i = 0; i < access.size(); i++) {
			tmp |= getEncodingForOffset(start.lineOffset());
			start.incr();
		}
		return tmp;
	}

	private long getEncodingForOffset(int off) {
		return 1L << off;
	}

	/** Perform a data read specified by the given access. */
	public DataMemoryAccessResult read(final DataAccess access) {
		if (access.isAtomic()) {
			stats.pc_TotalAtomicReads.incr();
			stats.pc_TotalAtomicAccesses.incr();
		} else if (access.isLockAccess()) {
			stats.pc_TotalLockReads.incr();
			stats.pc_TotalLockAccesses.incr();
		} else {
			stats.pc_TotalDataReads.incr();
			stats.pc_TotalMemoryAccesses.incr();
		}

		DataMemoryAccessResult dmaResult = new DataMemoryAccessResult();
		dmaResult.remoteCommunicatedHappened = false;

		MemoryResponse<Line> resp = L1cache.request(this, access.addr(), true);
		Line line = resp.lineHit;
		assert line.id() == this.id : "The owner of a private line should always be the current core";
		assert line.valid() && line.getLevel() == CacheLevel.L1;

		switch (resp.whereHit) {
		case L1: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicReadHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockReadHits.incr();
			} else {
				stats.pc_l1d.pc_ReadHits.incr();
			}

			memoryCyclesElapsed(MemorySystemConstants.L1_HIT_LATENCY, dmaResult, false);

			if (MESISim.assertsEnabled) {
				Line l2Line = L2cache.getLine(line);
				assert l2Line.getLevel() == CacheLevel.L2 && line.dirty() == l2Line.dirty();
				assert l2Line.getState() == line.getState() : "L1 and L2 state should match";
			}

			if (MESISim.enableXasserts()) {
				Verify.verifyCacheInclusivity(this);
				Verify.verifyStateOfLLCOnlyLines();
				Verify.verifyModifiedLines(this);
				Verify.verifyExclusiveAndSharedLines(this);
				Verify.verifyInvalidLinesInLLC();
				Verify.verifyCacheIndexing();
				Verify.verifyL1AndL2Lines(this);
				Verify.verifyExecutionCostBreakdown(this);
			}
			break;
		}

		case L2: {
			assert params.useL2();
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicReadHits.incr();
				}
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockReadHits.incr();
				}
			} else {
				stats.pc_l1d.pc_ReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_ReadHits.incr();
				}
			}
			memoryCyclesElapsed(MemorySystemConstants.L2_HIT_LATENCY, dmaResult, false);

			if (MESISim.assertsEnabled) {
				Line l2Line = L2cache.getLine(line);
				assert l2Line.getLevel() == CacheLevel.L2 && l2Line.dirty() == line.dirty();
				assert l2Line.getState() == line.getState() : "L1 and L2 state should match";
			}

			if (MESISim.enableXasserts()) {
				Verify.verifyCacheInclusivity(this);
				Verify.verifyStateOfLLCOnlyLines();
				Verify.verifyModifiedLines(this);
				Verify.verifyExclusiveAndSharedLines(this);
				Verify.verifyInvalidLinesInLLC();
				Verify.verifyCacheIndexing();
				Verify.verifyL1AndL2Lines(this);
				Verify.verifyExecutionCostBreakdown(this);
			}
			break;
		}

		case L3: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicReadMisses.incr();
				}
				stats.pc_l3d.pc_AtomicReadHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockReadMisses.incr();
				}
				stats.pc_l3d.pc_LockReadHits.incr();
			} else {
				stats.pc_l1d.pc_ReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_ReadMisses.incr();
				}
				stats.pc_l3d.pc_ReadHits.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult, false);
			break;
		}

		case MEMORY: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicReadMisses.incr();
				}
				stats.pc_l3d.pc_AtomicReadMisses.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockReadMisses.incr();
				}
				stats.pc_l3d.pc_LockReadMisses.incr();
			} else {
				stats.pc_l1d.pc_ReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_ReadMisses.incr();
				}
				stats.pc_l3d.pc_ReadMisses.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.MEMORY_LATENCY, dmaResult, false);
			break;
		}
		}

		// If we missed in the LLC, then remote caches should not have the data.
		// Or we hit in the LLC. We need to check the state since the directory may not
		// own the data, and hence
		// we need to check the remote caches for appropriate state changes.

		MESIState newMesiState = MESIState.MESI_INVALID;
		Processor<Line> lastWriter = null;

		if (resp.whereHit == CacheLevel.MEMORY) {
			/* NB: we always fetch from memory into Exclusive. */
			newMesiState = MESIState.MESI_EXCLUSIVE;

			if (MESISim.assertsEnabled) {
				Line llcLine = L3cache.getLine(line);
				assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
			}

			if (params.conflictExceptions()) {
				// If any other cache indicates it has local read bits set for the requested
				// line,
				// the line is brought in from memory in shared state
				CEPerLineMetadata<Line> md = machine.globalTable.get(line.lineAddress().get());
				if (md != null) {
					for (int i = 0; i < params.numProcessors(); i++) {
						CpuId cpudID = new CpuId(i);
						CEGlobalTableValue val = md.getPerCoreMetadata(machine.getProc(cpudID));
						if (val.localReads != 0) {
							newMesiState = MESIState.MESI_SHARED;
							break;
						}
					}
				}
				Line llcLine = L3cache.getLine(line);
				llcLine.changeStateTo(newMesiState);
			}

		} else if (resp.whereHit == CacheLevel.L3) {
			// Exclusive state is considered a owned state.

			// Remote processors MAY have the line cached
			RemoteReadResponse rrr = performRemoteRead(access);
			lastWriter = rrr.lastWriter;

			// Take care of incoming access bits
			if (params.conflictExceptions()) {
				line.orRemoteWrites(rrr.orCombinedWriteEnc);
				// Set the l2 line
				Line l2Line = L2cache.getLine(line);
				assert l2Line != null;
				l2Line.orRemoteWrites(rrr.orCombinedWriteEnc);
				assert line.getRemoteWrites() == l2Line.getRemoteWrites();
			}

			if (rrr.isShared) { // One remote cache had the data in S state
				// I->S, S->S
				newMesiState = MESIState.MESI_SHARED;

			} else if (rrr.providedData) { // One remote cache had the data in E/M state
				// I->S, M/E->S, M/E->S

				dmaResult.remoteCommunicatedHappened = true;
				// Valid Read-Reply From Modified/Exclusive
				stats.pc_MESIReadRemoteHits.incr();

				// Remote core replies with the data to both the current core and to the
				// directory -- data message.
				// We have already accounted for one full LLC latency.
				memoryCyclesElapsed(
						Math.max(MemorySystemConstants.REMOTE_HIT_LATENCY, MemorySystemConstants.DIRECTORY_ACCESS),
						dmaResult, true);

				newMesiState = MESIState.MESI_SHARED;

			} else {
				// I->E, I->E

				if (MESISim.assertsEnabled) {
					Line llcLine = L3cache.getLine(line);
					assert llcLine.valid() && llcLine.getState() == MESIState.MESI_INVALID;
				}

				newMesiState = MESIState.MESI_EXCLUSIVE;
			}

			// We need to do this since we assume that the directory lives in the LLC
			Line llcLine = L3cache.getLine(line);
			assert llcLine != null && llcLine.valid();
			llcLine.changeStateTo(newMesiState);
		}

		Line l2Line = L2cache.getLine(line);
		if (newMesiState != MESIState.MESI_INVALID) {
			line.changeStateTo(newMesiState);
			assert l2Line.getLevel() == CacheLevel.L2 && l2Line.valid();
			l2Line.changeStateTo(newMesiState);
		}

		if (params.conflictExceptions() && access.isRegularMemAccess()) {
			// Set the local bit first to allow detecting conflicts involving the current
			// access
			long enc = getEncodingForAccess(access);
			// Check for conflicts
			long remoteWrites = line.getRemoteWrites();
			if (checkPreciseWriteReadConflict(line, remoteWrites, enc, access.siteInfo())) {
				stats.pc_PreciseConflicts.incr();
				if (reRunEvent || restartRegion) {
					return dmaResult;
				}
			}

			if (lastWriter != null && params.dirtyEscapeOpt()) {
				lastWriter.hasDirtyEscape = true;
			}
			line.orLocalReads(enc);
			l2Line.orLocalReads(enc);
			line.updateReadSiteInfo(enc, access.siteInfo());
			l2Line.updateReadSiteInfo(enc, access.siteInfo());
		}

		if (MESISim.enableXasserts()) {
			Verify.verifyCacheInclusivity(this);
			Verify.verifyStateOfLLCOnlyLines();
			Verify.verifyModifiedLines(this);
			Verify.verifyExclusiveAndSharedLines(this);
			Verify.verifyInvalidLinesInLLC();
			Verify.verifyModifiedLLCLines(this);
			Verify.verifyCacheIndexing();
			Verify.verifyL1AndL2Lines(this);
			Verify.verifyExecutionCostBreakdown(this);
		}

		return dmaResult;
	} // end read()

	class RemoteReadResponse {
		boolean isShared = false;
		boolean providedData = false;
		// CE variable
		boolean isAnyLocalReadBitSet = false;
		long orCombinedWriteEnc = 0L;
		Processor<Line> lastWriter;
	}

	private RemoteReadResponse performRemoteRead(final DataAccess access) {
		RemoteReadResponse rrr = new RemoteReadResponse();
		rrr.isShared = false;
		rrr.providedData = false;

		for (Processor<Line> otherProc : allProcessors) {
			if (otherProc == this) {
				continue; // skip ourselves
			}

			MemoryResponse<Line> resp = otherProc.L1cache.search(access.addr(), params.remoteAccessesAffectLRU());
			Line otherLine = resp.lineHit;

			// Check only private remote caches, we assume the L3 to be shared
			if (resp.whereHit == CacheLevel.MEMORY || resp.whereHit == CacheLevel.L3) {
				continue;
			}

			// found in a remote cache!
			switch (otherLine.getState()) {
			case MESI_EXCLUSIVE:
			case MESI_MODIFIED: {
				otherLine.changeStateTo(MESIState.MESI_SHARED);
				otherLine.resetDeferredWriteBit();
				if (resp.whereHit == CacheLevel.L1) {
					otherProc.L2cache.getLine(otherLine).resetDeferredWriteBit();
				}
				rrr.providedData = true;
				break;
			}
			case MESI_SHARED: {
				rrr.isShared = true;
				break;
			}
			case MESI_INVALID: {
				assert false : "Hit in a private cache implies line must be valid.";
			}
			}

			// Conflict exceptions: Servicing a remote read miss request
			if (params.conflictExceptions()) {
				// Return local write bits OR remote write bits
				if (otherLine.isRead()) {
					rrr.isAnyLocalReadBitSet = true;
				}
				rrr.orCombinedWriteEnc = otherLine.getLocalWrites() | otherLine.getRemoteWrites();
				if (otherLine.isWritten()) {
					if (params.dirtyEscapeOpt()) {
						rrr.lastWriter = otherProc;
					} else {
						otherProc.hasDirtyEscape = true;
					}

					// We are here implies the hit was in private caches
					// Set line- and cache-level supplied bits
					otherLine.setSupplied();
					if (resp.whereHit == CacheLevel.L1) {
						// l2line guaranteed to be valid due to the hit in L1
						// otherProc.L2cache.getLine(otherLine).setSupplied();
					}
					otherProc.L2cache.supplied = true;
				}
			}

			// Check if the line is in L2 as well
			if (resp.whereHit == CacheLevel.L1) {
				Line l2Line = otherProc.L2cache.getLine(otherLine);
				assert l2Line != null : "Violates inclusivity";
				switch (l2Line.getState()) {
				case MESI_EXCLUSIVE:
				case MESI_MODIFIED: {
					l2Line.changeStateTo(MESIState.MESI_SHARED);
					break;
				}
				case MESI_SHARED: {
					break;
				}
				case MESI_INVALID: {
					assert false : "Hit in a private cache implies line must be valid.";
				}
				}
			}

		} // done with other caches

		assert !rrr.isShared || !rrr.providedData : "line cannot be both shared and exclusive/modified at the "
				+ "same time in remote caches";

		// LLC hit no longer implies that data is present in at least one remote cache,
		// since the LLC line
		// state can be MESI_INVALID
		return rrr;
	}

	/** Make a write request. */
	public DataMemoryAccessResult write(final DataAccess access) {
		if (access.isAtomic()) {
			stats.pc_TotalAtomicWrites.incr();
			stats.pc_TotalAtomicAccesses.incr();
		} else if (access.isLockAccess()) {
			stats.pc_TotalLockWrites.incr();
			stats.pc_TotalLockAccesses.incr();
		} else {
			stats.pc_TotalDataWrites.incr();
			stats.pc_TotalMemoryAccesses.incr();
		}

		DataMemoryAccessResult dmaResult = new DataMemoryAccessResult();

		MemoryResponse<Line> resp = L1cache.request(this, access.addr(), false);
		Line line = resp.lineHit;
		assert line.id() == id : "The owner of a private line should always be the current core";
		assert line.valid();
		assert line.getLevel() == CacheLevel.L1;

		switch (resp.whereHit) {
		case L1: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockWriteHits.incr();
			} else {
				stats.pc_l1d.pc_WriteHits.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.L1_HIT_LATENCY, dmaResult, false);
			break;
		}

		case L2: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicWriteHits.incr();
				}
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockWriteHits.incr();
				}
			} else {
				stats.pc_l1d.pc_WriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_WriteHits.incr();
				}
			}
			memoryCyclesElapsed(MemorySystemConstants.L2_HIT_LATENCY, dmaResult, false);
			break;
		}

		case L3: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicWriteMisses.incr();
				}
				stats.pc_l3d.pc_AtomicWriteHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockWriteMisses.incr();
				}
				stats.pc_l3d.pc_LockWriteHits.incr();
			} else {
				stats.pc_l1d.pc_WriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_WriteMisses.incr();
				}
				stats.pc_l3d.pc_WriteHits.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult, false);
			break;
		}

		case MEMORY: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicWriteMisses.incr();
				}
				stats.pc_l3d.pc_AtomicWriteMisses.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockWriteMisses.incr();
				}
				stats.pc_l3d.pc_LockWriteMisses.incr();
			} else {
				stats.pc_l1d.pc_WriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_WriteMisses.incr();
				}
				stats.pc_l3d.pc_WriteMisses.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.MEMORY_LATENCY, dmaResult, false);

			if (MESISim.enableXasserts()) {
				Verify.cacheExclusivity(this, line);
			}
			break;
		}
		}

		// we either hit somewhere in our private cache(s) or we hit in the shared
		// cache,
		// but we may not have write permissions.

		Processor<Line> lastWriter = null;

		switch (line.getState()) {
		case MESI_SHARED: { // upgrade miss
			// It does not matter whether we hit in a private cache or the LLC, since we
			// need to perform
			// the same tasks.

			assert resp.whereHit != CacheLevel.MEMORY; // Line should have been in MODIFIED state

			// I/S->M, S->M, S->I
			stats.pc_MESIUpgradeMisses.incr();

			// Inv control message from directory to sharers. This is concurrent with data
			// message from directory
			// back to the requester. If we allot different costs to data and control
			// messages, then we will need to
			// do a max.
			RemoteWriteResponse rwr = performRemoteWrite(access); // invalidate other copies
			lastWriter = rwr.lastWriter;
			// This is not a correct assertion. Other cores can have evicted the line,
			// thereby decreasing the count to one,
			// which would mean that only the current core is the sharer.
			// assert params.numProcessors() > 1 && numRemoteInvalidations >= 1;

			// We ignore number of remote invalidations since they are concurrent messages
			// sent out by the directory
			// memoryCyclesElapsed(MemorySystemConstants.DIRECTORY_ACCESS, dmaResult);

			// Inv-Ack control message, messages are concurrent
			memoryCyclesElapsed(MemorySystemConstants.REMOTE_HIT_LATENCY, dmaResult, true);

			// need to send out so many Inv-Ack control messages, that is done from the
			// performRemoteWrite() method

			if (params.conflictExceptions()) {
				// Process incoming bits
				line.orRemoteReads(rwr.readBits);
				// Exception?? incoming write bit is set, and the corresponding remote write bit
				// is also set, but
				// not sure if the latter was written by the current core.
				// Let's make a decision after conflict detection.
				// long tmp = ~line.getLocalWrites();
				line.orRemoteWrites(rwr.writeBits);
				// Set the bits in L2 as well
				Line l2Line = L2cache.getLine(line);
				assert l2Line != null;
				l2Line.orRemoteReads(rwr.readBits);
				l2Line.orRemoteWrites(rwr.writeBits);
				assert line.getRemoteReads() == l2Line.getRemoteReads();
				assert line.getRemoteWrites() == l2Line.getRemoteWrites();
			}

			dmaResult.remoteCommunicatedHappened = true;
			break;
		}

		case MESI_EXCLUSIVE: // write hit
		case MESI_MODIFIED: {
			if (resp.whereHit == CacheLevel.L1 || resp.whereHit == CacheLevel.L2) {
				dmaResult.remoteCommunicatedHappened = false;

			} else if (resp.whereHit == CacheLevel.L3) {
				// I->M, M/E->M, M/E->I

				dmaResult.remoteCommunicatedHappened = true;

				// Directory needs to forward the request to the proper owner Fwd-GetM control
				// message
				RemoteWriteResponse rwr = performRemoteWrite(access);
				int numRemoteInvalidations = rwr.numInvalidations;
				// If the current core had the line in MODIFIED state, and then evicted it, the
				// line would have
				// changed to an INVALID state in the LLC. So a MODIFIED state of the LLC line
				// must represent a
				// remote writer.
				assert numRemoteInvalidations == 1;

				if (params.conflictExceptions()) {
					// Process incoming bits
					line.orRemoteReads(rwr.readBits);
					// Exception?? incoming write bit is set, and the corresponding remote write bit
					// is also set, but
					// not sure if the latter was written by the current core.
					// Let's make a decision after conflict detection.
					// long tmp = ~line.getLocalWrites();
					line.orRemoteWrites(rwr.writeBits);
					// Set the bits in L2 as well
					Line l2Line = L2cache.getLine(line);
					l2Line.orRemoteReads(rwr.readBits);
					l2Line.orRemoteWrites(rwr.writeBits);
				}

				// The remote core needs to send the data back to the current core - data
				// message
				memoryCyclesElapsed(MemorySystemConstants.REMOTE_HIT_LATENCY, dmaResult, true);

				// Core-to-core data message is accounted for in the performRemoteWrite() method

			} else {
				// Hit in memory implies the cache line resp should be in the MODIFIED state
				assert line.getState() == MESIState.MESI_MODIFIED;
			}
			break;
		}

		case MESI_INVALID: {
			// LLC line can be valid and MESI_INVALID
			// I->M, I->M
			if (MESISim.assertsEnabled) {
				Line llcLine = L3cache.getLine(line);
				assert llcLine.valid() && llcLine.getState() == MESIState.MESI_INVALID;
			}

			// bits already got from the global table.
			break;
		}
		}

		line.changeStateTo(MESIState.MESI_MODIFIED);

		Line l2Line = L2cache.getLine(line);
		assert l2Line.getLevel() == CacheLevel.L2;
		// On an upgrade, we need to change the state in the L2 cache as well. If the
		// hit is in L2, then
		// we ignore upgrading the LLC line state. Instead we assert that it should be
		// in EXCLUSIVE or
		// MODIFIED state just below.
		l2Line.changeStateTo(MESIState.MESI_MODIFIED);

		// LLC line should be in MESI_MODIFIED or MESI_EXCLUSIVE state if written
		Line llcLine = L3cache.getLine(line);
		assert llcLine != null && llcLine.getLevel() == CacheLevel.L3;
		switch (llcLine.getState()) {
		case MESI_MODIFIED: {
			break;
		}
		case MESI_EXCLUSIVE:
		case MESI_SHARED: {
			llcLine.changeStateTo(MESIState.MESI_MODIFIED);
			break;
		}
		case MESI_INVALID: {
			// LLC line can be MESI_INVALID and valid
			llcLine.changeStateTo(MESIState.MESI_MODIFIED);
			break;
		}
		}

		if (params.conflictExceptions() && access.isRegularMemAccess()) {
			// Set the local bit first to allow detecting conflicts involving the current
			// access
			long enc = getEncodingForAccess(access);

			// Check for conflicts
			// long localWrites = line.getLocalWrites();
			long remoteWrites = line.getRemoteWrites();
			long remoteReads = line.getRemoteReads();
			if (checkPreciseWriteWriteConflict(line, remoteWrites, enc, access.siteInfo())
					|| checkPreciseReadWriteConflict(line, remoteReads, enc, access.siteInfo())) {
				stats.pc_PreciseConflicts.incr();
				if (reRunEvent || restartRegion) {
					return dmaResult;
				}
			}

			if (lastWriter != null && params.dirtyEscapeOpt()) {
				lastWriter.hasDirtyEscape = true;
			}

			// write to a line with values written by prior regions, need to back up those
			// values in L2
			if ((params.restartAtFailedValidationsOrDeadlocks() || params.FalseRestart())
					&& params.BackupDeferredWritebacksLasily() && line.hasDirtyValuesFromPriorRegions()) {
				Line l2cLine = L2cache.getLine(line);
				assert l2cLine.valid() && l2cLine.getLocalWrites() == 0;
				// l2cLine.copyAllValues(line);
				l2cLine.setDeferredWriteBit();
			}
			line.setDeferredWriteBit();

			if (params.setWriteBitsInL2() && line.getLocalWrites() == 0) { // write through write bits (and
				// MRU-bits at first write)
				l2Line.l2WriteBit = true;
				L2cache.setMRUBit(l2Line, false); // Ensure the invariance that we set MRU bits every time setting
				// write bits
			}
			line.orLocalWrites(enc);
			line.updateWriteSiteInfo(enc, access.siteInfo());
			// l2Line.orLocalWrites(enc);
			l2Line.updateWriteSiteInfo(enc, access.siteInfo());
		}

		if (MESISim.enableXasserts()) {
			Verify.verifyCacheInclusivity(this);
			Verify.verifyStateOfLLCOnlyLines();
			Verify.verifyModifiedLines(this);
			Verify.verifyExclusiveAndSharedLines(this);
			Verify.verifyInvalidLinesInLLC();
			Verify.verifyModifiedLLCLines(this);
			Verify.verifyCacheIndexing();
			Verify.verifyL1AndL2Lines(this);
			Verify.verifyExecutionCostBreakdown(this);
		}

		return dmaResult;
	} // end write()

	class RemoteWriteResponse {

		int numInvalidations = 0;
		// CE variables
		long readBits = 0L;
		long writeBits = 0L;
		Processor<Line> lastWriter;
	}

	/**
	 * Invalidate remote copies of this line. The remote copy could be in both L1
	 * and L2 caches.
	 */
	@SuppressWarnings("unchecked")
	private RemoteWriteResponse performRemoteWrite(final DataAccess access) {
		int numInvalidations = 0;
		RemoteWriteResponse rwr = new RemoteWriteResponse();

		for (Processor<Line> otherProc : allProcessors) {
			if (otherProc == this) {
				continue; // skip ourselves
			}

			// CE: Servicing a remote write or invalidate miss request
			if (params.conflictExceptions()) {
				// Return the local read and write bits
				MemoryResponse<Line> ceresp = otherProc.L1cache.ceSearchPrivateCaches(access.addr(),
						params.remoteAccessesAffectLRU());
				// only search for valid lines but merge remote bits too since otherwise
				// we would lose metadata of evicted lines.
				if (ceresp.lineHit != null) {
					boolean supplied = false;

					Line ceOtherLine = ceresp.lineHit;
					// if (ceOtherLine.isRead() | ceOtherLine.isRemoteRead()) {
					// rwr.readBits |= ceOtherLine.getLocalReads() | ceOtherLine.getRemoteReads();
					if (ceOtherLine.isRead()) {
						supplied = true;
						rwr.readBits |= ceOtherLine.getLocalReads();
					}
					if (ceOtherLine.isWritten()) {
						supplied = true;
						// if (ceOtherLine.isWritten() | ceOtherLine.isRemoteWritten()) {
						// rwr.writeBits |= ceOtherLine.getLocalWrites() |
						// ceOtherLine.getRemoteWrites();
						rwr.writeBits |= ceOtherLine.getLocalWrites();
						if (params.dirtyEscapeOpt()) {
							rwr.lastWriter = otherProc;
						} else {
							otherProc.hasDirtyEscape = true;
						}
					}

					if (supplied) {
						// Set the line- and cache-level supplied bits
						ceOtherLine.setSupplied();
						if (ceOtherLine.getLevel() == CacheLevel.L1 && params.useL2()) {
							MemoryResponse<Line> ceL2Resp = otherProc.L2cache.ceSearchPrivateCaches(access.addr(),
									params.remoteAccessesAffectLRU());
							// the following doesn't necessarily hold since the L1 line may be invalid
							// and inclusivity isn't guaranteed for such a line.
							// TODO do we need to maintain inclusivity for invalid lines?
							// assert ceL2Resp.lineHit != null;
							if (ceL2Resp.lineHit != null) {
								// ceL2Resp.lineHit.setSupplied();
							}
						}
						otherProc.L2cache.supplied = true;
					}
					// Preserve the supplied and access bits associated with the cache line. This
					// can be done with
					// modifying the cache lookup to include invalid lines as long as the line is
					// not evicted. But
					// it needs to be backed up in the global table upon eviction from the L2.
				} /*
					 * else { // already got bits from global table RemoteWriteResponse gtr =
					 * performRemoteWriteAtLLC(access, otherProc); rwr.readBits |= gtr.readBits;
					 * rwr.writeBits |= gtr.writeBits; }
					 */
			}

			MemoryResponse<Line> resp = otherProc.L1cache.search(access.addr(), params.remoteAccessesAffectLRU());
			Line otherLine = resp.lineHit;

			// Check only private remote caches, we assume the L3 to be shared
			if (resp.whereHit == CacheLevel.MEMORY || resp.whereHit == CacheLevel.L3) {
				continue;
			}

			// found a remote copy of this line
			switch (otherLine.getState()) {
			case MESI_MODIFIED:
			case MESI_EXCLUSIVE: {
				stats.pc_ModifiedLineFetches.incr();

				otherLine.resetDeferredWriteBit();
				if (resp.whereHit == CacheLevel.L1) {
					otherProc.L2cache.getLine(otherLine).resetDeferredWriteBit();
				}
				otherLine.invalidate((Machine<MESILine>) this.machine);
				otherLine.changeStateTo(MESIState.MESI_INVALID);
				numInvalidations++;
				break;
			}
			case MESI_SHARED: {
				otherLine.invalidate((Machine<MESILine>) this.machine);
				otherLine.changeStateTo(MESIState.MESI_INVALID);
				numInvalidations++;
				// have to keep searching to find all Shared copies
				break;
			}
			case MESI_INVALID: {
				assert false : "Hit in a private cache implies line must be valid.";
			}
			}

			// Check if the line is in L2 as well
			if (resp.whereHit == CacheLevel.L1) {
				Line l2Line = otherProc.L2cache.getLine(otherLine);
				assert l2Line != null : "Violates inclusivity";
				switch (l2Line.getState()) {
				case MESI_MODIFIED:
				case MESI_EXCLUSIVE:
				case MESI_SHARED: {
					l2Line.invalidate((Machine<MESILine>) this.machine);
					otherLine.changeStateTo(MESIState.MESI_INVALID);
					// have to keep searching to find all Shared copies
					break;
				}
				case MESI_INVALID: {
					assert false : "Hit in a private cache implies line must be valid.";
				}
				}
			}

		} // done with other caches

		stats.pc_MESIWriteRemoteHits.incr(numInvalidations);

		rwr.numInvalidations = numInvalidations;
		return rwr;
	} // end writeRemoteAction()

	private int broadcastEORMessage(CacheLevel level, HashSet<Long> skipL1Lines) {
		HierarchicalCache<Line> cache = (level == CacheLevel.L1) ? L1cache : L2cache;
		int size = 0;

		for (LinkedList<Line> set : cache.sets) {
			for (Line l : set) {
				if (level == CacheLevel.L2) {
					if (l.lineAddress() != null && skipL1Lines.contains(l.lineAddress().get())) {
						continue;
					}
				}

				// Cannot leave out invalid lines so that supplied bits are processed properly
				if (l.isSupplied()) {
					assert l.lineAddress() != null;
					size += (MemorySystemConstants.TAG_BYTES + MemorySystemConstants.CE_READ_METADATA_BYTES
							+ MemorySystemConstants.CE_WRITE_METADATA_BYTES);

					// Process the incoming endR message at remote cores
					for (Processor<Line> p : allProcessors) {
						if (p.id.equals(id)) {
							continue;
						}
						processIncomingEndRLine(p, l);
					}
					if (level == CacheLevel.L1) {
						skipL1Lines.add(l.lineAddress().get());
					}
				}
			}
		}
		return size;
	}

	private void processIncomingEndRLine(Processor<Line> proc, Line incoming) {
		// L1 and L2 caches in MESI are inclusive
		Line l2Line = proc.L2cache.getLine(incoming);
		if (l2Line != null) {
			Line llcLine = proc.L3cache.getLine(incoming);
			assert llcLine != null;

			Line l1Line = proc.L1cache.getLine(incoming);
			if (l1Line != null) {
				assert l1Line.getRemoteReads() == l2Line.getRemoteReads();
				assert l1Line.getRemoteWrites() == l2Line.getRemoteWrites();

				// Reset remote bit if bit is set in endR message
				l1Line.setRemoteWrites(l1Line.getRemoteWrites() & (~incoming.getLocalWrites()));
				long oldRemoteReads = l1Line.getRemoteReads();
				l1Line.setRemoteReads(l1Line.getRemoteReads() & (~incoming.getLocalReads()));
				assert Long.bitCount(oldRemoteReads) >= Long.bitCount(l1Line.getRemoteReads());
				if (oldRemoteReads > Long.bitCount(l1Line.getRemoteReads())) {
					// Downgrade the line to shared
					if (l1Line.getState() == MESIState.MESI_EXCLUSIVE) {
						assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
						l1Line.changeStateTo(MESIState.MESI_SHARED);
					} else if (l1Line.getState() == MESIState.MESI_MODIFIED) {
						assert llcLine.getState() == MESIState.MESI_MODIFIED;
						l1Line.changeStateTo(MESIState.MESI_SHARED);
					}
				}
			}

			l2Line.setRemoteWrites(l2Line.getRemoteWrites() & (~incoming.getLocalWrites()));
			long oldRemoteReads = l2Line.getRemoteReads();
			l2Line.setRemoteReads(l2Line.getRemoteReads() & (~incoming.getLocalReads()));
			assert Long.bitCount(oldRemoteReads) >= Long.bitCount(l2Line.getRemoteReads());
			if (oldRemoteReads > Long.bitCount(l2Line.getRemoteReads())) {
				// Downgrade the line to shared
				if (l2Line.getState() == MESIState.MESI_EXCLUSIVE) {
					l2Line.changeStateTo(MESIState.MESI_SHARED);
					assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
					llcLine.changeStateTo(MESIState.MESI_SHARED);
				} else if (l2Line.getState() == MESIState.MESI_MODIFIED) {
					l2Line.changeStateTo(MESIState.MESI_SHARED);
					assert llcLine.getState() == MESIState.MESI_MODIFIED;
					llcLine.changeStateTo(MESIState.MESI_SHARED);
				}
			}
			if (l1Line != null) {
				assert l1Line.getRemoteReads() == l2Line.getRemoteReads();
				assert l1Line.getRemoteWrites() == l2Line.getRemoteWrites();
			}
		}
	}

	public void processIncomingEndRMetadata(Processor<Line> proc, Long lineAddr, CEGlobalTableValue val) {
		// L1 and L2 caches in MESI are inclusive
		Line l2Line = proc.L2cache.getLine(new DataLineAddress(lineAddr));
		if (l2Line != null) {
			Line llcLine = proc.L3cache.getLine(new DataLineAddress(lineAddr));
			assert llcLine != null;

			Line l1Line = proc.L1cache.getLine(new DataLineAddress(lineAddr));
			if (l1Line != null) {
				assert l1Line.getRemoteReads() == l2Line.getRemoteReads();
				assert l1Line.getRemoteWrites() == l2Line.getRemoteWrites();

				// Reset remote bit if bit is set in endR message
				l1Line.setRemoteWrites(l1Line.getRemoteWrites() & (~val.localWrites));
				long oldRemoteReads = l1Line.getRemoteReads();
				l1Line.setRemoteReads(l1Line.getRemoteReads() & (~val.localReads));
				assert Long.bitCount(oldRemoteReads) >= Long.bitCount(l1Line.getRemoteReads());
				if (oldRemoteReads > Long.bitCount(l1Line.getRemoteReads())) {
					// Downgrade the line to shared
					if (l1Line.getState() == MESIState.MESI_EXCLUSIVE) {
						assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
						l1Line.changeStateTo(MESIState.MESI_SHARED);
					} else if (l1Line.getState() == MESIState.MESI_MODIFIED) {
						assert llcLine.getState() == MESIState.MESI_MODIFIED;
						l1Line.changeStateTo(MESIState.MESI_SHARED);
					}
				}
			}

			l2Line.setRemoteWrites(l2Line.getRemoteWrites() & (~val.localWrites));
			long oldRemoteReads = l2Line.getRemoteReads();
			l2Line.setRemoteReads(l2Line.getRemoteReads() & (~val.localReads));
			assert Long.bitCount(oldRemoteReads) >= Long.bitCount(l2Line.getRemoteReads());
			if (oldRemoteReads > Long.bitCount(l2Line.getRemoteReads())) {
				// Downgrade the line to shared
				if (l2Line.getState() == MESIState.MESI_EXCLUSIVE) {
					l2Line.changeStateTo(MESIState.MESI_SHARED);
					assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
					llcLine.changeStateTo(MESIState.MESI_SHARED);
				} else if (l2Line.getState() == MESIState.MESI_MODIFIED) {
					l2Line.changeStateTo(MESIState.MESI_SHARED);
					assert llcLine.getState() == MESIState.MESI_MODIFIED;
					llcLine.changeStateTo(MESIState.MESI_SHARED);
				}
			}
			if (l1Line != null) {
				assert l1Line.getRemoteReads() == l2Line.getRemoteReads();
				assert l1Line.getRemoteWrites() == l2Line.getRemoteWrites();
			}
		}
	}

	public void processSyncOp(ThreadId tid) {
		// endR message
		if (params.conflictExceptions()) {
			HashSet<Long> skipL1Lines = new HashSet<>(); // L1 lines that need to be skipped in the L2
			assert params.useL2() : "We use the L2 cache's supplied bit.";
			if (L2cache.supplied) {
				// Even though both local and remote access bits are synchronized, we cannot
				// skip
				// L1 since an invalid L2 line may be evicted.
				broadcastEORMessage(CacheLevel.L1, skipL1Lines);
				broadcastEORMessage(CacheLevel.L2, skipL1Lines);
			}

			// If the out-of-cache bit is set, even if the cache-level supplied bit is not
			// set,
			// the cache retrieves evicted line addresses from its local table and supplied
			// and
			// local bits from the global table, and appends to the end-of-region message
			// those that
			// have their supplied bit set.
			assert params.useL2() && !L1cache.outOfCache : "We use the L2 cache's outOfCache bit.";
			if (L2cache.outOfCache) {
				for (Long lineAddr : perRegionLocalTable) {
					CEPerLineMetadata<Line> md = machine.globalTable.get(lineAddr);
					CEGlobalTableValue val = md.getPerCoreMetadata(this);
					if (val.supplied) {
						// Process the incoming endR message at remote cores
						for (Processor<Line> p : allProcessors) {
							if (p.id.equals(id)) {
								continue;
							}
							processIncomingEndRMetadata(p, lineAddr, val);
						}
					}
				}
			}

			// Add network cost for core-to-core cost for acknowledgement.
			for (Processor<Line> p : allProcessors) {
				if (p.id.equals(id)) {
					continue;
				}
			}

			// Similar to RCC, we assume concurrency and add the cost of a core-2-core
			// latency for sending the endR message
			int cycles = MemorySystemConstants.REMOTE_HIT_LATENCY;
			stats.pc_ExecDrivenCycleCount.incr(cycles);
			stats.pc_MESICoherenceExecDrivenCycleCount.incr(cycles);
			updateBWCycles(cycles);

			// The in-region bit is cleared together with each of the line-level supplied
			// bits and local access bits
			clearPrivateCacheMetadata(CacheLevel.L1);
			if (params.useL2()) {
				clearPrivateCacheMetadata(CacheLevel.L2);
			}

			// Now reset the bits
			L1cache.inRegion = false;
			L1cache.supplied = false;
			L1cache.outOfCache = false;
			if (params.useL2()) {
				L2cache.inRegion = false;
				L2cache.supplied = false;
				L2cache.outOfCache = false;
			}

			// Clear per-region local table
			perRegionLocalTable.clear();
			// Clear entries from the global table. This is expensive so we use epochs.
			// clearGlobalTableEntries();

			// Set the in-region bit, beginR message
			L1cache.inRegion = true;
			if (params.useL2()) {
				L2cache.inRegion = true;
			}

			if (params.pauseCoresAtConflicts())
				reRunPausedCores();

			machine.incrementEpoch(id);

			// Current region finishes, reset counter flags
			resetFlagsForRegionCounters();
		}

		if (regionContainsWrite) {
			stats.pc_RegionsWithWrites.incr();
			regionContainsWrite = false;
		}
	}

	private void resetFlagsForRegionCounters() {
		regionConflicted = false;
		regionWithExceptions = false;
		hasDirtyEscape = false;
		regionHasDirtyEvictionBeforeFRV = false;
		regionHasDirtyEvictionBeforeDL = false;
		hasDirtyEviction = false;
		regionHasNoTimeout = false;
	}

	public void reRunPausedCores() {
		// Clear pausingBits to rerun paused cores
		if (pausedCores != 0) {
			long t = 1L;
			double end_time = stats.pc_BandwidthDrivenCycleCount.stat;
			for (short i = 0; i < params.numProcessors(); i++) {
				if ((pausedCores & t) != 0L) { // core i was paused at a
												// conflict
												// with the current core
					machine.clearPausingBitsAtOffset(i);

					// System.out.println("[Current proc: " + id + "] Rerun p" + i + " end-time: " +
					// end_time);

					// count cycles for pause
					Processor<Line> proc = machine.processors[i];
					double paused_cycles = end_time - proc.start_time;
					if (paused_cycles < 0) {
						throw new RuntimeException(
								"paused_cycles < 0: " + end_time + " " + proc.start_time + " " + id + " " + i);
					} else {
						proc.stats.pc_BandwidthDrivenCycleCount.incr(paused_cycles);
						proc.stats.pc_ViserPauseBWDrivenCycleCount.incr(paused_cycles);
					}
				}
				t *= 2;
			}
			pausedCores = 0L;
		}

	}

	public void checkLongPausingCores(int timeout) {
		if (pausedCores != 0) {
			long t = 1L;
			double end_time = stats.pc_BandwidthDrivenCycleCount.stat;
			for (short i = 0; i < params.numProcessors(); i++) {
				if ((pausedCores & t) != 0L) { // core i was paused at a
												// conflict
												// with the current core

					// count cycles for pause
					Processor<Line> proc = machine.processors[i];
					double paused_cycles = end_time - proc.start_time;
					if (paused_cycles < 0) {
						throw new RuntimeException(
								"paused_cycles < 0: " + end_time + " " + proc.start_time + " " + id + " " + i);
					}
					if (paused_cycles > timeout) {
						// resume the paused core after long waiting and throw an exception
						clearPausedCores(i);
						machine.clearPausingBitsAtOffset(i);
						proc.stats.pc_BandwidthDrivenCycleCount.incr(paused_cycles);
						proc.stats.pc_ViserPauseBWDrivenCycleCount.incr(paused_cycles);

						if (timeout <= 0) {
							if (!regionHasNoTimeout) {
								regionHasNoTimeout = true;
								stats.pc_RegionsTermPausingCoresWoTimeout.incr();
							}
							continue;
						}
						if (!regionWithExceptions) {
							if (!regionConflicted) {
								regionConflicted = true;
								stats.pc_RegionsWithLongPausingCores.incr();
								stats.pc_RegionsWithExceptions.incr();
								regionWithExceptions = true;
								if (params.isHttpd() && inTrans) { // restart transaction
									updateStatsForTransRestart();
								} else {
									updateStatsForReboot();
								}
							}
						}
					}
				}
				t *= 2;
			}
		}

	}

	void updateCostsFromTmpCounters(boolean isRegionRestart) {
		// Note that the tmp counters will be automatically cleared after get().
		// We "forceInc" dependent counters to avoid checking ROI and guarantee their
		// sum equals the counter they depend
		// on.
		if (isRegionRestart) {
			stats.pc_ViserRegionRestartBWDrivenCycleCount
					.incr(tmpNormalExecBWDrivenCycleCount.get() + tmpPauseBWDrivenCycleCount.get(), true);
		} else {
			// Counters we care about
			stats.pc_ViserNormalExecBWDrivenCycleCount.incr(tmpNormalExecBWDrivenCycleCount.get(), true);
			stats.pc_ViserPauseBWDrivenCycleCount.incr(tmpPauseBWDrivenCycleCount.get(), true);
		}
	}

	private void clearPrivateCacheMetadata(CacheLevel level, boolean toRestartRegion) {
		HierarchicalCache<Line> cache = (level == CacheLevel.L1) ? L1cache : L2cache;
		for (LinkedList<Line> set : cache.sets) {
			for (Line l : set) {
				l.clearSupplied();
				/*
				 * if (l.addr != null && l.addr.get() == 6320960 && id.get() == 2) {
				 * System.out.println(level + " l6320960 local reads get cleared " +
				 * l.getLocalReads()); }
				 */
				l.clearLocalReads();
				/*
				 * if (l.addr != null && l.addr.get() == 6320960 && id.get() == 2) {
				 * System.out.println(level + " l6320960 local reads after cleared " +
				 * l.getLocalReads()); }
				 */
				if (toRestartRegion && l.isWritten()) {
					// roll back to old value backed up in lower cache
					if (level == CacheLevel.L2) {
						l.changeStateTo(MESIState.MESI_INVALID);
						Line l1Line = L1cache.getLine(l);
						if (l1Line != null) {
							l1Line.changeStateTo(MESIState.MESI_INVALID);
						}
					}
				}
				l.clearLocalWrites();
				l.clearRemoteWrites();
				l.clearRemoteWrites();
				l.l2WriteBit = false;
			}
		}
	}

	private void clearPrivateCacheMetadata(CacheLevel level) {
		clearPrivateCacheMetadata(level, false);
	}

	public boolean ignoreEvents() {
		return (ignoreEvents > 0);
	}

	public void incIgnoreCounter() {
		assert (ignoreEvents >= 0);
		this.ignoreEvents++;
		/*
		 * if (id.get() == 0) { System.out.println(id + " increased the counter " +
		 * ignoreEvents + " " + getCurrentEpoch()); }
		 */
	}

	public void decIgnoreCounter() {
		assert (ignoreEvents > 0);
		this.ignoreEvents--;
		if (ignoreEvents < 0) {
			System.out.println(id + " decreased the counter " + ignoreEvents + " " + getCurrentEpoch());
		}
	}

	public void setPausedCores(short offset) {
		pausedCores |= (1L << offset);
	}

	public void clearPausedCores(short offset) {
		pausedCores &= ~(1L << offset);
	}

	public long getPausedCores() {
		return pausedCores;
	}

	// private void clearGlobalTableEntries() {
	// Set<Long> keys = machine.globalTable.keySet();
	// Iterator<Long> it = keys.iterator();
	// while (it.hasNext()) {
	// Long lineAddr = it.next();
	// CEPerLineMetadata md = machine.globalTable.get(lineAddr);
	// md.clear(id);
	// }
	// }

	class Verifier {

		/** Check whether line {@code myLine} is exclusive to processor {@code proc}. */
		public void cacheExclusivity(Processor<Line> proc, final Line myLine) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();
			assert myLine.valid() && myLine.id() == proc.id
					&& (myLine.getState() == MESIState.MESI_EXCLUSIVE || myLine.getState() == MESIState.MESI_MODIFIED);

			for (Processor<Line> p : allProcessors) {
				if (proc == p) {
					continue;
				}
				HierarchicalCache.LineVisitor<Line> privateLv = new HierarchicalCache.LineVisitor<Line>() {
					@Override
					public void visit(Line line) {
						if (line.valid()) {
							if (line.lineAddress().get() == myLine.lineAddress().get()) {
								throw new RuntimeException(
										"Modified/exclusive line should not appear in remote caches.");
							}
						}
					}
				};
				p.L1cache.visitAllLines(privateLv);
				if (p.params.useL2()) {
					p.L2cache.visitAllLines(privateLv);
				}
			}
		}

		// Private line states should match with the LLC
		public void verifyExclusiveAndSharedLines(Processor<Line> proc) {
			verifyLineState(proc, MESIState.MESI_EXCLUSIVE);
			verifyLineState(proc, MESIState.MESI_SHARED);
		}

		private void verifyLineState(Processor<Line> proc, final MESIState state) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			final HashSet<Line> localSet = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid() && line.getState() == state) {
						localSet.add(line);
					}
				}
			};
			proc.L1cache.visitAllLines(lv);
			if (params.useL2()) {
				proc.L2cache.visitAllLines(lv);
			}

			// Check LLC
			final HashSet<Line> llcLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> llcLv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid() && line.getState() != MESIState.MESI_INVALID) {
						llcLines.add(line);
					}
				}
			};
			proc.L3cache.visitAllLines(llcLv);

			for (Line privateLine : localSet) {
				assert privateLine.getState() == state;
				boolean found = false;
				for (Line llcLine : llcLines) {
					assert llcLine.valid();
					if (privateLine.lineAddress().get() == llcLine.lineAddress().get()) {
						found = true;
						if (llcLine.getState() != state) {
							System.out.println("Private line: " + privateLine);
							System.out.println(llcLine);
							throw new RuntimeException("Line states do not match");
						}
						break;
					}
				}
				if (!found) {
					throw new RuntimeException("Private cache and LLC inclusivity violated");
				}
			}
		}

		public void verifyModifiedLLCLines(Processor<Line> proc) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			// Iterate over invalid LLC lines
			final HashSet<Line> modifiedLLCLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> modifiedLLCV = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.getState() == MESIState.MESI_MODIFIED) {
						modifiedLLCLines.add(line);
					}
				}
			};
			L3cache.visitAllLines(modifiedLLCV);

			// Now iterate over all cache lines in all processors
			for (Line modLine : modifiedLLCLines) {
				boolean found = false;
				for (Processor<Line> p : allProcessors) {
					Line tmp = p.L1cache.getLine(modLine);
					if (tmp != null) {
						assert tmp.getState() == MESIState.MESI_MODIFIED;
						found = true;
						if (params.useL2()) {
							assert p.L2cache.getLine(modLine).getState() == MESIState.MESI_MODIFIED;
						}
					}
					if (!found && params.useL2()) {
						tmp = p.L2cache.getLine(modLine);
						if (tmp != null) {
							assert tmp.getState() == MESIState.MESI_MODIFIED;
							found = true;
							break;
						}
					}
				}
				if (!found) {
					System.out.println(modLine);
				}
				assert found;
			}
		}

		/**
		 * Cache lines that are modified in a private cache should not be in any other
		 * private caches, and should also be marked modified in the LLC.
		 */
		public void verifyModifiedLines(Processor<Line> proc) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			final HashSet<Line> localModifiedSet = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid() && line.getState() == MESIState.MESI_MODIFIED) {
						localModifiedSet.add(line);
					}
				}
			};
			proc.L1cache.visitAllLines(lv);
			if (params.useL2()) {
				proc.L2cache.visitAllLines(lv);
			}

			// Check remote caches
			final HashSet<Line> remoteSet = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> remoteLv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.getState() != MESIState.MESI_INVALID;
						remoteSet.add(line);
					}
				}
			};
			for (Processor<Line> p : allProcessors) {
				if (p == proc) {
					continue;
				}
				p.L1cache.visitAllLines(remoteLv);
				if (p.params.useL2()) {
					p.L2cache.visitAllLines(remoteLv);
				}
			}

			for (Line privateLine : localModifiedSet) {
				for (Line remoteLine : remoteSet) {
					assert remoteLine.valid();
					if (privateLine.lineAddress().get() == remoteLine.lineAddress().get()) {
						throw new RuntimeException(
								"Modified line in a private cache should not be present and valid in remote caches.");
					}
				}
			}

			// Check LLC
			final HashSet<Line> llcLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> llcLv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid() && line.getState() != MESIState.MESI_INVALID) {
						llcLines.add(line);
					}
				}
			};
			proc.L3cache.visitAllLines(llcLv);

			for (Line privateLine : localModifiedSet) {
				boolean found = false;
				for (Line llcLine : llcLines) {
					assert llcLine.valid();
					if (privateLine.lineAddress().get() == llcLine.lineAddress().get()) {
						if (llcLine.getState() != MESIState.MESI_MODIFIED
								&& llcLine.getState() != MESIState.MESI_EXCLUSIVE) {
							System.out.println(MESISim.totalEvents);
							System.out.println(llcLine.getState());
							System.out.println(llcLine.lineAddress().get());
							throw new RuntimeException(
									"Modified line in a private cache should be modifed or exclusive in the LLC.");
						}
						found = true;
						break;
					}
				}
				if (!found) {
					throw new RuntimeException("Private cache and LLC inclusivity violated");
				}
			}
		}

		/**
		 * Any cache line marked invalid in the LLC should not be valid in any other
		 * private cache.
		 * 
		 * When a private line is evicted (say E->I), the line is marked invalid in the
		 * LLC. If the same line is again brought in to the private cache, a new LLC
		 * line is created at the MRU spot. In that case, there are two lines in the LLC
		 * that have the same address, with one valid and another invalid. Therefore, we
		 * cannot assert that the line address of the invalid line will not be present
		 * in any private cache. That is why, we only check on LLC line addresses (as
		 * Integer objects since it is incorrect to compare addresses) that correspond
		 * to invalid lines and are present only once.
		 */
		public void verifyInvalidLinesInLLC() {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			// Iterate over invalid LLC lines
			final HashSet<Line> invalidLLCLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> invalidLLCV = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.invalid() && line.lineAddress() != null) {
						invalidLLCLines.add(line);
					}
				}
			};
			L3cache.visitAllLines(invalidLLCV);

			// Iterate over all LLC lines
			final HashSet<Line> allLLCLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> allLLCV = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.lineAddress() != null) {
						allLLCLines.add(line);
					}
				}
			};
			L3cache.visitAllLines(allLLCV);

			// Now strip invalid LLC lines that are also present as valid
			Iterator<Line> it = invalidLLCLines.iterator();
			while (it.hasNext()) {
				Line invalidLLCLine = it.next();
				assert invalidLLCLine.invalid();
				for (Line allLLCLine : allLLCLines) {
					if (allLLCLine.lineAddress().get() == invalidLLCLine.lineAddress().get() && allLLCLine.valid()) {
						it.remove();
					}
				}
			}

			// Now iterate over all cache lines in all processors
			final HashSet<Line> privateLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid() && line.lineAddress() != null) {
						privateLines.add(line);
					}
				}
			};
			for (Processor<Line> p : allProcessors) {
				p.L1cache.visitAllLines(lv);
				if (p.params.useL2()) {
					p.L2cache.visitAllLines(lv);
				}
			}

			// Now compare the two sets
			for (Line inv : invalidLLCLines) {
				for (Line priv : privateLines) {
					if (priv.lineAddress().get() == inv.lineAddress().get() && priv.valid()) {
						// System.out.println("Violating line:" + priv);
						throw new RuntimeException(
								"Invalid lines in LLC should not be present and valid in any private cache.");
					}
				}
			}

		}

		/**
		 * Cache lines that are only in the LLC and not in any private cache should be
		 * in the Invalid state.
		 */
		public void verifyStateOfLLCOnlyLines() {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			// First iterate over all cache lines in all processors
			final HashSet<Line> privateLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						privateLines.add(line);
					}
				}
			};
			for (Processor<Line> p : allProcessors) {
				p.L1cache.visitAllLines(lv);
				if (p.params.useL2()) {
					p.L2cache.visitAllLines(lv);
				}
			}
			assert privateLines.size() > 0;

			// Now iterate over LLC lines
			final HashSet<Line> llcLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> llcV = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					llcLines.add(line);
				}
			};
			L3cache.visitAllLines(llcV);

			for (Line llcLine : llcLines) {
				boolean found = false;
				for (Line privateLine : privateLines) {
					assert privateLine.valid();
					if ((llcLine.lineAddress() != null)
							&& (privateLine.lineAddress().get() == llcLine.lineAddress().get())) {
						found = true;
						break;
					}
				}
				if (!found) { // LLC line is not in any private cache
					if (llcLine.getState() != MESIState.MESI_INVALID) {
						throw new RuntimeException(
								"LLC-Only line should be in Invalid state instead of " + llcLine.getState());
					}
				}
			}
		}

		public void verifyL1AndL2Lines(Processor<Line> proc) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			final HashSet<Line> l1Set = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> l1Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.getState() != MESIState.MESI_INVALID;
						assert line.getLevel() == CacheLevel.L1;
						l1Set.add(line);
					}
				}
			};
			L1cache.visitAllLines(l1Lv);

			if (params.useL2()) {
				final HashSet<Line> l2Set = new HashSet<Line>();
				HierarchicalCache.LineVisitor<Line> l2Lv = new HierarchicalCache.LineVisitor<Line>() {
					@Override
					public void visit(Line line) {
						if (line.valid()) {
							assert line.getState() != MESIState.MESI_INVALID;
							assert line.getLevel() == CacheLevel.L2;
							l2Set.add(line);
						}
					}
				};
				L2cache.visitAllLines(l2Lv);

				for (Line l1Line : l1Set) {
					assert l1Line.valid();
					boolean found = false;
					for (Line l2Line : l2Set) {
						assert l2Line.valid();
						assert l1Line.id() == l2Line.id();
						if (l1Line.lineAddress().get() == l2Line.lineAddress().get()) {
							// Also verify the state of the lines, both the lines should be in the same
							// state
							// for consistency
							if (l1Line.getState() != l2Line.getState()) {
								throw new RuntimeException("L1 and L2 line should be in the same state");
							}
							assert l1Line.dirty() == l2Line.dirty();

							if (proc.params.conflictExceptions()) {
								// Check consistency of L1 and L2 read/write bits
								assert l1Line.getRemoteReads() == l2Line.getRemoteReads() : MESISim.totalEvents + " L1:"
										+ l1Line.getRemoteReads() + " L2: " + l2Line.getRemoteReads();
								assert l1Line.getRemoteWrites() == l2Line.getRemoteWrites() : MESISim.totalEvents
										+ " L1:" + l1Line.getRemoteWrites() + " L2: " + l2Line.getRemoteWrites();
								assert l1Line.getLocalReads() == l2Line.getLocalReads() : MESISim.totalEvents + " L1: "
										+ l1Line.getLocalReads() + " L2: " + l2Line.getLocalReads();
								assert l2Line.getLocalWrites() == l2Line.getLocalWrites() : MESISim.totalEvents
										+ " L1: " + l1Line.getLocalWrites() + " L2: " + l2Line.getLocalWrites();
							}
							found = true;
							break;
						}
					}
					if (!found) {
						throw new RuntimeException("L1 and L2 violate inclusivity.");
					}
				}

			}
		}

		/**
		 * Each cache lower in the hierarchy should include all cache lines up the
		 * hierarchy. We are only checking for valid lines.
		 */
		public void verifyCacheInclusivity(Processor<Line> proc) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			final HashSet<Line> l1Set = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> l1Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						l1Set.add(line);
					}
				}
			};
			L1cache.visitAllLines(l1Lv);

			final HashSet<Line> l2Set = new HashSet<Line>();
			if (params.useL2()) {
				HierarchicalCache.LineVisitor<Line> l2Lv = new HierarchicalCache.LineVisitor<Line>() {
					@Override
					public void visit(Line line) {
						if (line.valid()) {
							l2Set.add(line);
						}
					}
				};
				L2cache.visitAllLines(l2Lv);

				for (Line l1Line : l1Set) {
					assert l1Line.valid();
					boolean found = false;
					for (Line l2Line : l2Set) {
						assert l2Line.valid();
						assert l1Line.id() == l2Line.id();
						if (l1Line.lineAddress().get() == l2Line.lineAddress().get()) {
							// Also verify the state of the lines, both the lines should be in the same
							// state
							// for consistency
							if (l1Line.getState() != l2Line.getState()) {
								System.out.println(MESISim.totalEvents);
								System.out.println(l1Line.lineAddress().get());
								System.out.println(l1Line.getState());
								System.out.println(l2Line.getState());
							}
							assert l1Line.getState() == l2Line
									.getState() : "L1 and L2 line should be in the same state";
							found = true;
							break;
						}
					}
					if (!found) {
						throw new RuntimeException("L1 and L2 violate inclusivity.");
					}
				}
			}

			final HashSet<Line> l3Set = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> l3Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						l3Set.add(line);
					}
				}
			};
			L3cache.visitAllLines(l3Lv);

			if (params.useL2()) {
				for (Line l2Line : l2Set) {
					assert l2Line.valid();
					boolean found = false;
					for (Line l3Line : l3Set) {
						assert l3Line.valid();
						if (l2Line.lineAddress().get() == l3Line.lineAddress().get()) {
							found = true;
							break;
						}
					}
					if (!found) {
						System.out.println(MESISim.totalEvents);
						System.out.println(l2Line.lineAddress());
						throw new RuntimeException("L2 and L3 violate inclusivity.");
					}
				}
			}

			for (Line l1Line : l1Set) {
				assert l1Line.valid();
				boolean found = false;
				for (Line l3Line : l3Set) {
					assert l3Line.valid();
					if (l1Line.lineAddress().get() == l3Line.lineAddress().get()) {
						found = true;
						break;
					}
				}
				if (!found) {
					throw new RuntimeException("L1 and L3 violate inclusivity.");
				}
			}
		}

		private void verifyCacheIndexing() {
			for (Processor<Line> p : allProcessors) {
				p.L1cache.verifyIndices();

				if (p.L2cache != null) {
					p.L2cache.verifyIndices();
				}
			}

			if (L3cache != null)
				L3cache.verifyIndices();
		}

		private void verifyExecutionCostBreakdown(final Processor<Line> proc) {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			assert proc.stats.pc_ExecDrivenCycleCount.get() == proc.stats.pc_BandwidthDrivenCycleCount.get();

			double target = proc.stats.pc_ExecDrivenCycleCount.get();
			double actual = proc.stats.pc_MESIMemSystemExecDrivenCycleCount.get()
					+ proc.stats.pc_MESICoherenceExecDrivenCycleCount.get();
			assert target == actual : "Values differ";
		}

		/**
		 * Verify that a cache line object is not resident in multiple caches
		 * simultaneously.
		 */
		void cacheExclusivity() {
			assert MESISim.XASSERTS && MESISim.enableXasserts();

			LinkedList<HashSet<Line>> cacheContents = new LinkedList<HashSet<Line>>();
			final HashSet<Line> set = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					set.add(line);
				}
			};

			for (Processor<Line> p : allProcessors) {
				p.L1cache.visitAllLines(lv);
				cacheContents.add(new HashSet<Line>(set));
				set.clear();

				if (p.L2cache != null) {
					p.L2cache.visitAllLines(lv);
					cacheContents.add(new HashSet<Line>(set));
					set.clear();
				}
			}

			if (L3cache != null) {
				L3cache.visitAllLines(lv);
				cacheContents.add(new HashSet<Line>(set));
				set.clear();
			}

			// every set in cacheContents should be disjoint from every other

			for (HashSet<Line> a : cacheContents) {
				for (HashSet<Line> b : cacheContents) {
					if (a != b) {
						HashSet<Line> _a = new HashSet<Line>(a);
						boolean changed = _a.retainAll(b); // a intersect b
						assert changed;
						assert _a.size() == 0;
					}
				}
			}
		}

	} // end class Verifier

	Verifier Verify = new Verifier();

} // end class Processor
