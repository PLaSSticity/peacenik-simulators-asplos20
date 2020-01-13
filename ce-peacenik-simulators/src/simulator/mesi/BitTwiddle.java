package simulator.mesi;

public class BitTwiddle {
	public static boolean isPowerOf2(long n) {
		// thank you, Hacker's Delight!
		return 0 == (n & (n - 1));
	}

	public static int floorLog2(long n) {
		// thank you, Hacker's Delight!
		if (n == 0)
			return -1;

		int pos = 0;
		if (n >= 1L << 32) {
			n >>= 32;
			pos += 32;
		}
		if (n >= 1 << 16) {
			n >>= 16;
			pos += 16;
		}
		if (n >= 1 << 8) {
			n >>= 8;
			pos += 8;
		}
		if (n >= 1 << 4) {
			n >>= 4;
			pos += 4;
		}
		if (n >= 1 << 2) {
			n >>= 2;
			pos += 2;
		}
		if (n >= 1 << 1) {
			pos += 1;
		}
		return pos;
	}
}
