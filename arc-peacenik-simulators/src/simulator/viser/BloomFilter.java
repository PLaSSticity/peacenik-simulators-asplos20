package simulator.viser;

import java.util.BitSet;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;

/** A fixed-size Bloom filter, with support for up to three hash functions. */
public final class BloomFilter {

	private static final int NUM_BITS = 112;
	public static final int NUM_BYTES = NUM_BITS / MemorySystemConstants.BITS_IN_BYTE;

	private final BitSet filter = new BitSet(NUM_BITS); // number of bits

	// Guava hash
	private static final HashFunction hf2 = Hashing.murmur3_32();
	private static final HashFunction hf3 = Hashing.murmur3_128();

	// Use two hash functions by default, help avoid false positives. This improves
	// the
	// network usage of fluidanimate, and helps the geomean slightly.
	private static final boolean useJavaHash = false;
	private static final boolean useMurmur3_32 = true;

	// private static final boolean useMurmur3_128 = false;
	public void clear() {
		filter.clear();
	}

	// We possibly need not bother with negative hashcodes
	// RZ: I encountered a negative hashcode with raytrace with simsmall.
	/*
	 * Exception in thread "main" java.lang.IndexOutOfBoundsException: bitIndex < 0:
	 * -16 at java.util.BitSet.set(BitSet.java:436) at
	 * simulator.viser.BloomFilter.setBit(BloomFilter.java:--) at
	 * simulator.viser.BloomFilter.add(BloomFilter.java:--) ...
	 */
	public void add(long lineAddress) {
		int bitPos;
		if (useJavaHash) {
			bitPos = getJavaHashBit(lineAddress);
			if (bitPos < 0) {
				System.out.println("Negative hashcode:" + bitPos + " from line address:" + lineAddress);
			}
			setBit(bitPos);
		}
		if (useMurmur3_32) {
			bitPos = getMurmur3_32_Bit(lineAddress);
			if (bitPos < 0) {
				System.out.println("Negative hashcode:" + bitPos + " from line address:" + lineAddress);
			}
			setBit(bitPos);
		}
		if (/* useMurmur3_128 || */ ViserSim.useTwoBloomFuncs()) {
			bitPos = getMurmur3_128_Bit(lineAddress);
			if (bitPos < 0) {
				System.out.println("Negative hashcode:" + bitPos + " from line address:" + lineAddress);
			}
			setBit(bitPos);
		}
	}

	private void setBit(int bitPos) {
		filter.set(bitPos);
	}

	// Java hashcode
	private int getJavaHashBit(long value) {
		int firstInt = Long.valueOf(value).hashCode();
		int firstBit = (Math.abs(firstInt)) % NUM_BITS;
		return firstBit;
	}

	// Guava murmur3_32
	private int getMurmur3_32_Bit(long value) {
		int secondInt = hf2.hashLong(value).asInt();
		int secondBit = Math.abs(secondInt) % NUM_BITS;
		return secondBit;
	}

	// Guava murmur3_128
	private int getMurmur3_128_Bit(long value) {
		long thirdLong = hf3.hashLong(value).asLong();
		int thirdBit = (int) (Math.abs(thirdLong) % NUM_BITS);
		return thirdBit;
	}

	public boolean contains(long lineAddress) {
		if (useJavaHash) {
			if (!filter.get(getJavaHashBit(lineAddress))) {
				return false;
			}
		}
		if (useMurmur3_32) {
			if (!filter.get(getMurmur3_32_Bit(lineAddress))) {
				return false;
			}
		}
		if (/* useMurmur3_128 || */ ViserSim.useTwoBloomFuncs()) {
			if (!filter.get(getMurmur3_128_Bit(lineAddress))) {
				return false;
			}
		}
		return true;
	}

	public void print() {
		System.out.println(filter.toString());
	}

	@Override
	public String toString() {
		return filter.toString();
	}

	public int cardinality() {
		return filter.cardinality();
	}
}
