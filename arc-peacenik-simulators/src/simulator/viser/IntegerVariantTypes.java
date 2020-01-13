package simulator.viser;

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
	private final byte tid;

	public ThreadId(byte id) {
		tid = (byte) id;
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
		return "T" + this.tid;
	}
}

/**
 * Represent per-thread epochs. This is in general a complicated issue, because threads and processes can be swapped in
 * and out and the fact needs to be noted in the cache. To be simple, we disallow swapping threads in and out in a
 * region, except at region boundaries. Moreover, validation needs to be done if a thread gets stuck doing IO within a
 * long region.
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

	public boolean isNull() {
		return lineAddr == 0L;
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

	@Override
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

/** Per-byte metadata needed for site tracking. */
/*class SiteInfo {
	short[] siteIndex = new short[MemorySystemConstants.LINE_SIZE()];

	public SiteInfo() {
		// TODO Auto-generated constructor stub
	}

	public SiteInfo(SiteInfo si) {
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			this.siteIndex[i] = si.siteIndex[i];
		}
	}

	public void	updateByEncoding(long enc, SiteInfo si) {
		for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
			if (((1L << i) & enc) != 0)
				this.siteIndex[i] = si.siteIndex[i];
		}
	}
} */

class PerCoreLineMetadata {
	/** Represent precise access information, one bit per byte */
	long writeEncoding = 0L;
	long readEncoding = 0L;
	/**
	 * Per-thread (not core) epoch. This can be used to avoid clearing lines in the LLC and main memory. Each LLC+ line
	 * needs to remember the line from each core.
	 */
	Epoch epoch;
	int[] writeSiteInfo = null;
	int[] readSiteInfo = null;
	int[] writeLastSiteInfo = null;
	int[] readLastSiteInfo = null;

	public PerCoreLineMetadata() {
		this.epoch = new Epoch();
		this.writeEncoding = 0L;
		this.readEncoding = 0L;
	}

	public PerCoreLineMetadata(Epoch ep, long write, long read, int[] writeinfo, int[] readinfo, int[] writelastinfo,
			int[] readlastinfo) {
		this.epoch = new Epoch(ep);
		this.writeEncoding = write;
		this.readEncoding = read;
		
		if (writeinfo != null) {
			writeSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			writeLastSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
				this.writeSiteInfo[i] = writeinfo[i];
				this.writeLastSiteInfo[i] = writelastinfo[i];
			}
		}
		if (readinfo != null) {
			readSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			readLastSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			for (int i = 0; i < MemorySystemConstants.LINE_SIZE(); i++) {
				this.readSiteInfo[i] = readinfo[i];
				this.readLastSiteInfo[i] = readlastinfo[i];
			}
		}
	}

	@Override
	public String toString() {
		return "Epoch:" + epoch + " Read encoding:" + Long.toBinaryString(readEncoding) + " Write encoding:"
				+ Long.toBinaryString(writeEncoding)
				+ "\n";
	}
}
