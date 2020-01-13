package simulator.mesi;

import simulator.mesi.MESISim.PARSEC_PHASE;

public class tmpCounter {
	private double stat = 0;

	public tmpCounter() {
	}

	// the counter will be automatically cleared after get()
	public double get() {
		double tmp = stat;
		stat = 0;
		return tmp;
	}

	public void incr(double a) {
		if (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI) {
			stat += a;
		}
	}

}
