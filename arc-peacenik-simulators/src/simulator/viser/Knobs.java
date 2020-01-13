package simulator.viser;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedList;
import java.util.List;
import java.util.StringTokenizer;

import joptsimple.OptionParser;
import joptsimple.OptionSpec;

public class Knobs {
	public static final OptionSpec<Boolean> Help;
	public static final OptionSpec<Boolean> Xasserts;
	public static final OptionSpec<Integer> AssertPeriod;
	public static final OptionSpec<String> StatsFile;
	public static final OptionSpec<String> ToSimulatorFifo;

	public static final OptionSpec<Integer> Cores;
	public static final OptionSpec<Integer> PinThreads;

	// caches
	public static final OptionSpec<Integer> LineSize;

	public static final OptionSpec<Integer> L1Size;
	public static final OptionSpec<Integer> L1Assoc;

	public static final OptionSpec<Boolean> UseL2;
	public static final OptionSpec<Integer> L2Size;
	public static final OptionSpec<Integer> L2Assoc;

	public static final OptionSpec<Integer> L3Size;
	public static final OptionSpec<Integer> L3Assoc;

	public static final OptionSpec<String> SimulationMode;
	public static final OptionSpec<Boolean> IgnoreStackRefs;
	public static final OptionSpec<Boolean> RemoteAccessesAffectLRU;
	public static final OptionSpec<Boolean> WritebackInMemory;
	public static final OptionSpec<Boolean> modelOnlyROI;

	public static final OptionSpec<Boolean> AlwaysInvalidateReadOnlyLines;
	public static final OptionSpec<Boolean> InvalidateWrittenLinesOnlyAfterVersionCheck;
	public static final OptionSpec<Boolean> UpdateWrittenLinesDuringVersionCheck;
	public static final OptionSpec<Boolean> InvalidateUntouchedLinesOptimization;
	public static final OptionSpec<Boolean> UseSpecialInvalidState;
	public static final OptionSpec<Boolean> UseBloomFilter;
	public static final OptionSpec<Boolean> UseTwoBloomFuncs;

	public static final OptionSpec<Boolean> UseAIMCache;
	public static final OptionSpec<Integer> NumAIMLines;

	public static final OptionSpec<Boolean> ClearAIMAtRegionBoundaries;

	public static final OptionSpec<Boolean> DeferWritebacks;
	public static final OptionSpec<Boolean> DeferredWritebacksPrecise;

	public static final OptionSpec<Boolean> SkipValidatingReadLines;
	public static final OptionSpec<Boolean> IgnoreFetchingDeferredLinesDuringReadValidation;
	public static final OptionSpec<Boolean> IgnoreFetchingReadBits;
	public static final OptionSpec<Boolean> IgnoreFetchingWriteBits;
	public static final OptionSpec<Boolean> ValidateL1ReadsAlongWithL2;

	public static final OptionSpec<Boolean> Pintool;

	// ARC+ configs.
	public static final OptionSpec<Boolean> PauseCoresAtConflicts;
	public static final OptionSpec<Boolean> SiteTracking;
	public static final OptionSpec<Boolean> RestartAtFailedValidationsOrDeadlocks;
	public static final OptionSpec<Boolean> Lockstep;
	public static final OptionSpec<Boolean> TreatAtomicUpdatesAsRegularAccesses;
	public static final OptionSpec<Boolean> IsHttpd;
	public static final OptionSpec<Boolean> EvictCleanLineFirst;
	public static final OptionSpec<Boolean> UsePLRU;
	public static final OptionSpec<Boolean> SetWriteBitsInL2;
	public static final OptionSpec<Boolean> TreatAtomicUpdatesAsRegionBoundaries;
	public static final OptionSpec<Boolean> BackupDeferredWritebacksLasily;
	public static final OptionSpec<Boolean> FalseRestart;

	public static final OptionSpec<Integer> CheckPointingRate;
	
	public static final OptionParser parser;
	private Knobs() {
	}

	static {
		parser = new OptionParser();
		BooleanParameters = new LinkedList<OptionSpec<Boolean>>();
		StringParameters = new LinkedList<OptionSpec<String>>();
		IntegerParameters = new LinkedList<OptionSpec<Integer>>();
		EnumParameters = new LinkedList<OptionSpec<? extends Enum<?>>>();

		Help = parser.accepts("help", "print this help message").withOptionalArg().ofType(Boolean.class)
				.defaultsTo(false);
		Xasserts = parser.accepts("xasserts", "enable eXpensive assert checks").withOptionalArg().ofType(Boolean.class)
				.defaultsTo(false);
		AssertPeriod = parser.accepts("assert-period", "enable asserts after so many events").withOptionalArg()
				.ofType(Integer.class).defaultsTo(1);
		StatsFile = parser.accepts("stats-file", "stats file to generate").withRequiredArg().defaultsTo("sim-stats.py");
		ToSimulatorFifo = parser.accepts("tosim-fifo", "named fifo used to get events from the front-end")
				.withRequiredArg();
		modelOnlyROI = parser.accepts("model-only-roi", "Whether to only simulate the ROI?").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(true);

		// processor parameters
		Cores = registerInt(parser.accepts("cores", "number of cores to simulate").withRequiredArg()
				.ofType(Integer.class).defaultsTo(1));
		PinThreads = registerInt(parser.accepts("pinThreads", "number of Pin threads").withRequiredArg()
				.ofType(Integer.class).defaultsTo(1));
		// cache parameters
		LineSize = registerInt(parser.accepts("line-size", "Line size for all caches")
				.withOptionalArg().ofType(Integer.class).defaultsTo(64));

		L1Size = registerInt(parser.accepts("l1-size", "Size (in bytes) of each private L1 cache")
				.withOptionalArg().ofType(Integer.class).defaultsTo(1 << 15/* 32KB */));
		L1Assoc = registerInt(parser.accepts("l1-assoc", "Associativity of each private L1 cache")
				.withOptionalArg().ofType(Integer.class).defaultsTo(8));

		UseL2 = registerBool(parser.accepts("use-l2", "Model a private L2 for each core").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(true));
		L2Size = registerInt(parser.accepts("l2-size", "Size (in bytes) of the private L2 cache").withOptionalArg()
				.ofType(Integer.class).defaultsTo(1 << 18/* 256KB */));
		L2Assoc = registerInt(parser.accepts("l2-assoc", "Associativity of the private L2 cache").withOptionalArg()
				.ofType(Integer.class).defaultsTo(8));

		L3Size = registerInt(parser.accepts("l3-size", "Size (in bytes) of the shared L3 cache").withOptionalArg()
				.ofType(Integer.class).defaultsTo(1 << 24/* 16MB */));
		L3Assoc = registerInt(parser.accepts("l3-assoc", "Associativity of the shared L3 cache").withOptionalArg()
				.ofType(Integer.class).defaultsTo(16));

		SimulationMode = registerString(parser.accepts("sim-mode", "Simulation mode to use (one of: baseline, viser).")
				.withRequiredArg().defaultsTo("viser"));

		// optional stuff
		IgnoreStackRefs = registerBool(parser.accepts("ignore-stack", "Assume stack references are thread-private.")
				.withOptionalArg().ofType(Boolean.class).defaultsTo(false));

		RemoteAccessesAffectLRU = registerBool(
				parser.accepts("remote-accesses-affect-lru", "Remote accesses update LRU cache state.")
				.withOptionalArg().ofType(Boolean.class).defaultsTo(false));

		WritebackInMemory = registerBool(parser.accepts("writeback-in-memory", "Writeback metadata in memory")
				.withOptionalArg().ofType(Boolean.class).defaultsTo(false));

		AlwaysInvalidateReadOnlyLines = registerBool(
				parser.accepts("always-invalidate-read-only-lines", "Self invalidate read-only lines").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(true));
		InvalidateWrittenLinesOnlyAfterVersionCheck = registerBool(parser
				.accepts("invalidate-written-lines-only-after-version-check",
						"Self invalidate written lines after a failed version check")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		UpdateWrittenLinesDuringVersionCheck
		= registerBool(parser.accepts("update-written-lines-during-version-check",
				"Update written lines with LLC contents after a failed version check")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));

		InvalidateUntouchedLinesOptimization = registerBool(parser
				.accepts("invalidate-untouched-lines-opt", "Check version of untouched lines before self-invalidating")

				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));

		UseSpecialInvalidState = registerBool(
				parser.accepts("special-invalid-state", "Use special invalid state, subject to version check")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		UseBloomFilter = registerBool(parser.accepts("use-bloom-filter", "Use bloom filter to store updated LLC lines.")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		UseTwoBloomFuncs = registerBool(parser.accepts("use-two-bloom-funcs", "Use two Bloom filter functions.")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(true));

		UseAIMCache = registerBool(parser.accepts("use-aim-cache", "Use AIM cache").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(false));
		NumAIMLines = registerInt(parser.accepts("num-aim-lines", "Num AIM lines (set * assoc)").withOptionalArg()
				.ofType(Integer.class).defaultsTo(1 << 15/* 32K */));

		DeferWritebacks = registerBool(
				parser.accepts("defer-write-backs", "Defer write backs of dirty lines at line granularity")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		DeferredWritebacksPrecise = registerBool(parser
				.accepts("defer-write-backs-precise", "Defer write backs of dirty lines precisely (at byte granularity")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));

		ClearAIMAtRegionBoundaries = registerBool(
				parser.accepts("clear-aim-region-boundaries", "Clear AIM cache at region boundaries").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(false));

		PauseCoresAtConflicts = registerBool(
				parser.accepts("pause-cores-at-conflicts", "Pause corresponding cores if conflicts are detected")
						.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		SkipValidatingReadLines = registerBool(parser
				.accepts("skip-validating-read-lines", "Skip validating read lines if they are not updated in the LLC")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));

		IgnoreFetchingDeferredLinesDuringReadValidation = registerBool(parser
				.accepts("ignore-deferred-lines-read-validation",
						"Incorrectly ignore fetching deferred lines " + "during read validation to see the impact")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		IgnoreFetchingReadBits = registerBool(parser
.accepts("ignore-fetching-read-bits", "Ignore fetching read access bits into private caches")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		IgnoreFetchingWriteBits = registerBool(
				parser.accepts("ignore-fetching-write-bits", "Ignore fetching write access bits into private caches")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		ValidateL1ReadsAlongWithL2 = registerBool(parser
				.accepts("validate-l1-reads-along-with-l2",
						"Validate additional reads from L1 lines along with L2 read validation")
				.withOptionalArg().ofType(Boolean.class).defaultsTo(true));

		Pintool = registerBool(parser.accepts("pintool", "Is the Pintool executing?").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(false));

		SiteTracking = registerBool(parser.accepts("site-tracking", "Track site information for memory accesses.")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		Lockstep = registerBool(parser.accepts("lockstep", "Execute the backend in lockstep with the Pintool")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		RestartAtFailedValidationsOrDeadlocks = registerBool(parser
				.accepts("restart-at-failed-validations-or-deadlocks",
						"Restart the current region of a core if failed validations or deadlocks are detected")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		TreatAtomicUpdatesAsRegularAccesses = registerBool(parser
				.accepts("treat-atomic-updates-as-regular-accesses", "Treat atomic updates as regular memory accesses")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		IsHttpd = registerBool(parser.accepts("is-httpd", "Simulate the Apache Httpd server").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(false));
		EvictCleanLineFirst = registerBool(parser
				.accepts("evict-clean-line-first",
						"Use a cache algorithm that evicts clean lines first instread of LRU")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		UsePLRU = registerBool(parser.accepts("use-plru", "Use the PLRU cache replacement policy").withRequiredArg()
				.ofType(Boolean.class).defaultsTo(false));

		SetWriteBitsInL2 = registerBool(parser.accepts("set-write-bits-in-l2", "Also set write bits in L2 at a write")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		TreatAtomicUpdatesAsRegionBoundaries = registerBool(
				parser.accepts("treat-atomic-updates-as-region-boundaries", "Treat atomic updates as region boundaries")
						.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		BackupDeferredWritebacksLasily = registerBool(
				parser.accepts("backup-deferred-writebacks-lasily", "Backup deferred writebacks lasily")
						.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		FalseRestart = registerBool(parser.accepts("false-restart", "Use false restart to save memory space")
				.withRequiredArg().ofType(Boolean.class).defaultsTo(false));
		
		CheckPointingRate = registerInt(parser.accepts("check-pointing-rate", "number of interations between each two check points").withRequiredArg()
                .ofType(Integer.class).defaultsTo(0));
	}

	/*
	 * Below is the stuff that automatically allows certain flags ("registered"
	 * ones) to appear in the stats output, without any additional effort.
	 */

	private static final List<OptionSpec<Boolean>> BooleanParameters;
	private static final List<OptionSpec<String>> StringParameters;
	private static final List<OptionSpec<Integer>> IntegerParameters;
	private static final List<OptionSpec<? extends Enum<?>>> EnumParameters;

	private static OptionSpec<String> registerString(OptionSpec<String> o) {
		StringParameters.add(o);
		return o;
	}

	private static OptionSpec<Integer> registerInt(OptionSpec<Integer> o) {
		IntegerParameters.add(o);
		return o;
	}

	private static OptionSpec<Boolean> registerBool(OptionSpec<Boolean> o) {
		BooleanParameters.add(o);
		return o;
	}

	@SuppressWarnings("unused")
	private static <E extends Enum<E>> OptionSpec<E> registerEnum(OptionSpec<E> o) {
		EnumParameters.add(o);
		return o;
	}

	private static String format(String flag) {
		// strip brackets
		String noBrackets = flag.replaceAll("\\[", "").replaceAll("\\]", "");

		// tokenize on dashes
		String result = "";
		StringTokenizer tok = new StringTokenizer(noBrackets, "-");
		while (tok.hasMoreTokens()) {
			String t = tok.nextToken();
			// capitalize each token
			result += (t.substring(0, 1).toUpperCase() + t.substring(1));
		}

		return result;
	}

	public static void dumpRegisteredParams(Writer w) throws IOException {
		for (OptionSpec<Boolean> osb : BooleanParameters) {
			String value = ViserSim.Options.valueOf(osb) ? "True" : "False";
			w.write("'" + format(osb.toString()) + "': " + value + ", ");
		}
		for (OptionSpec<String> os : StringParameters) {
			w.write("'" + format(os.toString()) + "': '" + ViserSim.Options.valueOf(os) + "', ");
		}
		for (OptionSpec<? extends Enum<?>> os : EnumParameters) {
			w.write("'" + format(os.toString()) + "': '" + ViserSim.Options.valueOf(os).toString() + "', ");
		}
		for (OptionSpec<Integer> os : IntegerParameters) {
			if (os == Cores) {
				w.write("'" + format(os.toString()) + "': '" + ViserSim.Options.valueOf(os) + "p', ");
			} else if (os == L1Size || os == L2Size || os == L3Size) {
				int kb = ViserSim.Options.valueOf(os) / 1024;
				w.write("'" + format(os.toString()) + "': '" + kb + "KB', ");
			} else {
				w.write("'" + format(os.toString()) + "': " + ViserSim.Options.valueOf(os) + ", ");
			}
		}
	}

}
