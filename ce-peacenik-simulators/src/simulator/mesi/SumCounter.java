package simulator.mesi;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class SumCounter extends Counter {
	/** List of all the stats that have been created. */
	protected static List<SumCounter> AllCounters = new LinkedList<SumCounter>();

	// maps pc counter names => global Counter objects
	static Map<String, SumCounter> globalCounters = new HashMap<String, SumCounter>();

	public static void dumpCounters(Writer wr, String prefix, String suffix) throws IOException {
		// generate global counters
		currentCpu = new CpuId(-1);

		for (SumCounter c : AllCounters) {
			if (!c.name.startsWith("pc_")) {
				continue;
			}
			if (globalCounters.containsKey(c.name)) {
				SumCounter g = globalCounters.get(c.name);
				g.set(g.get() + c.get()); // sum
			} else {
				SumCounter g = new SumCounter(c.name.replace("pc_", "g_"), true);
				g.set(c.get());
				globalCounters.put(c.name, g);
			}
		}
		currentCpu = null;
		AllCounters.addAll(globalCounters.values());

		// write counter values
		for (SumCounter c : AllCounters) {
			wr.write(prefix + "'cpuid': " + c.cpuid.get() + ", '" + c.name + "': " + c.stat + suffix);
		}
	}

	private SumCounter(String name, boolean _) {
		super(currentCpu, name);
	}

	SumCounter(String name) {
		super(currentCpu, name);
		AllCounters.add(this);
	}
}
