package simulator.mesi;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

public enum EventType {

	INVALID_EVENT(0),
	/** Tracking the PARSEC region-of-interest */
	ROI_START(1), ROI_END(2),
	/** When a new thread starts running, so we can setup its resources */
	THREAD_START(3),
	/** When a thread exits, so we can teardown its resources */
	THREAD_FINISH(4),
	/** When a thread is about to block inside the kernel, e.g. when doing a join */
	THREAD_BLOCKED(5),
	/**
	 * When a thread returns from blocking inside the kernel, e.g. after having
	 * joined
	 */
	THREAD_UNBLOCKED(6), MEMORY_READ(7), MEMORY_WRITE(8), MEMORY_ALLOC(9), MEMORY_FREE(10),
	/** Insn counting */
	BASIC_BLOCK(11), LOCK_ACQUIRE(12), LOCK_RELEASE(13), THREAD_JOIN(14), THREAD_SPAWN(15), IGNORE_CONFLICTS_BEGIN(16),
	IGNORE_CONFLICTS_END(17), ATOMIC_READ(18), ATOMIC_WRITE(19),
	// Used for read/write accesses that are part of lock operations
	LOCK_ACQ_READ(20), LOCK_ACQ_WRITE(21), LOCK_REL_WRITE(22),
	// Region boundary events
	REG_BEGIN(23), REG_END(24),
	// Used for server programs
	SERVER_ROI_START(25), SERVER_ROI_END(26), TRANS_START(27), TRANS_END(28), CHECK_POINT(29);

	private byte code;

	private EventType(int code) {
		this.code = (byte) code;
	}

	private static final Map<Byte, EventType> lookup = new HashMap<Byte, EventType>();

	static {
		for (EventType et : EnumSet.allOf(EventType.class))
			lookup.put(et.asByte(), et);
	}

	public static EventType fromByte(byte code) {
		if (MESISim.assertsEnabled) {
			assert lookup.containsKey(code);
		}
		return lookup.get(code);
	}

	public byte asByte() {
		return code;
	}

}
