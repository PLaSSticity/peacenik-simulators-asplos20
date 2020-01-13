package simulator.viser;

public class Event {
	final EventType type;
	final EventType semantics;
	final ThreadId tid;
	long addr = 0;
	byte memOpSize = 0;
	boolean stackRef = false;
	long value = 0;
	int insnCount = 0;
	int siteIndex;
	int lastSiteIndex;

	Event(EventType typ, EventType semantics, byte tid) {
		this.type = typ;
		this.semantics = semantics;
		this.tid = new ThreadId(tid);
	}

	boolean isRegionBoundary() {
		return semantics == EventType.REG_END || semantics == EventType.REG_BEGIN;
	}

	boolean isLockAccess() {
		return type == EventType.LOCK_ACQ_READ || type == EventType.LOCK_ACQ_WRITE || type == EventType.LOCK_REL_WRITE;
	}

	@Override
	public String toString() {
		return "type=" + type + " semantics=" + semantics + " tid=" + tid + " addr=" + addr + " memOpSize=" + memOpSize + " stackRef=" + stackRef
 + " value=" + value + " inscount=" + insnCount + " siteIndex=" + siteIndex
				+ " lastSiteIndex=" + lastSiteIndex;
	}
}
