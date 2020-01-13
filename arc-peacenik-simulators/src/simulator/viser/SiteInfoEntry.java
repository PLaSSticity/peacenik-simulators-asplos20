package simulator.viser;

public class SiteInfoEntry {
	short fileIndexNo;
	short lineNo;
	short routineIndexNo;

	public SiteInfoEntry(short fno, short lno, short rno) {
		fileIndexNo = fno;
		lineNo = lno;
		routineIndexNo = rno;
	}

	@Override
	public String toString() {
		return fileIndexNo + ":" + lineNo + ":" + routineIndexNo;
	}

	@Override
	public boolean equals(Object o) {
		SiteInfoEntry other = (SiteInfoEntry) o;
		return fileIndexNo == other.fileIndexNo && lineNo == other.lineNo && routineIndexNo == other.routineIndexNo;
	}
}
