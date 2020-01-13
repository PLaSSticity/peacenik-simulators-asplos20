package simulator.viser;

enum MemoryAccessType {
	MEMORY_READ, MEMORY_WRITE, ATOMIC_READ, ATOMIC_WRITE, LOCK_ACQ_READ, LOCK_ACQ_WRITE, LOCK_REL_WRITE
}

class MemorySystemConstants {

	public static final int L1_HIT_LATENCY            = 1;
	public static final int L2_HIT_LATENCY            = 10;
	public static final int L2_ACCESS = L2_HIT_LATENCY / 2; // One way access
	public static final int REMOTE_HIT_LATENCY        = 15;
	public static final int L3_HIT_LATENCY            = 35;
	public static final int L3_ACCESS = L3_HIT_LATENCY / 2; // One way access
	public static final int MEMORY_LATENCY     = 120;
	public static final int MEMORY_ACCESS = MEMORY_LATENCY / 2;

	// Assumption is that the directory is embedded with the LLC
	public static final int DIRECTORY_ACCESS = L3_ACCESS; // One way cost
	public static final int DIRECTORY_LATENCY = L3_HIT_LATENCY; // Roundtrip cost

	public static final int CONTROL_MESSAGE_SIZE_BYTES = 8; // bytes, 48 bits for address + tags + core id
	// The return message needs core id and MSHR id. These would require log2 (#cores + #MSHRs) bits.
	public static final int DATA_MESSAGE_CONTROL_BYTES = 2; // 16 bits
	public static final int DATA_MESSAGE_SIZE_BYTES = 64; // bytes, 64 byte line + 1 byte of metadata

	// We just need the TAG bits, hence the computation is 4*(42+32)/8 bytes, assuming a 4 byte version
	private static final int VISER_RV_DATA_MESSAGE_SIZE_BYTES = 37;
	// Multiple cores can validate their reads simultaneously, so we need control header words
	public static final int VISER_RV_MESSAGE_SIZE_BYTES = DATA_MESSAGE_CONTROL_BYTES + VISER_RV_DATA_MESSAGE_SIZE_BYTES;

	// Try to fit it into one 16-byte flit
	public static final int BLOOM_FILTER_LLC_MESSAGE = DATA_MESSAGE_CONTROL_BYTES + BloomFilter.NUM_BYTES;

	public static final int BITS_IN_BYTE = 8;
	private static final int ADDRESS_BITS = 48;
	public static final int ADDRESS_BYTES = ADDRESS_BITS / BITS_IN_BYTE;
	private static final int TAG_BITS = ADDRESS_BITS - (int) (Math.log(DATA_MESSAGE_SIZE_BYTES) / Math.log(2));
	// Remember that there can be a precision problem if we are dividing 42/8 as an integer
	public static final int TAG_BYTES = (int) Math.ceil((double) TAG_BITS / BITS_IN_BYTE);

	private static final int WRITE_METADATA_BITS = 64;
	public static final int VISER_WRITE_METADATA_BYTES = WRITE_METADATA_BITS / BITS_IN_BYTE;
	private static final int READ_METADATA_BITS = 64;
	public static final int VISER_READ_METADATA_BYTES = READ_METADATA_BITS / BITS_IN_BYTE;
	public static final int VISER_VERSION_BYTES = 4; // overflows with 2 bytes version

	public static final double BYTES_IN_FLIT_4 = 4; // 32 bits
	public static final double BYTES_IN_FLIT_8 = 8; // 64 bits
	public static final double BYTES_IN_FLIT_16 = 16; // 128 bits
	public static final double BYTES_IN_FLIT_32 = 32; // 256 bits

	private static final int ONCHIP_BANDWIDTH = 100; // 100 GB/s
	private static final int LLC_TO_MEM_BANDWIDTH = 48; // 48 GB/s
	static final double CLOCK_RATE = 1.6; // GHz, number of cycles per second
	// These are dependent on the above units.
	public static final double LLC_MULTIPLIER = (MemorySystemConstants.CLOCK_RATE * Math.pow(10, 9)) / (ONCHIP_BANDWIDTH * Math.pow(2, 30));
	public static final double MEM_MULTIPLIER = (MemorySystemConstants.CLOCK_RATE * Math.pow(10, 9)) / (LLC_TO_MEM_BANDWIDTH * Math.pow(2, 30));

	// Maximum version that can be represented with 14 bits.
	public static final int MAX_14_BIT_VERSION = (1 << 14) - 1;
	public static final int MAX_8_BIT_VERSION = (1 << 8) - 1;
	public static final int MAX_16_BIT_VERSION = (1 << 16) - 1;
	public static final int MAX_24_BIT_VERSION = (1 << 24) - 1;
	public static final int MAX_32_BIT_VERSION = (int) ((1L << 32) - 1);

	public static final int TCC_WRITE_BUFFER_SIZE = 1 << 13; // 8K entries
	public static final int TCC_VICTIM_CACHE_SIZE = 1 << 4; // 16 entries
	
	public static final long LOCK_ADDR_OFFSET = 0x0010000000000000L;

	// Costs of restarting a whole server application
	// The following are counted from tests with the httpd server with 4 worker threads
	// public static final int RESTART_SERVER_BWDRIVEN_CYCLES = 24100000;
	// public static final int RESTART_SERVER_CORELLCMESSAGE_BYTES = 2200000;
	// mysqld
	// public static final int RESTART_SERVER_BWDRIVEN_CYCLES = 523000000;
	// public static final int RESTART_SERVER_CORELLCMESSAGE_BYTES = 509000000;

	private static boolean validLineSize = false;
	private static int     lineSize;
	private static int     lineSizeBits;

	/** NB: must be called before any GenericAccess objects are created. */
	public static void setLineSize(int ls) {
		assert !validLineSize;
		unsafeSetLineSize(ls);
	}

	/**
	 * Ordinarily, the line size should be set only once per execution; setLineSize() enforces this
	 * invariant. This unsafe version allows the line size to be reset, and should be used for
	 * testing purposes only.
	 */
	static void unsafeSetLineSize(int ls) {
		lineSize = ls;
		lineSizeBits = BitTwiddle.floorLog2(ls);
		validLineSize = true;
	}

	/**
	 * The cache line size used throughout this system. NB: this simulator only supports a constant
	 * global line size.
	 */
	public static int LINE_SIZE() {
		assert validLineSize;
		return lineSize;
	}

	/** The bitmask used to mask out everything but the offset within a line. */
	public static long LINE_OFFSET_MASK() {
		assert validLineSize;
		return lineSize - 1;
	}

	/** The number of bits needed to represent the line size. */
	public static int LINE_SIZE_BITS() {
		assert validLineSize;
		return lineSizeBits;
	}

}

abstract class GenericAccess {
	/** cpuid used when manipulating "global" metadata addresses */
	static final CpuId GLOBAL_CPUID = new CpuId(0);
	/** cpuid used when manipulating data addresses */
	static final CpuId DATA_CPUID   = new CpuId(0);

	protected MemoryAccessType type;
	protected ByteAddress baseAddr;
	protected ByteAddress addr;
	protected int size;

	abstract void incr();

	abstract boolean keepGoing();

	final boolean write() {
		return type == MemoryAccessType.MEMORY_WRITE;
	}

	final boolean read() {
		return type == MemoryAccessType.MEMORY_READ;
	}

	final MemoryAccessType type() {
		return type;
	}

	final int size() {
		return size;
	}

	ByteAddress addr() {
		return addr;
	}

	LineAddress lineAddress() {
		return addr.lineAddress();
	}

	short lineOffset() {
		return addr.lineOffset();
	}

	@Override
	public String toString() {
		return "type:" + String.valueOf(this.type) + " base:" + this.baseAddr.toString() + " addr:"
				+ this.addr.toString() + " size:" + this.size;
	}
}

class DataAccess extends GenericAccess {
	private long value;
	private CpuId core;
	private ThreadId thread; // This is important for epochs
	private int[] siteInfo = null;
	private int[] lastSiteInfo = null;
	private long encoding;

	DataAccess(MemoryAccessType mtype, ByteAddress addr, final int size, long value, CpuId core, ThreadId tid,
			int siteIndex, int lastSiteIndex) {
		this.type = mtype;
		// make defensive copies
		this.baseAddr = new DataByteAddress(addr.get());
		this.addr = new DataByteAddress(addr.get());
		this.size = size;
		this.value = value;
		// ensure access fits within a cache line
		assert (lineOffset() + size <= MemorySystemConstants.LINE_SIZE());

		this.core = core;
		this.thread = tid;

		if (siteIndex != -1) {
			if (siteInfo == null) {
				siteInfo = new int[MemorySystemConstants.LINE_SIZE()];
				lastSiteInfo = new int[MemorySystemConstants.LINE_SIZE()];
			}
			for (int i = addr.lineOffset(); i < addr.lineOffset() + size; i++) {
				this.siteInfo[i] = siteIndex;
				this.lastSiteInfo[i] = lastSiteIndex;
			}
		}
		this.encoding = getEncodingForAccess();
	}

	@Override
	public DataByteAddress addr() {
		return (DataByteAddress) this.addr;
	}

	@Override
	void incr() {
		assert keepGoing();
		addr.incr();
	}

	@Override
	boolean keepGoing() {
		return addr.get() < (baseAddr.get() + size);
	}

	public long value() {
		return value;
	}

	public CpuId core() {
		return core;
	}

	public ThreadId thread() {
		return thread;
	}

	public int[] siteInfo() {
		return siteInfo;
	}

	public int[] lastSiteInfo() {
		return lastSiteInfo;
	}

	public boolean isAtomic() {
		return type == MemoryAccessType.ATOMIC_READ || type == MemoryAccessType.ATOMIC_WRITE;
	}
	
	public boolean isLockAccess() {
		return type == MemoryAccessType.LOCK_ACQ_READ || type == MemoryAccessType.LOCK_ACQ_WRITE || type == MemoryAccessType.LOCK_REL_WRITE;
	}

	public boolean isRegularMemAccess() {
		return type == MemoryAccessType.MEMORY_READ || type == MemoryAccessType.MEMORY_WRITE;
	}
	
	public long getEncoding() {
		return encoding;
	}

	private long getEncodingForOffset(int off) {
		return (1L << off);
	}

	// full bit map, we want to be precise at the byte-level
	private long getEncodingForAccess() {
		long tmp = 0;
		ByteAddress start = new DataByteAddress(this.addr().get());
		for (int i = 0; i < this.size(); i++) {
			tmp |= getEncodingForOffset(start.lineOffset());
			start.incr();
		}
		return tmp;
	}
}
