package simulator.mesi;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import joptsimple.OptionSet;
import simulator.mesi.Machine.SimulationMode;

public class MESISim {

	/** enable checking of computationally expensive asserts */
	public static boolean XASSERTS = true;

	public static final ThreadId INVALID_THREADID = new ThreadId(-1);

	public enum PARSEC_PHASE {
		PRE_ROI, IN_ROI, POST_ROI, IN_SERVER_ROI, POST_SERVER_ROI
	}

	private static PARSEC_PHASE phase = PARSEC_PHASE.PRE_ROI;

	static OptionSet Options;

	private static int maxLiveThreads;
	private static int numSpawnedThreads;
	private static int currentLiveThreads;
	private static long insnsExecuted;
	private static long stackAccesses;

	static long totalEvents = 1;
	private static long basicBlockEvents;

	public static long totalRegionRestarts = 0;
	private static double SERVER_STARTUP_BWDRIVEN_CYCLES = 0;

	public static double totalScavengeTime = 0;

	/** Events buffer for paused cores */
	private static final List<List<Event>> eventsBuffer = new ArrayList<List<Event>>();
	public static int[] pos;

	public static final long debugStart = 626000000;
	public static final long debugCurrent = 626057257;
	public static final long debugByteAddress = 22582336L;
	public static final long debugLineAddress = 140637238945664L;

	public static boolean debugPrint() {
		return totalEvents == debugCurrent;
	}

	// http://docs.oracle.com/javase/7/docs/technotes/guides/language/assert.html
	static boolean assertsEnabled = false;

	// private static int count;

	static {
		assert assertsEnabled = true; // Intentional side effect!!!
	}

	// These checks are expensive
	public static boolean enableXasserts() {
		if (XASSERTS) {
			if ((totalEvents % Options.valueOf(Knobs.AssertPeriod) == 0)
			// &&
			// totalEvents > debugStart
			) {
				return true;
			}
		}
		return false;
	}

	public static boolean modelOnlyROI() {
		return Options.valueOf(Knobs.modelOnlyROI);
	}

	public static void setPhase(PARSEC_PHASE p) {
		phase = p;
	}

	public static PARSEC_PHASE getPARSECPhase() {
		return phase;
	}

	public static int numProcessors() {
		return Options.valueOf(Knobs.Cores);
	}

	public static void main(String[] args) throws IOException {
		Options = Knobs.parser.parse(args);
		if (Options.has(Knobs.Help)) {
			Knobs.parser.printHelpOn(System.out);
			return;
		}
		XASSERTS = Options.valueOf(Knobs.Xasserts);

		final SimulationMode simMode;
		// if (Options.valueOf(Knobs.SimulationMode).equals("baseline")) {
		simMode = SimulationMode.BASELINE;
		/*
		 * } else if (Options.valueOf(Knobs.SimulationMode).equals("viser")) { throw new
		 * UnsupportedOperationException("Viser mode not implemented."); } else { throw
		 * new IllegalStateException("Invalid simulation mode: " +
		 * Options.valueOf(Knobs.SimulationMode)); }
		 */

		DataInputStream in;
		String fifoName = Options.valueOf(Knobs.ToSimulatorFifo);
		String bench = fifoName.split("\\.")[0];
		try {
			FileInputStream fis = new FileInputStream(fifoName);
			in = new DataInputStream(new BufferedInputStream(fis));
		} catch (FileNotFoundException fnf) {
			fnf.printStackTrace();
			return;
		}

		final long startTime = System.currentTimeMillis();
		MemorySystemConstants.setLineSize(Options.valueOf(Knobs.LineSize));
		MemorySystemConstants.setLLCAccessTimes(numProcessors());

		Machine.MachineParams<MESILine> p = new Machine.MachineParams<MESILine>() {
			SimulationMode simulationMode() {
				return simMode;
			}

			@Override
			int numProcessors() {
				return Options.valueOf(Knobs.Cores);
			}

			@Override
			int numPinThreads() {
				return Options.valueOf(Knobs.PinThreads);
			}

			@Override
			boolean pintool() {
				return Options.valueOf(Knobs.Pintool);
			}

			CacheConfiguration<MESILine> l1config() {
				// NB: be lazy and return a new object each time; shouldn't affect anything
				// since the
				// values are always the same
				return new CacheConfiguration<MESILine>() {
					{
						cacheSize = Options.valueOf(Knobs.L1Size);
						lineSize = MemorySystemConstants.LINE_SIZE();
						assoc = Options.valueOf(Knobs.L1Assoc);
						level = CacheLevel.L1;
					}
				};
			}

			boolean useL2() {
				return Options.valueOf(Knobs.UseL2);
			}

			CacheConfiguration<MESILine> l2config() {
				return new CacheConfiguration<MESILine>() {
					{
						cacheSize = Options.valueOf(Knobs.L2Size);
						lineSize = MemorySystemConstants.LINE_SIZE();
						assoc = Options.valueOf(Knobs.L2Assoc);
						level = CacheLevel.L2;
					}
				};
			}

			CacheConfiguration<MESILine> l3config() {
				return new CacheConfiguration<MESILine>() {
					{
						cacheSize = Options.valueOf(Knobs.L3Size);
						lineSize = MemorySystemConstants.LINE_SIZE();
						assoc = Options.valueOf(Knobs.L3Assoc);
						level = CacheLevel.L3;
					}
				};
			}

			LineFactory<MESILine> lineFactory() {
				return new LineFactory<MESILine>() {
					@Override
					public MESILine create(CpuId id, CacheLevel level) {
						return new MESILine(id, level);
					}

					@Override
					public MESILine create(CpuId id, CacheLevel level, LineAddress la) {
						return new MESILine(id, level, la);
					}

					@Override
					public MESILine create(CpuId id, CacheLevel level, MESILine l) {
						assert l.valid() : "Source line should be valid.";
						MESILine tmp = new MESILine(id, level, l.lineAddress());
						tmp.changeStateTo(l.getState());
						tmp.setDirty(l.dirty());
						tmp.setLastWriters(l.getLastWriters());

						if (conflictExceptions()) {
							tmp.setLocalReads(l.getLocalReads());
							tmp.setLocalWrites(l.getLocalWrites());
							tmp.setRemoteReads(l.getRemoteReads());
							tmp.setRemoteWrites(l.getRemoteWrites());
							if (l.isSupplied()) {
								tmp.setSupplied();
							}
						}
						return tmp;
					}
				};
			}

			@Override
			boolean ignoreStackReferences() {
				return Options.valueOf(Knobs.IgnoreStackRefs);
			}

			@Override
			boolean remoteAccessesAffectLRU() {
				return Options.valueOf(Knobs.RemoteAccessesAffectLRU);
			}

			@Override
			boolean conflictExceptions() {
				return Options.valueOf(Knobs.ConflictExceptions);
			}

			@Override
			boolean printConflictingSites() {
				return false;
			}

			@Override
			boolean treatAtomicUpdatesAsRegularAccesses() {
				return Options.valueOf(Knobs.TreatAtomicUpdatesAsRegularAccesses);
			}

			@Override
			boolean usePLRU() {
				return Options.valueOf(Knobs.UsePLRU);
			}

			@Override
			boolean withPacifistBackends() {
				return Options.valueOf(Knobs.WithPacifistBackends);
			}

			@Override
			boolean pauseCoresAtConflicts() {
				return Options.valueOf(Knobs.PauseCoresAtConflicts);
			}

			@Override
			boolean siteTracking() {
				return Options.valueOf(Knobs.SiteTracking);
			}

			@Override
			boolean lockstep() {
				return Options.valueOf(Knobs.Lockstep);
			}

			@Override
			boolean isHttpd() {
				return Options.valueOf(Knobs.IsHttpd);
			}

			@Override
			boolean dirtyEscapeOpt() {
				return Options.valueOf(Knobs.DirtyEscapeInvovledConflictOpt);
			}

			@Override
			boolean restartAtFailedValidationsOrDeadlocks() {
				return Options.valueOf(Knobs.RestartAtFailedValidationsOrDeadlocks);
			}

			@Override
			boolean evictCleanLineFirst() {
				return Options.valueOf(Knobs.EvictCleanLineFirst);
			}

			@Override
			boolean setWriteBitsInL2() {
				return Options.valueOf(Knobs.SetWriteBitsInL2);
			}

			@Override
			boolean BackupDeferredWritebacksLasily() {
				return Options.valueOf(Knobs.BackupDeferredWritebacksLasily);
			}

			@Override
			boolean FalseRestart() {
				return Options.valueOf(Knobs.FalseRestart);
			}

			@Override
			int pausingTimeout() {
				return Options.valueOf(Knobs.PausingTimeout);
			}
		};

		Machine<MESILine> sim = new Machine<MESILine>(p);
		if (p.lockstep()) {
			sim.openPerThreadFifos(bench);
		}
		if (p.conflictExceptions()) {
			sim.initializeEpochs();
		}

		String prix;
		if (sim.params.restartAtFailedValidationsOrDeadlocks() || sim.params.FalseRestart()) {
			pos = new int[numProcessors()];
			for (int i = 0; i < numProcessors(); i++) {
				// eventsBuffer.add(new ArrayList<Event>(initCapacity));
				eventsBuffer.add(new ArrayList<Event>());
				pos[i] = 0;
			}
			if (sim.params.evictCleanLineFirst())
				prix = "[cesim-restart] ";
			else
				prix = "[cesim-restartunopt] ";
			// prix += "[" + sim.params.l2config().assoc + "] ";
			if (sim.params.FalseRestart() && !sim.params.restartAtFailedValidationsOrDeadlocks()) {
				prix += "[F]";
			}

		} else if (p.pauseCoresAtConflicts()) {
			for (int i = 0; i < numProcessors(); i++) {
				eventsBuffer.add(new ArrayList<Event>());
			}
			prix = "[cesim-pausing] ";
		} else if (p.conflictExceptions()) {
			prix = "[cesim] ";
		} else {
			prix = "[mesisim] ";
		}
		System.out.println(prix + "starting simulation...");

		short Cid = 0;
		while (true) {
			try {
				Event e = getNextEvent(in, sim, Cid);
				boolean simulationFinished = handleEvent(e, sim, prix);
				Cid = sim.cpuOfTid(e.tid).get();
				if (!sim.params.restartAtFailedValidationsOrDeadlocks()) {
					if (sim.processors[Cid].reRunEvent) {
						eventsBuffer.get(Cid).add(0, e); // rerun immediately when resuming from pausing
						// totalPausings++;
						/*
						 * System.out.println(prix + "p" + Cid +
						 * " is pausing and will rerun the current event later on. " + sim.pausingBits);
						 */
						sim.processors[Cid].reRunEvent = false;
					} else if (simulationFinished) {
						break;
					}
				} else {
					if (sim.processors[Cid].restartRegion && sim.processors[Cid].reRunEvent) {
						throw new RuntimeException(prix + "p" + Cid + ": error in pausing or restarting.");
					}
					if (sim.processors[Cid].restartRegion) {
						// restart the current region for the core from the beginning
						pos[Cid] = 0;
						sim.processors[Cid].restartRegion = false;
						totalRegionRestarts++;

						System.out.println(prix + "p" + Cid + " will restart the current region. ");
					} else if (sim.processors[Cid].reRunEvent) {
						pos[Cid]--; // rerun the event
						sim.processors[Cid].reRunEvent = false;
						// totalPausings++;
						/*
						 * System.out.println(prix + "p" + Cid +
						 * " is pausing and will rerun the current event later on. " + sim.pausingBits);
						 */
					} else if (simulationFinished) {
						break;
					} else if (e.isRegionBoundary()) { // successfully finish a region
						// reset the current position indicator. The core's event buffer has been
						// cleared.
						pos[Cid] = 0;
					}
				}
				assert sim.getPausingBits() != ((1L << numProcessors()) - 1) : "At least one core's running."
						+ sim.getPausingBits();
			} catch (EOFException eof) {
				break;
			}
		}

		in.close();

		double mins = (System.currentTimeMillis() - startTime) / (double) (1000 * 60);

		if (p.withPacifistBackends() || p.pauseCoresAtConflicts()) {
			Processor<MESILine>[] processors = sim.getProcs();
			for (int i = 0; i < numProcessors(); i++) {
				if (processors[i].ignoreEvents()) {
					System.out.println(prix + "Non-zero depth counter with P" + i);
				}
			}
		}

		assert (eventsBuffer.size() == 0) : "Non-empty eventBuffer size " + eventsBuffer.size();
		if (p.pauseCoresAtConflicts())
			for (int i = 0; i < numProcessors(); i++) {
				if (eventsBuffer.get(i).size() != 0) {
					System.out
							.println(prix + "Non-empty eventBuffer size " + eventsBuffer.get(i).size() + " for p" + i);
					System.out.println(
							sim.processors[i].getPausedCores() + " p" + i + " " + sim.processors[i].reRunEvent);
				}
			}

		if (p.lockstep()) {
			sim.closePerThreadFifos(); // Close per-thread fifos
		}
		if (p.isHttpd() && p.conflictExceptions()) {
			computeCounters(sim, prix + "[Exit] ", false);
		}

		if (p.siteTracking() && p.conflictExceptions() && !p.pauseCoresAtConflicts()) {
			printConflicts(sim, prix);
		}

		System.out.println(prix + "exiting...");
		generateStats(mins, sim);
		System.err.println(prix + "finished");

	} // end main()

	private static void printConflicts(Machine<MESILine> sim, String prex) {
		System.out.println("====================================================================================");
		System.out.println("Total Sites: " + sim.siteInfo.size());
		System.out.println(prex + "Conflicts (source_file_index_number:line_number:routine_index_number): ");
		int dynamicConflicts = 0;
		int staticConflicts = 0;
		for (int i = 0; i < sim.conflicts.size(); i++) {
			Conflict conflict = sim.conflicts.get(i);
			if (conflict.getCounter() == 0) { // The conflict was detected outside of ROIs.
				continue;
			}
			if (conflict.lineNumber0 != 0 && conflict.lineNumber1 != 0) {
				dynamicConflicts += conflict.getCounter();
				staticConflicts++;
				System.out.println("\t\t " + conflict);
			} else {
				System.out.println("\t\t\t " + conflict);
			}
		}
		System.out.println("\t\t Static " + staticConflicts + " and dynamic " + dynamicConflicts
				+ " in total (excluding those detected in lib functions).");
		System.out.println(
				"\t*Please see [benchmark].filenames and [benchmark].rtnnames under the Pintool directory for source file paths and routine names.");
		System.out.println("====================================================================================");
	}

	private static Event getNextEvent(DataInputStream in, Machine<MESILine> sim, short lastCid) throws IOException {
		boolean needPause = sim.params.pauseCoresAtConflicts();
		long currentPausingBits = sim.getPausingBits();
		if (needPause) {
			if (sim.params.restartAtFailedValidationsOrDeadlocks()) {
				List<Event> EB;
				for (short i = 0; i < eventsBuffer.size(); i++) {
					// fair round-robin
					// TODO this may introduce exec changes compared to pause config even without
					// actual region restart
					if (i == lastCid)
						continue;
					EB = eventsBuffer.get(i);
					if (pos[i] < EB.size() && (currentPausingBits & (1L << i)) == 0L) {
						// Not paused, restarting or rerun
						// System.out.println("return events from EB1" + pos[i] + " " + EB.size());
						return EB.get(pos[i]++);
					}
				}
				EB = eventsBuffer.get(lastCid);
				if (pos[lastCid] < EB.size() && (currentPausingBits & (1L << lastCid)) == 0L) {
					// Not paused, restarting or rerun
					// System.out.println("return events from EB2" + pos[lastCid] + " " +
					// EB.size());
					return EB.get(pos[lastCid]++);
				}
			} else {
				for (short i = 0; i < eventsBuffer.size(); i++) {
					List<Event> EB = eventsBuffer.get(i);
					// this core is not paused
					if ((currentPausingBits & (1L << i)) == 0L && !EB.isEmpty()) {
						Event e = EB.get(0);
						EB.remove(0);
						// System.out.println("[visersim] Event from eventsBuffer. Type: " + e.type + ",
						// Tid: " +
						// e.tid);
						return e;
					}
				}
			}
		}
		do {
			byte type = in.readByte();
			byte semantics = in.readByte();
			byte tid = (byte) in.readShort();
			Event e = new Event(EventType.fromByte(type), EventType.fromByte(semantics), tid);
			e.addr = in.readLong();
			e.memOpSize = (byte) in.readInt();
			byte bits = in.readByte();
			e.stackRef = (bits & 0x1) == 1;
			e.value = in.readLong();
			e.insnCount = in.readInt();

			// site info
			short lineno = in.readShort();
			short fno = in.readShort();
			short rno = in.readShort();
			in.readInt(); // eventID
			short lastLineno = in.readShort();
			short lastFno = in.readShort();
			/*
			 * if (lineno == 1120 && e.addr == 6321000 && tid == 3 && e.type ==
			 * EventType.MEMORY_READ) { System.out.println(++count + ", T3 read 1120 id: " +
			 * iid); } else if (lineno == 1111 && e.addr == 6321000 && tid == 2 && e.type ==
			 * EventType.MEMORY_WRITE) { System.out.println("T2 write 1111 id: " + iid); }
			 */
			if (sim.params.siteTracking()) {
				SiteInfoEntry siEntry = new SiteInfoEntry(fno, lineno, rno);
				int index = sim.siteInfo.indexOf(siEntry);
				if (index == -1) {
					sim.siteInfo.add(siEntry);
					index = sim.siteInfo.size() - 1;
				}
				e.siteIndex = index;

				// last site info
				siEntry = new SiteInfoEntry(lastFno, lastLineno, (short) 0);
				index = sim.siteInfo.indexOf(siEntry);
				if (index == -1) {
					sim.siteInfo.add(siEntry);
					index = sim.siteInfo.size() - 1;
				}
				// e.lastSiteIndex = index;
			} else {
				e.siteIndex = -1;
				// e.lastSiteIndex = -1;
			}

			totalEvents++;
			short pid = sim.cpuOfTid(e.tid).get();

			// if (e.tid.get() > 0)
			// System.out.println(e);

			// if (totalEvents % 1000000 == 0)
			// System.out.println("totalEvents: " + totalEvents);

			boolean debug = false;
			if (debug && totalEvents >= 1355800) {
				System.out.println(totalEvents);
				System.out.println("Event type:" + EventType.fromByte(type)/* + " Byte:" + by */);
				System.out.println("Semantics:" + EventType.fromByte(semantics));
				System.out.println("Tid:" + tid);
				System.out.println("Addr:" + e.addr);
				System.out.println("Mem op size:" + e.memOpSize);
				System.out.println("Stack ref:" + e.stackRef);
				System.out.println("Value:" + e.value);
				System.out.println("Insn count:" + e.insnCount);
				System.out.println("SiteIndex:" + e.siteIndex);
				System.out.println();
			}

			if (!needPause || (currentPausingBits & (1L << pid)) == 0L) { // The core is not paused.
				// System.out.println(currentPausingBits + " getEvent from trace files. Type:" +
				// e.type + ", Pid:" +
				// pid);
				if (sim.params.restartAtFailedValidationsOrDeadlocks() && (!sim.params.isHttpd()
						&& (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
						|| MESISim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI) && !e.isLockAccess()) {
					// all events of the current regions should be saved in case of restart
					eventsBuffer.get(pid).add(e);
					pos[pid]++;
				}
				return e;
			}
			// The core is paused.
			eventsBuffer.get(pid).add(e);
		} while (true);
	}

	/**
	 * Dispatch the given event to the simulator code.
	 *
	 * @param e the next event from the front-end
	 * @return true when the simulation is finished, false otherwise
	 */
	private static boolean handleEvent(final Event e, Machine<MESILine> machine, String prefix) {
		CpuId cpuid = machine.cpuOfTid(e.tid);
		int cid = cpuid.get();

		switch (e.type) {
		case ROI_START: {
			assert phase == PARSEC_PHASE.PRE_ROI;
			phase = PARSEC_PHASE.IN_ROI;
			break;
		}

		case ROI_END: {
			assert phase == PARSEC_PHASE.IN_ROI;
			phase = PARSEC_PHASE.POST_ROI;
			break;
		}

		case MEMORY_ALLOC:
		case MEMORY_FREE:
		case THREAD_BLOCKED:
		case THREAD_UNBLOCKED: {
			assert false : "Impossible event type.";
		}

		case THREAD_JOIN:
		case THREAD_SPAWN: { // Called from the parent thread
			if (machine.params.restartAtFailedValidationsOrDeadlocks()) {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			} else {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics);
			}
			break;
		}

		case THREAD_START: { // Called from the child thread
			currentLiveThreads++;
			numSpawnedThreads++;
			maxLiveThreads = Math.max(maxLiveThreads, currentLiveThreads);
			if (machine.params.restartAtFailedValidationsOrDeadlocks()) {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			} else {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics);
			}
			break;
		}

		case THREAD_FINISH: { // Called from the child thread
			currentLiveThreads--;
			if (machine.params.restartAtFailedValidationsOrDeadlocks()) {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			} else {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics);
			}
			// when main thread exits, tear down simulation
			if (e.tid.get() == 0) {
				return true;
			}
			break;
		}

		case MEMORY_READ: {
			machine.cacheRead(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.MEMORY_READ);
			break;
		}

		case MEMORY_WRITE: {
			machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.MEMORY_WRITE);
			break;
		}

		case BASIC_BLOCK: {
			insnsExecuted += e.insnCount;
			machine.insnsExecuted(cpuid, e.insnCount);
			basicBlockEvents++;
			break;
		}

		case LOCK_ACQUIRE:
		case LOCK_RELEASE: {
			if (machine.params.restartAtFailedValidationsOrDeadlocks()) {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			} else {
				machine.processSyncOp(cpuid, e.tid, e.type, e.semantics);
			}
			break;
		}

		case ATOMIC_READ: {
			if (machine.params.treatAtomicUpdatesAsRegularAccesses()) {
				machine.cacheRead(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.MEMORY_READ);
			} else {
				machine.cacheRead(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.ATOMIC_READ);
			}
			break;
		}

		case ATOMIC_WRITE: {
			if (machine.params.treatAtomicUpdatesAsRegularAccesses()) {
				machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.MEMORY_WRITE);
			} else {
				machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.ATOMIC_WRITE);
			}
			break;
		}

		case LOCK_ACQ_READ: {
			machine.cacheRead(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.LOCK_ACQ_READ);
			break;
		}

		case LOCK_ACQ_WRITE: {
			machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.LOCK_ACQ_WRITE);
			break;
		}

		case LOCK_REL_WRITE: {
			machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.siteIndex, MemoryAccessType.LOCK_REL_WRITE);
			break;
		}

		case INVALID_EVENT: {
			System.out.println("Invalid event found: ");
			System.out.println(e);
			break;
		}

		case CHECK_POINT: {
			break;
		}
		case TRANS_START: {
			machine.processors[cpuid.get()].setTransactionStart();
			break;
		}

		case TRANS_END: {
			machine.processors[cpuid.get()].inTrans = false;
			break;
		}
		case SERVER_ROI_START: {
			if (phase == PARSEC_PHASE.PRE_ROI) {
				phase = PARSEC_PHASE.IN_SERVER_ROI;
				// printConflicts(machine, prefix + "[mid] ");
				if (machine.params.conflictExceptions()) {
					computeCounters(machine, prefix + "[SERVER_ROI_START] ", true);
				}
			}
			break;
		}

		case SERVER_ROI_END: {
			if (phase == PARSEC_PHASE.IN_SERVER_ROI) {
				phase = PARSEC_PHASE.POST_SERVER_ROI;
				if (machine.params.conflictExceptions()) {
					computeCounters(machine, prefix + "[SERVER_ROI_END] ", false);
				}
			}
			break;
		}

		default: {
			throw new RuntimeException("Impossible event type:\n" + e);
		}
		}
		totalEvents++;
		if (e.stackRef) {
			stackAccesses++;
		}
		return false; // not done processing events yet

	} // end handleEvent()

	private static void computeCounters(Machine<MESILine> sim, String prex, boolean isMid) {
		double maxCycles = 0;
		short pid = -1;
		for (Processor<MESILine> pc : sim.processors) {

			double cycles = pc.stats.pc_BandwidthDrivenCycleCount.stat;
			if (cycles > maxCycles) {
				pid = pc.id.get();
				maxCycles = cycles;
			}
		}

		if (sim.params.isHttpd()) {
			if (isMid) { // get startup costs for server programs
				SERVER_STARTUP_BWDRIVEN_CYCLES = maxCycles;
				System.out.println(
						prex + "SERVER_STARTUP_BWDRIVEN_CYCLES (P" + pid + "): " + SERVER_STARTUP_BWDRIVEN_CYCLES);
			} else {
				System.out.println(prex + "cycles (P" + pid + "): " + maxCycles);
			}
		}
	}

	private static void generateStats(double simRuntimeMins, Machine<MESILine> machine) throws IOException {
		if (machine.params.conflictExceptions()) {
			populateRestartBWDrivenCycleCount(machine);
		}

		// each stat is dumped as a Python dictionary object

		StringWriter prefix = new StringWriter();
		prefix.write("{'MESIStat':True, ");
		Knobs.dumpRegisteredParams(prefix);

		String suffix = "}" + System.getProperty("line.separator");

		// Viser: Overwrite files
		String statsFilename = Options.valueOf(Knobs.StatsFile);
		File f = new File(statsFilename);
		// // check for filename collisions and rename around them
		// while (f.exists()) {
		// statsFilename += ".1";
		// f = new File(statsFilename);
		// }
		BufferedWriter statsFd = new BufferedWriter(new FileWriter(f));

		// dump stats from the caches
		machine.dumpStats(statsFd, prefix.toString(), suffix);

		DecimalFormat fmt = new DecimalFormat("0.000");

		double denom = MaxCounter.globalCounters.get("pc_ExecutionDrivenCycleCount").get();

		double value = DependentCounter.globalCounters.get("pc_MESIMemSystemExecDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioMESIMemSystemExecDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_MESICoherenceExecDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioMESICoherenceExecDrivenCycleCount': " + fmt.format(value) + suffix);

		denom = MaxCounter.globalCounters.get("pc_BandwidthDrivenCycleCount").get();

		// dump "global" stats

		statsFd.write(
				prefix.toString() + "'SimulationRunningTimeMins': " + String.format("%.2f", simRuntimeMins) + suffix);
		statsFd.write(
				prefix.toString() + "'ScavengeRunningTimeMins': " + String.format("%.2f", totalScavengeTime) + suffix);

		double gigs = Runtime.getRuntime().totalMemory() / (double) (1 << 30);
		String memUsage = "'MemUsageGB': " + String.format("%.2f", gigs);
		statsFd.write(prefix.toString() + memUsage + suffix);

		statsFd.write(prefix.toString() + "'MaxLiveThreads': " + maxLiveThreads + suffix);
		statsFd.write(prefix.toString() + "'NumSpawnedThreads': " + numSpawnedThreads + suffix);
		statsFd.write(prefix.toString() + "'StackAccesses': " + stackAccesses + suffix);
		statsFd.write(prefix.toString() + "'Instructions': " + insnsExecuted + suffix);
		statsFd.write(prefix.toString() + "'TotalEvents': " + totalEvents + suffix);
		statsFd.write(prefix.toString() + "'BasicBlocks': " + basicBlockEvents + suffix);
		double totalMemAccesses = SumCounter.globalCounters.get("pc_TotalMemoryAccesses").get();
		double totalRegionBoundaries = SumCounter.globalCounters.get("pc_RegionBoundaries").get();
		double avgRegSize = totalMemAccesses / totalRegionBoundaries;
		statsFd.write(prefix.toString() + "'AverageRegionSize': " + avgRegSize + suffix);
		statsFd.close();
	}

	private static void populateRestartBWDrivenCycleCount(Machine<MESILine> sim) {
		double rebootCycles = 0;

		// compute total performance/traffic costs of restart
		if (sim.params.isHttpd()) {
			double serverRestarts = 0;
			for (Processor<MESILine> pc : sim.processors) {
				double pc_restarts = pc.stats.pc_TotalReboots.get();
				serverRestarts += pc_restarts;

				// cycles
				pc.stats.pc_ViserRebootBWDrivenCycleCount
						.set(pc.stats.pc_BandwidthDrivenCycleCountForRequestRestart.get());
			}
			rebootCycles = serverRestarts * SERVER_STARTUP_BWDRIVEN_CYCLES;
		} else {
			for (Processor<MESILine> pc : sim.processors) {
				rebootCycles += pc.stats.pc_BandwidthDrivenCycleCountForReboot.get();
			}
		}
		for (Processor<MESILine> pc : sim.processors) {
			pc.stats.pc_ViserRebootBWDrivenCycleCount.incr(rebootCycles, true);

			// cycles
			// we can't incr() the counter since it's outside of ROIs, but "forceIncr" will
			// work.
			pc.stats.pc_BandwidthDrivenCycleCount.incr(pc.stats.pc_ViserRebootBWDrivenCycleCount.get(), true);
		}
	}
}
