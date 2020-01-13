package simulator.mesi;

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

/** A class that manages the set of processors in the system. */
public class Machine<Line extends MESILine> {

	final Processor<Line>[] processors;
	final MachineParams<Line> params;

	public enum SimulationMode {
		BASELINE, VISER
	}

	List<Conflict> conflicts = new ArrayList<>();
	List<SiteInfoEntry> siteInfo = new ArrayList<SiteInfoEntry>();

	HashMap<Long, CEPerLineMetadata<Line>> globalTable = new HashMap<>();
	/**
	 * Machine-wide epoch. This is per-core and not per-thread. We can just maintain
	 * an array of integers.
	 */
	private Epoch[] epochMap = null;

	public long pausingBits = 0L;
	private DataOutputStream[] perThreadFifoOut;

	/**
	 * Arguments to the Machine ctor. We encode these values as abstract methods so
	 * that we can't forget to initialize one of them. The values get initialized by
	 * creating an anonymous subclass that is forced to override all these methods.
	 */
	static abstract class MachineParams<Line extends MESILine> {
		/** Whether to simulate the Radish processor extensions or not */
		abstract SimulationMode simulationMode();

		/** The number of processors to simulate */
		abstract int numProcessors();

		abstract int numPinThreads();

		abstract boolean pintool();

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

		abstract boolean conflictExceptions();

		abstract boolean printConflictingSites();

		abstract boolean treatAtomicUpdatesAsRegularAccesses();

		abstract boolean usePLRU();

		abstract boolean withPacifistBackends();

		abstract boolean lockstep();

		// ARC+ configs.
		abstract boolean pauseCoresAtConflicts();

		/** Whether to report sites involved in a conflict detected */
		abstract boolean siteTracking();

		abstract boolean isHttpd();

		abstract boolean dirtyEscapeOpt();

		abstract boolean restartAtFailedValidationsOrDeadlocks();

		abstract boolean evictCleanLineFirst();

		abstract boolean setWriteBitsInL2();

		abstract boolean BackupDeferredWritebacksLasily();

		abstract boolean FalseRestart();

		abstract int pausingTimeout();
	}

	@SuppressWarnings("unchecked")
	public Machine(MachineParams<Line> args) {
		this.params = args;
		Map<LineAddress, Integer> varmap = new HashMap<LineAddress, Integer>();

		if (params.conflictExceptions()) {
			createEpochs(params.numProcessors());
		}

		// construct processors
		processors = new Processor[args.numProcessors()];
		for (int i = 0; i < processors.length; i++) {
			CpuId cpuid = new CpuId(i);
			/* HACK: see Counter.currentCpu for details */
			Counter.currentCpu = cpuid;
			processors[i] = new Processor<Line>(args, this, cpuid, processors, varmap);
		}
		Counter.currentCpu = null;
	}

	/** map from thread id to cpu id */
	public CpuId cpuOfTid(ThreadId tid) {
		// NB: we have a very simple mapping of threads onto caches
		// Take care of the IO thread in Pintool
		if (params.pintool() && tid.get() == 1) {
			throw new RuntimeException("Tid 1 is not expected");
		}
		int pid = (tid.get() > 1) ? tid.get() - 1 : 0;
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

	public Processor<Line>[] getProcs() {
		return processors;
	}

	public void insnsExecuted(final CpuId cpuid, int n) {
		getProc(cpuid).insnsExecuted(n);
	}

	public void cacheRead(final CpuId cpuid, final long addr, final int size, int siteIndex, MemoryAccessType type) {
		cacheAccess(cpuid, false, addr, size, siteIndex, type, true);
	}

	public void testCacheMemoryRead(final CpuId cpuid, final long addr, final int size) {
		cacheAccess(cpuid, false, addr, size, 0, MemoryAccessType.MEMORY_READ, true);
	}

	public void cacheWrite(final CpuId cpuid, final long addr, final int size, int siteIndex, MemoryAccessType type) {
		cacheAccess(cpuid, true, addr, size, siteIndex, type, true);
	}

	public void testCacheMemoryWrite(final CpuId cpuid, final long addr, final int size) {
		cacheAccess(cpuid, true, addr, size, 0, MemoryAccessType.MEMORY_WRITE, true);
	}

	public void cacheAccess(final CpuId cpuid, final boolean write, final long addr, final int size, int siteIndex,
			MemoryAccessType type, boolean doMetadataAccess) {
		Processor<Line> proc = getProc(cpuid);
		int timeout = params.pausingTimeout();
		// We ignore events in the Pacifist simulators, so to be fair, we also do so in
		// the MESI simulator.
		if ((params.withPacifistBackends() || params.pauseCoresAtConflicts())
				&& (type != MemoryAccessType.LOCK_ACQ_READ && type != MemoryAccessType.LOCK_ACQ_WRITE)
				&& proc.ignoreEvents()) {
			// System.out.println("Access ignored: type " + type + ", core " + cpuid + ",
			// addr " + addr + ", size " +
			// size
			// + ", value " + value);
			// resume paused cores and throw an exception immediately
			if (timeout > 0) {
				proc.checkLongPausingCores(-1);
			}
			return;
		}

		if (timeout > 0) {
			proc.checkLongPausingCores(timeout);
		}

		switch (params.simulationMode()) {
		case BASELINE: {
			break;
		}
		case VISER: {
			throw new UnsupportedOperationException("Not yet implemented.");
		}
		default:
			assert false;
		}

		Processor.DataMemoryAccessResult mopResult = new Processor.DataMemoryAccessResult();
		mopResult.remoteCommunicatedHappened = false;
		int remainingSize = size;

		if (type == MemoryAccessType.LOCK_ACQ_READ || type == MemoryAccessType.LOCK_ACQ_WRITE
				|| type == MemoryAccessType.LOCK_REL_WRITE) {
			// Assume a one-word access for each lock operation
			remainingSize = 2;
		}

		for (long a = addr; remainingSize > 0;) {
			DataByteAddress dba = new DataByteAddress(a);
			int data_bytesFromStartOfLine = dba.lineOffset();
			int data_maxSizeAccessWithinThisLine = MemorySystemConstants.LINE_SIZE() - data_bytesFromStartOfLine;

			// data access
			int accessSize = Math.min(remainingSize, data_maxSizeAccessWithinThisLine);
			Processor.DataMemoryAccessResult tempMor;
			if (write) {
				tempMor = proc.write(new DataAccess(type, dba, accessSize, siteIndex));
			} else {
				tempMor = proc.read(new DataAccess(type, dba, accessSize, siteIndex));
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

	public void processSyncOp(final CpuId performingCpu, ThreadId tid, EventType type, EventType semantics) {
		processSyncOp(performingCpu, tid, type, semantics, null, 0);
	}

	public void processSyncOp(final CpuId performingCpu, ThreadId tid, EventType type, EventType semantics,
			List<Event> EB, int curPosition) {
		Processor<Line> performingProc = getProc(performingCpu);

		if (semantics == EventType.REG_END) {
			performingProc.processSyncOp(tid);
			performingProc.stats.pc_RegionBoundaries.incr();
			performingProc.updateCostsFromTmpCounters(false);

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
		}

		// We ignore events in the Pacifist simulators, so to be fair, we also do so in
		// the MESI simulator.
		if ((params.withPacifistBackends() || params.pauseCoresAtConflicts()) && type == EventType.LOCK_ACQUIRE) {
			if (semantics == EventType.REG_END) {
				performingProc.incIgnoreCounter();
			} else {
				performingProc.decIgnoreCounter();
				if (params.restartAtFailedValidationsOrDeadlocks()) {
					// clear the event buffer of this core
					EB.subList(0, curPosition).clear();
				}
			}
		}
	}

	public void printGlobalTable() {
		for (Long l : globalTable.keySet()) {
			System.out.println("Line address: " + l);
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

	public void updateConflictCounters(int f0, int l0, int r0, int f1, int l1, int r1) {
		for (int i = 0; i < conflicts.size(); i++) {
			Conflict conflict = conflicts.get(i);
			if (conflict.isTheSame(f0, l0, f1, l1)) {
				conflict.inc();
				return;
			}
		}
		Conflict conflict = new Conflict(f0, l0, r0, f1, l1, r1);
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
