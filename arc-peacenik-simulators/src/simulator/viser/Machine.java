package simulator.viser;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

class MultipleWritersLineMD<Line> {
	Line llcLine;
	HashMap<CpuId, Line> procMap;
}

/** A class that manages the set of processors in the system. */
public class Machine<Line extends ViserLine> {

	// Variable-sized message requires a two byte header: the first is a read vector
	// for N cores, and the second is a
	// write vector for N cores. This is usually required for communication
	// involving memory.
	public final int VISER_VARIABLE_MSG_HEADER = 2
			* (int) (Math.ceil(ViserSim.numProcessors() / MemorySystemConstants.BITS_IN_BYTE));

	final Processor<Line>[] processors;

	final MachineParams<Line> params;

	public enum SimulationMode {
		BASELINE, VISER
	};

	// public int[] rtnCoverage = new int[1500];
	// public int[] srcCoverage = new int[400];

	// At most 25 static conflicts
	List<Conflict> conflicts = new ArrayList<>();
	List<SiteInfoEntry> siteInfo = new ArrayList<SiteInfoEntry>();

	double[] check_point_time;

	// We backup evicted LLC lines to memory, including the metadata.
	/** Machine memory. Holds addresses written to. The key is the line address. */
	final public HashMap<Long, Line> memory = new HashMap<>();
	private static final int SCAVENGE_MEMORY = 100000;
	private int[] scavengeMap = null;
	/**
	 * Machine-wide epoch. This is per-core and not per-thread. We can just maintain
	 * an array of integers.
	 */
	private Epoch[] epochMap = null;
	/** For pausing: Global metadata to indicate cores to be paused. */
	public long pausingBits = 0L;

	private DataOutputStream[] perThreadFifoOut;

	/**
	 * Arguments to the Machine ctor. We encode these values as abstract methods so
	 * that we can't forget to initialize one of them. The values get initialized by
	 * creating an anonymous subclass that is forced to override all these methods.
	 */
	static abstract class MachineParams<Line extends ViserLine> {
		abstract SimulationMode simulationMode();

		/** The number of processors to simulate */
		abstract int numProcessors();

		abstract int numPinThreads();

		abstract boolean pintool();

		abstract boolean lockstep();

		/** The cache geometry for the private L1 caches */
		abstract CacheConfiguration<Line> l1config();

		/** Whether to simulate private L2s or not */
		abstract boolean useL2();

		/** The cache geometry for the private L2 caches */
		abstract CacheConfiguration<Line> l2config();

		/** The cache geometry for the shared L3 cache */
		abstract CacheConfiguration<Line> l3config();

		abstract LineFactory<Line> lineFactory();

		abstract boolean ignoreStackReferences();

		abstract boolean remoteAccessesAffectLRU();

		abstract boolean writebackInMemory();

		abstract boolean alwaysInvalidateReadOnlyLines();

		abstract boolean invalidateWrittenLinesOnlyAfterVersionCheck();

		abstract boolean updateWrittenLinesDuringVersionCheck();

		abstract boolean invalidateUntouchedLinesOptimization();

		abstract boolean useSpecialInvalidState();

		abstract boolean useBloomFilter();

		abstract boolean useAIMCache();

		abstract boolean deferWriteBacks();

		abstract boolean areDeferredWriteBacksPrecise();

		abstract boolean skipValidatingReadLines();

		abstract boolean ignoreFetchingDeferredLinesDuringReadValidation();

		abstract boolean ignoreFetchingReadBits();

		abstract boolean ignoreFetchingWriteBits();

		abstract boolean clearAIMCacheAtRegionBoundaries();

		abstract boolean validateL1ReadsAlongWithL2();

		/** Whether to report sites involved in a conflict detected */
		abstract boolean siteTracking();

		abstract boolean printConflictingSites();

		// ARC+ configs.
		abstract boolean pauseCoresAtConflicts();

		abstract boolean restartAtFailedValidationsOrDeadlocks();

		abstract boolean treatAtomicUpdatesAsRegularAccesses();

		abstract boolean isHttpd();

		abstract boolean evictCleanLineFirst();

		abstract boolean usePLRU();

		abstract boolean setWriteBitsInL2();

		abstract boolean treatAtomicUpdatesAsRegionBoundaries();

		abstract boolean BackupDeferredWritebacksLasily();

		abstract boolean FalseRestart();

		abstract int checkPointingRate();
	}

	@SuppressWarnings("unchecked")
	public Machine(MachineParams<Line> args) {
		this.params = args;

		Map<LineAddress, Integer> varmap = new HashMap<LineAddress, Integer>();

		createEpochs(params.numProcessors());

		// construct processors
		processors = new Processor[args.numProcessors()];
		for (int i = 0; i < processors.length; i++) {
			CpuId cpuid = new CpuId(i);
			/* HACK: see Counter.currentCpu for details */
			Counter.currentCpu = cpuid;
			processors[i] = new Processor<Line>(args, this, cpuid, processors, varmap);
		}
		Counter.currentCpu = null;

		check_point_time = new double[processors.length];
	}

	/** map from thread id to cpu id */
	public CpuId cpuOfTid(ThreadId tid) {
		// NB: we have a very simple mapping of threads onto caches
		// Take care of the IO thread in Pintool
		if (params.pintool() && tid.get() == 1) {
			throw new RuntimeException("Tid 1 is not expected");
		}
		int pid = (tid.get() > 1) ? tid.get() - 1 : 0;
		// if (pid >= processors.length)
		// System.out.println(pid + " PID");
		return processors[pid % processors.length].id;
	}

	Processor<Line> getProc(CpuId cpuid) {
		Processor<Line> p = processors[cpuid.get()];
		assert p != null;
		return p;
	}

	public void dumpStats(Writer wr, String prefix, String suffix) throws IOException {
		// the Counter class keeps track of all its instances, so we only need to dump
		// once
		SumCounter.dumpCounters(wr, prefix, suffix);
		MaxCounter.dumpCounters(wr, prefix, suffix);
		DependentCounter.dumpCounters(wr, prefix, suffix);
	}

	public void insnsExecuted(final CpuId cpuid, int n) {
		getProc(cpuid).insnsExecuted(n);
	}

	public void cacheRead(final CpuId cpuid, final long addr, final int size, long value, ThreadId tid, int siteIndex,
			int lastSiteIndex, MemoryAccessType type) {
		cacheAccess(cpuid, false, addr, size, value, tid, siteIndex, lastSiteIndex, type);
	}

	// Make the method name explicit to avoid potential misuse
	public void testCacheMemoryRead(final CpuId cpuid, final long addr, final int size, long value, ThreadId tid) {
		cacheRead(cpuid, addr, size, value, tid, 0, 0, MemoryAccessType.MEMORY_READ);
	}

	public void testCacheMemoryRead(final CpuId cpuid, final long addr, final int size, long value, ThreadId tid,
			int siteIndex, int lastSiteIndex) {
		cacheRead(cpuid, addr, size, value, tid, siteIndex, lastSiteIndex, MemoryAccessType.MEMORY_READ);
	}

	public void cacheWrite(final CpuId cpuid, final long addr, final int size, long value, ThreadId tid, int siteIndex,
			int lastSiteIndex, MemoryAccessType type) {
		cacheAccess(cpuid, true, addr, size, value, tid, siteIndex, lastSiteIndex, type);
	}

	// Make the method name explicit to avoid potential misuse
	public void testCacheMemoryWrite(final CpuId cpuid, final long addr, final int size, long value, ThreadId tid) {
		cacheWrite(cpuid, addr, size, value, tid, 0, 0, MemoryAccessType.MEMORY_WRITE);
	}

	public void testCacheMemoryWrite(final CpuId cpuid, final long addr, final int size, long value, ThreadId tid,
			int siteIndex, int lastSiteIndex) {
		cacheWrite(cpuid, addr, size, value, tid, siteIndex, lastSiteIndex, MemoryAccessType.MEMORY_WRITE);
	}

	public void cacheAccess(final CpuId cpuid, final boolean write, final long addr, final int size, long value,
			ThreadId tid, int siteIndex, int lastSiteIndex, MemoryAccessType type) {
		Processor<Line> proc = getProc(cpuid);
		if ((type != MemoryAccessType.LOCK_ACQ_READ && type != MemoryAccessType.LOCK_ACQ_WRITE)
				&& proc.ignoreEvents()) {
			// System.out.println("Access ignored: type " + type + ", core " + cpuid + ",
			// addr " + addr + ", size " +
			// size
			// + ", value " + value);
			return;
		}

		switch (params.simulationMode()) {
		case VISER: {
			break;
		}
		case BASELINE:
		default: {
			throw new UnsupportedOperationException("Not supported.");
		}
		}

		Processor.DataMemoryAccessResult mopResult = new Processor.DataMemoryAccessResult();
		mopResult.remoteCommunicatedHappened = false;
		int remainingSize = size;

		// Translate the address for atomic and lock accesses, so that they lie on a
		// separate line
		long translatedAddr = addr;
		if (type == MemoryAccessType.ATOMIC_READ || type == MemoryAccessType.ATOMIC_WRITE
				|| type == MemoryAccessType.LOCK_ACQ_READ || type == MemoryAccessType.LOCK_ACQ_WRITE
				|| type == MemoryAccessType.LOCK_REL_WRITE) {
			if (translatedAddr <= 0) {
				/*
				 * mysqld's own synchronization operations don't have the same signature as
				 * pthread functions and the pintool can't get valid lock addresses for them.
				 */
				translatedAddr = MemorySystemConstants.LOCK_ADDR_OFFSET;
			}
			translatedAddr += MemorySystemConstants.LOCK_ADDR_OFFSET;
			// Access the whole line
			translatedAddr &= (~MemorySystemConstants.LINE_OFFSET_MASK());
			remainingSize = MemorySystemConstants.LINE_SIZE();
		}

		for (long a = translatedAddr; remainingSize > 0;) {
			DataByteAddress dba = new DataByteAddress(a);
			int data_bytesFromStartOfLine = dba.lineOffset();
			int data_maxSizeAccessWithinThisLine = MemorySystemConstants.LINE_SIZE() - data_bytesFromStartOfLine;

			// data access
			int accessSize = Math.min(remainingSize, data_maxSizeAccessWithinThisLine);
			Processor.DataMemoryAccessResult tempMor;
			if (write) {
				tempMor = proc
						.write(new DataAccess(type, dba, accessSize, value, cpuid, tid, siteIndex, lastSiteIndex));
			} else {
				tempMor = proc.read(new DataAccess(type, dba, accessSize, value, cpuid, tid, siteIndex, lastSiteIndex));
			}
			if (proc.reRunEvent) {
				return;
			}
			if (proc.restartRegion) {
				// Costs should be counted for "region restart"
				proc.updateCostsFromTmpCounters(true);
				return;
			}
			mopResult.aggregate(tempMor);

			a += accessSize;
			remainingSize -= accessSize;
		}
	}

	// IMP: This method is to support unit tests
	public void testProcessRegionBoundary(final CpuId performingCpu, ThreadId tid, EventType type) {
		processRegionBoundary(performingCpu, tid, EventType.LOCK_RELEASE, EventType.REG_END, null, 0);
	}

	public void testProcessRegionBoundary(final CpuId performingCpu, ThreadId tid, EventType type, List<Event> EB,
			int curPosition) {
		processRegionBoundary(performingCpu, tid, EventType.LOCK_RELEASE, EventType.REG_END, EB, curPosition);
	}

	public void processRegionBoundary(final CpuId performingCpu, ThreadId tid, EventType type, EventType semantics) {
		processRegionBoundary(performingCpu, tid, type, semantics, null, 0);
	}

	public void processRegionBoundary(final CpuId performingCpu, ThreadId tid, EventType type, EventType semantics,
			List<Event> EB, int curPosition) {
		Processor<Line> performingProc = getProc(performingCpu);

		boolean successful = false;
		if (semantics == EventType.REG_BEGIN) {
			if (type == EventType.LOCK_ACQUIRE) {
				if (params.useBloomFilter() || params.invalidateUntouchedLinesOptimization()) {
					performingProc.postCommitSelfInvalidateHelper(type, true);
					performingProc.updateCostsFromTmpCounters(false);
				}
				performingProc.decIgnoreCounter();
				successful = true;
			}
		} else if (semantics == EventType.REG_END) {
			successful = performingProc.processRegionEnd(tid, type);
			if (successful) {
				performingProc.stats.pc_RegionBoundaries.incr();

				// Costs should be counted for "normal execution"
				performingProc.updateCostsFromTmpCounters(false);
				if (type == EventType.LOCK_ACQUIRE) {
					performingProc.incIgnoreCounter();
				}
			} else if (performingProc.restartRegion) {
				// Costs should be counted for "region restart"
				performingProc.updateCostsFromTmpCounters(true);
			} // else if (performingProc.reRunEvent) // pause: do nothing
		} else {
			throw new RuntimeException("Invalid region semantics");
		}

		if (successful) { // successfully finish a region
			// We now split up region boundary events into multiple constituent events.
			/*
			 * if ((type == EventType.LOCK_ACQUIRE && semantics == EventType.REG_END) ||
			 * (type == EventType.LOCK_RELEASE && semantics == EventType.REG_END) || (type
			 * == EventType.THREAD_START) || (type == EventType.THREAD_FINISH) || (type ==
			 * EventType.THREAD_SPAWN && semantics == EventType.REG_END) || (type ==
			 * EventType.THREAD_JOIN && semantics == EventType.REG_END)) {
			 * performingProc.stats.pc_RegionBoundaries.incr(); }
			 */

			if (performingProc.params.lockstep() && (type == EventType.LOCK_ACQUIRE || type == EventType.LOCK_RELEASE)
					&& semantics == EventType.REG_END) {
				assert tid.get() != 1;
				// clear the event buffer of this core
				if (params.restartAtFailedValidationsOrDeadlocks()) {
					EB.clear();
				}
				// Now write to the per-core fifo to signal the frontend
				try {
					// We could convert short (2 bytes in Java) to a byte, so that we can avoid
					// endian swapping.
					// byte b = (byte) (tid.get() & 0xFF);
					short value = tid.get();
					// System.out.println("[visersim] The backend is writing to the fifo: Event:" +
					// ViserSim.totalEvents
					// + " Event type:" + type + " Thread:" + value + " Value: " + value);
					// Writes a short to the underlying output stream as two bytes, high byte first.
					perThreadFifoOut[value].writeShort(value);
					perThreadFifoOut[value].flush();
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else if (params.restartAtFailedValidationsOrDeadlocks()) {
				// clear the event buffer of this core
				EB.subList(0, curPosition).clear();
			}
			// No need to invoke this expensive method at both region begin and end
			scavengeMemory(performingProc);
		}
	}

	public void printEpochMap() {
		System.out.println("Printing epoch map:");
		for (int i = 0; i < epochMap.length; i++) {
			Epoch tmp = epochMap[i];
			System.out.println("Core/thread id:" + i + " Region id:" + tmp.getRegionId());
		}
	}

	public void createEpochs(int numProcs) {
		epochMap = new Epoch[numProcs];
		for (int i = 0; i < numProcs; i++) {
			epochMap[i] = new Epoch(0);
		}
	}

	public void initializeEpochs() {
		for (int i = 0; i < epochMap.length; i++) {
			epochMap[i] = new Epoch(Epoch.REGION_ID_START);
		}
	}

	/** Increment epoch for current core id (not thread) */
	public void incrementEpoch(CpuId id) {
		epochMap[id.get()].incrementRegionId();
	}

	public Epoch getEpoch(CpuId id) {
		return epochMap[id.get()];
	}

	public long getPausingBits() {
		return pausingBits;
	}

	public void setPausingBitsAtOffset(int offset) {
		if (offset > Long.SIZE) {
			System.out.println("The offset of pausingBits cannot exceed 64.");
			return;
		}
		long t = 1L << offset;
		pausingBits |= t;
	}

	public void clearPausingBitsAtOffset(int offset) {
		if (offset > Long.SIZE) {
			System.out.println("The offset of pausingBits cannot exceed 64.");
			return;
		}
		long t = ~(1L << offset);
		pausingBits &= t;
	}

	public void prepareScavengeMap(int numProcs) {
		scavengeMap = new int[numProcs];
	}

	// Check if the memory entries can be freed
	private boolean shouldScavenge() {
		if (memory.size() <= SCAVENGE_MEMORY) {
			return false;
		}
		// Check if all cores have executed at least a few regions in between
		for (int i = 0; i < params.numProcessors(); i++) {
			assert scavengeMap[i] <= epochMap[i].getRegionId() : "Scavenges should have happened past in time";
			// Have a gap of one to be more effective.
			// LATER: This might not work if the main thread is blocked, waiting
			// for the child threads to finish
			if (scavengeMap[i] >= (epochMap[i].getRegionId() - 1)) {
				return false;
			}
		}
		return true;
	}

	/*
	 * private void updateScavengeMap() { for (int i = 0; i <
	 * params.numProcessors(); i++) { scavengeMap[i] = getEpoch(new
	 * CpuId(i)).getRegionId(); } }
	 */

	public void printScavengeMap() {
		System.out.println("Printing scavenge map:");
		for (int i = 0; i < scavengeMap.length; i++) {
			System.out.println("Core/thread id:" + i + " Region id:" + scavengeMap[i]);
		}
	}

	// This is an expensive operation. Optimize its invocation, ideally should be
	// invoked only if
	// needed.
	private void scavengeMemory(Processor<Line> proc) {
		if (!shouldScavenge()) {
			return;
		}

		proc.stats.pc_NumScavenges.incr();
		return;
		/*
		 * final long startTime = System.currentTimeMillis();
		 * 
		 * // Iterate over all entries in the map, and check whether all the epochs for
		 * all cores // have expired for a line. Then, we can safely remove that line.
		 * 
		 * Iterator<Entry<Long, Line>> iter = memory.entrySet().iterator(); while
		 * (iter.hasNext()) { Map.Entry<Long, Line> entry = iter.next(); Line test =
		 * entry.getValue(); assert test.valid() :
		 * "Should not backup INVALID lines to memory";
		 * 
		 * boolean remove = true; for (int i = 0; i < params.numProcessors(); i++) {
		 * CpuId cpu = new CpuId(i); PerCoreLineMetadata md =
		 * test.getPerCoreMetadata(cpu); if (md.epoch.getRegionId() ==
		 * getEpoch(cpu).getRegionId()) { remove = false; break; } else if
		 * (md.epoch.getRegionId() > getEpoch(cpu).getRegionId()) { assert false :
		 * "Scavenges should have happened past in time"; } else { // Nothing to do } }
		 * 
		 * if (remove) { // Check if the line is in VALID state in any private cache.
		 * The // core may have // accessed the line in the current region. for
		 * (Processor<Line> p : processors) { // We can just iterate over L2 since L1 is
		 * included in L2, // and the line states // in the two caches are consistent.
		 * // LATER: Maybe it is okay to invalidate the L1 and L2 lines? Check if it
		 * helps // with simlarge workload sizes. Line l2Line = p.L2cache.getLine(test);
		 * if (l2Line != null && l2Line.valid()) { remove = false; break; } } }
		 * 
		 * // Only remove after all of the above checks have passed if (remove) {
		 * iter.remove(); } }
		 * 
		 * updateScavengeMap();
		 * 
		 * ViserSim.totalScavengeTime += (System.currentTimeMillis() - startTime) /
		 * (double)(1000 * 60);
		 */
	}

	/** Should mostly contain of addresses/lines written to. */
	void dumpMachineMemory() {
		System.out.println("*************MACHINE MEMORY START*************\n");
		for (Entry<Long, Line> entry : memory.entrySet()) {
			Line line = entry.getValue();
			System.out.println("Line: " + line);
			System.out.println("Per-core metadata:");
			for (int i = 0; i < params.numProcessors(); i++) {
				System.out.print("\tCore " + i + ": " + line.getPerCoreMetadata(new CpuId(i)));
			}
		}
		System.out.println("\n*************MACHINE MEMORY END*************\n");
	}

	public boolean detectDeadlock(short[] father, short[] ends) {
		short n = (short) processors.length;
		short[] visit = new short[n];
		// boolean res = false;
		for (short i = 0; i < n; i++)
			if (visit[i] == 0) {
				if (processors[i].getPausedCores() != 0) {
					if (dfsVisit(i, visit, father, ends)) {
						return true;
					}
				} else { // no out-edges
					visit[i] = 2;
				}
			}
		return false;
	}

	private boolean dfsVisit(short node, short[] visit, short[] father, short[] ends) {
		visit[node] = 1;
		for (short i = 0; i < processors.length; i++)
			if (i != node && pausedFor(processors[node].getPausedCores(), i)) {
				if (visit[i] == 1)// find a cycle
				{
					ends[0] = node;
					ends[1] = i;
					/*
					 * // father node release child proc tmp (i) clearPausingBitsAtOffset(i);
					 * processors[node].clearPausedCores(i); processors[i].reRunEvent = false;
					 * System.out.println( "[viserpuasesim] " + processors[node].id + " releases " +
					 * processors[i].id + " to break the deadlock " + getPausingBits());
					 */
					return true;
				} else if (visit[i] == 0) {
					father[i] = node;
					if (dfsVisit(i, visit, father, ends))
						return true;
				}
			}

		visit[node] = 2;
		return false;
	}

	private boolean pausedFor(long pausedCores, short i) {
		return ((pausedCores & (1L << i)) != 0L); // i need to be released by
													// node
	}

	void openPerThreadFifos(String bench) {
		perThreadFifoOut = new DataOutputStream[5 * params.numPinThreads()];
		for (int i = 0; i < 5 * params.numPinThreads(); i++) {
			try {
				FileOutputStream fos = new FileOutputStream(System.getenv("ST_PINTOOL_ROOT") + "/fifo.tid" + i + bench);
				perThreadFifoOut[i] = new DataOutputStream(new BufferedOutputStream(fos));
				// System.out.println("[visersim] Opened per-thread fifo for writing:" +
				// System.getenv("PINTOOL_ROOT")
				// + "/fifo.tid" + i);
			} catch (FileNotFoundException fnf) {
				fnf.printStackTrace();
				return;
			}
		}
	}

	void closePerThreadFifos() {
		for (int i = 0; i < 5 * params.numPinThreads(); i++) {
			try {
				perThreadFifoOut[i].close();
				// Path path = FileSystems.getDefault().getPath(System.getenv("PINTOOL_ROOT") +
				// "/fifo.tid" + i);
				// Files.deleteIfExists(path);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	public void updateConflictCounters(int f0, int l0, int r0, int f1, int l1, int r1, int lf0, int ll0, int lf1,
			int ll1) {
		for (int i = 0; i < conflicts.size(); i++) {
			Conflict conflict = conflicts.get(i);
			if (conflict.isTheSame(f0, l0, f1, l1, lf0, ll0, lf1, ll1)) {
				conflict.inc();
				return;
			}
		}
		Conflict conflict = new Conflict(f0, l0, r0, f1, l1, r1, lf0, ll0, lf1, ll1);
		conflict.inc();
		conflicts.add(conflict);
	}

	// reset the counters to allow counting for other lines.
	public void resetConflictCounter() {
		for (int i = 0; i < conflicts.size(); i++) {
			conflicts.get(i).allowCounting();
		}
	}
};
