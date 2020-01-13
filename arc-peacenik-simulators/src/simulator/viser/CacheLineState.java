package simulator.viser;

/** Base class for all cache lines */
abstract class CoherentLine {
	protected LineAddress addr;

	abstract public boolean valid();

	abstract public boolean invalid();

	abstract public void invalidate();

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

	@Override
	public boolean valid() {
		return valid;
	}

	@Override
	public boolean invalid() {
		return !valid();
	}

	@Override
	public void invalidate() {
		valid = false;
	}

	@Override
	public LineAddress lineAddress() {
		return addr;
	}

	@Override
	public boolean contains(ByteAddress ba) {
		return this.addr.equals(ba.lineAddress());
	}

	@Override
	public String toString() {
		return "addr=" + (addr == null ? "null" : addr.toString()) + "\t" + (valid ? "valid" : " invalid") + "\t"
				+ (dirty() ? " dirty" : " clean");
	}

	// This property is used to control write back from LLC to memory. This is
	// not very
	// useful for private cache lines.
	public boolean dirty() {
		return dirty;
	}

	public void setDirty(boolean status) {
		dirty = status;
	}
}

enum ViserState {
	VISER_INVALID, VISER_INVALID_TENTATIVE, VISER_VALID
};

class ViserLine extends VILine {
	/** For private lines, proc which owns the line, for LLC it is always P0. */
	private Processor<ViserLine> ownerProc;
	private CacheLevel level;

	private ViserState state = ViserState.VISER_INVALID;
	/**
	 * Used for validation, and to prevent ABA problem with value validation.
	 * Incremented on every writeback to LLC.
	 */
	private int version = 0;
	private boolean deferredWriteBit = false; // the dirty bit

	// FIXME the bit is sort of confusing with the above dirtyBit.
	public boolean l2WriteBit = false;
	/**
	 * Viser needs to store values in addition to tags (unlike MESI). We do not need
	 * per-core values, since LLC values are globally visible.
	 */
	private long[] values = new long[MemorySystemConstants.LINE_SIZE()];
	private short[] lastWriters = new short[MemorySystemConstants.LINE_SIZE()];
	/** Per-core metadata that LLC needs to maintain for evicted lines. */
	private PerCoreLineMetadata[] perCoreMd;
	/**
	 * LLC needs to maintain the owner core in case write back of dirty lines are
	 * deferred.
	 */
	private int deferredLineOwnerID = -1;
	private boolean concurrentRemoteWrite;
	private int lockOwnerID = -1;

	public ViserLine(Processor<ViserLine> proc, CacheLevel level) {
		// NB: super(a) sets valid = true;
		super();
		if (level == CacheLevel.L3 && proc.id.get() != 0) {
			throw new RuntimeException("LLC line should be owned by P0");
		}
		this.ownerProc = proc;
		this.level = level;
		allocatePerCoreMetadata();
		initLastWriters();
	}

	public ViserLine(Processor<ViserLine> proc, CacheLevel level, LineAddress a) {
		// NB: super(a) sets valid = true;
		super(a);
		if (level == CacheLevel.L3 && proc.id.get() != 0) {
			throw new RuntimeException("LLC line should be owned by P0");
		}
		this.ownerProc = proc;
		this.level = level;
		allocatePerCoreMetadata();
		initLastWriters();
	}

	// The space allocation is optimized for L1/L2 lines. However, the potential
	// for improvement is bounded.
	private void allocatePerCoreMetadata() {
		int size = (level == CacheLevel.L3) ? ownerProc.params.numProcessors() : 1;
		perCoreMd = new PerCoreLineMetadata[size];
		for (int i = 0; i < perCoreMd.length; i++) {
			perCoreMd[i] = new PerCoreLineMetadata();
		}
	}

	public ViserState getState() {
		return state;
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

	public void changeStateTo(ViserState s) {
		valid = (s != ViserState.VISER_INVALID);
		state = s;
	}

	public CpuId id() {
		return ownerProc.id;
	}

	public CacheLevel getLevel() {
		return level;
	}

	public boolean isPrivateCacheLine() {
		return getLevel().compareTo(ownerProc.llc()) < 0;
	}

	@Override
	public boolean valid() {
		if (valid) {
			if (ownerProc.params.useSpecialInvalidState()) {
				assert (state == ViserState.VISER_VALID
						|| state == ViserState.VISER_INVALID_TENTATIVE) : "Valid state is inconsistent";
			} else {
				assert state == ViserState.VISER_VALID : "Valid state is inconsistent";
			}
		}
		return this.valid;
	}

	@Override
	public void invalidate() {
		if (ownerProc.params.usePLRU()) {
			if (level == CacheLevel.L1)
				ownerProc.L1cache.resetMRUBit(this);
			else if (level == CacheLevel.L2)
				ownerProc.L2cache.resetMRUBit(this);
			else // shouldn't reach here because we don't invalidate l3 lines
				ownerProc.L3cache.resetMRUBit(this);
		}

		this.valid = false;
		this.state = ViserState.VISER_INVALID;
	}

	@Override
	public String toString() {
		String str = super.toString() + "\t" + this.state.toString() + "\tIdHash:" + System.identityHashCode(this)
				+ "\tCPU:" + id() + "\tLevel:" + level + "\tVer:" + version + "\tLock owner:" + lockOwnerID;
		if (isPrivateCacheLine()) {
			str += "\tRegion:" + getEpoch(id()) + "\tWrite bits:" + Long.toHexString(getWriteEncoding(id()))
					+ "\tRead bits:" + Long.toHexString(getReadEncoding(id()));
		} else if (ownerProc.params.deferWriteBacks()) {
			str += "\tOwnerCore:" + deferredLineOwnerID;
		}
		str += "\t";
		/*
		 * for (int i = 0; i < values.length; i++) { str = str + "[" + i + "]:" +
		 * values[i] + " "; }
		 */
		return str;
	}

	public void incrementVersion() {
		version++;
		// We do not expect cache line versions to overflow
		assert Integer.MAX_VALUE == Math.pow(2,
				((MemorySystemConstants.VISER_VERSION_BYTES * MemorySystemConstants.BITS_IN_BYTE) - 1)) - 1;
		if (version > Math.pow(2, (MemorySystemConstants.VISER_VERSION_BYTES * MemorySystemConstants.BITS_IN_BYTE - 1))
				- 1) {
			throw new RuntimeException("Version overflow");
		}
	}

	public void setVersion(int ver) {
		this.version = ver;
	}

	public int getVersion() {
		return version;
	}

	// In Java, we do not easily get an unsigned integer
	public long getWriteEncoding(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);
		assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId() : "Epoch mismatch";

		if (p.getCurrentEpoch().equals(md.epoch)) {
			return md.writeEncoding;
		} else {
			return 0;
		}
	}

	public int[] getWriteSiteInfo(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		return md.writeSiteInfo;
	}

	public int[] getWriteLastSiteInfo(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		return md.writeLastSiteInfo;
	}

	public void orWriteEncoding(CpuId cid, long enc) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		if (p.getCurrentEpoch().equals(md.epoch)) {
			md.writeEncoding |= enc;
		} else {
			md.writeEncoding = enc;
		}
	}

	public void clearWriteEncodingFromAccess(CpuId cid, long enc) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		if (p.getCurrentEpoch().equals(md.epoch)) {
			md.writeEncoding &= ~enc;
		} else {
			md.writeEncoding = 0L;
		}
	}

	public void updateWriteSiteInfo(CpuId cid, long enc, int[] si, int[] lastSi) {
		if (si == null) {
			return;
		}

		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);
		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();

		if (md.writeSiteInfo == null) {
			md.writeSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			md.writeLastSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
		}
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			if (((1L << i) & enc) != 0) {
				md.writeSiteInfo[i] = si[i];
				md.writeLastSiteInfo[i] = lastSi[i];
			}
		}
	}

	public void clearWriteEncoding(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);
		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		md.writeEncoding = 0L;
	}

	public boolean isOffsetWritten(CpuId cid, long enc) {
		return (getWriteEncoding(cid) & enc) != 0;
	}

	public boolean hasWrittenOffsets(CpuId cid) {
		return getWriteEncoding(cid) != 0;
	}

	public long getReadEncoding(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		if (p.getCurrentEpoch().equals(md.epoch)) {
			return md.readEncoding;
		} else {
			return 0;
		}
	}

	public int[] getReadSiteInfo(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		return md.readSiteInfo;
	}

	public int[] getReadLastSiteInfo(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		return md.readLastSiteInfo;
	}

	public void orReadEncoding(CpuId cid, long enc) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		if (p.getCurrentEpoch().equals(md.epoch)) {
			md.readEncoding |= enc;
		} else {
			md.readEncoding = enc;
		}
	}

	public void clearReadEncodingFromAccess(CpuId cid, long enc) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);

		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		if (p.getCurrentEpoch().equals(md.epoch)) {
			md.readEncoding &= ~enc;
		} else {
			md.readEncoding = 0L;
		}
	}

	public void updateReadSiteInfo(CpuId cid, long enc, int[] si, int[] lastSi) {
		if (si == null) {
			return;
		}

		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);
		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();

		if (md.readSiteInfo == null) {
			md.readSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			md.readLastSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
		}
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			if (((1L << i) & enc) != 0) {
				md.readSiteInfo[i] = si[i];
				md.readLastSiteInfo[i] = lastSi[i];
			}
		}
	}

	public void clearReadEncoding(CpuId cid) {
		Processor<ViserLine> p = ownerProc.machine.getProc(cid);
		PerCoreLineMetadata md = getPerCoreMetadata(cid);
		assert p.getCurrentEpoch().getRegionId() >= md.epoch.getRegionId();
		md.readEncoding = 0L;
	}

	/** Read by the given cpu, before being updated. */
	public boolean isOffsetRead(CpuId cid, long enc) {
		return (getReadEncoding(cid) & enc) != 0;
	}

	public boolean isOffsetReadOnly(CpuId cid, long enc) {
		return ((getReadEncoding(cid) & ~getWriteEncoding(cid)) & enc) != 0;
	}

	/**
	 * Any byte offset has been read before being updated (it may have been also
	 * written later).
	 */
	public boolean hasReadOffsets(CpuId cid) {
		return getReadEncoding(cid) != 0;
	}

	public boolean hasUntouchedOffsets(CpuId cid) {
		if (MemorySystemConstants.LINE_SIZE() == 64)
			return (~(getReadEncoding(cid) | getWriteEncoding(cid))) != 0;
		return ((~(getReadEncoding(cid) | getWriteEncoding(cid)))
				& ((1L << MemorySystemConstants.LINE_SIZE()) - 1)) != 0;
	}

	/** Return true for any offset */
	public boolean isWrittenAfterRead(CpuId cid) {
		return (getReadEncoding(cid) & getWriteEncoding(cid)) != 0;
	}

	/** Return true if some offsets are read-only */
	public boolean hasReadOnlyOffsets(CpuId cid) {
		return (getReadEncoding(cid) & ~getWriteEncoding(cid)) != 0;
	}

	/** Return true if the line has only been read, but not written. */
	public boolean isLineReadOnly(CpuId cid) {
		return hasReadOffsets(cid) && !hasWrittenOffsets(cid);
	}

	/**
	 * We assume that metadata is cleared at region boundaries for private cache
	 * lines. For LLC, we need to check epochs.
	 */
	public boolean isAccessedInThisRegion(CpuId cid) {
		return hasReadOffsets(cid) || hasWrittenOffsets(cid);
	}

	/** Get value stored at offset for addr in the line. */
	public long getValue(ByteAddress addr) {
		assert this.contains(addr) : "Addr does not belong to this line.";
		return getValue(addr.lineOffset());
	}

	public short getLastWriter(ByteAddress addr) {
		assert this.contains(addr) : "Addr does not belong to this line.";
		return getLastWriter(addr.lineOffset());
	}

	public long getValue(int offset) {
		assert offset < MemorySystemConstants.LINE_SIZE();
		return values[offset];
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

	public void setLastWritersFromPrivateLine(ViserLine src) {
		assert src.perCoreMd.length == 1;
		long enc = src.getWriteEncoding(src.id());
		setLastWriters(enc, src.id());
	}

	public void setLastWriters(long enc, CpuId lastWriter) {
		short l_writers = lastWriter.get();
		for (int i = 0; i < lastWriters.length; i++) {
			if (((1L << i) & enc) != 0)
				lastWriters[i] = l_writers;
		}
	}

	public void setValue(ByteAddress addr, long value) {
		setValue(addr.lineOffset(), value);
	}

	public void setValue(int offset, long value) {
		assert offset < MemorySystemConstants.LINE_SIZE();
		values[offset] = value;
	}

	/** this <-- other */
	public void copyAllValues(ViserLine other) {
		assert values != null && other.values != null && values.length == other.values.length;
		for (int i = 0; i < values.length; i++) {
			values[i] = other.values[i];
		}
	}

	/**
	 * Note that the cpu id from the source line is used. WAR bits are also copied.
	 */
	public void copyReadValuesFromSource(ViserLine source) {
		copyRequestedValues(source, source.getReadEncoding(source.id()));
	}

	/** Note that the cpu id from the source line is used. WAR bits are ignored. */
	public void copyReadOnlyValuesFromSource(ViserLine source) {
		long bits = source.getReadEncoding(source.id()) & ~source.getWriteEncoding(source.id());
		copyRequestedValues(source, bits);
	}

	/** Note that the cpu id from the source line is used. */
	public void copyWrittenValuesFromSource(ViserLine source) {
		copyRequestedValues(source, source.getWriteEncoding(source.id()));
	}

	/** Copy values based on enc. */
	public void copyRequestedValues(ViserLine source, long enc) {
		// Iterate over all the bits
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			// Check if the ith bit is set in "enc". if yes, then update the value from
			// source line to this line.
			long mask = 1L << i; // Lines are 64 bytes
			if ((enc & mask) != 0) { // ith bit is set
				assert (enc & mask) == mask;
				values[i] = source.values[i];
			}
		}
	}

	public Epoch getEpoch(CpuId cid) {
		return getPerCoreMetadata(cid).epoch;
	}

	public void setEpoch(CpuId cid, Epoch ep) {
		if (isPrivateCacheLine()) {
			assert cid.equals(id());
			cid = new CpuId(0);
		}
		perCoreMd[cid.get()].epoch = (ep == null ? new Epoch() : new Epoch(ep));
	}

	public void setPerCoreMetadata(CpuId cid, PerCoreLineMetadata md) {
		if (isPrivateCacheLine()) {
			assert cid.equals(id());
			cid = new CpuId(0);
		}
		perCoreMd[cid.get()] = md;
	}

	public PerCoreLineMetadata getPerCoreMetadata(CpuId cid) {
		if (isPrivateCacheLine()) {
			assert cid.equals(id());
			cid = new CpuId(0);
		}
		return perCoreMd[cid.get()];
	}

	public boolean isLineDeferred() {
		assert getLevel() == CacheLevel.L3;
		return deferredLineOwnerID >= 0;
	}

	public int getDeferredLineOwnerID() {
		assert getLevel() == CacheLevel.L3;
		return deferredLineOwnerID;
	}

	public void setDefferedLineOwnerID(int val) {
		assert getLevel() == CacheLevel.L3;
		deferredLineOwnerID = val;
	}

	public void clearDeferredLineOwnerID() {
		setDefferedLineOwnerID(-1);
	}

	public boolean isThereAConcurrentRemoteWrite() {
		return concurrentRemoteWrite;
	}

	public void setConcurrentRemoteWrite() {
		assert !concurrentRemoteWrite;
		concurrentRemoteWrite = true;
	}

	public void clearConcurrentRemoteWrite() {
		concurrentRemoteWrite = false;
	}

	public int getLockOwnerID() {
		return lockOwnerID;
	}

	public void setLockOwnerID(int lock) {
		lockOwnerID = lock;
	}

	public void clearLockOwnerID() {
		lockOwnerID = -1;
	}
}

/**
 * Used by HierarchicalCache to construct new line objects, since generic ctors
 * can't be called directly (due to type erasure).
 */
interface LineFactory<Line extends ViserLine> {
	public Line create(Processor<Line> proc, CacheLevel level);

	public Line create(Processor<Line> proc, CacheLevel level, LineAddress la);

	public Line create(Processor<Line> proc, CacheLevel level, Line l); // Clone
																		// a
																		// line
}
