package simulator.viser;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import simulator.viser.ViserSim.PARSEC_PHASE;

public class Processor<Line extends ViserLine> implements CacheCallbacks<Line> {

	public enum ExecutionPhase {
		REGION_BODY, PRE_COMMIT, PRE_COMMIT_L1, PRE_COMMIT_L2, READ_VALIDATION, POST_COMMIT, PAUSE, REGION_L2_COMMIT,
		EVICT_L1_READ_VALIDATION, EVICT_L2_READ_VALIDATION
	};

	public enum ConflictType {
		RW, WR, WW
	};

	final CpuId id;
	/** Machine reference is shared by all processors */
	final Machine<Line> machine;

	/** L1 caches are always present, and are private to each processor */
	public final HierarchicalCache<Line> L1cache;
	/** L2 caches are private to each processor */
	public final HierarchicalCache<Line> L2cache;
	/** the L3 cache is shared by all processors */
	public final HierarchicalCache<Line> L3cache;

	// Access information cache
	public final AIMCache<Line> aimcache;

	public final BloomFilter bf;
	public final HashSet<Long> set; // keep track of unique lines written by the LLC in a region

	// Needed if deferred write backs need to be precise
	public final HashMap<Long, Long> wrMdDeferredDirtyLines = new HashMap<Long, Long>();

	/**
	 * List of all the processors in the system. All processors share the same array
	 * object.
	 */
	final Processor<Line>[] allProcessors;
	final Machine.MachineParams<Line> params;
	final ProcessorStats stats = new ProcessorStats();
	public long pausedCores = 0L;
	boolean inTrans = false;
	boolean hasTransRestart = false; // if the current transaction has been restarted.
	private short ignoreEvents = 0; // depth counter to ignore events within a lock acquire if there's any sent from
									// the
									// frontend due to multiplexing.

	boolean reRunEvent = false;
	boolean restartRegion = false;

	// counter flags should be reset when the current region finishes without
	// pausing
	boolean hasDirtyEviction = false;
	boolean regionConflicted = false;
	boolean regionWithExceptions = false;
	boolean regionHasDirtyEvictionBeforeFRV = false;
	boolean regionHasDirtyEvictionBeforeDL = false;

	double start_time = 0;
	double last_pause = 0;

	double trans_start_time = 0;

	/* tmp counters for a region. */
	tmpCounter tmpRegExecBWDrivenCycleCount = new tmpCounter();
	tmpCounter tmpPostCommitBWDrivenCycleCount = new tmpCounter();
	tmpCounter tmpPreCommitBWDrivenCycleCount = new tmpCounter();
	tmpCounter tmpReadValidationBWDrivenCycleCount = new tmpCounter();
	tmpCounter tmpNormalExecBWDrivenCycleCount = new tmpCounter();
	tmpCounter tmpPauseBWDrivenCycleCount = new tmpCounter();

	// Set<Integer> rtnCoveredSet = new HashSet<Integer>();
	// Set<Integer> srcCoveredSet = new HashSet<Integer>();

	class ProcessorStats {
		// counters for cache events
		class CacheEventCounter {
			SumCounter pc_ReadHits;
			SumCounter pc_ReadMisses;
			SumCounter pc_WriteHits;
			SumCounter pc_WriteMisses;
			SumCounter pc_LineEvictions;

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

		SumCounter pc_ViserWARUpgrades = new SumCounter("pc_ViserWARUpgrades");

		SumCounter pc_DefiniteExtraCyclesByLibConflicts = new SumCounter("pc_DefiniteExtraCyclesByLibConflicts");
		SumCounter pc_PossibleExtraCyclesByLibConflicts = new SumCounter("pc_PossibleExtraCyclesByLibConflicts");

		// This stat tracks the proportion of messages that actually need to be written
		// back due to read and write
		// metadata. If the proportion is low, then we can imagine using a software to
		// convert/encode the data before
		// writing back to memory for Viser.
		SumCounter pc_ViserLLCToMemoryMetadataWriteback = new SumCounter("pc_ViserLLCToMemoryMetadataWriteback");

		SumCounter pc_RegionBoundaries = new SumCounter("pc_RegionBoundaries");
		SumCounter pc_RegionsWithWrites = new SumCounter("pc_RegionsWithWrites");

		SumCounter pc_potentialWrRdValConflicts = new SumCounter("pc_potentialWrRdValConflicts");
		SumCounter pc_ValidationAttempts = new SumCounter("pc_ValidationAttempts");
		SumCounter pc_FailedValidations = new SumCounter("pc_FailedValidations");

		// The following "conflict"-related counters don't count failed validations.
		SumCounter pc_ConflictCheckAttempts = new SumCounter("pc_ConflictCheckAttempts");
		SumCounter pc_PreciseConflicts = new SumCounter("pc_PreciseConflicts");
		SumCounter pc_RegExecPreciseConflicts = new SumCounter("pc_RegExecPreciseConflicts");
		SumCounter pc_PreCommitPreciseConflicts = new SumCounter("pc_PreCommitPreciseConflicts");
		SumCounter pc_PostCommitPreciseConflicts = new SumCounter("pc_PostCommitPreciseConflicts");
		SumCounter pc_ReadValidationPreciseConflicts = new SumCounter("pc_ReadValidationPreciseConflicts");
		SumCounter pc_RegL2CommitPreciseConflicts = new SumCounter("pc_RegL2CommitPreciseConflicts");
		SumCounter pc_RegEvictionRVPreciseConflicts = new SumCounter("pc_RegEvictionRVPreciseConflicts");
		SumCounter pc_RWPreciseConflicts = new SumCounter("pc_RWPreciseConflicts");
		SumCounter pc_WWPreciseConflicts = new SumCounter("pc_WWPreciseConflicts");
		SumCounter pc_WRPreciseConflicts = new SumCounter("pc_WRPreciseConflicts");

		SumCounter pc_RegionsWithFRVs = new SumCounter("pc_RegionsWithFRVs");
		SumCounter pc_RegionsWithFRVsAfterPrecommit = new SumCounter("pc_RegionsWithFRVsAfterPrecommit");
		SumCounter pc_RegionsWithTolerableConflicts = new SumCounter("pc_RegionsWithTolerableConflicts");
		SumCounter pc_PotentialDeadlocks = new SumCounter("pc_PotentialDeadlocks");
		SumCounter pc_RegionsWithPotentialDeadlocks = new SumCounter("pc_RegionsWithPotentialDeadlocks");
		SumCounter pc_RegionsWithExceptions = new SumCounter("pc_RegionsWithExceptions");
		SumCounter pc_RegionHasDirtyEvictionBeforeFRV = new SumCounter("pc_RegionHasDirtyEvictionBeforeFRV");
		SumCounter pc_RegionHasDirtyEvictionBeforeDL = new SumCounter("pc_RegionHasDirtyEvictionBeforeDL");
		SumCounter pc_ExceptionsByFRVs = new SumCounter("pc_ExceptionsByFRVs");
		SumCounter pc_ExceptionsByPotentialDeadlocks = new SumCounter("pc_ExceptionsByPotentialDeadlocks");
		SumCounter pc_RegionsWithExceptionsByFRVs = new SumCounter("pc_RegionsWithExceptionsByFRVs");
		SumCounter pc_RegionsWithExceptionsByPotentialDeadlocks = new SumCounter(
				"pc_RegionsWithExceptionsByPotentialDeadlocks");
		SumCounter pc_TotalRegionRestarts = new SumCounter("pc_TotalRegionRestarts");
		SumCounter pc_TotalReboots = new SumCounter("pc_TotalReboots");
		SumCounter pc_TotalRequestRestarts = new SumCounter("pc_TotalRequestRestarts");

		SumCounter pc_TotalCheckPointRestores = new SumCounter("pc_TotalCheckPointRestores");

		SumCounter pc_DirtyL2Evictions = new SumCounter("pc_DirtyL2Evictions");
		SumCounter pc_CleanL2DirtyL1OnL2Eviction = new SumCounter("pc_CleanL2DirtyL1OnL2Eviction");

		AvgCounter pc_TotalIntervals = new AvgCounter("pc_TotalIntervals");

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

		SumCounter pc_CheckPoints = new SumCounter("pc_CheckPoints");
		SumCounter pc_PotentialCheckPoints = new SumCounter("pc_PotentialCheckPoints");

		MaxCounter pc_BandwidthDrivenCycleCount = new MaxCounter("pc_BandwidthDrivenCycleCount");
		// Break down of the cycle counts.
		DependentCounter pc_ViserRegExecBWDrivenCycleCount = new DependentCounter("pc_ViserRegExecBWDrivenCycleCount",
				pc_BandwidthDrivenCycleCount);
		DependentCounter pc_ViserPreCommitBWDrivenCycleCount = new DependentCounter(
				"pc_ViserPreCommitBWDrivenCycleCount", pc_BandwidthDrivenCycleCount);
		DependentCounter pc_ViserReadValidationBWDrivenCycleCount = new DependentCounter(
				"pc_ViserReadValidationBWDrivenCycleCount", pc_BandwidthDrivenCycleCount);
		DependentCounter pc_ViserPostCommitBWDrivenCycleCount = new DependentCounter(
				"pc_ViserPostCommitBWDrivenCycleCount", pc_BandwidthDrivenCycleCount);
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

		HashMap<Integer, Integer> hgramLLCUpdatesInARegion = new HashMap<Integer, Integer>();
		HashMap<Integer, Integer> hgramLinesValidated = new HashMap<Integer, Integer>();
		HashMap<Integer, Integer> hgramVersionSizes = new HashMap<Integer, Integer>();
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
			this.L3cache = new HierarchicalCache<Line>(args.l3config(), this, null, args.lineFactory(), this);
			if (params.useAIMCache()) {
				this.aimcache = new AIMCache<Line>(L3cache, args.lineFactory(), this);
			} else {
				this.aimcache = null;
			}
		} else {
			// reuse processor 0's L3 reference
			this.L3cache = this.allProcessors[0].L3cache;
			this.aimcache = this.allProcessors[0].aimcache;
		}

		if (args.useL2()) {
			this.L2cache = new HierarchicalCache<Line>(args.l2config(), this, this.L3cache, args.lineFactory(), this);
			this.L1cache = new HierarchicalCache<Line>(args.l1config(), this, this.L2cache, args.lineFactory(), this);
		} else {
			throw new RuntimeException("L2 is currently required in Viser");
		}

		// Create a per-core bloom filter, which is maintained by the LLC in the design
		bf = new BloomFilter();
		set = new HashSet<Long>();
	}

	@Override
	public String toString() {
		return "Processor:" + id.toString();
	}

	/* Each instruction takes one cycle. */
	public void insnsExecuted(int n) {
		stats.pc_BandwidthDrivenCycleCount.incr(n);
		updatePhaseBWDrivenCycleCost(ExecutionPhase.REGION_BODY, n);
	}

	private void memoryCyclesElapsed(int n, DataMemoryAccessResult mor) {
		stats.pc_BandwidthDrivenCycleCount.incr(n);
		updatePhaseBWDrivenCycleCost(ExecutionPhase.REGION_BODY, n);

		if (mor != null) {
			mor.latency += n;
		}
	}

	/** Returns which level is the last-level cache in the system. */
	CacheLevel llc() {
		if (L3cache != null) {
			return CacheLevel.L3;
		} else if (L2cache != null) {
			return CacheLevel.L2;
		} else {
			return CacheLevel.L1;
		}
	}

	void updatePhaseBWDrivenCycleCost(ExecutionPhase phase, double value) {
		if (phase != ExecutionPhase.PAUSE) {
			tmpNormalExecBWDrivenCycleCount.incr(value);
		}
		switch (phase) {
		case REGION_BODY: {
			tmpRegExecBWDrivenCycleCount.incr(value);
			break;
		}
		case POST_COMMIT: {
			tmpPostCommitBWDrivenCycleCount.incr(value);
			break;
		}
		case PRE_COMMIT: {
			tmpPreCommitBWDrivenCycleCount.incr(value);
			break;
		}
		case READ_VALIDATION: {
			tmpReadValidationBWDrivenCycleCount.incr(value);
			break;
		}
		case PAUSE: {
			tmpPauseBWDrivenCycleCount.incr(value);
			break;
		}
		default: {
			assert false;
		}
		}
	}

	void updatePhaseTolerableConflicts(ExecutionPhase phase) {
		switch (phase) {
		case REGION_BODY: {
			stats.pc_RegExecPreciseConflicts.incr();
			break;
		}
		case POST_COMMIT: {
			stats.pc_PostCommitPreciseConflicts.incr();
			break;
		}
		case PRE_COMMIT: {
			stats.pc_PreCommitPreciseConflicts.incr();
			break;
		}
		case READ_VALIDATION: {
			stats.pc_ReadValidationPreciseConflicts.incr();
			break;
		}
		case REGION_L2_COMMIT: {
			stats.pc_RegL2CommitPreciseConflicts.incr();
			break;
		}
		case EVICT_L1_READ_VALIDATION:
		case EVICT_L2_READ_VALIDATION: {
			stats.pc_RegEvictionRVPreciseConflicts.incr();
			break;
		}
		default: {
			assert false;
		}
		}
	}

	void updateTypeTolerableConflicts(ConflictType type) {
		switch (type) {
		case RW: {
			stats.pc_RWPreciseConflicts.incr();
			break;
		}
		case WW: {
			stats.pc_WWPreciseConflicts.incr();
			break;
		}
		case WR: {
			stats.pc_WRPreciseConflicts.incr();
			break;
		}
		default: {
			assert false;
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
			// break-downs we don't use for Pacifist
			stats.pc_ViserRegExecBWDrivenCycleCount.incr(tmpRegExecBWDrivenCycleCount.get(), true);
			stats.pc_ViserPostCommitBWDrivenCycleCount.incr(tmpPostCommitBWDrivenCycleCount.get(), true);
			stats.pc_ViserPreCommitBWDrivenCycleCount.incr(tmpPreCommitBWDrivenCycleCount.get(), true);
			stats.pc_ViserReadValidationBWDrivenCycleCount.incr(tmpReadValidationBWDrivenCycleCount.get(), true);

			// Counters we care about
			stats.pc_ViserNormalExecBWDrivenCycleCount.incr(tmpNormalExecBWDrivenCycleCount.get(), true);
			stats.pc_ViserPauseBWDrivenCycleCount.incr(tmpPauseBWDrivenCycleCount.get(), true);
		}
	}

	@Override
	public Line eviction(final Line incoming, final LinkedList<Line> set, final CacheLevel level, ExecutionPhase phase,
			short bits) {
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
						enc = l1Line.getWriteEncoding(id) | toEvict.getWriteEncoding(id);
					else
						enc = toEvict.getWriteEncoding(id);
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

		assert toEvict.id() == id;
		if (toEvict.valid()) {
			switch (level) {
			case L1: {
				stats.pc_l1d.pc_LineEvictions.incr();
				break;
			}
			case L2: {
				stats.pc_l2d.pc_LineEvictions.incr();
				break;
			}
			case L3: {
				stats.pc_l3d.pc_LineEvictions.incr();
				break;
			}
			case MEMORY:
			default: {
				assert false : "Wrong level";
			}
			}
		}
		return toEvict;
	}

	public static class DataMemoryAccessResult {
		/** The latency of this memory operation, in cycles. */
		int latency = 0;
		/**
		 * true if remote communication happened, due to remote hit or LLC miss, false
		 * on a purely local hit
		 */
		boolean remoteCommunicatedHappened = false;

		/**
		 * Aggregate the result of another memory op into the current result.
		 */
		void aggregate(DataMemoryAccessResult dmar) {
			this.remoteCommunicatedHappened |= dmar.remoteCommunicatedHappened;
			this.latency = Math.max(this.latency, dmar.latency);
		}
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

		MemoryResponse<Line> resp = null;
		resp = L1cache.requestWithSpecialInvalidState(this, access, true);
		if (params.useSpecialInvalidState()) {
			// Both cannot be true at the same time
			assert (resp.invalidStateHit ? !resp.invalidStateFailure : true)
					&& (resp.invalidStateFailure ? !resp.invalidStateHit : true);
		}
		if (resp.invalidStateHit || resp.invalidStateFailure) {
			stats.pc_TotalMemoryAccessesSpecialInvalidState.incr();
		}

		Line line = resp.lineHit;
		if (!reRunEvent && !restartRegion) {
			assert line.id() == id : "The owner of a private line should always be the current core";
			assert line.valid() && line.getLevel() == CacheLevel.L1;
			assert line.getEpoch(id).equals(getCurrentEpoch());
		}

		// update metadata after compute costs

		switch (resp.whereHit) {
		case L1: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicReadHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockReadHits.incr();
			} else {
				stats.pc_l1d.pc_ReadHits.incr();
			}

			if (resp.invalidStateHit) {
				// The core had to talk with the LLC, L1 latency is included
				int cost;
				if (resp.invalidStateSharedHitLevel == CacheLevel.L3) {
					cost = MemorySystemConstants.L3_HIT_LATENCY;
				} else {
					cost = MemorySystemConstants.MEMORY_LATENCY;
				}
				memoryCyclesElapsed(cost, dmaResult);
			} else {
				memoryCyclesElapsed(MemorySystemConstants.L1_HIT_LATENCY, dmaResult);
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

			if (resp.invalidStateHit) {
				// The core had to talk with the LLC, L2 latency is included
				int cost;
				if (resp.invalidStateSharedHitLevel == CacheLevel.L3) {
					cost = MemorySystemConstants.L3_HIT_LATENCY;
				} else {
					cost = MemorySystemConstants.MEMORY_LATENCY;
				}
				memoryCyclesElapsed(cost, dmaResult);
			} else {
				memoryCyclesElapsed(MemorySystemConstants.L2_HIT_LATENCY, dmaResult);
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

			if (params.useAIMCache()) {
				// This is a L3 hit, check the AIM status
				AIMResponse<Line> aimResp = aimcache.request(this, access.addr(), true);
				switch (aimResp.whereHit) {
				case L3: {
					memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
					stats.pc_aim.pc_ReadHits.incr();
					break;
				}
				case MEMORY: {
					MemoryResponse<Line> sharedResp = getLineFromLLCOrMemory(line);
					assert sharedResp.lineHit != null;
					Line llcLine = sharedResp.lineHit;
					boolean miss = false;
					for (int i = 0; i < params.numProcessors(); i++) {
						CpuId cpuId = new CpuId(i);
						if (llcLine.hasReadOffsets(cpuId) || llcLine.hasWrittenOffsets(cpuId)) {
							miss = true;
							break;
						}
					}

					if (miss) {
						memoryCyclesElapsed(MemorySystemConstants.MEMORY_LATENCY, dmaResult);
						stats.pc_aim.pc_ReadMisses.incr();
					} else {
						memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
						stats.pc_aim.pc_ReadHits.incr();
					}
					break;
				}
				default: {
					assert false;
				}
				}
			} else {
				memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
			}

			if (!params.ignoreFetchingReadBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearReadBitsForPrivateLines(line);
				}
			}
			if (!params.ignoreFetchingWriteBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearWriteBitsForPrivateLines(line);
				}
			}
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
					stats.pc_l1d.pc_LockReadMisses.incr();
				}
				stats.pc_l3d.pc_LockReadMisses.incr();
			} else {
				stats.pc_l1d.pc_ReadMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_ReadMisses.incr();
				}
				stats.pc_l3d.pc_ReadMisses.incr();
			}

			memoryCyclesElapsed(MemorySystemConstants.MEMORY_LATENCY, dmaResult);

			if (!params.ignoreFetchingReadBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearReadBitsForPrivateLines(line);
				}
			}
			if (!params.ignoreFetchingWriteBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearWriteBitsForPrivateLines(line);
				}
			}

			break;
		}
		}

		if (access.isRegularMemAccess()) { // regular read
			// Update read encoding only if it happens before a write
			long enc = access.getEncoding();
			if (!line.isOffsetWritten(id, enc) && !reRunEvent && !restartRegion) {
				line.orReadEncoding(id, enc);
				line.updateReadSiteInfo(id, enc, access.siteInfo(), access.lastSiteInfo());
			}
		} else { // Lock + atomic accesses
			// We don't set metadata for these special accesses

			if ((resp.whereHit == CacheLevel.L3 || resp.whereHit == CacheLevel.MEMORY)
					&& line.getLockOwnerID() != id.get() && line.getLockOwnerID() >= 0) {
				// Account for a round trip latency and network cost (LLC to owner core +
				// response from owner
				// core to LLC)
				memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
			}
		}

		// L1 and L2 states and versions should always match
		if (ViserSim.assertsEnabled && !reRunEvent && !restartRegion) {
			Line l1Line = L1cache.getLine(line);
			Line l2Line = L2cache.getLine(line);
			assert l1Line.getState() == l2Line.getState() : "Event:" + ViserSim.totalEvents;
			assert l1Line.getVersion() == l2Line.getVersion() : "L1 and L2 cache line version should match, Event: "
					+ ViserSim.totalEvents;
			assert l1Line.getEpoch(id).equals(l2Line.getEpoch(id)) : "Event:" + ViserSim.totalEvents;
		}

		if (ViserSim.xassertsEnabled()) {
			// This does not hold in Viser, since the L3 cache is not inclusive.
			// Verify.verifyInvalidLinesInLLC();
			Verify.verifyCacheIndexing();
			Verify.verifyPrivateCacheInclusivityAndVersions(this);
			Verify.verifyPerCoreMetadata(this);
			Verify.verifyExecutionCostBreakdown(this);
			if (params.useAIMCache()) {
				Verify.verifyAIMCacheInclusivity(this);
				Verify.verifyAIMCacheDuplicates(this);
			}
		}

		return dmaResult;
	} // end read()

	int computeVariableMessageSizeBytes(Line memLine) {
		int sizeBytes = MemorySystemConstants.DATA_MESSAGE_SIZE_BYTES/* line size */ + machine.VISER_VARIABLE_MSG_HEADER
				+ MemorySystemConstants.VISER_VERSION_BYTES; // version bytes
		for (int i = 0; i < params.numProcessors(); i++) {
			CpuId cpuId = new CpuId(i);
			PerCoreLineMetadata md = memLine.getPerCoreMetadata(cpuId);
			Processor<Line> p = machine.getProc(cpuId);
			assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
			if (md.epoch.equals(p.getCurrentEpoch())) {
				// read and write information for this processor, if it is
				// non-zero
				if (memLine.hasReadOffsets(cpuId)) {
					sizeBytes += MemorySystemConstants.VISER_READ_METADATA_BYTES;
				}
				if (memLine.hasWrittenOffsets(cpuId)) {
					sizeBytes += MemorySystemConstants.VISER_WRITE_METADATA_BYTES;
				}
			}
		}
		return sizeBytes;
	}

	class RemoteReadResponse {
		boolean isShared = false;
		boolean providedData = false;
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
		return (1L << off);
	}

	// No need to fetch the line if the access misses a private cache
	public DataMemoryAccessResult lockReleaseWrite(final DataAccess access) {
		DataMemoryAccessResult dmaResult = new DataMemoryAccessResult();
		MemoryResponse<Line> resp = L1cache.requestWithSpecialInvalidState(this, access, false);
		if (params.useSpecialInvalidState()) {
			// Both cannot be true at the same time
			assert (resp.invalidStateHit ? !resp.invalidStateFailure : true)
					&& (resp.invalidStateFailure ? !resp.invalidStateHit : true);
		}
		if (resp.invalidStateHit || resp.invalidStateFailure) {
			stats.pc_TotalMemoryAccessesSpecialInvalidState.incr();
		}

		Line line = resp.lineHit;
		if (resp.whereHit.compareTo(llc()) < 0) {
			assert line.id() == id : "The owner of a private line should always be the current core";
			assert line.valid() && line.getLevel() == CacheLevel.L1;
			assert line.getEpoch(id).equals(getCurrentEpoch());
		}

		switch (resp.whereHit) {
		case L1: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteHits.incr();
			} else {
				stats.pc_l1d.pc_LockWriteHits.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.L1_HIT_LATENCY, dmaResult);
			break;
		}

		case L2: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicWriteHits.incr();
				}
			} else {
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockWriteHits.incr();
				}
			}
			memoryCyclesElapsed(MemorySystemConstants.L2_HIT_LATENCY, dmaResult);
			break;
		}
		case L3: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l1d.pc_AtomicWriteMisses.incr();
				}
				stats.pc_l1d.pc_AtomicWriteHits.incr();
			} else {
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l1d.pc_LockWriteMisses.incr();
				}
				stats.pc_l1d.pc_LockWriteHits.incr();
			}
			// request to LLC, and no return message
			memoryCyclesElapsed(MemorySystemConstants.L3_ACCESS, dmaResult);
			break;
		}
		case MEMORY: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_AtomicWriteMisses.incr();
				}
				stats.pc_l3d.pc_AtomicWriteMisses.incr();
			}
			{
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_LockWriteMisses.incr();
				}
				stats.pc_l3d.pc_LockWriteMisses.incr();
			}
			memoryCyclesElapsed(MemorySystemConstants.MEMORY_ACCESS, dmaResult);

			break;
		}
		}
		MemoryResponse<Line> sharedResp = getLineFromLLCOrMemory(line);
		assert sharedResp.lineHit != null;
		Line llcLine = sharedResp.lineHit;
		// Still keep the same owner
		llcLine.setLockOwnerID(id.get());
		line.setLockOwnerID(id.get());

		if (access.type == MemoryAccessType.ATOMIC_WRITE) {
			assert resp.whereHit.compareTo(llc()) < 0; // We expect this to hit all the time since there's an atomic
														// read ahead.
			// Invalidate the line in the last owner
			if (line.getLockOwnerID() >= 0 && line.getLockOwnerID() != id.get()) {
				Processor<Line> lastOwner = machine.getProc(new CpuId(line.getLockOwnerID()));
				Line l2Line = lastOwner.L2cache.getLine(line);
				if (l2Line != null && l2Line.valid()) {
					l2Line.invalidate();
					Line l1Line = lastOwner.L1cache.getLine(line);
					if (l1Line != null && l1Line.valid())
						l1Line.invalidate();
				}
			}
		}

		return dmaResult;
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

		if (access.type() == MemoryAccessType.LOCK_REL_WRITE || access.type() == MemoryAccessType.ATOMIC_WRITE) {
			return lockReleaseWrite(access);
		}

		DataMemoryAccessResult dmaResult = new DataMemoryAccessResult();

		MemoryResponse<Line> resp = L1cache.requestWithSpecialInvalidState(this, access, false);
		if (params.useSpecialInvalidState()) {
			// Both cannot be true at the same time
			assert (resp.invalidStateHit ? !resp.invalidStateFailure : true)
					&& (resp.invalidStateFailure ? !resp.invalidStateHit : true);
		}
		if (resp.invalidStateHit || resp.invalidStateFailure) {
			stats.pc_TotalMemoryAccessesSpecialInvalidState.incr();
		}

		Line line = resp.lineHit;

		if (!reRunEvent && !restartRegion) {
			assert line.id() == id : "The owner of a private line should always be the current core";
			assert line.valid() && line.getLevel() == CacheLevel.L1;
			assert line.getEpoch(id).equals(getCurrentEpoch());
		}
		long enc = access.getEncoding();
		if (access.isRegularMemAccess()) { // regular write
			// Handle write-after-read upgrade case, where a write follows an earlier read,
			// but only if the write hits in L1.
			if (line.isOffsetReadOnly(id, enc) && resp.whereHit == CacheLevel.L1) {
				// Only handle WAR at the first pair of read-write
				// Write back the old read value and the read encoding to the L2 cache as
				// backup.
				// Do not copy all the values in the whole line, since that is incorrect.
				// Consider the following situation:
				// R1
				// W1
				// R2
				// W2
				// The second write back will overwrite the backed up read possibly before the
				// read was validated
				// Only handle WAR at the first pair of read-write, i.e. read-only offsets
				long readOnlyBits = ((line.getReadEncoding(id) & ~line.getWriteEncoding(id)) & enc);
				L2cache.handleWriteAfterReadUpgrade(line, readOnlyBits);

				// Ideally, the L2 line will already have the data that is read, since it will
				// be fetched from
				// memory.
				// So, we do not need to add the cost. Data is actually flowing in from the
				// memory, not the
				// front-end.
				stats.pc_ViserWARUpgrades.incr();
			}
			// write to a deferred line, need to back up the deferred values in L2
			if ((params.restartAtFailedValidationsOrDeadlocks() || params.FalseRestart())
					&& params.BackupDeferredWritebacksLasily() && line.isDeferredWriteBitSet()
					&& line.getWriteEncoding(id) == 0) {
				Line l2cLine = L2cache.getLine(line);
				assert l2cLine.valid() && l2cLine.getWriteEncoding(id) == 0;
				assert params.deferWriteBacks();
				l2cLine.copyAllValues(line);
				l2cLine.setDeferredWriteBit();
				line.resetDeferredWriteBit();
			}

		}
		// set write encoding after computing costs
		line.setValue(access.addr(), access.value());

		switch (resp.whereHit) {
		case L1: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockWriteHits.incr();
			} else {
				stats.pc_l1d.pc_WriteHits.incr();
			}

			if (access.isRegularMemAccess() && resp.invalidStateHit) {
				// The core had to talk with the LLC, L1 latency is included
				int cost;
				if (resp.invalidStateSharedHitLevel == CacheLevel.L3) {
					cost = MemorySystemConstants.L3_HIT_LATENCY;
				} else {
					cost = MemorySystemConstants.MEMORY_LATENCY;
				}
				memoryCyclesElapsed(cost, dmaResult);
			} else {
				memoryCyclesElapsed(MemorySystemConstants.L1_HIT_LATENCY, dmaResult);
			}
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

			if (access.isRegularMemAccess() && resp.invalidStateHit) {
				// The core had to talk with the LLC, L2 latency is included
				int cost;
				if (resp.invalidStateSharedHitLevel == CacheLevel.L3) {
					cost = MemorySystemConstants.L3_HIT_LATENCY;
				} else {
					cost = MemorySystemConstants.MEMORY_LATENCY;
				}
				memoryCyclesElapsed(cost, dmaResult);
			} else {
				memoryCyclesElapsed(MemorySystemConstants.L2_HIT_LATENCY, dmaResult);
			}
			break;
		}

		case L3: {
			if (access.isAtomic()) {
				stats.pc_l1d.pc_AtomicWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l1d.pc_AtomicWriteMisses.incr();
				}
				stats.pc_l1d.pc_AtomicWriteHits.incr();
			} else if (access.isLockAccess()) {
				stats.pc_l1d.pc_LockWriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l1d.pc_LockWriteMisses.incr();
				}
				stats.pc_l1d.pc_LockWriteHits.incr();
			} else {
				stats.pc_l1d.pc_WriteMisses.incr();
				if (params.useL2()) {
					stats.pc_l2d.pc_WriteMisses.incr();
				}
				stats.pc_l3d.pc_WriteHits.incr();
			}

			if (params.useAIMCache() && access.isRegularMemAccess()) {
				// This is a L3 hit, check the AIM status
				AIMResponse<Line> aimResp = aimcache.request(this, access.addr(), false);
				switch (aimResp.whereHit) {
				case L3: {
					memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
					stats.pc_aim.pc_WriteHits.incr();
					break;
				}
				case MEMORY: {
					// Check if the line has non-zero metadata for at least one core. It is an AIM
					// miss only
					// if so.
					// L1/L2 and the LLC is not inclusive for Viser. Since this is an LLC hit, we
					// expect a
					// fetch from LLC should hold over here as well. But there could the following
					// scenario:
					// LLC line is fetched into L2, L2 line is evicted, and that causes the current
					// LLC line to
					// get evicted.
					// This happens at least for canneal and dedup
					MemoryResponse<Line> sharedResp = getLineFromLLCOrMemory(line);
					assert sharedResp.lineHit != null;
					Line llcLine = sharedResp.lineHit;
					boolean miss = false;
					for (int i = 0; i < params.numProcessors(); i++) {
						CpuId cpuId = new CpuId(i);
						if (llcLine.hasReadOffsets(cpuId) || llcLine.hasWrittenOffsets(cpuId)) {
							miss = true;
							break;
						}
					}

					if (miss) {
						memoryCyclesElapsed(MemorySystemConstants.MEMORY_LATENCY, dmaResult);
						stats.pc_aim.pc_WriteMisses.incr();
					} else {
						memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
						stats.pc_aim.pc_WriteHits.incr();
					}

					break;
				}
				default: {
					assert false;
				}
				}
			} else {
				memoryCyclesElapsed(MemorySystemConstants.L3_HIT_LATENCY, dmaResult);
			}

			if (!params.ignoreFetchingReadBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearReadBitsForPrivateLines(line);
				}
			}
			if (!params.ignoreFetchingWriteBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearWriteBitsForPrivateLines(line);
				}
			}

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
			memoryCyclesElapsed(MemorySystemConstants.MEMORY_LATENCY, dmaResult);

			if (!params.ignoreFetchingReadBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearReadBitsForPrivateLines(line);
				}
			}
			if (!params.ignoreFetchingWriteBits()) {
			} else {
				if (!reRunEvent && !restartRegion) {
					clearWriteBitsForPrivateLines(line);
				}
			}

			break;
		}
		}

		if (access.isRegularMemAccess()) {
			// set metadata
			if (!reRunEvent && !restartRegion) {
				if (params.setWriteBitsInL2() && !line.hasWrittenOffsets(id)) { // write through write bits (and
																				// MRU-bits at first write)
					Line l2Line = L2cache.getLine(line);
					l2Line.l2WriteBit = true;
					L2cache.setMRUBit(l2Line, false); // Ensure the invariance that we set MRU bits every time setting
														// write bits
				}
				line.orWriteEncoding(id, enc);
				// No harm to set the deferred-write bit of an L1 line even if we backup
				// deferred write-backs eagerly
				if (line.hasWrittenOffsets(id))
					line.setDeferredWriteBit();
				line.updateWriteSiteInfo(id, enc, access.siteInfo(), access.lastSiteInfo());
			}
		} else { // lock acquire write
			// We don't set metadata for special accesses

			assert access.type == MemoryAccessType.LOCK_ACQ_WRITE;
			assert resp.whereHit.compareTo(llc()) < 0; // We expect this to hit all the time since there's a lock
														// acquire read ahead.
			MemoryResponse<Line> sharedResp = getLineFromLLCOrMemory(line);
			assert sharedResp.lineHit != null;
			Line llcLine = sharedResp.lineHit;

			// Invalidate the line in the last owner
			if (line.getLockOwnerID() >= 0 && line.getLockOwnerID() != id.get()) {
				Processor<Line> lastOwner = machine.getProc(new CpuId(line.getLockOwnerID()));
				Line l2Line = lastOwner.L2cache.getLine(line);
				if (l2Line != null && l2Line.valid()) {
					l2Line.invalidate();
					Line l1Line = lastOwner.L1cache.getLine(line);
					if (l1Line != null && l1Line.valid())
						l1Line.invalidate();
				}
			}

			line.setLockOwnerID(id.get());
			llcLine.setLockOwnerID(id.get()); // Current core owns the lock
		}

		if (!reRunEvent && !restartRegion && ViserSim.assertsEnabled
				&& access.type() != MemoryAccessType.LOCK_REL_WRITE) {
			// Since we invalidate the private line on a release that misses in the private
			// cache to simulate not
			// bringing in a line
			Line l1Line = L1cache.getLine(line);
			Line l2Line = L2cache.getLine(line);
			assert l1Line.getState() == l2Line.getState();
			assert l1Line.getVersion() == l2Line
					.getVersion() : "L1 and L2 cache line version should match, Total events: " + ViserSim.totalEvents;
			assert l1Line.getEpoch(id).equals(l2Line.getEpoch(id));
		}

		if (ViserSim.xassertsEnabled()) {
			// This does not hold in Viser, since the L3 cache is not inclusive.
			// Verify.verifyInvalidLinesInLLC();
			Verify.verifyCacheIndexing();
			Verify.verifyPrivateCacheInclusivityAndVersions(this);
			Verify.verifyPerCoreMetadata(this);
			Verify.verifyExecutionCostBreakdown(this);
			if (params.useAIMCache()) {
				Verify.verifyAIMCacheInclusivity(this);
				Verify.verifyAIMCacheDuplicates(this);
			}
		}

		return dmaResult;
	} // end write()

	void clearReadBitsForPrivateLines(Line line) {
		assert line.getLevel() == CacheLevel.L1;
		line.clearReadEncoding(id);
		if (params.useL2()) {
			Line l2Line = L2cache.getLine(line);
			assert l2Line != null;
			l2Line.clearReadEncoding(id);
		}
	}

	void clearWriteBitsForPrivateLines(Line line) {
		assert line.getLevel() == CacheLevel.L1;
		line.clearWriteEncoding(id);
		line.resetDeferredWriteBit();
		if (params.useL2()) {
			Line l2Line = L2cache.getLine(line);
			assert l2Line != null;
			l2Line.clearWriteEncoding(id);
			l2Line.resetDeferredWriteBit();
		}
	}

	public Epoch getCurrentEpoch() {
		return machine.getEpoch(id);
	}

	/**
	 * Check for precise conflicts between two cache lines, ignoring the current
	 * processor.
	 *
	 * @param b
	 * @param earlyReadValidation
	 */
	void checkPreciseWriteReadConflicts(Line sharedLine, Line privLine, ExecutionPhase phase) {
		if (!(!params.isHttpd() && (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
				|| ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI)) { // Not in ROIs
			return;
		}

		stats.pc_ConflictCheckAttempts.incr();
		long existingReads = privLine.getReadEncoding(id);
		for (int i = 0; i < params.numProcessors(); i++) {
			if (i == id.get()) {
				continue; // ignore the same processor
			}
			CpuId cpuId = new CpuId(i);
			Processor<Line> p = machine.getProc(cpuId);
			PerCoreLineMetadata md = sharedLine.getPerCoreMetadata(cpuId);
			assert md != null;
			assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
			if (md.epoch.equals(p.getCurrentEpoch())) { // The region is ongoing
				long sharedWrites = sharedLine.getWriteEncoding(cpuId);
				if ((sharedWrites & existingReads) != 0) {
					boolean preciseConflict = true;
					if (params.siteTracking()) {
						preciseConflict = reportConflict(phase, "write", "read", sharedLine, cpuId, privLine,
								sharedWrites, existingReads);
					}
					if (preciseConflict) {
						stats.pc_PreciseConflicts.incr();
						updatePhaseTolerableConflicts(phase);
						updateTypeTolerableConflicts(ConflictType.WR);
						// Pause the current(reader) core at a precise write-read conflict
						handleConflict(cpuId);
						return;
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
			if (!machine.processors[i].hasDirtyEviction) { // eligible for restart
				// pause the current core
				machine.processors[rid].setPausedCores(cid);
				if (last_pause != 0)
					stats.pc_TotalIntervals.incr(stats.pc_BandwidthDrivenCycleCount.stat - last_pause);
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
					System.out.println("paused_cycles < 0: " + end_time + " " + eligileCore.start_time + " "
							+ waiteeCore + " " + eligileCore);
					System.exit(-177);
				} else {
					eligileCore.stats.pc_BandwidthDrivenCycleCount.incr(paused_cycles);
					eligileCore.updatePhaseBWDrivenCycleCost(ExecutionPhase.PAUSE, paused_cycles);
				}
				waiteeCore.clearPausedCores(i);

				// restart the core with an eligible region
				eligileCore.stats.pc_TotalRegionRestarts.incr();
				eligileCore.prepareRestart();
				ViserSim.pos[i] = 0;
				ViserSim.totalRegionRestarts++;
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

	private boolean reportConflict(ExecutionPhase phase, String preop, String curop, Line preline, CpuId cpuId,
			Line curline, long preenc, long curenc) {
		/*
		 * rtnCoveredSet.clear(); srcCoveredSet.clear();
		 */
		boolean preciseConflict = false;
		boolean print = params.printConflictingSites();
		long enc = preenc & curenc;
		int[] preSiIndex;
		int[] curSiIndex;
		int[] preLastSiIndex;
		int[] curLastSiIndex;
		if (preop.equals("write")) {
			preSiIndex = preline.getWriteSiteInfo(cpuId);
			preLastSiIndex = preline.getWriteLastSiteInfo(cpuId);
		} else {
			preSiIndex = preline.getReadSiteInfo(cpuId);
			preLastSiIndex = preline.getReadLastSiteInfo(cpuId);
		}
		if (curop.equals("write")) {
			curSiIndex = curline.getWriteSiteInfo(id);
			curLastSiIndex = curline.getWriteLastSiteInfo(id);
		} else {
			curSiIndex = curline.getReadSiteInfo(id);
			curLastSiIndex = curline.getReadLastSiteInfo(id);
		}
		if (print)
			System.out.println("[visersim] During " + phase + ", a " + preop + "-" + curop + " conflict is detected at "
					+ curline.addr.lineAddr + ".");
		/*
		 * boolean noSrcInfo = false; boolean noFuncName = false;
		 */
		for (int off = 0; off < MemorySystemConstants.LINE_SIZE(); off++) {
			if (((1L << off) & enc) != 0) {
				SiteInfoEntry curSi = machine.siteInfo.get(curSiIndex[off]);
				SiteInfoEntry curLastSi = machine.siteInfo.get(curLastSiIndex[off]);
				SiteInfoEntry preSi = machine.siteInfo.get(preSiIndex[off]);
				SiteInfoEntry preLastSi = machine.siteInfo.get(preLastSiIndex[off]);
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
						System.out.println("\tcurrent " + curop + " by " + curline.id() + " at " + curfno + ":" + curlno
								+ ":" + currno + " (callerSite: " + curLastSi.fileIndexNo + ":" + curLastSi.lineNo
								+ ").");
						System.out.println("\tprevious " + preop + " by " + cpuId + " at " + prefno + ":" + prelno + ":"
								+ prerno + " (callerSite: " + preLastSi.fileIndexNo + ":" + preLastSi.lineNo + ").");
					}
				}
				machine.updateConflictCounters(curfno, curlno, currno, prefno, prelno, prerno, curLastSi.fileIndexNo,
						curLastSi.lineNo, preLastSi.fileIndexNo, preLastSi.lineNo);
			}
		}
		// reset the counters to allow counting for other lines.
		machine.resetConflictCounter();
		if (print)
			System.out.println("=================================== Conflicts =======================================");

		/*
		 * for (int fno : srcCoveredSet) { machine.srcCoverage[fno]++; } for (int rno :
		 * rtnCoveredSet) { machine.rtnCoverage[rno]++; } if (noSrcInfo)
		 * stats.pc_ConflictsWithoutSrcInfo.incr(); if (noFuncName)
		 * stats.pc_ConflictsWithoutFuncName.incr();
		 */
		return preciseConflict;
	}

	void handleConflict(CpuId cpuId) {
		if (params.pauseCoresAtConflicts()) {
			if (!regionConflicted) {
				regionConflicted = true;
				stats.pc_RegionsWithTolerableConflicts.incr();
			}

			machine.processors[cpuId.get()].setPausedCores(id.get());
			short[] waitee = new short[params.numProcessors()];
			short[] ends = new short[2];
			if (!machine.detectDeadlock(waitee, ends)) {
				if (last_pause != 0)
					stats.pc_TotalIntervals.incr(stats.pc_BandwidthDrivenCycleCount.stat - last_pause);
				last_pause = stats.pc_BandwidthDrivenCycleCount.stat;
				start_time = machine.processors[cpuId.get()].stats.pc_BandwidthDrivenCycleCount.stat;
				machine.setPausingBitsAtOffset(id.get());
				/*
				 * System.out.println("[Current proc: " + id + "] Pause the writer core " + id +
				 * " at a read-write conflict with " + cpuId + " current: " + last_pause +
				 * " start: " + start_time);
				 */
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

	/**
	 * Check for precise conflicts between two cache lines, ignoring the current
	 * processor.
	 */
	// used at precommit, early-pre-commit
	void checkPreciseConflicts(Line sharedLine, Line privLine, ExecutionPhase phase) {
		if (!(!params.isHttpd() && (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
				|| ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI)) { // Not in ROIs
			return;
		}

		stats.pc_ConflictCheckAttempts.incr();
		long existingWrites = privLine.getWriteEncoding(id);
		// long existingReads = privLine.getReadEncoding(id);
		for (int i = 0; i < params.numProcessors(); i++) {
			if (i == id.get()) {
				continue; // ignore the same processor
			}
			CpuId cpuId = new CpuId(i);
			Processor<Line> p = machine.getProc(cpuId);
			PerCoreLineMetadata md = sharedLine.getPerCoreMetadata(cpuId);
			assert md != null;
			assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
			if (md.epoch.equals(p.getCurrentEpoch())) { // The region is ongoing
				long sharedWrites = sharedLine.getWriteEncoding(cpuId);
				long sharedReads = sharedLine.getReadEncoding(cpuId);
				if ((sharedReads & existingWrites) != 0) {
					boolean preciseConflict = true;
					if (params.siteTracking()) {
						preciseConflict = reportConflict(phase, "read", "write", sharedLine, cpuId, privLine,
								sharedReads, existingWrites);
					}
					if (preciseConflict) {
						stats.pc_PreciseConflicts.incr();
						updatePhaseTolerableConflicts(phase);
						updateTypeTolerableConflicts(ConflictType.RW);
						handleConflict(cpuId);
						return;
					}
				} else if ((sharedWrites & existingWrites) != 0) {
					boolean preciseConflict = true;
					if (params.siteTracking()) {
						preciseConflict = reportConflict(phase, "write", "write", sharedLine, cpuId, privLine,
								sharedWrites, existingWrites);
					}
					if (preciseConflict) {
						stats.pc_PreciseConflicts.incr();
						updatePhaseTolerableConflicts(phase);
						updateTypeTolerableConflicts(ConflictType.WW);
						handleConflict(cpuId);
						return;
					}
				}
			}
		}
	}

	private void handleDeadlock(CpuId cpuId, short[] waitee, short[] ends) {
		if (params.printConflictingSites())
			System.out.println("Potential deadlocks between the current core " + id + " and " + cpuId);
		machine.processors[cpuId.get()].clearPausedCores(id.get());
		stats.pc_PotentialDeadlocks.incr();
		if (!regionWithExceptions) {
			stats.pc_RegionsWithPotentialDeadlocks.incr();

			if (!regionHasDirtyEvictionBeforeDL && hasDirtyEviction) {
				stats.pc_RegionHasDirtyEvictionBeforeDL.incr();
				regionHasDirtyEvictionBeforeDL = true;
			}
			boolean restartable = false;
			if (params.restartAtFailedValidationsOrDeadlocks()) {
				if (!hasDirtyEviction) {
					restartable = true;
					stats.pc_TotalRegionRestarts.incr();
					assert !restartRegion;
					restartRegion = true;
					prepareRestart();
				} else if (restartAnEligibleRegionFormAnotherCore(waitee, ends, cpuId.get())) {
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

	public void setPausedCores(short offset) {
		pausedCores |= (1L << offset);
	}

	public void clearPausedCores(short offset) {
		pausedCores &= ~(1L << offset);
	}

	public long getPausedCores() {
		return pausedCores;
	}

	/**
	 * Supposed to be executed only while fetching from memory. This will clear old
	 * access information for past epochs.
	 */
	Line clearAccessEncoding(Line sharedLine) {
		for (int i = 0; i < params.numProcessors(); i++) {
			CpuId cpuId = new CpuId(i);
			Processor<Line> p = machine.getProc(cpuId);
			PerCoreLineMetadata md = sharedLine.getPerCoreMetadata(cpuId);
			assert md != null;
			assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
			if (md.epoch.getRegionId() < p.getCurrentEpoch().getRegionId()) {
				sharedLine.clearReadEncoding(cpuId);
				sharedLine.clearWriteEncoding(cpuId);
			}
		}
		return sharedLine;
	}

	// Cannot use hashCode to search Java collections without overriding equals()
	// and hashCode(),
	// since we create/use different line objects
	public Long getDeferredWriteMetadata(Line l) {
		Iterator<Map.Entry<Long, Long>> entries = wrMdDeferredDirtyLines.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<Long, Long> entry = entries.next();
			if (l.lineAddress().get() == entry.getKey()) {
				return entry.getValue();
			}
		}
		return null;
	}

	public void removeDeferredWriteLine(Line l) {
		Iterator<Map.Entry<Long, Long>> entries = wrMdDeferredDirtyLines.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<Long, Long> entry = entries.next();
			if (l.lineAddress().get() == entry.getKey()) {
				entries.remove();
				return;
			}
		}
		assert false;
	}

	/**
	 * Write back write encoding and dirty values to shared memory (LLC, then main
	 * memory). Also form epochs for later lookups.
	 */
	private void sendDirtyValuesToLLC() {
		final HashSet<Long> dirtyL1Lines = new HashSet<Long>();

		for (Deque<Line> set : L1cache.sets) {
			for (Line l : set) {
				if (l.hasWrittenOffsets(id)) {
					assert l.valid() : "Written line has to be VALID";
					assert l.getEpoch(id).equals(getCurrentEpoch());

					dirtyL1Lines.add(l.lineAddress().get()); // We want to skip L2 lines
					L3cache.updateWriteInfoInLLC(this, l, ExecutionPhase.PRE_COMMIT_L1);
				}
			}
		}

		if (params.useL2()) {
			// Visit L2-only dirty lines, and skip L1 dirty lines.
			for (Deque<Line> set : L2cache.sets) {
				for (Line l : set) {
					if (l.hasWrittenOffsets(id)) {
						assert l.valid() : "Dirty line has to be VALID.";
						assert l.getEpoch(id).equals(getCurrentEpoch());
						if (!dirtyL1Lines.contains(l.lineAddress().get())) {
							L3cache.updateWriteInfoInLLC(this, l, ExecutionPhase.PRE_COMMIT_L2);
						}
					}
				}
			}
		}
	}

	/**
	 * Check read/write-write conflicts for written lines and compute costs
	 */
	private boolean preCommitWriteBackDirtyLines(final Processor<Line> proc) {
		final HashSet<Long> dirtyL1Lines = new HashSet<Long>();

		// We can assume parallelization while sending messages, and hence account for
		// the slowest message in a batch
		// for estimating the cycle cost. But that is not the default for tracking
		// execution cost.
		double bandwidthBasedCost = 0;
		// We consider these to be streaming operations. So that is why, we add up all
		// the bytes and compute
		// network traffic in terms of flits.

		boolean written = false; // Track whether there is at least one written line
		// phase = ExecutionPhase.PRE_COMMIT_L1;
		loops: for (Deque<Line> set : L1cache.sets) {
			for (Line l : set) {
				if (l.hasWrittenOffsets(id)) {
					if (!written) {
						written = true;
					}

					assert l.valid() : "Written line has to be VALID";
					assert l.getEpoch(id).equals(getCurrentEpoch());

					dirtyL1Lines.add(l.lineAddress().get()); // We want to skip L2 lines

					// Compute size of a message to LLC. We do not need to send
					// the version, we can just increment it
					int sizeInBytes = MemorySystemConstants.TAG_BYTES
							+ MemorySystemConstants.VISER_WRITE_METADATA_BYTES;

					// send values after read validation
					if (!params.deferWriteBacks()) {
						// size of values sent
						sizeInBytes += Long.bitCount(l.getWriteEncoding(id));
					}

					boolean hit = L3cache.checkConflictsForWrittenLines(proc, l, ExecutionPhase.PRE_COMMIT_L1);

					// Count execution cycles but taking into account bandwidth
					bandwidthBasedCost += (sizeInBytes * MemorySystemConstants.LLC_MULTIPLIER);
					if (!hit) {
						bandwidthBasedCost += (sizeInBytes * MemorySystemConstants.MEM_MULTIPLIER);
					}
					if (proc.reRunEvent || proc.restartRegion) {
						// should update traffic counters before return;
						// return written;
						break loops;
					}
				}
			}
		}

		int bwCost = (int) Math.ceil(bandwidthBasedCost);
		stats.pc_BandwidthDrivenCycleCount.incr(bwCost);
		updatePhaseBWDrivenCycleCost(ExecutionPhase.PRE_COMMIT, bwCost);
		bandwidthBasedCost = 0;

		if (!(proc.reRunEvent || proc.restartRegion) && params.useL2()) {
			// phase = ExecutionPhase.PRE_COMMIT_L2;
			// Visit L2-only dirty lines, and skip L1 dirty lines.
			loops: for (Deque<Line> set : L2cache.sets) {
				for (Line l : set) {
					if (l.hasWrittenOffsets(id)) {
						assert l.valid() : "Dirty line has to be VALID.";
						assert l.getEpoch(id).equals(getCurrentEpoch());

						if (!dirtyL1Lines.contains(l.lineAddress().get())) {
							if (!written) {
								written = true;
							}

							// Compute size of a message to LLC. We do not need to send the version, we can
							// just
							// increment it
							int sizeBytes = MemorySystemConstants.TAG_BYTES
									+ MemorySystemConstants.VISER_WRITE_METADATA_BYTES;
							if (!params.deferWriteBacks()) {
								sizeBytes += Long.bitCount(l.getWriteEncoding(id));
							}

							boolean hit = L3cache.checkConflictsForWrittenLines(proc, l, ExecutionPhase.PRE_COMMIT_L2);

							// Count execution cycles but taking into account bandwidth
							bandwidthBasedCost += (sizeBytes * MemorySystemConstants.LLC_MULTIPLIER);
							if (!hit) {
								bandwidthBasedCost += (sizeBytes * MemorySystemConstants.MEM_MULTIPLIER);
							}
							if (proc.reRunEvent || proc.restartRegion) {
								// should update traffic counters before return;
								// return written;
								break loops;
							}
						}
					}
				}
			}

			bwCost = (int) Math.ceil(bandwidthBasedCost);
			stats.pc_BandwidthDrivenCycleCount.incr(bwCost);
			updatePhaseBWDrivenCycleCost(ExecutionPhase.PRE_COMMIT, bwCost);
		}

		return written;
	}

	static class ReadValidationResponse {
		boolean versionsMatch;
		int numValidatedLines;
		boolean overlap;
		// boolean wrConlictbyEvictFound = false;

		public ReadValidationResponse() {
			versionsMatch = true;
			numValidatedLines = 0;
			overlap = false;
		}
	};

	// Return true is a write bit is set in an ongoing region by some other core
	boolean checkIfWriteBitIsSet(Line sharedLine) {
		for (Processor<Line> p : allProcessors) {
			if (p.id.equals(this.id)) {
				continue;
			}
			long writeMd = sharedLine.getWriteEncoding(p.id);
			if (Long.bitCount(writeMd) > 0) {
				return true;
			}
		}
		return false;
	}

	private ReadValidationResponse rvValidationMergedHelper(boolean retryAttempt) {
		ReadValidationResponse rvResp;
		if (params.useL2()) { // Do validation on the L2 lines updated with L1 line's read values
			if (!retryAttempt) { // Merge the read bits only on the first attempt
				rvMergeReadInformationFromL1ToL2();
			}
			rvResp = rvPrivateCacheMergedHelper(L2cache, retryAttempt);
			if (reRunEvent || restartRegion)
				return rvResp;
		} else {
			rvResp = rvPrivateCacheMergedHelper(L1cache, retryAttempt);
			if (reRunEvent || restartRegion)
				return rvResp;
		}
		return rvResp;
	}

	private ReadValidationResponse rvPrivateCacheMergedHelper(HierarchicalCache<Line> cache, boolean retryAttempt) {
		ReadValidationResponse vvResp = new ReadValidationResponse();

		// We are assuming a parallelism in sending requests to the LLC
		final int BATCH_SIZE = 4;

		int l2LineCounter = 0;
		int numLinesValidated = 0;
		int maxBatchExecLatency = 0; // number of cycles based on pre-defined constants
		double memBatchSizeBytes = 0; // number of bytes that need to go to memory in a batch
		CacheLevel whereHitInBatch = CacheLevel.L3; // number of cycles based on bandwidth data

		boolean rdValAndWriteSignatureOverlap = false;

		loops: for (Deque<Line> set : cache.sets) {
			for (Line l : set) {
				if (!l.valid() || !l.hasReadOffsets(id)) {
					continue;
				}
				// Line is valid and has some reads

				// A read line needs to be validated only if the Bloom filter tells the core
				// that the line has been
				// updated during the region.
				if (params.skipValidatingReadLines() && params.useBloomFilter()) {
					if (!bf.contains(l.lineAddress().get())) {
						continue;
					}
				}

				// There is a read line that is updated by a remote core, so read validation
				// needs to repeat
				// at least one more time after fetching the write signature, according to the
				// do-while-retry
				// algorithm. We do not implement that algorithm faithfully since the simulator
				// is single-threaded.
				rdValAndWriteSignatureOverlap = true;

				int l2Version = l.getVersion();

				// Get the corresponding line from memory or LLC
				MemoryResponse<Line> resp = getLineFromLLCOrMemory(l);
				Line sharedLine = resp.lineHit;
				assert sharedLine != null && sharedLine.getLevel() == CacheLevel.L3;

				int sharedVersion = sharedLine.getVersion();

				if (resp.whereHit == CacheLevel.L3) {
					maxBatchExecLatency = Math.max(maxBatchExecLatency, MemorySystemConstants.L3_ACCESS);
				} else {
					assert resp.whereHit == CacheLevel.MEMORY;
					maxBatchExecLatency = Math.max(maxBatchExecLatency, MemorySystemConstants.MEMORY_ACCESS);
					whereHitInBatch = CacheLevel.MEMORY;

					// LLC will forward the message to memory
					int sizeMemBytes = MemorySystemConstants.DATA_MESSAGE_CONTROL_BYTES
							+ MemorySystemConstants.TAG_BYTES + MemorySystemConstants.VISER_VERSION_BYTES;

					memBatchSizeBytes += sizeMemBytes;
				}

				// In case of a version match, the LLC responds with a NACK if a write bit is
				// set in the AIM cache,
				// indicating a potential write-read conflict. Otherwise, RCC could miss
				// serializability violations.
				// Validation is not retried in case a conflict is observed since the versions
				// already match.
				if (l2Version == sharedVersion && !retryAttempt) {
					if (checkIfWriteBitIsSet(sharedLine)) {
						stats.pc_potentialWrRdValConflicts.incr();

						// Check for a precise write-read conflict
						checkPreciseWriteReadConflicts(sharedLine, l, ExecutionPhase.READ_VALIDATION);
						if (restartRegion || reRunEvent)
							return vvResp;

						// We do not account for the performance, since we expect this case to be rare
						// and to be on the
						// slow path
					}
				}

				short[] countExtraCosts = new short[1];
				boolean preciseConflictFound = false;
				if (l2Version != sharedVersion) {
					assert l2Version < sharedVersion : "Local version should be smaller it seems.";

					// Since there's no early writing back during pre-commit, version mismatch
					// definitely
					// indicates the existence of remote writers.
					if (l.hasWrittenOffsets(id)) {
						// The current core is the last writer, but there are remote writers as well.
						// Still need to setConcurrentRemoteWrite for self-invalidation during
						// post-commit.
						l.setConcurrentRemoteWrite();
					}

					l.setVersion(sharedVersion);

					vvResp.versionsMatch = false;

					if (!params.ignoreFetchingDeferredLinesDuringReadValidation()) {
						// Before value validation, make sure that the LLC has the up-to-date values
						// This might be high if there are lots of true conflicts or false positives
						if (params.deferWriteBacks() && sharedLine.isLineDeferred()
								&& sharedLine.getDeferredLineOwnerID() != id.get()) {
							fetchDeferredLineFromPrivateCache(sharedLine, true, false);
						}
					}

					preciseConflictFound = valueValidateReadLine(ExecutionPhase.READ_VALIDATION, l, sharedLine,
							countExtraCosts);
				}

				l2LineCounter++;
				numLinesValidated++;

				// Execution cycles should be over one batch of concurrent messages since the
				// latency
				// depends on where the shared line hits (LLC or memory).
				if (l2LineCounter % BATCH_SIZE == 0) {
					int batchSizeBytes = MemorySystemConstants.VISER_RV_MESSAGE_SIZE_BYTES;

					// Count execution cycles but taking into account bandwidth
					long bandwidthBasedLatency = (long) Math
							.ceil(batchSizeBytes * MemorySystemConstants.LLC_MULTIPLIER);
					if (whereHitInBatch == CacheLevel.MEMORY) {
						// assert memBatchSizeBytes < batchSizeBytes;
						bandwidthBasedLatency += (long) Math
								.ceil(memBatchSizeBytes * MemorySystemConstants.MEM_MULTIPLIER);
					}
					stats.pc_BandwidthDrivenCycleCount.incr(bandwidthBasedLatency);
					updatePhaseBWDrivenCycleCost(ExecutionPhase.READ_VALIDATION, bandwidthBasedLatency);

					maxBatchExecLatency = 0;
					memBatchSizeBytes = 0;
					l2LineCounter = 0;
					whereHitInBatch = CacheLevel.L3;

					if (!preciseConflictFound) {
						if (countExtraCosts[0] == 1) {
							stats.pc_DefiniteExtraCyclesByLibConflicts.incr(bandwidthBasedLatency);
						} else if (countExtraCosts[0] == 2) {
							stats.pc_PossibleExtraCyclesByLibConflicts.incr(bandwidthBasedLatency);
						}
					}
				}
				if (preciseConflictFound) {
					// update counters
					stats.pc_FailedValidations.incr();
					if (!regionWithExceptions) {
						stats.pc_RegionsWithFRVs.incr();
						// After pre-commit
						stats.pc_RegionsWithFRVsAfterPrecommit.incr();
						if (!regionHasDirtyEvictionBeforeFRV && hasDirtyEviction) {
							stats.pc_RegionHasDirtyEvictionBeforeFRV.incr();
							regionHasDirtyEvictionBeforeFRV = true;
						}

						if (params.restartAtFailedValidationsOrDeadlocks() && !hasDirtyEviction) {
							// Read validation failed, restart.
							stats.pc_TotalRegionRestarts.incr();
							assert !restartRegion;
							restartRegion = true;
							// Track L2 lines that were invalidated, so that we can also invalidate
							// those lines in the L1 cache to guarantee private cache's being inclusive.
							prepareRestart();
							break loops;
						} else {
							stats.pc_ExceptionsByFRVs.incr();
							stats.pc_RegionsWithExceptionsByFRVs.incr();

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
		}

		// Left-over accounting
		if (l2LineCounter > 0) {
			assert l2LineCounter < BATCH_SIZE;

			// Count the cost of sending a batched message
			int batchSizeBytes = MemorySystemConstants.VISER_RV_MESSAGE_SIZE_BYTES;

			// Count execution cycles but taking into account bandwidth
			long bandwidthBasedLatency = (long) Math.ceil(batchSizeBytes * MemorySystemConstants.LLC_MULTIPLIER);
			if (whereHitInBatch == CacheLevel.MEMORY) {
				assert memBatchSizeBytes < batchSizeBytes;
				bandwidthBasedLatency += (long) Math.ceil(memBatchSizeBytes * MemorySystemConstants.MEM_MULTIPLIER);
			}
			stats.pc_BandwidthDrivenCycleCount.incr(bandwidthBasedLatency);
			updatePhaseBWDrivenCycleCost(ExecutionPhase.READ_VALIDATION, bandwidthBasedLatency);
		}

		// Count the cost of sending all messages
		vvResp.numValidatedLines = numLinesValidated;
		vvResp.overlap = rdValAndWriteSignatureOverlap;
		return vvResp;
	}

	// invalidate read lines before restarting
	private void performInvalidation(CacheLevel level, HashSet<Line> skippedL2Lines, Epoch nextEp) {
		HierarchicalCache<Line> cache = (level == CacheLevel.L1) ? L1cache : L2cache;
		for (Deque<Line> set : cache.sets) {
			for (Line l : set) {
				if (!l.valid()) {
					continue;
				}
				assert l.id() == id : "Private lines should be owned by the same core";
				if (params.useL2() && level == CacheLevel.L1) {
					boolean found = false;
					Iterator<Line> it = skippedL2Lines.iterator();
					while (it.hasNext()) {
						Line skip = it.next();
						if (skip.lineAddress().get() == l.lineAddress().get()) {
							found = true;
							it.remove();
							break;
						}
					}

					if (found) {
						// invalidate the private L1 line
						l.invalidate();
						continue;
					}
				}
				if (l.hasReadOffsets(id) | l.hasWrittenOffsets(id)) {
					MemoryResponse<Line> resp = getLineFromLLCOrMemory(l);
					Line sharedLine = resp.lineHit;
					assert sharedLine != null && resp.whereHit == CacheLevel.L3;
					if (params.deferWriteBacks() && sharedLine.isLineDeferred()
							&& sharedLine.getDeferredLineOwnerID() == id.get()) {
						// check OwnerID in the LLC is an equivalent of check deferred-write bits in L2
						// (or
						// L1/l2 if BackupDeferredWritebacksLasily.
						// should write back deferred values because they were written in previous
						// regions.
						fetchDeferredLineFromPrivateCache(sharedLine, false, false);
					}

					l.invalidate();
					if (level == CacheLevel.L2) {
						skippedL2Lines.add(l);
					} else if (params.useL2()) { // invalidate the corresponding L2 line
						MemoryResponse<Line> l2resp = L2cache.searchPrivateCache(l);
						assert l2resp.lineHit != null;
						Line l2Line = l2resp.lineHit;
						l2Line.invalidate();
					}
				} else {
					// clear R/W metadat in the private cache and update epoch
					/*
					 * l.clearReadEncoding(id); if (l.hasWrittenOffsets(id)) // l is not a deferred
					 * line, reset its dirty bit l.resetDirty(); l.clearWriteEncoding(id);
					 */
					l.setEpoch(id, nextEp);
				}
			}
		}
	}

	public void prepareRestart() {
		/* System.out.println("Core " + id + " is preparing restart..."); */
		Epoch currentEp = getCurrentEpoch();
		Epoch nextEp = new Epoch(currentEp.getRegionId() + 1);

		// invalidate touched lines
		HashSet<Line> skippedL2Lines = new HashSet<Line>();
		if (params.useL2())
			performInvalidation(CacheLevel.L2, skippedL2Lines, nextEp);
		performInvalidation(CacheLevel.L1, skippedL2Lines, nextEp);

		// increase epoch to clear read/write metadata in the LLC
		machine.incrementEpoch(id);

		// rerun those cores which have been waiting for the core.
		reRunPausedCores();

		stats.pc_RegionBoundaries.incr();
		resetFlagsForRegionCounters();
	}

	/**
	 * Update each L2 line with information (read/write bits and values) from L1
	 * cache
	 */
	private void rvMergeReadInformationFromL1ToL2() {
		assert params.useL2();

		for (Deque<Line> set : L2cache.sets) {
			for (Line l2Line : set) {
				if (!l2Line.valid()) {
					continue;
				}
				Line l1Line = L1cache.getLine(l2Line);
				if (l1Line == null) {
					continue;
				}
				if (l1Line.hasReadOffsets(id)) {
					// To comply with value updates, only update read-only bits from l1line
					l2Line.orReadEncoding(id, l1Line.getReadEncoding(id) & ~l1Line.getWriteEncoding(id));
					l2Line.updateReadSiteInfo(id, l1Line.getReadEncoding(id) & ~l1Line.getWriteEncoding(id),
							l1Line.getReadSiteInfo(id), l1Line.getReadLastSiteInfo(id));
				}
				l2Line.copyReadOnlyValuesFromSource(l1Line);
				// L1Line's write bits are still needed although we don't increase versions for
				// shared lines during pre-commit, because we need to know if there are
				// concurrent writers.
				// not set dirty bit because we don't actually write back dirty values from L1
				// here.
				l2Line.orWriteEncoding(id, l1Line.getWriteEncoding(id));
			}
		}
	}

	// This is supposed to be asynchronous, so we do not account for it.
	// We do not increment the conflict stat immediately since read validation only
	// counts
	// one failed read validation.
	boolean valueValidateReadLine(ExecutionPhase phase, Line privLine, Line sharedLine, short[] countExtraCost) {
		assert sharedLine != null;
		stats.pc_ValidationAttempts.incr();
		countExtraCost[0] = 0;
		boolean valueMismatch = false;
		boolean preciseConflict = false;
		if (privLine.valid() && privLine.hasReadOffsets(id)) { // Line has some reads
			int[] privSiIndex = privLine.getReadSiteInfo(id);
			int[] privLastSiIndex = privLine.getReadLastSiteInfo(id);
			for (int offset = 0; offset < MemorySystemConstants.LINE_SIZE(); offset++) {
				long enc = getEncodingForOffset(offset);
				long privValue = privLine.getValue(offset);
				long sharedValue = sharedLine.getValue(offset);
				if (privValue != sharedValue) {
					valueMismatch = true;
					if (privLine.isOffsetRead(id, enc)) {
						// The current core has read from this byte offset, match values
						if (!params.isHttpd()
								&& (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
								|| ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI) {
							if (params.siteTracking()) {
								SiteInfoEntry privSi = machine.siteInfo.get(privSiIndex[offset]);
								SiteInfoEntry privLastSi = machine.siteInfo.get(privLastSiIndex[offset]);
								int privLno = privSi.lineNo;
								int privFno = privSi.fileIndexNo;
								int privRno = privSi.routineIndexNo;
								short lw = sharedLine.getLastWriter(offset);
								if (lw != -1) {
									CpuId lastWriter = new CpuId(lw);
									int sharedSiIndex = sharedLine.getWriteSiteInfo(lastWriter)[offset];
									SiteInfoEntry sharedSi = machine.siteInfo.get(sharedSiIndex);
									int sharedLastSiIndex = sharedLine.getWriteLastSiteInfo(lastWriter)[offset];
									SiteInfoEntry sharedLastSi = machine.siteInfo.get(sharedLastSiIndex);
									int sharedLno = sharedSi.lineNo;
									int sharedFno = sharedSi.fileIndexNo;
									int sharedRno = sharedSi.routineIndexNo;
									if (lw == id.get()) {
										System.out.println(
												"False race is detected between a core and itself! (Configs: pausing? "
														+ params.pauseCoresAtConflicts() + ", restart? "
														+ params.restartAtFailedValidationsOrDeadlocks() + ", opt? "
														+ params.evictCleanLineFirst() + ", false restart? "
														+ params.FalseRestart() + ".)");
										System.out.println("During " + phase + ", read validation failed at "
												+ privLine.addr.get() + "(+" + offset + "):");
										System.out.println("\t" + id + " reads value " + privValue + " at " + privFno
												+ ":" + privLno + ":" + privRno + "(callerSite: "
												+ privLastSi.fileIndexNo + ":" + privLastSi.lineNo + ").");
										System.out.println("\t" + lastWriter + " write value " + sharedValue + " at "
												+ sharedFno + ":" + sharedLno + ":" + sharedRno + "(callerSite: "
												+ sharedLastSi.fileIndexNo + ":" + sharedLastSi.lineNo + ").");
									} else {
										if (privLno != 0 && sharedLno != 0) {
											preciseConflict = true;
											if (params.printConflictingSites()) {
												System.out.println("During " + phase + ", read validation failed at "
														+ privLine.addr.get() + "(+" + offset + "):");
												System.out.println("\t" + id + " reads value " + privValue + " at "
														+ privFno + ":" + privLno + ":" + privRno + "(callerSite: "
														+ privLastSi.fileIndexNo + ":" + privLastSi.lineNo + ").");
												System.out.println("\t" + lastWriter + " write value " + sharedValue
														+ " at " + sharedFno + ":" + sharedLno + ":" + sharedRno
														+ "(callerSite: " + sharedLastSi.fileIndexNo + ":"
														+ sharedLastSi.lineNo + ").");
											}
										} else {
											countExtraCost[0] = 1; // lib races definitely cause a true read validation
																	// failure.
										}
										machine.updateConflictCounters(privFno, privLno, privRno, sharedFno, sharedLno,
												sharedRno, privLastSi.fileIndexNo, privLastSi.lineNo,
												sharedLastSi.fileIndexNo, sharedLastSi.lineNo);
									}
								} else {
									System.out.println(
											"Failed to get last writers during read validation! (Configs: pausing? "
													+ params.pauseCoresAtConflicts() + ", restart? "
													+ params.restartAtFailedValidationsOrDeadlocks() + ", opt? "
													+ params.evictCleanLineFirst() + ", false restart? "
													+ params.FalseRestart() + ".)");
									System.out.println("During " + phase + ", read validation failed at "
											+ privLine.addr.get() + "(+" + offset + "):");

									System.out.println("\t" + id + " reads value " + privValue + " at " + privFno + ":"
											+ privLno + ":" + privRno + ".");
									System.out.println("\t" + "p-1 write value " + sharedValue + ".");
								}
							} else {
								preciseConflict = true;
							}
						}
					}
					// Also need to update values even with the restart config.
					// because some failed read validations can't be tolerated by restart.
					// Update the L2 line, but not any WAR or written bytes because the core has
					// updated values for
					// these bytes in L1 (or L2 if the line's missing in L1)
					if (!privLine.isOffsetWritten(id, enc)) {
						privLine.setValue(offset, sharedValue);
						// L1's write bits have been merged into the L2 line
						Line l1line = L1cache.getLine(privLine);
						if (l1line != null)
							l1line.setValue(offset, sharedValue);
					}
				}
			}

			if (!valueMismatch && !preciseConflict && params.siteTracking()
					&& (!params.isHttpd() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI)) {
				int offset;
				for (offset = 0; offset < MemorySystemConstants.LINE_SIZE(); offset++) {
					SiteInfoEntry privSi = machine.siteInfo.get(privSiIndex[offset]);
					int privLno = privSi.lineNo;
					short lw = sharedLine.getLastWriter(offset);
					if (lw != -1) {
						CpuId lastWriter = new CpuId(lw);
						int sharedSiIndex = sharedLine.getWriteSiteInfo(lastWriter)[offset];
						SiteInfoEntry sharedSi = machine.siteInfo.get(sharedSiIndex);
						int sharedLno = sharedSi.lineNo;
						if (lw != id.get()) {
							if (privLno != 0 && sharedLno != 0) {
								if (countExtraCost[0] == 2) {
									break;
								}

							} else {
								if (countExtraCost[0] == 0 && offset > 0) {
									countExtraCost[0] = 2; // lib races may cause a version mismatch.
									break;
								} else {
									countExtraCost[0] = 2; // lib races may cause a version mismatch.
								}
							}
						}
					}
				}
				if (offset == MemorySystemConstants.LINE_SIZE() && countExtraCost[0] == 2) {
					countExtraCost[0] = 1; // lib races definitely cause a version mismatch.
				}
			}
		}

		if (params.siteTracking()) {
			machine.resetConflictCounter();
			if (preciseConflict && params.printConflictingSites())
				System.out.println(
						"============================= Faild read validation =================================");
		}
		return preciseConflict;
	}

	/**
	 * This is just for lookup. This does not bring in or evict lines, and neither
	 * does it add costs.
	 */
	MemoryResponse<Line> getLineFromLLCOrMemory(Line l) {
		assert l.valid();
		MemoryResponse<Line> resp = new MemoryResponse<Line>();

		// Get the corresponding line from memory or LLC
		Line llcLine = L3cache.getLine(l);
		if (llcLine == null) { // Line not in LLC, get from memory
			llcLine = machine.memory.get(l.lineAddress().get());
			assert !llcLine.isLineDeferred();
			resp.whereHit = CacheLevel.MEMORY;
		} else {
			resp.whereHit = CacheLevel.L3;
		}

		assert llcLine != null && llcLine.valid();
		assert llcLine.id().get() == 0;
		resp.lineHit = llcLine;
		return resp;
	}

	/** Initiated on behalf of the shared cache */
	void fetchDeferredLineFromPrivateCache(Line llcLine, boolean rv, boolean notCountCosts) {
		Processor<Line> ownerCore = machine.getProc(new CpuId(llcLine.getDeferredLineOwnerID()));
		CpuId cid = ownerCore.id;
		Line line;

		if (params.restartAtFailedValidationsOrDeadlocks() || params.FalseRestart()) {
			line = ownerCore.L2cache.getLine(llcLine);
			if (line == null || !line.isDeferredWriteBitSet()) {
				assert params.BackupDeferredWritebacksLasily();
				line = ownerCore.L1cache.getLine(llcLine);
				assert line.isDeferredWriteBitSet() && !line.hasWrittenOffsets(cid);
				line.resetDeferredWriteBit();
				llcLine.copyAllValues(line);
				// For site tracking, last writers have already been set during pre-commit.
				// They cannot be set here because the corresponding write metadata has been
				// cleared
				// in the last region.
				// llcLine.setLastWritersFromPrivateLine(line);
			} else { // the L2 line is the deferred line
				assert line.isDeferredWriteBitSet() && !line.hasWrittenOffsets(cid);
				line.resetDeferredWriteBit();
				llcLine.copyAllValues(line);

				if (params.BackupDeferredWritebacksLasily()) {
					// also clear dirty bit for the corresponding L1 line
					line = ownerCore.L1cache.getLine(llcLine);
					if (line != null && line.valid() && !line.hasWrittenOffsets(cid))
						line.resetDeferredWriteBit();
				}
			}
		} else {
			// search L1/L2
			MemoryResponse<Line> privateResp = ownerCore.L1cache.searchPrivateCache(llcLine);
			assert privateResp.lineHit != null;
			line = privateResp.lineHit;

			// Update read bits
			llcLine.orReadEncoding(cid, line.getReadEncoding(cid));
			llcLine.updateReadSiteInfo(cid, line.getReadEncoding(cid), line.getReadSiteInfo(cid),
					line.getReadLastSiteInfo(cid));
			// Update write bits
			llcLine.orWriteEncoding(cid, line.getWriteEncoding(cid));
			llcLine.updateWriteSiteInfo(cid, line.getWriteEncoding(cid), line.getWriteSiteInfo(cid),
					line.getWriteLastSiteInfo(cid));
			if (line.hasWrittenOffsets(cid)) {
				llcLine.incrementVersion();
				llcLine.setDirty(true);
				if (params.useBloomFilter()) {
					updatePerCoreBloomFilters(line);
				}
				line.setVersion(llcLine.getVersion());
			}
			llcLine.setEpoch(cid, ownerCore.getCurrentEpoch());

			// deal with values
			/*
			 * We can safely write back the whole line as long as we also send the line's
			 * write bits to the LLC so that the LLC can check write--read conflicts
			 * (RegularTests.testOptimizations8).
			 */
			llcLine.copyAllValues(line);
			llcLine.setLastWritersFromPrivateLine(line);

			// If both read and write offsets are set in the shared line, then no need to
			// maintain read encoding.
			// Reset read encoding in the LLC line directly.
			long readEnc = llcLine.getReadEncoding(cid);
			if (llcLine.isWrittenAfterRead(cid)) {
				readEnc &= (~llcLine.getWriteEncoding(cid));
			}
			llcLine.clearReadEncoding(cid);
			llcLine.orReadEncoding(cid, readEnc);
			// no need to update read site info

			// If's not safe to update the AIM cache since we're not sure if llcLine is in
			// the LLC or not (might be in
			// the memory)
			// So we need to check first before adding a line into the AIM cache.
			if (params.useAIMCache()) {
				if (line.hasReadOffsets(cid) || line.hasWrittenOffsets(cid)) {
					if (L3cache.getLine(line) != null) { // The line hits in the LLC
						// The line might be in the AIM cache
						aimcache.addLineIfNotPresent(this, line);
					}
				}
			}

			// Clear write/read bits of the private line to avoid false races
			line.clearReadEncoding(cid);
			line.clearWriteEncoding(cid);

			if (line.getLevel() == CacheLevel.L1) {
				Line l2Line = ownerCore.L2cache.getLine(line);
				l2Line.setVersion(line.getVersion());
				l2Line.clearReadEncoding(cid);
				l2Line.clearWriteEncoding(cid);
			}
		}

		llcLine.clearDeferredLineOwnerID();
		// The line is no longer deferred, hence remove the entry from the set of
		// deferred lines
		Long writeMd = null;
		if (params.areDeferredWriteBacksPrecise()) {
			writeMd = ownerCore.getDeferredWriteMetadata(line);
			assert writeMd != null : line.toString();
			assert Long.bitCount(writeMd) > 0 : "Otherwise it should not have been deferred";
			ownerCore.removeDeferredWriteLine(line);
		}

		if (!notCountCosts) {
			// Cost is LLC latency
			stats.pc_BandwidthDrivenCycleCount.incr(MemorySystemConstants.L3_HIT_LATENCY);
			updatePhaseBWDrivenCycleCost(ExecutionPhase.REGION_BODY, MemorySystemConstants.L3_HIT_LATENCY);
		}
	}

	boolean matchVersions(Line privLine, Line sharedLine) {
		if (privLine.getVersion() == sharedLine.getVersion()) {
			return true;
		}
		assert privLine.getVersion() < sharedLine.getVersion();
		return false;
	}

	// Iterate over each private line that has a read-only byte offset (not also
	// written)
	// On first iteration, check for versions.
	// If failed, check for values, and update versions.
	// Then recheck for versions.
	// Keep retrying, till either a failure or a success.
	// Either we detect a conflict and terminate, or we keep retrying in the
	// hope of reading a consistent snapshot
	private void performReadValidation() {
		boolean retryAttempt = false;
		while (true) {
			ReadValidationResponse ret = rvValidationMergedHelper(retryAttempt);
			if (reRunEvent || restartRegion) {
				return;
			}
			if (ret.versionsMatch) { // All private cache line versions matched.
				// Update stats
				updateNumValidatedLinesHistogram(ret.numValidatedLines);
				break;
			}
			retryAttempt = true;
		}
	}

	private void updateNumValidatedLinesHistogram(int count) {
		int key;
		if (count == 0) {
			key = 0;
		} else if (count > 0 && count <= 10) {
			key = 1;
		} else if (count > 10 && count <= 20) {
			key = 2;
		} else if (count > 20 && count <= 30) {
			key = 3;
		} else if (count > 30 && count <= 40) {
			key = 4;
		} else {
			key = 5;
		}
		Integer val = stats.hgramLinesValidated.get(key);
		if (val == null) {
			val = Integer.valueOf(1);
		} else {
			val++;
		}
		stats.hgramLinesValidated.put(key, val);
	}

	/*
	 * public boolean processRegionBegin(ThreadId tid, EventType type) { phase =
	 * ExecutionPhase.REGION_BODY; return true; }
	 */

	/**
	 * Currently we assume one thread to core mapping, otherwise it is more
	 * complicated.
	 */
	public boolean processRegionEnd(ThreadId tid, EventType type) {
		// Update LLC with precise write information and values from the private caches,
		// L1 and L2
		// phase = ExecutionPhase.PRE_COMMIT;
		boolean written = preCommitWriteBackDirtyLines(this);
		if (reRunEvent || restartRegion) {
			return false;
		}

		// Validate reads in the private caches, by first validating versions and then
		// validating values. Repeat until a consistent snapshot is validated.
		// phase = ExecutionPhase.READ_VALIDATION;
		performReadValidation();
		if (reRunEvent || restartRegion) {
			// System.out.println("Error: can't be here if we don't pause at
			// untolerable wr conflicts during read validations");
			return false;
		}

		// Only increase the counter for a successful region
		if (written) {
			stats.pc_RegionsWithWrites.incr();
		}

		// Tell the LLC to clear all write and read info for this core. This can be
		// better handled with epochs.
		// postCommitClearPerCoreMetadataInLLC();

		// Add network traffic for the Bloom filter sent by the LLC. This is
		// needed during read validation.
		if (params.useBloomFilter()) {
			// Update histogram
			updateBloomFilterHistogram();
		}

		// Send dirty values and clear W/R bits for private caches, L1 and L2
		// phase = ExecutionPhase.POST_COMMIT;
		postCommitSelfInvalidateHelper(type, false);

		if (params.pauseCoresAtConflicts())
			reRunPausedCores();

		// Tell the LLC to clear all write and read info for this core. This can
		// be better handled with epochs.
		machine.incrementEpoch(id);

		if (ViserSim.xassertsEnabled() && params.alwaysInvalidateReadOnlyLines()
				&& !params.invalidateWrittenLinesOnlyAfterVersionCheck()) {
			Verify.verifyPrivateCacheLinesAreInvalid(this);
		}

		// Clear the bloom filter
		if (params.useBloomFilter()) {
			// Cannot clear the Bloom filter at acquires if we are using RFRs and we plan to
			// skip validation
			set.clear();
			bf.clear();
		}

		// Clear stale lines from the AIM
		if (params.useAIMCache() && params.clearAIMCacheAtRegionBoundaries()) {
			aimcache.clearAIMCache2(this);
		}

		// NOTE: The following checks are expensive

		// Assert that all metadata related to this core is zero
		if (ViserSim.assertsEnabled || ViserSim.xassertsEnabled()) {
			Verify.verifyPrivateMetadataCleared(this);
			Verify.verifySharedMetadataCleared(this);
		}

		if (ViserSim.assertsEnabled && params.deferWriteBacks()) {
			Verify.verifyDeferredLines(this);
			if (params.areDeferredWriteBacksPrecise()) {
				Verify.verifyPrivateDeferredLines(this);
			}
		}

		// Current region finishes, reset counter flags
		resetFlagsForRegionCounters();

		return true;
	}

	private void resetFlagsForRegionCounters() {
		regionConflicted = false;
		regionWithExceptions = false;
		hasDirtyEviction = false;
		regionHasDirtyEvictionBeforeFRV = false;
		regionHasDirtyEvictionBeforeDL = false;
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
						System.out
								.println("paused_cycles < 0: " + end_time + " " + proc.start_time + " " + id + " " + i);
						System.exit(-177);
					} else {
						proc.stats.pc_BandwidthDrivenCycleCount.incr(paused_cycles);
						proc.updatePhaseBWDrivenCycleCost(ExecutionPhase.PAUSE, paused_cycles);
					}
				}
				t *= 2;
			}
			pausedCores = 0L;
		}

	}

	private void updateBloomFilterHistogram() {
		int count = set.size();
		int key;
		if (count == 0) {
			key = 0;
		} else if (count > 0 && count <= 10) {
			key = 1;
		} else if (count > 10 && count <= 20) {
			key = 2;
		} else if (count > 20 && count <= 30) {
			key = 3;
		} else if (count > 30 && count <= 40) {
			key = 4;
		} else {
			key = 5;
		}
		Integer val = stats.hgramLLCUpdatesInARegion.get(key);
		if (val == null) {
			val = Integer.valueOf(1);
		} else {
			val++;
		}
		stats.hgramLLCUpdatesInARegion.put(key, val);
	}

	public void updateVersionSizeHistogram(int version) {
		int key = 0;
		if (version < MemorySystemConstants.MAX_8_BIT_VERSION) {
			key = 0;
		} else if (version >= MemorySystemConstants.MAX_8_BIT_VERSION
				&& version < MemorySystemConstants.MAX_16_BIT_VERSION) {
			key = 1;
		} else if (version >= MemorySystemConstants.MAX_16_BIT_VERSION
				&& version < MemorySystemConstants.MAX_24_BIT_VERSION) {
			key = 2;
		} else if (version >= MemorySystemConstants.MAX_24_BIT_VERSION
				&& version < MemorySystemConstants.MAX_32_BIT_VERSION) {
			key = 3;
		}
		Integer val = stats.hgramVersionSizes.get(key);
		if (val == null) {
			val = Integer.valueOf(1);
		} else {
			val++;
		}
		stats.hgramVersionSizes.put(key, val);
	}

	// We do not add performance cost since this is not on the critical path, DRFx
	// 2011 adds two cycles
	void updatePerCoreBloomFilters(Line line) {
		long lineAddr = line.lineAddress().get();
		for (int i = 0; i < params.numProcessors(); i++) {
			CpuId cpuId = new CpuId(i);
			if (cpuId.equals(id)) { // Avoid polluting the filter of the initiator core
				continue;
			}
			Processor<Line> p = machine.getProc(cpuId);
			p.bf.add(lineAddr);
			p.set.add(lineAddr);
		}
	}

	// The core just issues on command to the cache controller to flash clear
	// lines, so this operation is for free.

	// There could be L1 lines that have been read, but the fact is not transmitted
	// to L2 lines because of
	// no L1 eviction. This implies that the L1 line is read only, but the L2 line
	// is not (read and write encoding
	// are both zero for the L2 line). For such cases, we need to skip invalidating
	// the L2 line as well. In general,
	// we want to avoid invalidating those L2 lines for which the corresponding L1
	// lines were not invalidated.
	public void postCommitSelfInvalidateHelper(EventType type, boolean onlyInvalidateUntouchedLines) {
		// Track L1 lines that were skipped, so that we can skip invalidating
		// those lines in the L2 cache.
		if (!onlyInvalidateUntouchedLines) {
			sendDirtyValuesToLLC();
		} else {
			// Add network traffic for the Bloom filter sent by the LLC. This is
			// needed when invalidating untouched lines.
			if (params.useBloomFilter()) {
				// Update histogram
				updateBloomFilterHistogram();
			}
		}
		HashSet<Line> skippedL1Lines = new HashSet<Line>();
		postCommitSelfInvalidateSFRs(type, CacheLevel.L1, skippedL1Lines, onlyInvalidateUntouchedLines);
		if (params.useL2()) {
			postCommitSelfInvalidateSFRs(type, CacheLevel.L2, skippedL1Lines, onlyInvalidateUntouchedLines);
		}
	}

	private void postCommitSelfInvalidateSFRs(EventType type, CacheLevel level, HashSet<Line> skippedL1Lines,
			boolean onlyInvalidateUntouchedLines) {
		Epoch currentEp = getCurrentEpoch();
		Epoch nextEp;
		if (!onlyInvalidateUntouchedLines) {
			nextEp = new Epoch(currentEp.getRegionId() + 1);
		} else {
			nextEp = currentEp;
		}

		// We can assume parallelization while sending messages, and hence account for
		// the slowest message in a batch
		// for estimating the cycle cost. But that is not the default for tracking
		// execution cost.
		double bandwidthBasedCost = 0;
		// We consider writing back WAR-upgraded dirty lines to be streaming operations.
		// So that is why, we add up all
		// the bytes and compute network traffic in terms of flits.

		HierarchicalCache<Line> cache = (level == CacheLevel.L1) ? L1cache : L2cache;
		for (Deque<Line> set : cache.sets) {
			for (Line l : set) {
				if (!l.valid()) {
					continue;
				}

				if (onlyInvalidateUntouchedLines && l.isAccessedInThisRegion(id)) {
					// We don't expect a touched line here.
					System.out.println("Unexpected touched line within a lock acquire: " + l);
					if (params.useSpecialInvalidState()) {
						l.clearReadEncoding(id);
						l.clearWriteEncoding(id);
						l.setEpoch(id, nextEp);
						l.clearConcurrentRemoteWrite();
						l.changeStateTo(ViserState.VISER_INVALID_TENTATIVE);
					} else {
						l.invalidate();
					}
					continue;
				}

				l.l2WriteBit = false;

				assert l.id() == id : "Private lines should be owned by the same core";

				if (level == CacheLevel.L2) {
					boolean found = false;
					Line l1Line = null;
					Iterator<Line> it = skippedL1Lines.iterator();
					// Speed up searching by removing found lines
					while (it.hasNext()) {
						Line skip = it.next();
						if (skip.lineAddress().get() == l.lineAddress().get()) {
							// So the L1 line corresponding to this L2 line was
							// not invalidated
							found = true;
							l1Line = skip;
							it.remove();
							break;
						}
					}

					if (found) {
						// Clear metadata from private L2 line
						l.clearReadEncoding(id);
						l.clearWriteEncoding(id);
						l.setVersion(l1Line.getVersion()); // Needed if the L1 line was dirty
						l.setEpoch(id, nextEp);
						l.clearConcurrentRemoteWrite();

						continue;
					}
				}

				if (l.isLineReadOnly(id)) {
					assert l.getEpoch(id).equals(currentEp);

					// Optimization: After a successful read validation, we know that read-only
					// lines have valid
					// values before the start of the next region, so we can avoid invalidating the
					// line. This should
					// allow more hits in the private caches.
					if (params.alwaysInvalidateReadOnlyLines()) {
						l.invalidate();
					} else {
						// Need to clear read and write metadata if we are not going to invalidate the
						// line
						l.clearReadEncoding(id);
						l.clearWriteEncoding(id);
						l.setEpoch(id, nextEp);
						l.clearConcurrentRemoteWrite();
						if (params.useL2() && level == CacheLevel.L1) {
							skippedL1Lines.add(l);
						}
					}

				} else if (l.hasWrittenOffsets(id)) { // Written line
					assert l.getEpoch(id).equals(currentEp);

					MemoryResponse<Line> resp = getLineFromLLCOrMemory(l);
					Line sharedLine = resp.lineHit;
					assert sharedLine != null;
					int sharedVer = sharedLine.getVersion();
					boolean llcHit = (resp.whereHit == CacheLevel.L3) ? true : false;

					// Need to *model* the write back the dirty bytes for WAR-upgraded lines
					if (l.isWrittenAfterRead(id)) {
						// Compute size of a message to LLC. We do not need to send the version, we can
						// just increment
						// it
						long writeEnc = l.getWriteEncoding(id);
						int sizeInBytes = MemorySystemConstants.TAG_BYTES;
						if (!params.deferWriteBacks()) {
							sizeInBytes += Long.bitCount(writeEnc);
						}

						// Count execution cycles but taking into account bandwidth
						bandwidthBasedCost += (sizeInBytes * MemorySystemConstants.LLC_MULTIPLIER);
						if (!llcHit) {
							bandwidthBasedCost += (sizeInBytes * MemorySystemConstants.MEM_MULTIPLIER);
						}
					}

					if (params.invalidateWrittenLinesOnlyAfterVersionCheck()) {
						long myVer = l.getVersion();
						assert myVer < sharedVer;
						// The line may have been read and written. In that
						// case, read validation updates the version number in the private cache,
						// but then writing back increases the version number in the LLC.
						// So sharedVer should always be larger than myVer.
						// TODO: opt opportunities: all the offsets of the line have been touched
						if (myVer == sharedVer - 1
								// myVer values may have been updated during read validation
								&& !l.isThereAConcurrentRemoteWrite()) {
							// Need not invalidate, since there has been no concurrent write
							l.clearReadEncoding(id);
							l.clearWriteEncoding(id);
							l.setVersion(sharedVer); // Update the version
							l.setEpoch(id, nextEp);
							if (params.useL2() && level == CacheLevel.L1) {
								skippedL1Lines.add(l);
							}
						} else {
							if (params.updateWrittenLinesDuringVersionCheck()) {
								// Update the values with LLC contents
								l.copyAllValues(sharedLine);
								if (level == CacheLevel.L1) {
									Line l2Line = L2cache.getLine(l);
									assert l2Line != null;
									l2Line.copyAllValues(sharedLine);
									if (params.useL2()) {
										skippedL1Lines.add(l);
									}
								}
								l.clearReadEncoding(id);
								l.clearWriteEncoding(id);
								l.setVersion(sharedVer);
								l.setEpoch(id, nextEp);

								int cost = (resp.whereHit == CacheLevel.L3) ? MemorySystemConstants.L3_ACCESS
										: MemorySystemConstants.MEMORY_ACCESS;
								stats.pc_BandwidthDrivenCycleCount.incr(cost);
								updatePhaseBWDrivenCycleCost(ExecutionPhase.POST_COMMIT, cost);
							} else {
								// A written line is now being invalidated, this requires that we should
								// write back the data and remove the line from the per-core deferred set if the
								// line was deferred.
								// The line should not be deferred and has already been written back.
								l.invalidate();
							}
						}

						l.clearConcurrentRemoteWrite();
					} else {
						l.invalidate();
					}

				} else { // Untouched lines

					// The read/write access information might not be up-to-date
					// in the L2 cache
					if (level == CacheLevel.L1) {
						if (params.alwaysInvalidateReadOnlyLines()
								&& !params.invalidateWrittenLinesOnlyAfterVersionCheck()
								&& !params.invalidateUntouchedLinesOptimization()) {
							assert l.getEpoch(id).getRegionId() < currentEp.getRegionId();
						}

						if (ViserSim.assertsEnabled) {
							// L2 line should also be untouched
							Line l2Line = L2cache.getLine(l);
							assert !l2Line.isLineReadOnly(id) && !l2Line.hasWrittenOffsets(id);
							assert !l2Line.isAccessedInThisRegion(id);
						}
					}

					if (params.invalidateUntouchedLinesOptimization()) {
						// Get the current version of the LLC line. If the versions match, it implies
						// that there was no
						// concurrent writer, so we can avoid invalidation.
						MemoryResponse<Line> resp = getLineFromLLCOrMemory(l);
						assert resp.lineHit != null;
						long sharedVer = resp.lineHit.getVersion();
						long myVer = l.getVersion();
						if (myVer == sharedVer) {
							// Need not invalidate, since there has been no
							// concurrent write
							l.clearReadEncoding(id);
							l.clearWriteEncoding(id);
							l.setEpoch(id, nextEp);
							l.clearConcurrentRemoteWrite();

							// If an L1 line is untouched, then we can assert that the L2 line is also
							// untouched. In that case, we can avoid adding this line.
							if (level == CacheLevel.L1) {
								// skippedL1Lines.add(l);
								if (ViserSim.assertsEnabled) {
									Line l2Line = L2cache.getLine(l);
									assert !l2Line.isAccessedInThisRegion(id);
								}
							}
						} else {
							l.invalidate();
						}

					} else if (params.useBloomFilter()) {
						if (bf.contains(l.lineAddress().get())) { // LLC might have written it
							// Some of these lines could have been marked deferred in the LLC, which could
							// lead
							// to assertion failures while checking deferred LLC lines. This happens in
							// RCC-SI,
							// possibly because we do not use a special INVALID state there.
							if (params.useSpecialInvalidState()) {
								l.clearReadEncoding(id);
								l.clearWriteEncoding(id);
								l.setEpoch(id, nextEp);
								l.clearConcurrentRemoteWrite();
								l.changeStateTo(ViserState.VISER_INVALID_TENTATIVE);
							} else {
								l.invalidate();
							}
						} else { // Definitely not updated by the LLC
							l.clearReadEncoding(id);
							l.clearWriteEncoding(id);
							l.setEpoch(id, nextEp);
							l.clearConcurrentRemoteWrite();
						}

					} else if (params.useSpecialInvalidState()) {

						assert !params.useBloomFilter() : "Shouldn't come here if both are enabled";
						l.clearReadEncoding(id);
						l.clearWriteEncoding(id);
						l.setEpoch(id, nextEp);
						l.clearConcurrentRemoteWrite();
						l.changeStateTo(ViserState.VISER_INVALID_TENTATIVE);

					} else {
						// It is wrong to not invalidate untouched lines, because of imprecision.
						// The read might be data-race-free, but we would report a failed read
						// validation.
						l.invalidate();
					}
				}
			}
		}

		double bwCost = Math.ceil(bandwidthBasedCost);
		stats.pc_BandwidthDrivenCycleCount.incr(bwCost);
		updatePhaseBWDrivenCycleCost(ExecutionPhase.POST_COMMIT, bwCost);
	}

	class Verifier {

		/** AIM Cache lines should be a strict subset of the LLC lines */
		public void verifyAIMCacheInclusivity(final Processor<Line> proc) {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			AIMCache.LineVisitor<Line> lv = new AIMCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.lineAddress() != null) {
						assert line.getLevel() == CacheLevel.L3;
						Line llcLine = L3cache.getLine(line);
						if (llcLine == null) {
							System.out.println(ViserSim.totalEvents);
							System.out.println(line);
						}
						assert llcLine != null : "AIM is a subset of the LLC";
					}
				}
			};
			aimcache.visitAllLines(lv);
		}

		public void verifyAIMCacheDuplicates(final Processor<Line> proc) {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			for (Deque<Line> set : aimcache.sets) {
				for (Line l : set) {
					if (l.lineAddress() != null) {
						int counter = 0;
						for (Line tmp : set) {
							if (tmp.lineAddress() != null && l.lineAddress().equals(tmp.lineAddress())) {
								counter++;
							}
						}
						assert counter == 1;
					}
				}
			}
		}

		public void verifyPrivateMetadataCleared(final Processor<Line> proc) {
			HierarchicalCache.LineVisitor<Line> l1Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.id() == proc.id : "Private line should be owned by the same core";
						assert line.getLevel() == CacheLevel.L1;
						assert line.getReadEncoding(id) == 0;
						assert line.getWriteEncoding(id) == 0;
						assert !line.isThereAConcurrentRemoteWrite();
					}
				}
			};
			L1cache.visitAllLines(l1Lv);

			HierarchicalCache.LineVisitor<Line> l2Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.id() == proc.id : "Private line should be owned by the same core";
						assert line.getLevel() == CacheLevel.L2;
						assert line.getReadEncoding(id) == 0;
						assert line.getWriteEncoding(id) == 0;
						assert !line.isThereAConcurrentRemoteWrite();
					}
				}
			};
			L2cache.visitAllLines(l2Lv);
		}

		// Iterating over memory data structure is going to be slooow
		public void verifySharedMetadataCleared(final Processor<Line> proc) {
			HierarchicalCache.LineVisitor<Line> l3Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.getLevel() == CacheLevel.L3;
						assert line.getReadEncoding(id) == 0;
						assert line.getWriteEncoding(id) == 0;
					}
				}
			};
			L3cache.visitAllLines(l3Lv);
		}

		/**
		 * Verify that the owner core will have non-null metadata for private cache
		 * lines
		 */
		public void verifyPerCoreMetadata(final Processor<Line> proc) {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			HierarchicalCache.LineVisitor<Line> l1Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.id() == proc.id : "Private line should be owned by the same core";
						assert line.getLevel() == CacheLevel.L1;
						// The current core may have executed a region boundary, and so might have
						// cleared its metadata. But the following assertion should still work
						// provided we always invalidate lines.
						if (params.alwaysInvalidateReadOnlyLines()
								&& !params.invalidateWrittenLinesOnlyAfterVersionCheck()) {
							assert line.hasReadOffsets(proc.id) || line.hasWrittenOffsets(proc.id);
						}
					}
				}
			};
			L1cache.visitAllLines(l1Lv);

			HierarchicalCache.LineVisitor<Line> l2Lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid()) {
						assert line.id() == proc.id : "Private line should be owned by the same core";
						assert line.getLevel() == CacheLevel.L2;
						// L2 lines may not have updated metadata
					}
				}
			};
			L2cache.visitAllLines(l2Lv);
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
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

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
			}

			// Now compare the two sets
			for (Line inv : invalidLLCLines) {
				for (Line priv : privateLines) {
					if (priv.lineAddress().get() == inv.lineAddress().get() && priv.valid()) {
						throw new RuntimeException(
								"Invalid lines in LLC should not be present and valid in any private cache.");
					}
				}
			}

		}

		private void verifyCacheIndexing() {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			for (Processor<Line> p : allProcessors) {
				p.L1cache.verifyIndices();

				if (p.L2cache != null) {
					p.L2cache.verifyIndices();
				}
			}

			if (L3cache != null) {
				L3cache.verifyIndices();
			}
		}

		private void verifyExecutionCostBreakdown(final Processor<Line> proc) {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			double target = proc.stats.pc_BandwidthDrivenCycleCount.get();
			double actual = proc.stats.pc_ViserRegExecBWDrivenCycleCount.get()
					+ proc.stats.pc_ViserPreCommitBWDrivenCycleCount.get()
					+ proc.stats.pc_ViserReadValidationBWDrivenCycleCount.get()
					+ proc.stats.pc_ViserPostCommitBWDrivenCycleCount.get();
			assert target == actual : "Values differ: " + ViserSim.totalEvents;
		}

		private void verifyPrivateCacheLinesAreInvalid(final Processor<Line> proc) {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			HierarchicalCache.LineVisitor<Line> lv = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					assert line.id() == proc.id : "Private line should be owned by the same core";
					if (line.valid()) {
						// THIS DOES NOT ALWAYS WORK, ESPECIALLY IF WE ARE NOT
						// INVALIDATING READ-ONLY LINES
						assert false : "Private line should be INVALID";
					}
				}
			};
			L1cache.visitAllLines(lv);
			L2cache.visitAllLines(lv);
		}

		/**
		 * L1 and L2 cache are inclusive in this design. Meaningful only for valid
		 * lines. The cache line versions for VALID lines should be the same in both L1
		 * and L2 caches.
		 */
		public void verifyPrivateCacheInclusivityAndVersions(final Processor<Line> proc) {
			assert ViserSim.XASSERTS && ViserSim.xassertsEnabled();

			// Only valid is L2 is enabled
			if (params.useL2()) {
				final HashSet<Line> l1Set = new HashSet<Line>();
				HierarchicalCache.LineVisitor<Line> l1Lv = new HierarchicalCache.LineVisitor<Line>() {
					@Override
					public void visit(Line line) {
						if (line.valid()) {
							assert line.id() == proc.id : "Private line should be owned by the same core";
							l1Set.add(line);
						}
					}
				};
				L1cache.visitAllLines(l1Lv);

				final HashSet<Line> l2Set = new HashSet<Line>();
				HierarchicalCache.LineVisitor<Line> l2Lv = new HierarchicalCache.LineVisitor<Line>() {
					@Override
					public void visit(Line line) {
						if (line.valid()) {
							assert line.id() == proc.id : "Private line should be owned by the same core";
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
						if (l1Line.lineAddress().get() == l2Line.lineAddress().get()) {
							found = true;
							// Inclusivity is satisfied for this line, check for
							// matching versions
							assert l1Line.getVersion() == l2Line.getVersion() : "L1 and L2 line versions should match";
							break;
						}
					}
					if (!found) {
						throw new RuntimeException("L1 and L2 violate inclusivity.");
					}
				}
			}
		}

		public void verifyDeferredLines(final Processor<Line> proc) {
			// Iterate over valid LLC lines
			final HashSet<Line> deferredLLCLines = new HashSet<Line>();
			HierarchicalCache.LineVisitor<Line> deferredLLCV = new HierarchicalCache.LineVisitor<Line>() {
				@Override
				public void visit(Line line) {
					if (line.valid() && line.isLineDeferred()) {
						deferredLLCLines.add(line);
					}
				}
			};
			L3cache.visitAllLines(deferredLLCV);

			// Now check for the correctness of individual deferred lines
			for (Line dl : deferredLLCLines) {
				Processor<Line> ownerCore = proc.machine.getProc(new CpuId(dl.getDeferredLineOwnerID()));
				MemoryResponse<Line> privateResp = ownerCore.L1cache.searchPrivateCache(dl);
				if (privateResp.lineHit == null) {
					System.out.println(ViserSim.totalEvents);
					System.out.println(dl);
				}
				assert privateResp.lineHit != null;
				assert privateResp.lineHit.lineAddress().get() == dl.lineAddress().get();

				if (proc.params.areDeferredWriteBacksPrecise()) {
					Long writeMd = ownerCore.getDeferredWriteMetadata(privateResp.lineHit);

					if (writeMd == null) {
						System.out.println(dl);

						System.out.println(privateResp.whereHit);
						System.out.println(privateResp.lineHit);
						System.out
								.println(privateResp.lineHit.getWriteEncoding(new CpuId(dl.getDeferredLineOwnerID())));
					}
					assert writeMd != null : privateResp.lineHit.toString();
				}
			}
		}

		public void verifyPrivateDeferredLines(final Processor<Line> proc) {
			Iterator<Map.Entry<Long, Long>> entries = proc.wrMdDeferredDirtyLines.entrySet().iterator();
			while (entries.hasNext()) {
				Map.Entry<Long, Long> entry = entries.next();
				MemoryResponse<Line> resp = proc.L2cache.searchPrivateCache(new DataLineAddress(entry.getKey()));
				if (resp.lineHit == null) {
					System.out.println(ViserSim.totalEvents);
					System.out.println(proc);
					System.out.println("Line address:" + entry.getKey());
					System.out.println(proc.L2cache.getLine(new DataLineAddress(entry.getKey())));
				}
				assert resp.lineHit != null : "Line address:" + entry.getKey();
			}
		}

	} // end class Verifier

	Verifier Verify = new Verifier();

	// compute the performance cost of restarting the whole application at
	// intolerable conflicts.
	public void updateStatsForReboot() {
		if (params.checkPointingRate() > 0 && !params.isHttpd()) { // The whole application restores to the latest check
																	// point.
			stats.pc_TotalCheckPointRestores.incr();

			double restartCycleCost = 0;
			double BDcost;

			for (int i = 0; i < params.numProcessors(); i++) {
				BDcost = machine.processors[i].stats.pc_BandwidthDrivenCycleCount.get() - machine.check_point_time[i];
				if (BDcost > restartCycleCost)
					restartCycleCost = BDcost;
			}

			// cycles
			stats.pc_BandwidthDrivenCycleCountForReboot.incr(restartCycleCost);
			return;
		}

		stats.pc_TotalReboots.incr();
		if (params.isHttpd())
			return; // use fixed constants as restart costs

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

	public void updateStatsForTransRestart() {
		stats.pc_TotalRequestRestarts.incr();
		stats.pc_RequestRestarts.incr();

		// cycles
		double restartCycleCost = stats.pc_BandwidthDrivenCycleCount.get() - trans_start_time;
		stats.pc_BandwidthDrivenCycleCountForRequestRestart.incr(restartCycleCost);
	}

	public void setTransactionStart() {
		inTrans = true;
		hasTransRestart = false;
		trans_start_time = stats.pc_BandwidthDrivenCycleCount.get();
	}

	public void setCheckPoint() {
		int checkPointingRate = params.checkPointingRate();
		if (checkPointingRate == 0)
			return;
		if (stats.pc_PotentialCheckPoints.get() % checkPointingRate == 0) { // Set a check point.
			stats.pc_CheckPoints.incr();
			for (int i = 0; i < params.numProcessors(); i++) {
				machine.check_point_time[i] = machine.processors[i].stats.pc_BandwidthDrivenCycleCount.get();
			}
		}
		stats.pc_PotentialCheckPoints.incr();
	}

	public boolean ignoreEvents() {
		return (ignoreEvents > 0);
	}

	public void incIgnoreCounter() {
		assert (ignoreEvents >= 0);
		this.ignoreEvents++;
	}

	public void decIgnoreCounter() {
		assert (ignoreEvents > 0);
		this.ignoreEvents--;
		if (ignoreEvents < 0) {
			System.out.println(id + " decreased the counter " + ignoreEvents + " " + getCurrentEpoch());
		}
	}

} // end class Processor
