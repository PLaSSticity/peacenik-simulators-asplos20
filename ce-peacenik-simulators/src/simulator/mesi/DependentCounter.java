package simulator.mesi;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class DependentCounter extends Counter {
	private Counter baseCounter; // Dependent on the corresponding counter

	/** List of all the stats that have been created. */
	protected static List<DependentCounter> AllCounters = new LinkedList<DependentCounter>();

	// maps pc counter names => global Counter objects
	static Map<String, DependentCounter> globalCounters = new HashMap<String, DependentCounter>();

	public static void dumpCounters(Writer wr, String prefix, String suffix) throws IOException {
		// generate global counters
		currentCpu = new CpuId(-1);

		for (DependentCounter c : AllCounters) {
			if (!c.name.startsWith("pc_")) {
				continue;
			}

			// Get the related global counter object
			MaxCounter g_mc = MaxCounter.globalCounters.get(c.baseCounter.name());
			CpuId maxCpuID = new CpuId(g_mc.maxId.get());
			DependentCounter g = new DependentCounter(c.name.replace("pc_", "dep_"), true);
			for (DependentCounter sc : AllCounters) {
				if (sc.cpuid.equals(maxCpuID) && sc.name().equals(c.name())) {
					g.set(sc.get());
				}
			}
			globalCounters.put(c.name, g);
		}
		currentCpu = null;
		AllCounters.addAll(globalCounters.values());

		// write counter values
		for (DependentCounter dc : AllCounters) {
			wr.write(prefix + "'cpuid': " + dc.cpuid.get() + ", '" + dc.name + "': " + dc.stat + suffix);
		}
	}

	private DependentCounter(String name, boolean _) {
		super(currentCpu, name);
	}

	DependentCounter(String n, Counter base) {
		super(currentCpu, n);
		AllCounters.add(this);
		this.baseCounter = base;
	}
}
