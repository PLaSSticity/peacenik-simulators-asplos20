package simulator.viser;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

class AIMResponse<Line extends ViserLine> {
	/** The level of the cache hierarchy where the hit occurred */
	public CacheLevel whereHit;

	@Override
	public String toString() {
		return whereHit.toString();
	}
}

// This class just simulates an AIM cache structure, without actually storing
// the values. It would require 132 bytes
// to actually store all the metadata for 8 cores + 4 bytes version. Instead we
// just want to model the hit/miss ratio,
// so we store tags.
public final class AIMCache<Line extends ViserLine> {
	private final int assoc = 4;
	// this corresponds to the data line size, and not the actual capacity that
	// should also include
	// the metadata
	private final int lineSize = 64;
	private final int numLines; // Total lines (set * assoc)

	private int numSets;
	/** log_2(line size) */
	private short lineOffsetBits;
	/** mask used to clear out the tag bits */
	private long indexMask;

	List<Deque<Line>> sets;
	private LineFactory<Line> lineFactory;

	private CacheLevel levelInHierarchy = CacheLevel.L3;

	public HierarchicalCache<Line> l3cache;
	public Processor<Line> processor;

	/** Return the cache index for the given address */
	protected int index(long address) {
		return (int) ((address >> lineOffsetBits) & indexMask);
	}

	public AIMCache(HierarchicalCache<Line> llc, LineFactory<Line> factory, Processor<Line> processor) {
		assert BitTwiddle.isPowerOf2(assoc);
		assert BitTwiddle.isPowerOf2(lineSize);

		this.l3cache = llc;
		this.lineFactory = factory;
		this.processor = processor;

		this.numLines = ViserSim.Options.valueOf(Knobs.NumAIMLines); // 1 << 15
																		// or
																		// 32K
																		// is
																		// the
																		// default
		this.numSets = numLines / assoc;
		assert BitTwiddle.isPowerOf2(numSets);

		indexMask = numSets - 1;
		lineOffsetBits = (short) BitTwiddle.floorLog2(lineSize);

		sets = new ArrayList<Deque<Line>>(numSets);

		for (int i = 0; i < numSets; i++) {
			Deque<Line> set = new LinkedList<Line>();
			for (int j = 0; j < assoc; j++) {
				// the processor is always P0.
				assert this.processor.id.get() == 0;
				Line line = lineFactory.create(this.processor, this.levelInHierarchy);
				set.add(line);
			}
			sets.add(set);
		}
	}

	/** Just get the corresponding line */
	private Line getLine(Line line) {
		return getLine(line.lineAddress());
	}

	/** Just get the corresponding line */
	private Line getLine(LineAddress addr) {
		Deque<Line> set = sets.get(index(addr.get()));
		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.lineAddress() != null) {
				if (line.lineAddress().get() == addr.get()) {
					assert line.id().equals(processor.id);
					assert !line.isPrivateCacheLine();
					return line;
				}
			}
		}
		return null;
	}

	public AIMResponse<Line> request(Processor<Line> proc, final ByteAddress address, boolean read) {
		Deque<Line> set = sets.get(index(address.get()));
		assert set.size() == assoc;

		AIMResponse<Line> ret = new AIMResponse<Line>();

		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();

			if (line.lineAddress() != null && line.contains(address)) {
				ret.whereHit = this.levelInHierarchy;
				assert line.id().equals(processor.id);
				assert !line.isPrivateCacheLine();
				return ret;
			}
		}

		// if we made it here, we missed in this cache
		ret.whereHit = CacheLevel.MEMORY;
		// Unlike normal caches, this is just for lookup. We need not fetch
		// lines from memory

		return ret;
	}

	public interface LineVisitor<Line> {
		public void visit(Line l);
	}

	/**
	 * Calls the given visitor function once on each line in this cache. Lines
	 * are traversed in no particular order.
	 */
	public void visitAllLines(LineVisitor<Line> lv) {
		for (Deque<Line> set : sets) {
			for (Line l : set) {
				lv.visit(l);
			}
		}
	}

	/** Verify that each line is indexed into the proper set. */
	public void verifyIndices() {
		for (int i = 0; i < sets.size(); i++) {
			Deque<Line> set = sets.get(i);
			for (Line l : set) {
				if (l.lineAddress() != null) {
					assert index(l.lineAddress().get()) == i;
				}
			}
		}
	}

	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		if (this.levelInHierarchy.compareTo(this.processor.llc()) < 0) {
			s.append(this.processor + "\n");
		}
		s.append("aimcache=" + this.levelInHierarchy + System.getProperty("line.separator"));
		for (Deque<Line> set : sets) {
			for (Line l : set) {
				s.append(l.toString() + "\n");
			}
			s.append(System.getProperty("line.separator"));
		}

		return s.toString();
	}

	/**
	 * Intelligently select a victim line for eviction, based on metadata
	 * staleness. Otherwise, just fallback to LRU.
	 */
	public Line getVictimLineNoRegionBoundaryClearance(Processor<Line> proc, Line incoming) {
		assert !proc.params.clearAIMCacheAtRegionBoundaries();
		Deque<Line> set = sets.get(index(incoming.lineAddress().get()));
		Line remove = null;
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line l = it.next();
			if (l.lineAddress() != null) {
				assert l.id().equals(processor.id);
				assert !l.isPrivateCacheLine();

				Line llcLine = proc.L3cache.getLine(l);
				assert llcLine != null;
				boolean valid = false;
				for (int i = 0; i < proc.params.numProcessors(); i++) {
					CpuId cpuId = new CpuId(i);
					Processor<Line> p = proc.machine.getProc(cpuId);
					PerCoreLineMetadata md = llcLine.getPerCoreMetadata(cpuId);
					assert md != null;
					assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
					if (llcLine.hasReadOffsets(cpuId) || llcLine.hasWrittenOffsets(cpuId)) {
						valid = true;
						break;
					}
				}
				// Line would have been removed during earlier clearance
				if (!valid) {
					remove = l;
					break;
				}
			}
		}
		if (remove == null) {
			remove = set.getLast(); // fallback
		}
		return remove;
	}

	public void evictLine(Processor<Line> proc, Line toEvict) {
		Deque<Line> set = sets.get(index(toEvict.lineAddress().get()));
		boolean removed = false;
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line l = it.next();
			if (l.lineAddress() != null && l.lineAddress().equals(toEvict.lineAddress())) {
				assert l.id().equals(processor.id);
				assert !l.isPrivateCacheLine();

				it.remove();
				if (l.lineAddress() != null) {
					proc.stats.pc_aim.pc_LineEvictions.incr();
				}
				removed = true;
			}
		}
		if (removed) {
			set.addLast(lineFactory.create(processor, levelInHierarchy));
		}
		assert set.size() == assoc;
	}

	// Add blindly without checking for duplicates
	public void addLineWithoutCheckingForDuplicates(Processor<Line> proc, Line memLine) {
		assert getLine(memLine) == null : "Line is already added! " + memLine;
		LineAddress la = memLine.lineAddress();
		Deque<Line> set = sets.get(index(la.get()));
		Line toEvict = null;
		if (!proc.params.clearAIMCacheAtRegionBoundaries()) {
			toEvict = getVictimLineNoRegionBoundaryClearance(proc, memLine);
		} else {
			toEvict = set.getLast(); // LRU
		}
		boolean found = set.remove(toEvict);
		assert found;
		if (toEvict.lineAddress() != null) {
			proc.stats.pc_aim.pc_LineEvictions.incr();
		}

		// NB: add the incoming line *after* the eviction handler runs
		Line copy = lineFactory.create(processor, levelInHierarchy, memLine.lineAddress());
		set.addFirst(copy);
	}

	// Add only after checking for duplicates
	public void addLineIfNotPresent(Processor<Line> proc, Line privLine) {
		LineAddress la = privLine.lineAddress();
		Deque<Line> set = sets.get(index(la.get()));
		for (Line l : set) {
			if (l.lineAddress() != null && l.lineAddress().equals(la)) {
				// Line is already present, so need not add
				return;
			}
		}
		// Line is not present, so add
		addLineWithoutCheckingForDuplicates(proc, privLine);
	}

	// I had issues with ConcurrentModificationException. So I get around the
	// problem by creating
	// a copy and selectively copying over lines.
	void clearAIMCache(Processor<Line> proc) {
		List<Deque<Line>> tmpSets = new ArrayList<Deque<Line>>(numSets);
		for (int i = 0; i < numSets; i++) {
			tmpSets.add(new LinkedList<Line>());
		}

		int i = 0;
		while (i < numSets) {
			Deque<Line> origSet = sets.get(i);
			Deque<Line> newSet = tmpSets.get(i);
			assert newSet.size() == 0;

			for (Line origLine : origSet) {
				assert origLine.id().equals(processor.id);
				assert !origLine.isPrivateCacheLine();

				if (origLine.lineAddress() == null) {
					newSet.addLast(lineFactory.create(processor, levelInHierarchy));
				} else {
					boolean valid = false;
					// An AIM line has to be present in the LLC.
					Line llcLine = proc.L3cache.getLine(origLine);
					assert llcLine != null;
					for (int j = 0; j < proc.params.numProcessors(); j++) {
						CpuId cpuId = new CpuId(j);
						if (llcLine.hasReadOffsets(cpuId) || llcLine.hasWrittenOffsets(cpuId)) {
							valid = true;
							break;
						}
					}

					if (valid) {
						Line copy = lineFactory.create(processor, levelInHierarchy, origLine.lineAddress());
						newSet.addLast(copy);
					} else {
						newSet.addLast(lineFactory.create(processor, levelInHierarchy));
					}

				}
			}

			assert newSet.size() == origSet.size();
			assert newSet.size() == assoc;
			i++;
		}

		// Reset the cache
		sets = tmpSets;
	}

	void clearAIMCache2(Processor<Line> proc) {
		Iterator<Deque<Line>> setIt = sets.iterator();
		while (setIt.hasNext()) {
			Deque<Line> deq = setIt.next();
			Iterator<Line> deqIt = deq.iterator();
			int numLinesRemoved = 0;
			while (deqIt.hasNext()) {
				Line aimLine = deqIt.next();
				assert aimLine.id().equals(new CpuId(0));

				if (aimLine.lineAddress() == null) {
					continue;
				}

				boolean valid = false;
				Line llcLine = proc.L3cache.getLine(aimLine);
				assert llcLine != null;
				for (int i = 0; i < proc.params.numProcessors(); i++) {
					CpuId cpuId = new CpuId(i);
					Processor<Line> p = proc.machine.getProc(cpuId);
					PerCoreLineMetadata md = llcLine.getPerCoreMetadata(cpuId);
					assert md != null;
					assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
					if (llcLine.hasReadOffsets(cpuId) || llcLine.hasWrittenOffsets(cpuId)) {
						valid = true;
						break;
					}
				}

				if (!valid) {
					deqIt.remove();
					numLinesRemoved++;
				}
			}
			for (int i = 0; i < numLinesRemoved; i++) {
				deq.addLast(lineFactory.create(processor, levelInHierarchy));
			}
			assert deq.size() == assoc;
		}
	}
}
