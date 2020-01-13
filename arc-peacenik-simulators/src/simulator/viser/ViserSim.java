package simulator.viser;

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
import java.util.HashMap;
import java.util.List;

import joptsimple.OptionSet;
import simulator.viser.Machine.SimulationMode;

public class ViserSim {

	/** enable checking of computationally expensive asserts */
	public static boolean XASSERTS = true;

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
	static long totalEvents = 0;
	private static long basicBlockEvents;

	public static long totalRegionRestarts = 0;
	private static double SERVER_STARTUP_BWDRIVEN_CYCLES = 0;

	public static double totalScavengeTime = 0;

	/** Events buffer for paused cores */
	private static final List<List<Event>> eventsBuffer = new ArrayList<List<Event>>();
	public static int[] pos;

	public static final long debugStart = 640000;
	public static final long debugCurrent = 640643;
	public static final long debugByteAddress = 139812783786240L;
	public static final long debugLineAddress = 139812783786240L;

	public static boolean debugPrint() {
		return totalEvents == debugCurrent;
	}

	// http://docs.oracle.com/javase/7/docs/technotes/guides/language/assert.html
	static boolean assertsEnabled = false;

	static {
		assert assertsEnabled = true; // Intentional side effect!!!
	}

	// These checks are expensive
	public static boolean xassertsEnabled() {
		if (XASSERTS) {
			if ((totalEvents % Options.valueOf(Knobs.AssertPeriod) == 0) && totalEvents > debugStart) {
				return true;
			}
		}
		return false;
	}

	public static boolean modelOnlyROI() {
		return Options.valueOf(Knobs.modelOnlyROI);
	}

	public static void setPARSECPhase(PARSEC_PHASE p) {
		phase = p;
	}

	public static PARSEC_PHASE getPARSECPhase() {
		return phase;
	}

	public static boolean useTwoBloomFuncs() {
		return Options.valueOf(Knobs.UseTwoBloomFuncs);
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
		if (Options.valueOf(Knobs.SimulationMode).equals("baseline")) {
			throw new UnsupportedOperationException("Baseline mode not supported.");
		} else if (Options.valueOf(Knobs.SimulationMode).equals("viser")) {
			simMode = SimulationMode.VISER;
		} else {
			throw new IllegalStateException("Invalid simulation mode: " + Options.valueOf(Knobs.SimulationMode));
		}

		// if (!BitTwiddle.isPowerOf2(numProcessors())) {
		// throw new IllegalArgumentException("Number of cores is not a power of 2.");
		// }

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

		Machine.MachineParams<ViserLine> p = new Machine.MachineParams<ViserLine>() {
			@Override
			SimulationMode simulationMode() {
				return simMode;
			}

			@Override
			int numProcessors() {
				return ViserSim.numProcessors();
			}

			@Override
			int numPinThreads() {
				return Options.valueOf(Knobs.PinThreads);
			}

			@Override
			CacheConfiguration<ViserLine> l1config() {
				// NB: be lazy and return a new object each time; shouldn't
				// affect anything since the
				// values are always the same
				return new CacheConfiguration<ViserLine>() {
					{
						cacheSize = Options.valueOf(Knobs.L1Size);
						lineSize = MemorySystemConstants.LINE_SIZE();
						assoc = Options.valueOf(Knobs.L1Assoc);
						level = CacheLevel.L1;
					}
				};
			}

			@Override
			boolean pintool() {
				return Options.valueOf(Knobs.Pintool);
			}

			@Override
			boolean useL2() {
				return Options.valueOf(Knobs.UseL2);
			}

			@Override
			CacheConfiguration<ViserLine> l2config() {
				return new CacheConfiguration<ViserLine>() {
					{
						cacheSize = Options.valueOf(Knobs.L2Size);
						lineSize = MemorySystemConstants.LINE_SIZE();
						assoc = Options.valueOf(Knobs.L2Assoc);
						level = CacheLevel.L2;
					}
				};
			}

			@Override
			CacheConfiguration<ViserLine> l3config() {
				return new CacheConfiguration<ViserLine>() {
					{
						cacheSize = Options.valueOf(Knobs.L3Size);
						lineSize = MemorySystemConstants.LINE_SIZE();
						assoc = Options.valueOf(Knobs.L3Assoc);
						level = CacheLevel.L3;
					}
				};
			}

			@Override
			LineFactory<ViserLine> lineFactory() {
				return new LineFactory<ViserLine>() {

					@Override
					public ViserLine create(Processor<ViserLine> proc, CacheLevel level) {
						ViserLine line = new ViserLine(proc, level);
						line.setEpoch(proc.id, proc.getCurrentEpoch());
						return line;
					}

					@Override
					public ViserLine create(Processor<ViserLine> proc, CacheLevel level, LineAddress la) {
						ViserLine line = new ViserLine(proc, level, la);
						line.setEpoch(proc.id, proc.getCurrentEpoch());
						return line;
					}

					@Override
					public ViserLine create(Processor<ViserLine> proc, CacheLevel level, ViserLine l) {
						if (!l.valid()) {
							throw new RuntimeException("Source line should be VALID.");
						}
						ViserLine tmp = new ViserLine(proc, level, l.lineAddress());
						tmp.changeStateTo(l.getState());
						tmp.setVersion(l.getVersion());
						tmp.copyAllValues(l);
						tmp.setLastWriters(l.getLastWriters());
						tmp.setLockOwnerID(l.getLockOwnerID());
						// We do not update deferred owner id from here.
						if (level.compareTo(proc.llc()) < 0) { // private line
							CpuId cid = proc.id;
							tmp.orWriteEncoding(cid, l.getWriteEncoding(cid));
							tmp.orReadEncoding(cid, l.getReadEncoding(cid));
							tmp.updateWriteSiteInfo(cid, l.getWriteEncoding(cid), l.getWriteSiteInfo(cid),
									l.getWriteLastSiteInfo(cid));
							tmp.updateReadSiteInfo(cid, l.getReadEncoding(cid), l.getReadSiteInfo(cid),
									l.getReadLastSiteInfo(cid));
							tmp.setEpoch(proc.id, proc.getCurrentEpoch());
						} else {
							for (int i = 0; i < proc.params.numProcessors(); i++) {
								CpuId cpuId = new CpuId(i);
								PerCoreLineMetadata tmpMd = l.getPerCoreMetadata(cpuId);
								// We do not bother with epoch here, since it should be taken care of
								// automatically
								// later
								PerCoreLineMetadata md = new PerCoreLineMetadata(tmpMd.epoch, tmpMd.writeEncoding,
										tmpMd.readEncoding, tmpMd.writeSiteInfo, tmpMd.readSiteInfo,
										tmpMd.writeLastSiteInfo, tmpMd.readLastSiteInfo);
								tmp.setPerCoreMetadata(cpuId, md);
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
			boolean writebackInMemory() {
				return Options.valueOf(Knobs.WritebackInMemory);
			}

			@Override
			boolean alwaysInvalidateReadOnlyLines() {
				return Options.valueOf(Knobs.AlwaysInvalidateReadOnlyLines);
			}

			@Override
			boolean invalidateWrittenLinesOnlyAfterVersionCheck() {
				return Options.valueOf(Knobs.InvalidateWrittenLinesOnlyAfterVersionCheck);
			}

			@Override
			boolean updateWrittenLinesDuringVersionCheck() {
				return Options.valueOf(Knobs.UpdateWrittenLinesDuringVersionCheck);
			}

			@Override
			boolean invalidateUntouchedLinesOptimization() {
				return Options.valueOf(Knobs.InvalidateUntouchedLinesOptimization);
			}

			@Override
			boolean useSpecialInvalidState() {
				return Options.valueOf(Knobs.UseSpecialInvalidState);
			}

			@Override
			boolean useBloomFilter() {
				return Options.valueOf(Knobs.UseBloomFilter);
			}

			@Override
			boolean useAIMCache() {
				return Options.valueOf(Knobs.UseAIMCache);
			}

			@Override
			boolean deferWriteBacks() {
				return Options.valueOf(Knobs.DeferWritebacks);
			}

			@Override
			boolean areDeferredWriteBacksPrecise() {
				return Options.valueOf(Knobs.DeferredWritebacksPrecise);
			}

			@Override
			boolean skipValidatingReadLines() {
				return Options.valueOf(Knobs.SkipValidatingReadLines);
			}

			@Override
			boolean pauseCoresAtConflicts() {
				return Options.valueOf(Knobs.PauseCoresAtConflicts);
			}

			@Override
			boolean ignoreFetchingDeferredLinesDuringReadValidation() {
				return Options.valueOf(Knobs.IgnoreFetchingDeferredLinesDuringReadValidation);
			}

			@Override
			boolean clearAIMCacheAtRegionBoundaries() {
				return Options.valueOf(Knobs.ClearAIMAtRegionBoundaries);
			}

			@Override
			boolean ignoreFetchingReadBits() {
				return Options.valueOf(Knobs.IgnoreFetchingReadBits);
			}

			@Override
			boolean validateL1ReadsAlongWithL2() {
				return Options.valueOf(Knobs.ValidateL1ReadsAlongWithL2);
			}

			@Override
			boolean lockstep() {
				return Options.valueOf(Knobs.Lockstep);
			}

			@Override
			boolean siteTracking() {
				return Options.valueOf(Knobs.SiteTracking);
				// && Options.valueOf(Knobs.RestartAtFailedValidationsOrDeadlocks);
			}

			@Override
			boolean restartAtFailedValidationsOrDeadlocks() {
				return Options.valueOf(Knobs.RestartAtFailedValidationsOrDeadlocks);
			}

			@Override
			boolean treatAtomicUpdatesAsRegularAccesses() {
				return Options.valueOf(Knobs.TreatAtomicUpdatesAsRegularAccesses);
			}

			@Override
			boolean ignoreFetchingWriteBits() {
				return Options.valueOf(Knobs.IgnoreFetchingWriteBits);
			}

			@Override
			boolean printConflictingSites() {
				return false;
				// return Options.valueOf(Knobs.RestartAtFailedValidationsOrDeadlocks);
			}

			@Override
			boolean isHttpd() {
				return Options.valueOf(Knobs.IsHttpd);
			}

			@Override
			boolean evictCleanLineFirst() {
				return Options.valueOf(Knobs.EvictCleanLineFirst);
			}

			@Override
			boolean usePLRU() {
				return Options.valueOf(Knobs.UsePLRU);
			}

			@Override
			boolean setWriteBitsInL2() {
				return Options.valueOf(Knobs.SetWriteBitsInL2);
			}

			@Override
			boolean treatAtomicUpdatesAsRegionBoundaries() {
				return Options.valueOf(Knobs.TreatAtomicUpdatesAsRegionBoundaries);
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
			int checkPointingRate() {
				return Options.valueOf(Knobs.CheckPointingRate);
			}

		};

		Machine<ViserLine> sim = new Machine<ViserLine>(p);
		if (sim.params.lockstep()) {
			sim.openPerThreadFifos(bench);
		}
		sim.initializeEpochs();
		sim.prepareScavengeMap(p.numProcessors());

		String prix;
		if (sim.params.restartAtFailedValidationsOrDeadlocks() || sim.params.FalseRestart()) {
			pos = new int[numProcessors()];
			for (int i = 0; i < numProcessors(); i++) {
				// eventsBuffer.add(new ArrayList<Event>(initCapacity));
				eventsBuffer.add(new ArrayList<Event>());
				pos[i] = 0;
			}
			if (sim.params.evictCleanLineFirst())
				prix = "[restartoptsim] ";
			else
				prix = "[restartunoptsim] ";
			// prix += "[" + sim.params.l2config().assoc + "] ";
			if (sim.params.FalseRestart() && !sim.params.restartAtFailedValidationsOrDeadlocks()) {
				prix += "[F]";
			}

		} else if (sim.params.pauseCoresAtConflicts()) {
			for (int i = 0; i < numProcessors(); i++) {
				eventsBuffer.add(new ArrayList<Event>());
			}
			prix = "[pausingsim] ";
		} else if (sim.params.treatAtomicUpdatesAsRegularAccesses()) {
			prix = "[visersimAtomicAsRegular] ";
		} else if (sim.params.treatAtomicUpdatesAsRegionBoundaries()) {
			prix = "[visersimAtomicAsBoundary] ";
		} else if (sim.params.usePLRU()) {
			if (sim.params.evictCleanLineFirst()) {
				prix = "[visersimModPLRU] ";
			} else
				prix = "[visersimRegPLRU] ";
		} else {
			prix = "[visersim] ";
		}

		System.out.println(prix + "starting simulation...");

		/*
		 * // Test detectDeadlock() short[] father = new short[numProcessors()]; short[]
		 * ends = new short[2]; sim.processors[0].setPausedCores((short) 1);
		 * sim.processors[1].setPausedCores((short) 2);
		 * sim.processors[2].setPausedCores((short) 3);
		 * sim.processors[3].setPausedCores((short) 1); if (sim.detectDeadlock(father,
		 * ends)) { short tmp = ends[0]; System.out.print("[viserpuasesim] Deadlock: ");
		 * while (tmp != ends[1]) { System.out.print(tmp + " -> "); tmp = father[tmp]; }
		 * System.out.println(tmp); System.out.println(); return; }
		 */

		short Cid = 0;
		while (true) {
			try {
				Event e = getNextEvent(in, sim, Cid);
				boolean simulationFinished = handleEvent(e, sim, prix);
				Cid = sim.cpuOfTid(e.tid).get();
				if (!sim.params.restartAtFailedValidationsOrDeadlocks()) {
					// if ((sim.getPausingBits() & (1L << Cid)) != 0) {
					if (sim.processors[Cid].reRunEvent) {
						eventsBuffer.get(Cid).add(0, e); // rerun immediately when resuming from pausing
						// totalPausings++;
						/*
						 * System.out.println(prix + "p" + Cid +
						 * " is pausing and will rerun the current event later on. " + sim.pausingBits);
						 */
						sim.processors[Cid].reRunEvent = false;
					} else if (simulationFinished)
						break;
				} else {
					if (sim.processors[Cid].restartRegion && sim.processors[Cid].reRunEvent) {
						System.out.println(prix + "p" + Cid + ": error in pausing or restarting.");
						System.exit(-1);
					}
					if (sim.processors[Cid].restartRegion) {
						// restart the current region for the core from the beginning
						pos[Cid] = 0;
						sim.processors[Cid].restartRegion = false;
						totalRegionRestarts++;
						/*
						 * System.out.println(prix + "p" + Cid + " will restart the current region. ");
						 */
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
				assert sim.getPausingBits() != ((1L << numProcessors()) - 1) : "All cores are paused! "
						+ sim.getPausingBits();
			} catch (EOFException eof) {
				break;
			}
		}

		in.close();
		double mins = (System.currentTimeMillis() - startTime) / (double) (1000 * 60);

		for (int i = 0; i < numProcessors(); i++) {
			if (sim.processors[i].ignoreEvents()) {
				System.out.println(prix + "Non-zero depth counter with P" + i);
			}
		}

		assert (eventsBuffer.size() == 0) : "Non-empty eventBuffer size " + eventsBuffer.size();
		if (sim.params.pauseCoresAtConflicts())
			for (int i = 0; i < numProcessors(); i++) {
				if (eventsBuffer.get(i).size() != 0) {
					System.out
							.println(prix + "Non-empty eventBuffer size " + eventsBuffer.get(i).size() + " for P" + i);
					System.out.println(sim.processors[i].getPausedCores() + " P" + i);
				}
			}

		if (sim.params.lockstep()) {
			sim.closePerThreadFifos(); // Close per-thread fifos
		}
		if (sim.params.isHttpd()) {
			computeCounters(sim, prix + "[Exit] ", false);
		}

		if (sim.params.siteTracking() && !sim.params.pauseCoresAtConflicts() && sim.params.checkPointingRate() == 0) {
			printConflicts(sim, prix);
		}
		/*
		 * if (sim.params.reportSites()) { System.out.println("File coverage: "); int
		 * total1 = 0; int total2 = 0; int size1 = 0; int size2 = 0; for (int i = 0; i <
		 * sim.srcCoverage.length; i++) if (sim.srcCoverage[i] != 0) { size1++; total1
		 * += sim.srcCoverage[i]; System.out.print(i + ":" + sim.srcCoverage[i] + " ");
		 * } System.out.println("\nTotal occurences for files: " + total1 +
		 * ", file number: " + size1); System.out.println("\nRTN coverage: "); for (int
		 * i = 0; i < sim.rtnCoverage.length; i++) if (sim.rtnCoverage[i] != 0) {
		 * size2++; total2 += sim.rtnCoverage[i]; System.out.print(i + ":" +
		 * sim.rtnCoverage[i] + " "); }
		 * System.out.println("\nTotal occurences for RTNs: " + total2 +
		 * ", RTN number: " + size2); System.out.println("\nTotal conflicts: " + sum);
		 * System.out.println("Total failed RVs: " + frv);
		 * System.out.println("Total regions: " + regions); //
		 * System.out.println("No srcinfo: " + noinfo); //
		 * System.out.println("No funcinfo: " + nofuncinfo); }
		 */
		generateStats(mins, sim);
		System.err.println(prix + "finished");
	} // end main()

	private static void printConflicts(Machine<ViserLine> sim, String prex) {
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

	private static void computeCounters(Machine<ViserLine> sim, String prex, boolean isMid) {
		double maxCycles = 0;
		short pid = -1;
		for (Processor<ViserLine> pc : sim.processors) {
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

	private static Event getNextEvent(DataInputStream in, Machine<ViserLine> sim, short lastCid) throws IOException {
		boolean needPause = sim.params.pauseCoresAtConflicts();
		long currentPausingBits = sim.getPausingBits();
		if (needPause) {
			if (sim.params.restartAtFailedValidationsOrDeadlocks()) {
				List<Event> EB;
				for (short i = 0; i < eventsBuffer.size(); i++) {
					// fair round-robin
					if (i == lastCid)
						continue;
					EB = eventsBuffer.get(i);
					if (pos[i] < EB.size() && (currentPausingBits & (1L << i)) == 0L) {
						// Not paused, restarting or rerun
						return EB.get(pos[i]++);
					}
				}
				EB = eventsBuffer.get(lastCid);
				if (pos[lastCid] < EB.size() && (currentPausingBits & (1L << lastCid)) == 0L) {
					// Not paused, restarting or rerun
					return EB.get(pos[lastCid]++);
				}
			} else
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
				e.lastSiteIndex = index;
			} else {
				e.siteIndex = -1;
				e.lastSiteIndex = -1;
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
						&& (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI)
						|| ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_SERVER_ROI) && !e.isLockAccess()) {
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
	private static boolean handleEvent(final Event e, Machine<ViserLine> machine, String prefix) {
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
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			} else
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics);
			break;
		}

		case THREAD_START: { // Called from the child thread
			currentLiveThreads++;
			numSpawnedThreads++;
			maxLiveThreads = Math.max(maxLiveThreads, currentLiveThreads);
			if (machine.params.restartAtFailedValidationsOrDeadlocks())
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			else
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics);
			break;
		}

		case THREAD_FINISH: { // Called from the child thread
			currentLiveThreads--;
			if (machine.params.restartAtFailedValidationsOrDeadlocks())
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			else
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics); // when main thread exits, tear
																					// down simulation
			if (e.tid.get() == 0) {
				return true;
			}
			break;
		}

		case MEMORY_READ: {
			machine.cacheRead(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
					MemoryAccessType.MEMORY_READ);
			break;
		}

		case MEMORY_WRITE: {
			machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
					MemoryAccessType.MEMORY_WRITE);
			break;
		}

		case BASIC_BLOCK: {
			insnsExecuted += e.insnCount;
			machine.insnsExecuted(cpuid, e.insnCount);
			basicBlockEvents++;
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

		case LOCK_ACQUIRE:
		case LOCK_RELEASE: {
			if (machine.params.restartAtFailedValidationsOrDeadlocks())
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics, eventsBuffer.get(cid), pos[cid]);
			else
				machine.processRegionBoundary(cpuid, e.tid, e.type, e.semantics);
			break;
		}

		case SERVER_ROI_START: {
			if (phase == PARSEC_PHASE.PRE_ROI) {
				phase = PARSEC_PHASE.IN_SERVER_ROI;
				// printConflicts(machine, prefix + "[mid] ");
				computeCounters(machine, prefix + "[SERVER_ROI_START] ", true);
			}
			break;
		}

		case SERVER_ROI_END: {
			if (phase == PARSEC_PHASE.IN_SERVER_ROI) {
				phase = PARSEC_PHASE.POST_SERVER_ROI;
				computeCounters(machine, prefix + "[SERVER_ROI_END] ", false);
			}
			break;
		}

		case ATOMIC_READ: {
			if (machine.params.treatAtomicUpdatesAsRegularAccesses()) {
				machine.cacheRead(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
						MemoryAccessType.MEMORY_READ);
			} else {
				machine.cacheRead(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
						MemoryAccessType.ATOMIC_READ);
			}
			break;
		}

		case ATOMIC_WRITE: {
			if (machine.params.treatAtomicUpdatesAsRegularAccesses()) {
				machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
						MemoryAccessType.MEMORY_WRITE);
			} else {
				machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
						MemoryAccessType.ATOMIC_WRITE);
			}
			break;
		}

		case LOCK_ACQ_READ: {
			machine.cacheRead(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
					MemoryAccessType.LOCK_ACQ_READ);
			break;
		}

		case LOCK_ACQ_WRITE: {
			machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
					MemoryAccessType.LOCK_ACQ_WRITE);
			break;
		}

		case LOCK_REL_WRITE: {
			machine.cacheWrite(cpuid, e.addr, e.memOpSize, e.value, e.tid, e.siteIndex, e.lastSiteIndex,
					MemoryAccessType.LOCK_REL_WRITE);
			break;
		}

		case CHECK_POINT: {
			machine.processors[cpuid.get()].setCheckPoint();
			break;
		}

		case INVALID_EVENT: {
			throw new RuntimeException("Invalid event type:\n" + e);
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

	private static void generateStats(double simRuntimeMins, Machine<ViserLine> machine) throws IOException {
		System.out.println("[visersim] exiting...");

		populateRestartBWDrivenCycleCount(machine);

		// each stat is dumped as a Python dictionary object

		StringWriter prefix = new StringWriter();
		prefix.write("{'ViserStat':True, ");
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

		double denom = MaxCounter.globalCounters.get("pc_BandwidthDrivenCycleCount").get();
		double value = DependentCounter.globalCounters.get("pc_ViserRegExecBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserRegExecBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserPreCommitBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserPreCommitBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserReadValidationBWDrivenCycleCount").get() / denom;
		statsFd.write(
				prefix.toString() + "'ratioViserReadValidationBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserPostCommitBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserPostCommitBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserNormalExecBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserNormalExecBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserPauseBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserPauseBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserRegionRestartBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserRegionRestartBWDrivenCycleCount': " + fmt.format(value) + suffix);
		value = DependentCounter.globalCounters.get("pc_ViserRebootBWDrivenCycleCount").get() / denom;
		statsFd.write(prefix.toString() + "'ratioViserRebootBWDrivenCycleCount': " + fmt.format(value) + suffix);

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

		// Compute ratio of the number of memory write backs to the number of cycles
		fmt = new DecimalFormat("0.000000");
		value = SumCounter.globalCounters.get("pc_ViserLLCToMemoryMetadataWriteback").get() / denom;
		denom = MaxCounter.globalCounters.get("pc_BandwidthDrivenCycleCount").get();
		statsFd.write(prefix.toString() + "'ratioViserLLCToMemoryMetadataWritebackBandwidthCycles': "
				+ fmt.format(value) + suffix);

		// Compute global histograms
		statsFd.write(
				"# Histogram hgramLLCUpdatesInARegion description: 0 -- 0, 1 -- 1-10, 2 -- 11-20, 3 -- 21-30, 4 -- 31-40, 5 -- >=41\n");
		HashMap<Integer, Integer> global = new HashMap<Integer, Integer>();
		for (Processor<ViserLine> p : machine.processors) {
			for (Integer key : p.stats.hgramLLCUpdatesInARegion.keySet()) {
				Integer val = p.stats.hgramLLCUpdatesInARegion.get(key);
				if (val != null) {
					Integer tmp = global.get(key);
					if (tmp == null) {
						tmp = Integer.valueOf(val);
					} else {
						tmp += val;
					}
					global.put(key, tmp);
				}
			}
		}
		for (Integer key : global.keySet()) {
			statsFd.write(prefix.toString() + "'histogramKey" + key + "': " + fmt.format(global.get(key)) + suffix);
		}

		statsFd.write(
				"# Histogram hgramLinesValidated description: 0 -- 0, 1 -- 1-10, 2 -- 11-20, 3 -- 21-30, 4 -- 31-40, 5 -- >=41\n");
		global = new HashMap<Integer, Integer>();
		for (Processor<ViserLine> p : machine.processors) {
			for (Integer key : p.stats.hgramLinesValidated.keySet()) {
				Integer val = p.stats.hgramLinesValidated.get(key);
				if (val != null) {
					Integer tmp = global.get(key);
					if (tmp == null) {
						tmp = Integer.valueOf(val);
					} else {
						tmp += val;
					}
					global.put(key, tmp);
				}
			}
		}
		for (Integer key : global.keySet()) {
			statsFd.write(prefix.toString() + "'histogramKey" + key + "': " + fmt.format(global.get(key)) + suffix);
		}

		statsFd.write(
				"# Histogram hgramVersionSizes description: 0 -- <= 8 bits, 1 -- <= 9-16 bits, 2 -- <= 17-24 bits, 3 -- <= 25-32 bits \n");
		global = new HashMap<Integer, Integer>();
		for (Processor<ViserLine> p : machine.processors) {
			for (Integer key : p.stats.hgramVersionSizes.keySet()) {
				Integer val = p.stats.hgramVersionSizes.get(key);
				if (val != null) {
					Integer tmp = global.get(key);
					if (tmp == null) {
						tmp = Integer.valueOf(val);
					} else {
						tmp += val;
					}
					global.put(key, tmp);
				}
			}
		}
		for (Integer key : global.keySet()) {
			statsFd.write(prefix.toString() + "'histogramKey" + key + "': " + fmt.format(global.get(key)) + suffix);
		}

		statsFd.close();
	}

	private static void populateRestartBWDrivenCycleCount(Machine<ViserLine> sim) {
		double rebootCycles = 0;

		// compute total performance/traffic costs of restart
		if (sim.params.isHttpd()) {
			double serverRestarts = 0;
			for (Processor<ViserLine> pc : sim.processors) {
				double pc_restarts = pc.stats.pc_TotalReboots.get();
				serverRestarts += pc_restarts;

				// cycles
				pc.stats.pc_ViserRebootBWDrivenCycleCount
						.set(pc.stats.pc_BandwidthDrivenCycleCountForRequestRestart.get());
			}
			rebootCycles = serverRestarts * SERVER_STARTUP_BWDRIVEN_CYCLES;
		} else {
			for (Processor<ViserLine> pc : sim.processors) {
				rebootCycles += pc.stats.pc_BandwidthDrivenCycleCountForReboot.get();
			}
		}
		for (Processor<ViserLine> pc : sim.processors) {
			pc.stats.pc_ViserRebootBWDrivenCycleCount.incr(rebootCycles, true);

			// cycles
			// we can't incr() the counter since it's outside of ROIs, but "forceIncr" will
			// work.
			pc.stats.pc_BandwidthDrivenCycleCount.incr(pc.stats.pc_ViserRebootBWDrivenCycleCount.get(), true);
		}
	}

}
