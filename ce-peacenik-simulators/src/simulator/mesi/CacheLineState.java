package simulator.mesi;

/** Base class for all cache lines */
abstract class CoherentLine {
	protected LineAddress addr;

	abstract public boolean valid();

	abstract public boolean invalid();

	abstract public void invalidate(Machine<MESILine> machine); // machine is needed to reset MRU bits for PLRU

	abstract public LineAddress lineAddress();

	abstract public boolean contains(ByteAddress ba);
}

/** A simple valid/invalid coherence scheme */
class VILine extends CoherentLine {
	protected boolean valid = false;
	protected boolean dirty = false;

	public VILine() {
	}

	public VILine(LineAddress a) {
		this.valid = true;
		this.addr = a;
	}

	public boolean valid() {
		return valid;
	}

	public boolean invalid() {
		return !valid();
	}

	public void invalidate(Machine<MESILine> machine) {
		valid = false;
	}

	public LineAddress lineAddress() {
		return addr;
	}

	public boolean contains(ByteAddress ba) {
		return this.addr.equals(ba.lineAddress());
	}

	@Override
	public String toString() {
		return "addr=" + (addr == null ? "null" : addr.toString()) + (valid ? " valid" : " invalid")
				+ (dirty() ? " dirty" : " clean");
	}

	// This property is used to control write back from LLC to memory. This is not
	// very
	// useful for private cache lines.
	public boolean dirty() {
		return dirty;
	}

	public void setDirty(boolean status) {
		dirty = status;
	}
}

enum MESIState {
	MESI_MODIFIED, MESI_EXCLUSIVE, MESI_SHARED, MESI_INVALID
};

/** MESI coherence */
class MESILine extends VILine {
	protected MESIState state = MESIState.MESI_INVALID;

	private CpuId id;
	private CacheLevel level;

	// Conflict Exceptions
	private long localReads = 0L;
	private long localWrites = 0L;
	private long remoteReads = 0L;
	private long remoteWrites = 0L;

	private boolean supplied = false; // Private caches
	private boolean inMemory = false; // LLC line

	private boolean deferredWriteBit = false; // line has values which haven't been written back, may come from prior
												// regions (diff than local write bits)
	public boolean l2WriteBit = false;

	private short[] lastWriters = new short[MemorySystemConstants.LINE_SIZE()];
	int[] writeSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
	int[] readSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];

	public MESILine(CpuId id, CacheLevel level) {
		super();
		this.id = id;
		this.level = level;
	}

	public MESILine(CpuId id, CacheLevel level, LineAddress a) {
		// NB: super(a) sets valid = true;
		super(a);
		this.id = id;
		this.level = level;
	}

	public MESIState getState() {
		return state;
	}

	// An LLC line can be MESI_INVALID and valid at the same time.
	public void changeStateTo(MESIState s) {
		state = s;
	}

	public CpuId id() {
		return id;
	}

	public CacheLevel getLevel() {
		return level;
	}

	public boolean isPrivateCacheLine() {
		return getLevel() == CacheLevel.L1 || getLevel() == CacheLevel.L2;
	}

	@Override
	public String toString() {
		return super.toString() + " " + this.state.toString() + " CPU:" + id + " Identity hash code:"
				+ System.identityHashCode(this) + " Level:" + level;
	}

	public void invalidate(Machine<MESILine> machine) {
		Processor<MESILine> ownerProc = machine.getProc(id);
		if (ownerProc.params.usePLRU()) {
			if (level == CacheLevel.L1)
				ownerProc.L1cache.resetMRUBit(this);
			else if (level == CacheLevel.L2)
				ownerProc.L2cache.resetMRUBit(this);
			else // shouldn't reach here because we don't invalidate l3 lines
				ownerProc.L3cache.resetMRUBit(this);
		}

		this.valid = false;
		this.state = MESIState.MESI_INVALID;
	}

	public boolean isRead() {
		return getLocalReads() != 0;
	}

	public boolean isRemoteRead() {
		return getRemoteReads() != 0;
	}

	public long getLocalReads() {
		return localReads;
	}

	public void setLocalReads(long val) {
		localReads = val;
	}

	public void orLocalReads(long val) {
		localReads |= val;
	}

	public void clearLocalReads() {
		setLocalReads(0);
	}

	public boolean isWritten() {
		return getLocalWrites() != 0;
	}

	public boolean isRemoteWritten() {
		return getRemoteWrites() != 0;
	}

	public long getLocalWrites() {
		return localWrites;
	}

	public boolean hasWrittenOffsets() {
		return localWrites != 0;
	}

	public boolean hasReadOffsets() {
		return localReads != 0;
	}

	public void setLocalWrites(long val) {
		localWrites = val;
	}

	public void orLocalWrites(long val) {
		localWrites |= val;
	}

	public void clearLocalWrites() {
		setLocalWrites(0);
	}

	public long getRemoteReads() {
		return remoteReads;
	}

	public void setRemoteReads(long val) {
		remoteReads = val;
	}

	public void orRemoteReads(long val) {
		remoteReads |= val;
	}

	public void clearRemoteReads() {
		setRemoteReads(0);
	}

	public long getRemoteWrites() {
		return remoteWrites;
	}

	public void setRemoteWrites(long val) {
		remoteWrites = val;
	}

	public void orRemoteWrites(long val) {
		remoteWrites |= val;
	}

	public void clearRemoteWrites() {
		setRemoteWrites(0);
	}

	public void setSupplied() {
		supplied = true;
	}

	public boolean isSupplied() {
		return supplied;
	}

	public void clearSupplied() {
		supplied = false;
	}

	public void setInMemoryBit() {
		assert level == CacheLevel.L3;
		inMemory = true;
	}

	public boolean inMemory() {
		assert level == CacheLevel.L3;
		return inMemory;
	}

	// TODO: When is this cleared? Possibly on an LLC eviction.
	public void clearInMemory() {
		assert level == CacheLevel.L3;
		inMemory = false;
	}

	public short getLastWriter(int offset) {
		assert offset < MemorySystemConstants.LINE_SIZE();
		return lastWriters[offset];
	}

	public short[] getLastWriters() {
		return lastWriters;
	}

	public void initLastWriters() {
		for (int i = 0; i < lastWriters.length; i++) {
			lastWriters[i] = -1;
		}
	}

	public void setLastWriters(short[] l_writers) {
		for (int i = 0; i < lastWriters.length; i++) {
			lastWriters[i] = l_writers[i];
		}
	}

	public void setLastWriters(long enc, CpuId lastWriter) {
		short l_writers = lastWriter.get();
		for (int i = 0; i < lastWriters.length; i++) {
			if (((1L << i) & enc) != 0)
				lastWriters[i] = l_writers;
		}
	}

	public void updateReadSiteInfo(long enc, int[] si) {
		if (si == null) {
			return;
		}

		if (readSiteInfo == null) {
			readSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
		}
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			if (((1L << i) & enc) != 0) {
				readSiteInfo[i] = si[i];
			}
		}
	}

	public void updateWriteSiteInfo(long enc, int[] si) {
		if (si == null) {
			return;
		}

		if (writeSiteInfo == null) {
			writeSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
		}
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			if (((1L << i) & enc) != 0) {
				writeSiteInfo[i] = si[i];
			}
		}
	}

	public int[] getWriteSiteInfo() {
		return writeSiteInfo;
	}

	public int[] getReadSiteInfo() {
		return readSiteInfo;
	}

	public boolean isDeferredWriteBitSet() {
		return deferredWriteBit;
	}

	public void setDeferredWriteBit() {
		deferredWriteBit = true;
	}

	public void resetDeferredWriteBit() {
		deferredWriteBit = false;
	}

	public boolean hasDirtyValuesFromPriorRegions() {
		return deferredWriteBit && localWrites == 0;
	}
};

/**
 * Used by HierarchicalCache to construct new line objects, since generic ctors
 * can't be called directly (due to type erasure).
 */
interface LineFactory<Line extends CoherentLine> {
	public Line create(CpuId id, CacheLevel level);

	public Line create(CpuId id, CacheLevel level, LineAddress la);

	public Line create(CpuId id, CacheLevel level, Line l); // Clone a line
}
