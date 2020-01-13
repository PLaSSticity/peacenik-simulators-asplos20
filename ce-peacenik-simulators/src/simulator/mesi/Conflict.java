package simulator.mesi;

import simulator.mesi.MESISim.PARSEC_PHASE;

public class Conflict {
	// site0
	short fileNumber0;
	short lineNumber0;
	short routineNumber0;

	// site1
	short fileNumber1;
	short lineNumber1;
	short routineNumber1;

	// dynamic count
	private double counter;

	// we don't want to count a conflict more than once within a line. use the
	// following
	// flag to control this. Not count when the flag is true.
	private boolean allowCounting = true;

	public Conflict(int f0, int l0, int r0, int f1, int l1, int r1) {
		// put the site with smaller line number first;
		if (l0 <= l1) {
			fileNumber0 = (short) f0;
			lineNumber0 = (short) l0;
			routineNumber0 = (short) r0;
			fileNumber1 = (short) f1;
			lineNumber1 = (short) l1;
			routineNumber1 = (short) r1;
		} else {
			fileNumber0 = (short) f1;
			lineNumber0 = (short) l1;
			routineNumber0 = (short) r1;
			fileNumber1 = (short) f0;
			lineNumber1 = (short) l0;
			routineNumber1 = (short) r0;
		}
		counter = 0;
	}

	public void inc() {
		if (!MESISim.modelOnlyROI() || MESISim.getPARSECPhase() == PARSEC_PHASE.IN_ROI && allowCounting) {
			counter++;
			allowCounting = false;
		}
	}

	public boolean isTheSame(int f0, int l0, int f1, int l1) {
		if (l0 <= l1)
			return fileNumber0 == f0 && lineNumber0 == l0 && fileNumber1 == f1 && lineNumber1 == l1;
		else
			return fileNumber0 == f1 && lineNumber0 == l1 && fileNumber1 == f0 && lineNumber1 == l0;
	}

	public void allowCounting() {
		allowCounting = true;
	}

	public double getCounter() {
		return counter;
	}

	@Override
	public String toString() {
		String res = fileNumber0 + ":" + lineNumber0 + ":" + routineNumber0 + " vs. " + fileNumber1 + ":" + lineNumber1
				+ ":" + routineNumber1 + ". (Dynamic count: " + counter + ")";
		return res;
	}
}
