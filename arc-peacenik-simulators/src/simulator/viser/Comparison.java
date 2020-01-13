package simulator.viser;

public class Comparison<T extends Comparable<T>> {
	/** @return the min of a and b. */
	public T min(T a, T b) {
		if (a.compareTo(b) <= 0)
			return a;
		return b;
	}

	/** @return the max of a and b. */
	public T max(T a, T b) {
		if (a.compareTo(b) >= 0)
			return a;
		return b;
	}

	/** @return whether a >= b */
	public boolean gte(T a, T b) {
		return a.compareTo(b) >= 0;
	}

	/** @return whether a <= b */
	public boolean lte(T a, T b) {
		return a.compareTo(b) <= 0;
	}

	/** @return whether a > b */
	public boolean gt(T a, T b) {
		return a.compareTo(b) > 0;
	}

	/** @return whether a < b */
	public boolean lt(T a, T b) {
		return a.compareTo(b) < 0;
	}
}
