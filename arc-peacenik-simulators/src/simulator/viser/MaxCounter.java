package simulator.viser;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class MaxCounter extends Counter {
	CpuId maxId; // Track the CPU which contributes the maximum

	/** List of all the stats that have been created. */
	protected static List<MaxCounter> AllCounters = new LinkedList<MaxCounter>();

	// maps pc counter names => global Counter objects that has the max value
	static Map<String, MaxCounter> globalCounters = new HashMap<String, MaxCounter>();

	public static void dumpCounters(Writer wr, String prefix, String suffix) throws IOException {
		// generate global max counters
		currentCpu = new CpuId(-1);

		for (MaxCounter mc : AllCounters) {
			if (!mc.name.startsWith("pc_")) {
				continue;
			}
			if (globalCounters.containsKey(mc.name)) {
				MaxCounter g = globalCounters.get(mc.name);
				if (mc.get() > g.get()) {
					g.maxId = mc.cpuid;
				}
				g.set(Math.max(g.get(), mc.get()));
			} else {
				MaxCounter g = new MaxCounter(mc.name.replace("pc_", "max_"), true);
				g.set(mc.get());
				g.maxId = mc.cpuid;
				globalCounters.put(mc.name, g);
			}
		}
		currentCpu = null;
		AllCounters.addAll(globalCounters.values());

		// write counter values
		for (MaxCounter mc : AllCounters) {
			wr.write(prefix + "'cpuid': " + mc.cpuid.get() + ", '" + mc.name + "': " + mc.stat + suffix);
		}
	}

	private MaxCounter(String name, boolean _) {
		super(currentCpu, name);
	}

	MaxCounter(String name) {
		super(currentCpu, name);
		AllCounters.add(this);
	}
}
