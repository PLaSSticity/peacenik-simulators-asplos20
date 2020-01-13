package simulator.viser;

import simulator.viser.ViserSim.PARSEC_PHASE;

public class Conflict {
	// site0
	short fileNumber0;
	short lineNumber0;
	short routineNumber0;
	short lastFileNumber0;
	short lastLineNumber0;
	// site1
	short fileNumber1;
	short lineNumber1;
	short routineNumber1;
	short lastFileNumber1;
	short lastLineNumber1;
	// dynamic count
	private double counter;
	// we don't want to count a conflict more than once within a line. use the following
	// flag to control this. Not count when the flag is true.
	private boolean allowCounting = true;

	public Conflict(int f0, int l0, int r0, int f1, int l1, int r1, int lf0, int ll0, int lf1, int ll1) {
		// put the site with smaller line number first;
		if (l0 <= l1) {
			fileNumber0 = (short) f0;
			lineNumber0 = (short) l0;
			routineNumber0 = (short) r0;
			fileNumber1 = (short) f1;
			lineNumber1 = (short) l1;
			routineNumber1 = (short) r1;
			lastFileNumber0 = (short) lf0;
			lastFileNumber1 = (short) lf1;
			lastLineNumber0 = (short) ll0;
			lastLineNumber1 = (short) ll1;
		} else {
			fileNumber0 = (short) f1;
			lineNumber0 = (short) l1;
			routineNumber0 = (short) r1;
			fileNumber1 = (short) f0;
			lineNumber1 = (short) l0;
			routineNumber1 = (short) r0;
			lastFileNumber0 = (short) lf1;
			lastFileNumber1 = (short) lf0;
			lastLineNumber0 = (short) ll1;
			lastLineNumber1 = (short) ll0;
		}
		counter = 0;
	}

	public void inc() {
		if (!ViserSim.modelOnlyROI() || ViserSim.getPARSECPhase() == PARSEC_PHASE.IN_ROI && allowCounting) {
			counter++;
			allowCounting = false;
		}
	}

	public boolean isTheSame(int f0, int l0, int f1, int l1, int lf0, int ll0, int lf1, int ll1) {
		if (l0 != 0 && l1 != 0) {
		if (l0 <= l1)
			return fileNumber0 == f0 && lineNumber0 == l0 && fileNumber1 == f1 && lineNumber1 == l1;
		else
			return fileNumber0 == f1 && lineNumber0 == l1 && fileNumber1 == f0 && lineNumber1 == l0;
		} else {
			if (l0 <= l1)
				return fileNumber0 == f0 && lineNumber0 == l0 && fileNumber1 == f1 && lineNumber1 == l1
						&& lastFileNumber0 == lf0 && lastLineNumber0 == ll0 && lastFileNumber1 == lf1
						&& lastLineNumber1 == ll1;
			else
				return fileNumber0 == f1 && lineNumber0 == l1 && fileNumber1 == f0 && lineNumber1 == l0
						&& lastFileNumber0 == lf1 && lastLineNumber0 == ll1 && lastFileNumber1 == lf0
						&& lastLineNumber1 == ll0;
		}
	}

	public void allowCounting() {
		allowCounting = true;
	}

	public double getCounter() {
		return counter;
	}

	@Override
	public String toString() {
		String res;
		if (lineNumber0 != 0 && lineNumber1 != 0) {
			res = fileNumber0 + ":" + lineNumber0 + ":" + routineNumber0 + " vs. "
				+ fileNumber1 + ":" + lineNumber1 + ":" + routineNumber1 + ". (Dynamic count: " + counter + ")";
		} else {
			res = fileNumber0 + ":" + lineNumber0 + ":" + routineNumber0 + "(callerSite: " + lastFileNumber0 + ":"
					+ lastLineNumber0 + ") vs. " + fileNumber1 + ":" + lineNumber1 + ":" + routineNumber1
					+ "(callerSite: " + lastFileNumber1 + ":" + lastLineNumber1 + "). (Dynamic count: " + counter + ")";
		}
		return res;
	}
}
