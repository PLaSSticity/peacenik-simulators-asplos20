package simulator.mesi;

import simulator.mesi.MESISim.PARSEC_PHASE;

public abstract class Counter {
	protected CpuId cpuid;
	protected double stat;
	protected String name;

	/**
	 * Each counter is associated with a CPU. New Counters are associated with
	 * whatever this field's value is at the time of their construction. This is a
	 * hack to avoid having to pass a CpuId to the Counter ctor, which breaks direct
	 * initialization of Counter fields (and requires the init to happen in a ctor
	 * instead, duplicating all the Counter field names).
	 */
	public static CpuId currentCpu;

	Counter(CpuId cpu, String n) {
		assert currentCpu != null;
		this.cpuid = currentCpu;
		this.name = n;
	}

	public String name() {
		return name;
	}

	public double get() {
		return stat;
	}

	public void set(double v) {
		stat = v;
	}

	public void incr() {
		if (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI) {
			stat++;
		}
	}

	public void incr(double a) {
		if (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI) {
			stat += a;
		}
	}

	public void incr(double a, boolean forceInc) {
		if (forceInc || !MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI) {
			stat += a;
			if (a < 0) {
				System.out.println(name + " incr " + a);
				System.exit(-177);
			}
		}
	}
}
