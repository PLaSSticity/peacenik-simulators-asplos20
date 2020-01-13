package simulator.mesi;

enum MemoryAccessType {
    MEMORY_READ, MEMORY_WRITE, ATOMIC_READ, ATOMIC_WRITE, LOCK_ACQ_READ, LOCK_ACQ_WRITE, LOCK_REL_WRITE
}

class MemorySystemConstants {

    public static final int L1_HIT_LATENCY = 1;
    public static final int L2_HIT_LATENCY = 10;
    public static final int L2_ACCESS = L2_HIT_LATENCY / 2; // One way access
    public static final int REMOTE_HIT_LATENCY = 15;

    // This needs to change depending on the size and associativity of the LLC.
    public static int L3_HIT_LATENCY;
    public static int L3_ACCESS; // One way access

    public static final int MEMORY_LATENCY = 120;
    public static final int MEMORY_ACCESS = MEMORY_LATENCY / 2;

    // Assumption is that the directory is embedded with the LLC
    public static final int DIRECTORY_ACCESS = L3_ACCESS; // One way cost
    public static final int DIRECTORY_LATENCY = L3_HIT_LATENCY; // Roundtrip cost

    public static final int CONTROL_MESSAGE_SIZE_BYTES = 8; // bytes, 48 bits for address + tags + core id
    // The return message needs core id and MSHR id. These would require log2
    // (#cores + #MSHRs) bits.
    public static final int DATA_MESSAGE_CONTROL_BYTES = 2; // 16 bits
    public static final int DATA_MESSAGE_SIZE_BYTES = 64; // bytes, 64 byte line + 1 byte of metadata

    private static final int BITS_IN_BYTE = 8;

    private static final int ADDRESS_BITS = 48;
    public static final int ADDRESS_BYTES = ADDRESS_BITS / BITS_IN_BYTE;
    private static final int TAG_BITS = ADDRESS_BITS - (int) (Math.log(DATA_MESSAGE_SIZE_BYTES) / Math.log(2));;
    // Remember that there can be a precision problem if we are dividing 42/8 as an
    // integer
    public static final int TAG_BYTES = (int) Math.ceil(TAG_BITS / BITS_IN_BYTE);

    public static final double BYTES_IN_FLIT_4 = 4; // 32 bits
    public static final double BYTES_IN_FLIT_8 = 8; // 64 bits
    public static final double BYTES_IN_FLIT_16 = 16; // 128 bits
    public static final double BYTES_IN_FLIT_32 = 32; // 256 bits

    private static final int CE_WRITE_METADATA_BITS = 64;
    public static final int CE_WRITE_METADATA_BYTES = CE_WRITE_METADATA_BITS / BITS_IN_BYTE;
    private static final int CE_READ_METADATA_BITS = 64;
    public static final int CE_READ_METADATA_BYTES = CE_READ_METADATA_BITS / BITS_IN_BYTE;

    static final double CLOCK_RATE = 1.6; // GHz, number of cycles per second

    private static boolean validLineSize = false;
    private static int lineSize;
    private static int lineSizeBits;

    public static void setLLCAccessTimes(int numCores) {
        L3_ACCESS = 35;
        L3_HIT_LATENCY = L3_ACCESS / 2;
        /*
         * switch (numCores) { case 8: { L3_ACCESS = 35; L3_HIT_LATENCY = L3_ACCESS / 2;
         * break; } case 16: { L3_ACCESS = 40; L3_HIT_LATENCY = L3_ACCESS / 2; break; }
         * case 32: { L3_ACCESS = 50; L3_HIT_LATENCY = L3_ACCESS / 2; break; } default:
         * { throw new RuntimeException("Error initializing LLC access times."); } }
         */
    }

    /** NB: must be called before any GenericAccess objects are created. */
    public static void setLineSize(int ls) {
        assert !validLineSize;
        unsafeSetLineSize(ls);
    }

    /**
     * Ordinarily, the line size should be set only once per execution;
     * setLineSize() enforces this invariant. This unsafe version allows the line
     * size to be reset, and should be used for testing purposes only.
     */
    static void unsafeSetLineSize(int ls) {
        lineSize = ls;
        lineSizeBits = BitTwiddle.floorLog2(ls);
        validLineSize = true;
    }

    /**
     * The cache line size used throughout this system. NB: this simulator only
     * supports a constant global line size.
     */
    public static int LINE_SIZE() {
        assert validLineSize;
        return lineSize;
    }

    /** The bit mask used to mask out everything but the offset within a line. */
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
    static final CpuId DATA_CPUID = new CpuId(0);

    protected MemoryAccessType type;
    protected ByteAddress baseAddr;
    protected ByteAddress addr;
    protected int size;

    abstract void incr();

    abstract boolean keepGoing();

    // final boolean write() {
    // return type == MemoryAccessType.MEMORY_WRITE;
    // }
    //
    // final boolean read() {
    // return type == MemoryAccessType.MEMORY_READ;
    // }

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
    private int[] siteInfo = null;

    DataAccess(MemoryAccessType mtype, ByteAddress addr, final int size, int siteIndex) {
        this.type = mtype;
        // make defensive copies
        this.baseAddr = new DataByteAddress(addr.get());
        this.addr = new DataByteAddress(addr.get());
        this.size = size;
        this.siteInfo = new int[MemorySystemConstants.LINE_SIZE()];
        // ensure access fits within a cache line
        assert (lineOffset() + size <= MemorySystemConstants.LINE_SIZE());
        if (siteIndex != -1) {
            if (siteInfo == null) {
                siteInfo = new int[MemorySystemConstants.LINE_SIZE()];
            }
            for (int i = addr.lineOffset(); i < addr.lineOffset() + size; i++) {
                this.siteInfo[i] = siteIndex;
            }
        }
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

    public int[] siteInfo() {
        return siteInfo;
    }

    public boolean isAtomic() {
        return type == MemoryAccessType.ATOMIC_READ || type == MemoryAccessType.ATOMIC_WRITE;
    }

    public boolean isLockAccess() {
        return type == MemoryAccessType.LOCK_ACQ_READ || type == MemoryAccessType.LOCK_ACQ_WRITE
                || type == MemoryAccessType.LOCK_REL_WRITE;
    }

    public boolean isRegularMemAccess() {
        return type == MemoryAccessType.MEMORY_READ || type == MemoryAccessType.MEMORY_WRITE;
    }
}
