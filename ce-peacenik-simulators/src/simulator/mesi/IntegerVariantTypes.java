package simulator.mesi;

/**
 * These types would be encoded as "variant types" in other languages, but have to be distinct
 * classes in Java. Basically, we want to maintain the distinctions of different kinds of numbers,
 * instead of treating them all as ints/longs.
 */

/** Tracks CPU id's */
class CpuId {
	private final short cpuid;

	public CpuId(int id) {
		cpuid = (short) id;
	}

	public short get() {
		return cpuid;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof CpuId) {
			CpuId other = (CpuId) o;
			return this.cpuid == other.cpuid;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return this.cpuid;
	}

	@Override
	public String toString() {
		return "p" + this.cpuid;
	}
}

/** Tracks thread id's */
class ThreadId {
	private final short tid;

	public ThreadId(int id) {
		tid = (short) id;
	}

	public short get() {
		return tid;
	}

	public boolean equals(short a) {
		return a == tid;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof ThreadId) {
			ThreadId other = (ThreadId) o;
			return this.tid == other.tid;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return this.tid;
	}

	@Override
	public String toString() {
		return "t" + this.tid;
	}
}

/** The fully-qualified address of a particular byte in memory */
abstract class ByteAddress {
	protected long addr;

	public ByteAddress(long a) {
		this.addr = a;
	}

	public long get() {
		return this.addr;
	}

	public void incr() {
		this.addr++;
	}

	public void incr(long a) {
		this.addr += a;
	}

	abstract public LineAddress lineAddress();

	public short lineOffset() {
		return (short) (this.addr & MemorySystemConstants.LINE_OFFSET_MASK());
	}

	@Override
	public String toString() {
		return "ba:" + String.format("%d", this.addr);
	}
}

/** A cache-line-aligned address */
abstract class LineAddress {
	protected final long lineAddr;

	public LineAddress(long la) {
		assert (la & (~MemorySystemConstants.LINE_OFFSET_MASK())) == la;
		this.lineAddr = la;
	}

	public long get() {
		return this.lineAddr;
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof LineAddress) {
			LineAddress other = (LineAddress) o;
			return this.lineAddr == other.lineAddr;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return (int) this.lineAddr;
	}

	@Override
	public String toString() {
		return "la:" + String.format("%d", this.lineAddr);
	}
}

/** The address of a regular data byte. */
class DataByteAddress extends ByteAddress {
	public DataByteAddress(long a) {
		super(a);
	}

	public LineAddress lineAddress() {
		return new DataLineAddress(this.addr & (~MemorySystemConstants.LINE_OFFSET_MASK()));
	}

	@Override
	public String toString() {
		return "dba:" + String.format("%d", this.addr);
	}
}

/** The line address of a regular data byte. */
class DataLineAddress extends LineAddress {
	public DataLineAddress(long la) {
		super(la);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof DataLineAddress) {
			DataLineAddress other = (DataLineAddress) o;
			return this.lineAddr == other.lineAddr;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return (int) this.lineAddr;
	}

	@Override
	public String toString() {
		return "dla:" + String.format("%d", this.lineAddr);
	}
}

/**
 * Represent per-thread epochs. This is in general a complicated issue, because
 * threads and processes can be swapped in and out and the fact needs to be
 * noted in the cache. To be simple, we disallow swapping threads in and out in
 * a region, except at region boundaries. Moreover, validation needs to be done
 * if a thread gets stuck doing IO within a long region.
 */
class Epoch {
	static final int REGION_ID_START = 1;
	private int regionId;

	public Epoch() {
		this(-1);
	}

	public Epoch(Epoch ep) {
		this(ep.regionId);
	}

	public Epoch(int reg) {
		this.regionId = reg;
	}

	public int getRegionId() {
		return regionId;
	}

	public void setRegionId(int reg) {
		this.regionId = reg;
	}

	public void incrementRegionId() {
		this.regionId++;
		assert this.regionId <= Integer.MAX_VALUE : "Region id has overflowed for Thread";
	}

	@Override
	public String toString() {
		return Integer.toString(regionId);
	}

	@Override
	public boolean equals(Object o) {
		if (o instanceof Epoch) {
			Epoch that = (Epoch) o;
			return this.regionId == that.regionId;
		}
		return false;
	}

	@Override
	public int hashCode() {
		return regionId;
	}
}

// Includes supplied bit, and local access bits
class CEGlobalTableValue {
	long localReads = 0L;
	long localWrites = 0L;
	int[] writeSiteInfo = null;
	int[] readSiteInfo = null;
	boolean supplied = false;
	long regionID;

	public CEGlobalTableValue(long reads, int[] rsi, long writes, int[] wsi, boolean s, long regID) {
		localReads = reads;
		readSiteInfo = rsi;
		writeSiteInfo = wsi;
		localWrites = writes;
		supplied = s;
		regionID = regID;
	}

	public CEGlobalTableValue() {
		localReads = 0;
		localWrites = 0;
		supplied = false;
		regionID = -1;
	}

	public void setSiteInfo(int[] ri, int[] wi) {
		writeSiteInfo = wi;
		readSiteInfo = ri;
	}

	@Override
	public String toString() {
		return "Region ID: " + regionID + " Reads: " + localReads + " Writes: " + localWrites + " Supplied: "
				+ supplied;
	}
}

class CEPerLineMetadata<Line extends MESILine> {
	CEGlobalTableValue[] values;

	public CEPerLineMetadata(int numCpus) {
		values = new CEGlobalTableValue[numCpus];
		for (int i = 0; i < numCpus; i++) {
			values[i] = new CEGlobalTableValue();
		}
	}

	public CEGlobalTableValue getPerCoreMetadata(Processor<Line> proc) {
		CEGlobalTableValue tmp = values[proc.id.get()];
		assert tmp.regionID <= proc.getCurrentEpoch().getRegionId();

		if (tmp.regionID == proc.getCurrentEpoch().getRegionId()) {
			// Access information is up-to-date
		} else {
			tmp = new CEGlobalTableValue();
			setPerCoreMetadata(proc.id, tmp);
		}
		return tmp;
	}

	public void setPerCoreMetadata(CpuId id, CEGlobalTableValue val) {
		values[id.get()] = val;
	}
}
