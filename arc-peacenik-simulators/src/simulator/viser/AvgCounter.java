package simulator.viser;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import simulator.viser.ViserSim.PARSEC_PHASE;

public class AvgCounter extends Counter {
	/** List of all the stats that have been created. */
	protected static List<AvgCounter> AllCounters = new LinkedList<AvgCounter>();

	// maps pc counter names => global Counter objects
	static Map<String, AvgCounter> globalCounters = new HashMap<String, AvgCounter>();

	public static void dumpCounters(Writer wr, String prefix, String suffix) throws IOException {
		// generate global counters
		currentCpu = new CpuId(-1);

		for (AvgCounter c : AllCounters) {
			if (!c.name.startsWith("pc_")) {
				continue;
			}
			if (globalCounters.containsKey(c.name)) {
				AvgCounter g = globalCounters.get(c.name);
				g.set(g.get() + c.get()); // sum
			} else {
				AvgCounter g = new AvgCounter(c.name.replace("pc_", "g_"), true);
				g.set(c.get());
				globalCounters.put(c.name, g);
			}
		}
		currentCpu = null;
		AllCounters.addAll(globalCounters.values());

		// write counter values
		for (AvgCounter c : AllCounters) {
			wr.write(prefix + "'cpuid': " + c.cpuid.get() + ", '" + c.name + "': " + c.stat + suffix);
		}
	}

	int num = 0;

	private AvgCounter(String name, boolean _) {
		super(currentCpu, name);
	}

	@Override
	public void incr(double a) {
		if (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI) {
			stat += a;
			if (a > 0)
				num++;
			else
				System.out.println(name + " incr " + a);
		}
	}

	AvgCounter(String name) {
		super(currentCpu, name);
		AllCounters.add(this);
	}
}
