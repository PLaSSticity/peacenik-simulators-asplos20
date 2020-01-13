package simulator.viser;

import simulator.viser.ViserSim.PARSEC_PHASE;

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
		if (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI) {
			stat += a;
		}
	}

}
