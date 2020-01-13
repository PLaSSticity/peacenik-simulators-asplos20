package simulator.viser;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.List;

import simulator.viser.Processor.ExecutionPhase;

interface CacheCallbacks<Line extends ViserLine> {
	/**
	 * Called whenever a line needs to be evicted.
	 *
	 * @param set   the set from which we need to evict something; the list is
	 *              ordered from MRU (front) to LRU (back).
	 * @param level the level of the cache where the eviction is happening
	 * @return the line to evict
	 */
	Line eviction(final Line incoming, final LinkedList<Line> set, CacheLevel level, ExecutionPhase phase, short bits);
}

class CacheConfiguration<Line extends ViserLine> {
	public int cacheSize;
	public int assoc;
	public int lineSize;
	public CacheLevel level;
}

enum CacheLevel {
	L1, L2, L3, MEMORY
};

/** The response from sending a request to the memory hierarchy */
class MemoryResponse<Line extends ViserLine> {
	/** The level of the cache hierarchy where the hit occurred */
	public CacheLevel whereHit;
	/** The line containing the requested address. */
	public Line lineHit;

	public boolean invalidStateHit; // the line was in a special invalid state
	public boolean invalidStateFailure; // implies line was in special invalid state, but version check failed
	public CacheLevel invalidStateSharedHitLevel; // applicable to special invalid case

	@Override
	public String toString() {
		return whereHit.toString() + " " + String.valueOf(lineHit);
	}
}

public class HierarchicalCache<Line extends ViserLine> {

	protected CacheLevel levelInHierarchy = CacheLevel.L1;

	protected int lineSize;
	/** associativity */
	protected int assoc;

	/** log_2(line size) */
	protected short lineOffsetBits;
	/** log_2(num sets) */
	protected short indexBits;
	/** mask used to clear out the tag bits */
	protected long indexMask;
	protected short assocMask;

	protected int numLines;

	protected List<LinkedList<Line>> sets;
	protected short[] MRUBits;
	protected CacheCallbacks<Line> callbacks;
	protected LineFactory<Line> lineFactory;

	/**
	 * The next higher-level cache in the hierarchy. Can be shared by multiple
	 * lower-level caches.
	 */
	public HierarchicalCache<Line> nextCache;
	/** Keep a reference to the parent processor. */
	public Processor<Line> processor;

	/** Return the cache index for the given address */
	protected int index(long address) {
		return (int) ((address >> lineOffsetBits) & indexMask);
	}

	/**
	 * Construct a component of the cache hierarchy. NB: the components must be
	 * constructed in reverse level order, from highest/last-level -> lowest-level.
	 *
	 * @param thisConfig geometry and event handlers for this cache
	 * @param nextCache  the next higher cache in the hierarchy
	 * @param factory    factory for making Line objects
	 */
	public HierarchicalCache(CacheConfiguration<Line> thisConfig, CacheCallbacks<Line> handler,
			HierarchicalCache<Line> nextCache, LineFactory<Line> factory, Processor<Line> processor) {
		this.lineSize = thisConfig.lineSize;
		this.assoc = thisConfig.assoc;
		this.assocMask = (short) (Math.pow(2, assoc) - 1);
		this.callbacks = handler;
		this.nextCache = nextCache;
		this.lineFactory = factory;
		this.levelInHierarchy = thisConfig.level;
		this.processor = processor;

		// construct the cache itself

		assert BitTwiddle.isPowerOf2(thisConfig.lineSize);
		assert BitTwiddle.isPowerOf2(thisConfig.assoc);
		assert BitTwiddle.isPowerOf2(thisConfig.cacheSize);

		int numSets = thisConfig.cacheSize / (thisConfig.lineSize * thisConfig.assoc);
		assert BitTwiddle.isPowerOf2(numSets);
		assert numSets * thisConfig.lineSize * thisConfig.assoc == thisConfig.cacheSize;
		indexMask = numSets - 1;
		indexBits = (short) BitTwiddle.floorLog2(numSets);
		lineOffsetBits = (short) BitTwiddle.floorLog2(thisConfig.lineSize);
		numLines = thisConfig.cacheSize / thisConfig.lineSize;

		sets = new ArrayList<LinkedList<Line>>(numSets);
		MRUBits = new short[numSets];

		for (int i = 0; i < numSets; i++) {
			LinkedList<Line> set = new LinkedList<Line>();
			for (int j = 0; j < thisConfig.assoc; j++) {
				// For the LLC, the processor is always P0.
				if (levelInHierarchy == CacheLevel.L3) {
					assert this.processor.id.get() == 0;
				}
				Line line = lineFactory.create(this.processor, this.levelInHierarchy);
				set.add(line);
			}
			sets.add(set);
			MRUBits[i] = 0;
		}
	} // end ctor

	/**
	 * Update LLC line with updated write encoding, epoch, and values. Note that the
	 * corresponding LLC line might have been evicted by this time.
	 */
	boolean updateWriteInfoInLLC(Processor<Line> proc, Line privLine, ExecutionPhase phase) {
		assert privLine.hasWrittenOffsets(proc.id) : "Private line is expected to have write metadata.";
		assert phase == ExecutionPhase.PRE_COMMIT_L1
				|| phase == ExecutionPhase.PRE_COMMIT_L2 : "Writes are only from PRE_COMMIT";
		assert levelInHierarchy == CacheLevel.L3;
		assert privLine.getLevel().compareTo(CacheLevel.L3) < 0;
		assert processor.id.get() == 0;
		assert privLine.id().equals(proc.id);

		LinkedList<Line> set = sets.get(index(privLine.lineAddress().get()));

		boolean hit = true; // LLC hit or miss
		Line llcLine = getLine(privLine);

		// The line is not in the LLC, possibly it was evicted from the LLC, but not
		// from L1/L2 since they
		// are not inclusive. Bring in the line from memory. This is a policy decision,
		// we could have avoided
		// it, but it might be beneficial since we might use the line again during read
		// validation.
		if (llcLine == null) {
			hit = false;

			Line tmp = proc.machine.memory.get(privLine.lineAddress().get());
			assert tmp.getLevel() == CacheLevel.L3 && tmp.id().equals(processor.id) && !tmp.isLineDeferred();
			tmp = proc.clearAccessEncoding(tmp);
			llcLine = lineFactory.create(processor, levelInHierarchy, tmp);
			assert llcLine != null : "L1/L2 line should be present either in the LLC or in the memory";

			if (!proc.params.writebackInMemory()) {
				// Evict LRU line, bring line in from memory so that it can be updated
				Line toEvict = callbacks.eviction(privLine, set, levelInHierarchy, phase,
						MRUBits[index(privLine.lineAddress().get())]);
				if (proc.params.usePLRU()) {
					int lineIndex = set.indexOf(toEvict);
					set.set(lineIndex, llcLine);
					setMRUBit(llcLine, false);
				} else {
					boolean found = set.remove(toEvict);
					assert found;
					set.addFirst(llcLine);
				}
				// If we are evicting a valid line that has updated metadata,
				// then need to virtualize the line in memory
				if (toEvict.valid()) {
					evictValidLineFromLLC(proc, toEvict, phase);
					if (proc.params.useAIMCache()) {
						proc.aimcache.evictLine(proc, toEvict);
					}
				}
			}
		}

		CpuId cid = privLine.id();
		// clear obsolete encodings before set epoch
		llcLine.orReadEncoding(cid, 0L);
		llcLine.orWriteEncoding(cid, 0L);

		// Set the updated epoch on writing back to a shared line
		assert privLine.getEpoch(cid).equals(proc.getCurrentEpoch());
		llcLine.setEpoch(cid, privLine.getEpoch(cid));

		// Check if the LLC line is "already" deferred by a different core, if yes, then
		// get the values
		// from the owner core
		assert privLine.isDeferredWriteBitSet() || !proc.params.BackupDeferredWritebacksLasily();
		if (proc.params.deferWriteBacks()) {
			// TODO opt opportunities: all the offsets of the line have been touched
			if (llcLine.getVersion() != privLine.getVersion() ||
			// the following is for L2 lines whose versions have been updated during
			// validation
					privLine.isThereAConcurrentRemoteWrite()) {
				// cannot defer the private line
				if (llcLine.isLineDeferred() && llcLine.getDeferredLineOwnerID() != proc.id.get()) {
					proc.fetchDeferredLineFromPrivateCache(llcLine, false, false);
				}
				assert !llcLine.isLineDeferred();
				// not needed
				llcLine.clearDeferredLineOwnerID();
				llcLine.copyWrittenValuesFromSource(privLine);
				proc.hasDirtyEviction = true;
				privLine.resetDeferredWriteBit();
			} else { // the private line is up-to-date.
				// No need to fetch.
				assert !llcLine.isLineDeferred() || llcLine.getVersion() == privLine.getVersion();
				if (proc.params.areDeferredWriteBacksPrecise()) {
					// Back up the dirty bytes information, i.e., the write metadata
					Long existingWrMd = proc.getDeferredWriteMetadata(privLine);
					Long newWrMd = Long.valueOf(privLine.getWriteEncoding(cid));
					// Check whether the line is already deferred, in that case need to OR the write
					// metadata
					if (existingWrMd != null) {
						assert Long.bitCount(existingWrMd) > 0 : "Otherwise it should not have been deferred";
						newWrMd |= existingWrMd;
					}
					proc.wrMdDeferredDirtyLines.put(privLine.lineAddress().get(), newWrMd);
				}
				llcLine.setDefferedLineOwnerID(proc.id.get());
				// back up deferred write-backs in L2 eagerly
				if ((proc.params.restartAtFailedValidationsOrDeadlocks() || proc.params.FalseRestart())
						&& !proc.params.BackupDeferredWritebacksLasily()) {
					if (privLine.getLevel() == CacheLevel.L1) {
						Line l2Line = proc.L2cache.getLine(privLine);
						assert l2Line.valid();
						l2Line.copyAllValues(privLine);
						l2Line.setDeferredWriteBit();
					} else {
						privLine.setDeferredWriteBit();
					}
				}
			}
		} else {
			privLine.resetDeferredWriteBit();
			llcLine.copyWrittenValuesFromSource(privLine);
		}

		if (privLine.getLevel() == CacheLevel.L1 && proc.params.BackupDeferredWritebacksLasily())
			proc.L2cache.getLine(privLine).resetDeferredWriteBit();

		// For site tracking, set last writers even though the actual values are
		// deferred.
		llcLine.setLastWritersFromPrivateLine(privLine);
		llcLine.orWriteEncoding(cid, privLine.getWriteEncoding(cid));
		llcLine.updateWriteSiteInfo(cid, privLine.getWriteEncoding(cid), privLine.getWriteSiteInfo(cid),
				privLine.getWriteLastSiteInfo(cid));
		// always increase version no matter whether deferring write backs
		llcLine.incrementVersion();
		llcLine.setDirty(true);
		if (proc.params.useBloomFilter()) {
			proc.updatePerCoreBloomFilters(llcLine);
		}

		if (proc.params.writebackInMemory()) {
			proc.machine.memory.put(llcLine.lineAddress().get(), llcLine);
		}

		if (proc.params.useAIMCache()) {
			if (privLine.hasReadOffsets(cid) || privLine.hasWrittenOffsets(cid)) {
				// The line might be in the AIM cache
				proc.aimcache.addLineIfNotPresent(proc, privLine);
			}
		}
		return hit;
	}

	// We include network traffic costs in the callers.
	/** Update read encoding in the LLC. */
	private boolean updateReadInfoInLLC(Processor<Line> proc, Line privLine, ExecutionPhase phase) {
		assert levelInHierarchy == CacheLevel.L3;
		assert privLine.getLevel().compareTo(CacheLevel.L3) < 0;
		assert processor.id.get() == 0;
		assert privLine.id().equals(proc.id);

		LinkedList<Line> set = sets.get(index(privLine.lineAddress().get()));

		boolean hit = true; // LLC hit or miss
		Line llcLine = getLine(privLine);

		// The line is not in the LLC, possibly it was evicted from the LLC, but not
		// from L1/L2 since they
		// are not inclusive. Bring in the line from memory. This is a policy decision,
		// we could have avoided
		// it, but it might be beneficial since we might use the line again during read
		// validation.
		if (llcLine == null) {
			hit = false;

			Line tmp = proc.machine.memory.get(privLine.lineAddress().get());
			assert tmp.getLevel() == CacheLevel.L3 && tmp.id().equals(processor.id) && !tmp.isLineDeferred();
			tmp = proc.clearAccessEncoding(tmp);
			llcLine = lineFactory.create(processor, levelInHierarchy, tmp);
			assert llcLine != null : "L1/L2 line should be present either in the LLC or in the memory";

			if (!proc.params.writebackInMemory()) {
				// Evict LRU line, bring line in from memory so that it can be updated
				Line toEvict = callbacks.eviction(privLine, set, levelInHierarchy, phase,
						MRUBits[index(privLine.lineAddress().get())]);

				if (proc.params.usePLRU()) {
					int lineIndex = set.indexOf(toEvict);
					set.set(lineIndex, llcLine);
					setMRUBit(llcLine, false);
				} else {
					boolean found = set.remove(toEvict);
					assert found;
					set.addFirst(llcLine);
				}
				// If we are evicting a valid line that has updated metadata,
				// then need to virtualize the line in memory
				if (toEvict.valid()) {
					evictValidLineFromLLC(proc, toEvict, phase);
					if (proc.params.useAIMCache()) {
						proc.aimcache.evictLine(proc, toEvict);
					}
				}
			}
		}

		CpuId cid = privLine.id();
		// clear obsolete encodings before set epoch
		llcLine.orReadEncoding(cid, 0L);
		llcLine.orWriteEncoding(cid, 0L);
		// Set the updated epoch on writing back to a shared line
		assert privLine.getEpoch(cid).equals(proc.getCurrentEpoch());
		llcLine.setEpoch(cid, privLine.getEpoch(cid));

		llcLine.orReadEncoding(cid, privLine.getReadEncoding(cid));
		llcLine.updateReadSiteInfo(cid, privLine.getReadEncoding(cid), privLine.getReadSiteInfo(cid),
				privLine.getReadLastSiteInfo(cid));

		if (proc.params.writebackInMemory()) {
			proc.machine.memory.put(llcLine.lineAddress().get(), llcLine);
		}

		if (proc.params.useAIMCache()) {
			if (privLine.hasReadOffsets(cid) || privLine.hasWrittenOffsets(cid)) {
				// The line might be in the AIM cache
				proc.aimcache.addLineIfNotPresent(proc, privLine);
			}
		}
		return hit;
	}

	// We include network traffic costs in the callers.
	// Only check conflicts for written lines while making no changes in the LLC
	// except for fetching the line from the
	// memory if missing
	boolean checkConflictsForWrittenLines(Processor<Line> proc, Line privLine, ExecutionPhase phase) {
		assert levelInHierarchy == CacheLevel.L3;
		assert privLine.getLevel().compareTo(CacheLevel.L3) < 0;
		assert processor.id.get() == 0;
		assert privLine.id().equals(proc.id);

		LinkedList<Line> set = sets.get(index(privLine.lineAddress().get()));

		boolean hit = true; // LLC hit or miss
		Line llcLine = getLine(privLine);

		// The line is not in the LLC, possibly it was evicted from the LLC, but not
		// from L1/L2 since they
		// are not inclusive. Bring in the line from memory. This is a policy decision,
		// we could have avoided
		// it, but it might be beneficial since we might use the line again during read
		// validation.
		if (llcLine == null) {
			hit = false;

			Line tmp = proc.machine.memory.get(privLine.lineAddress().get());
			assert tmp.getLevel() == CacheLevel.L3 && tmp.id().equals(processor.id) && !tmp.isLineDeferred();
			tmp = proc.clearAccessEncoding(tmp);
			llcLine = lineFactory.create(processor, levelInHierarchy, tmp);
			assert llcLine != null : "L1/L2 line should be present either in the LLC or in the memory";

			if (!proc.params.writebackInMemory()) {
				// Evict LRU line, bring line in from memory so that it can be updated
				Line toEvict = callbacks.eviction(privLine, set, levelInHierarchy, phase,
						MRUBits[index(privLine.lineAddress().get())]);

				if (proc.params.usePLRU()) {
					int lineIndex = set.indexOf(toEvict);
					set.set(lineIndex, llcLine);
					setMRUBit(llcLine, false);
				} else {
					boolean found = set.remove(toEvict);
					assert found;
					set.addFirst(llcLine);
				}
				// If we are evicting a valid line that has updated metadata,
				// then need to virtualize the line in memory
				if (toEvict.valid()) {
					evictValidLineFromLLC(proc, toEvict, phase);
					if (proc.params.useAIMCache()) {
						proc.aimcache.evictLine(proc, toEvict);
					}
				}
			}
		}

		// Check for a precise conflict on writes
		assert phase == ExecutionPhase.PRE_COMMIT_L2
				|| phase == ExecutionPhase.PRE_COMMIT_L1 : "Writes are only from PRE_COMMIT";
		proc.checkPreciseConflicts(llcLine, privLine, ExecutionPhase.PRE_COMMIT);
		if (proc.restartRegion || proc.reRunEvent)
			return hit;

		// No need to pre-validate WAR lines since we send values after
		// performReadValidation().
		return hit;
	}

	/**
	 * Write back the values and read encoding to the L2 cache line.
	 *
	 * @param line is the L1 cache line
	 */
	void handleWriteAfterReadUpgrade(Line l1Line, long enc) {
		assert levelInHierarchy == CacheLevel.L2 : "This method is valid for only L2 caches.";
		assert l1Line.isAccessedInThisRegion(l1Line.id());

		Line l2Line = getLine(l1Line); // this returns a reference
		assert l2Line != null : "L1 and L2 caches are inclusive";
		assert l2Line.id() == l1Line.id();

		l2Line.copyRequestedValues(l1Line, enc);
		// Encoding updates should comply with value updates and be only for those
		// involved in a WAR upgrade
		l2Line.orReadEncoding(l1Line.id(), enc);
		// updateSourceLocationInfo(l2Line, l1Line, processor.id, true);// !! wrong:
		// only those offsets which have been
		// accessed in enc should be updated
		CpuId cid = l1Line.id();
		l2Line.updateReadSiteInfo(cid, enc, l1Line.getReadSiteInfo(cid), l1Line.getReadLastSiteInfo(cid));
		// There is no on-chip communication since this is between private L1 and L2
		// caches.
	}

	/**
	 * Private L1 and L2 caches are inclusive. This implies a recall if a valid line
	 * is evicted from L2. Note that the L2 cache is larger than L1, so the cache
	 * line may not be present in L1 at the time of recall.
	 */
	Line recallFromL1Cache(CacheLevel level, Line toEvict) {
		assert (level == CacheLevel.L2) && (levelInHierarchy == CacheLevel.L1) : "Only L1 and L2 are inclusive.";
		LinkedList<Line> set = sets.get(index(toEvict.lineAddress().get()));
		assert set.size() == assoc;
		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.valid() && line.lineAddress().get() == toEvict.lineAddress().get()) {
				Line tmp = lineFactory.create(processor, levelInHierarchy, line);
				if (processor.params.usePLRU()) {
					resetMRUBit(line);
					int lineIndex = set.indexOf(line);
					set.set(lineIndex, lineFactory.create(processor, levelInHierarchy));
				} else {
					it.remove();
					// Create an empty line
					set.addLast(lineFactory.create(processor, levelInHierarchy));
				}
				return tmp; // A valid line should only be found in one slot, so we can safely break
			}
		}
		return null;
	}

	// Virtualize a valid line that is getting evicted from the LLC. The LLC and the
	// private L1/L2 caches
	// are not inclusive.
	private void evictValidLineFromLLC(Processor<Line> proc, Line toEvict, ExecutionPhase phase) {
		assert this.levelInHierarchy == CacheLevel.L3 && toEvict.valid();
		assert toEvict.id().equals(new CpuId(0));

		// Check if the line is deferred, if yes, then get the values from the owner
		// core
		if (proc.params.deferWriteBacks() && toEvict.isLineDeferred()) {
			proc.fetchDeferredLineFromPrivateCache(toEvict, false, false);
		}

		// Check if the line actually needs to be written back
		boolean mdWritebackNeeded = false;

		for (int i = 0; i < proc.params.numProcessors(); i++) {
			CpuId cpuId = new CpuId(i);
			PerCoreLineMetadata md = toEvict.getPerCoreMetadata(cpuId);
			Processor<Line> p = processor.machine.getProc(cpuId);
			assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
			// Write back read metadata only if the region is ongoing. Write back the write
			// metadata if the region is
			// ongoing. Write back values if the line is dirty.
			if (md.epoch.equals(p.getCurrentEpoch())) { /* The region is ongoing */
				// The private line metadata may not have been written back to the LLC, since we
				// do not have
				// inclusivity.
				// assert toEvict.hasReadOffsets(cpuId) || toEvict.hasWrittenOffsets(cpuId);

				// RCC need not write back a line unless it has valid metadata bits or is dirty.
				// The fact that a
				// private cache has a line cached in an ongoing core is not sufficient reason
				// to write back the line.
				if (toEvict.hasReadOffsets(cpuId)) {
					mdWritebackNeeded = true;
				}
				if (toEvict.hasWrittenOffsets(cpuId)) {
					mdWritebackNeeded = true;
				}
			}
		}

		if (mdWritebackNeeded || toEvict.dirty()) {
			if (mdWritebackNeeded) {
				proc.stats.pc_ViserLLCToMemoryMetadataWriteback.incr();
			}
		}

		assert !toEvict.isLineDeferred() : "Fetch values for deferred line before eviction.";

		toEvict.setDirty(false); // Clear before sending to memory
		// Since a line is getting evicted from the LLC, we can safely overwrite the
		// contents in memory (if present). We
		// always write back so that we can always assert that a line will either be in
		// the LLC or in memory. This is a
		// source of space overhead.
		proc.machine.memory.put(toEvict.lineAddress().get(), toEvict);
	}

	public MemoryResponse<Line> searchPrivateCache(final Line line) {
		return searchPrivateCache(line.lineAddress());
	}

	public MemoryResponse<Line> searchPrivateCache(final LineAddress addr) {
		if (levelInHierarchy.compareTo(this.processor.llc()) >= 0) {
			throw new RuntimeException("Wrong cache level");
		}

		MemoryResponse<Line> ret = new MemoryResponse<Line>();
		final LinkedList<Line> set = sets.get(index(addr.get()));
		// search this cache
		for (Line l : set) {
			if (l.valid() && l.lineAddress().get() == addr.get()) {
				// hit!
				ret.lineHit = l;
				ret.whereHit = levelInHierarchy;
				return ret;
			}
		}

		if (nextCache != null && nextCache.levelInHierarchy.compareTo(processor.llc()) < 0) {
			return nextCache.searchPrivateCache(addr);
		}

		return ret;
	}

	/** Just get the corresponding line, from any cache. */
	public Line getLine(Line line) {
		return getLine(line.lineAddress());
	}

	/** Just get the corresponding line, from any cache. */
	public Line getLine(LineAddress addr) {
		LinkedList<Line> set = sets.get(index(addr.get()));
		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.valid() && line.lineAddress().get() == addr.get()) {
				return line;
			}
		}
		return null;
	}

	private void updateL2LineWithL1Line(Processor<Line> proc, Line destLine, Line sourceLine, boolean isL1Eviction) {
		assert destLine.valid() && sourceLine.valid();
		assert destLine.getVersion() == sourceLine.getVersion() : "L1 and L2 line versions are read-only.";
		assert destLine.id() == sourceLine.id();

		CpuId cid = sourceLine.id();

		if ((processor.params.restartAtFailedValidationsOrDeadlocks() || processor.params.FalseRestart())
				&& sourceLine.hasWrittenOffsets(cid) && destLine.isDeferredWriteBitSet()
				&& destLine.getWriteEncoding(cid) == 0) {
			// write dirty values to a deferred l2line from L1
			Line llcLine = proc.L3cache.getLine(destLine);
			assert proc.params.deferWriteBacks();
			assert llcLine != null && llcLine.valid() && llcLine.isLineDeferred();
			llcLine.copyAllValues(destLine);
			llcLine.clearDeferredLineOwnerID();
			destLine.resetDeferredWriteBit();
		}

		if (sourceLine.hasReadOffsets(cid)) {
			destLine.orReadEncoding(cid, sourceLine.getReadEncoding(cid));
			destLine.updateReadSiteInfo(cid, sourceLine.getReadEncoding(cid), sourceLine.getReadSiteInfo(cid),
					sourceLine.getReadLastSiteInfo(cid));
		}

		if (isL1Eviction) {
			if (sourceLine.hasWrittenOffsets(cid)) {
				setMRUBit(destLine, false); // Ensure the invariance that we set MRU bits every time setting write bits
			}
		} else if (!(destLine.hasWrittenOffsets(cid) || destLine.l2WriteBit) && sourceLine.hasWrittenOffsets(cid)) {
			proc.stats.pc_CleanL2DirtyL1OnL2Eviction.incr();
		}

		destLine.orWriteEncoding(cid, sourceLine.getWriteEncoding(cid));

		if (!isL1Eviction && destLine.hasWrittenOffsets(cid))
			proc.stats.pc_DirtyL2Evictions.incr();

		// l1 might be a deferred line even if it has no write bit set.
		if (proc.params.BackupDeferredWritebacksLasily() && sourceLine.isDeferredWriteBitSet())
			destLine.setDeferredWriteBit();
		destLine.updateWriteSiteInfo(cid, sourceLine.getWriteEncoding(cid), sourceLine.getWriteSiteInfo(cid),
				sourceLine.getWriteLastSiteInfo(cid));
		/*
		 * With ARC's optimizations, even untouched offsets may have values which
		 * haven't been updated to the LLC due to deferring write-backs or have been
		 * updated to the LLC but not to the L2. In either case, they shouldn't be lost
		 * (in the second case, if the values are not updated to the L2, following reads
		 * will read obsolete values from the L2 and cause read validation failure). So
		 * we need to copyAllValues from the L1 line to the L2 line here.
		 */
		destLine.copyAllValues(sourceLine);
		destLine.setLockOwnerID(sourceLine.getLockOwnerID());
	}

	protected void evictedFromHigherCache(Processor<Line> proc, Line incoming, ExecutionPhase phase) {
		assert levelInHierarchy == CacheLevel.L2 || levelInHierarchy == CacheLevel.L3;
		assert proc.id == incoming.id();

		if (incoming.invalid()) { // Ignore the eviction of invalid lines
			return;
		}

		if (levelInHierarchy == CacheLevel.L2) {
			assert this.processor.params.useL2() && proc == processor;
			assert incoming.getLevel() == CacheLevel.L1;
			// L1 and L2 caches are inclusive, this implies the line "incoming"
			// should be present in L2
			if (ViserSim.xassertsEnabled()) {
				proc.Verify.verifyPrivateCacheInclusivityAndVersions(proc);
			}
			evictedFromL1Cache(proc, incoming, phase);
		} else {
			assert incoming.getLevel() == CacheLevel.L2;
			evictedFromL2Cache(proc, incoming, phase);
		}
	}

	// Only for WAR upgrades.
	private boolean validateReadL2LineOnL1Eviction(Processor<Line> proc, Line l1Line, Line l2Line,
			ExecutionPhase phase) {
		assert proc.id.equals(l2Line.id()) && proc.id.equals(l1Line.id());
		assert l2Line.hasReadOffsets(l2Line.id()) : "The read must have been backed up in the L2";
		assert l1Line.hasReadOffsets(l1Line.id()) : "There must be an WAR upgrade";

		// Check for version mismatch,
		// do precise write-read conflict checking
		// if no conflict, then do value validation
		// If versions match and write bit is set in the shared line,
		// check for precise conflicts

		// The following doesn't fetch the line from memory to LLC even if a LLC miss
		MemoryResponse<Line> resp = proc.getLineFromLLCOrMemory(l2Line);
		assert resp.lineHit != null;
		Line sharedLine = resp.lineHit;

		if (proc.params.validateL1ReadsAlongWithL2()) {
			l2Line.orReadEncoding(proc.id, l1Line.getReadEncoding(proc.id) & ~l1Line.getWriteEncoding(proc.id));
			l2Line.copyReadOnlyValuesFromSource(l1Line);
			l2Line.updateReadSiteInfo(proc.id, l1Line.getReadEncoding(proc.id) & ~l1Line.getWriteEncoding(proc.id),
					l1Line.getReadSiteInfo(proc.id), l1Line.getReadLastSiteInfo(proc.id));
		}

		if (!proc.matchVersions(l2Line, sharedLine)) {
			// might save the cost of value validation, but introduce false validation
			// failure when values match
			// boolean conflict = proc.checkPreciseWriteReadConflicts(sharedLine, l2Line,
			// ExecutionPhase.L1_EARLY_READ_VALIDATION, false);

			// if (!conflict) {
			if (proc.params.deferWriteBacks() && sharedLine.isLineDeferred()) {
				if (sharedLine.getDeferredLineOwnerID() != proc.id.get()) {
					proc.fetchDeferredLineFromPrivateCache(sharedLine, false, false);
				}
			}

			short[] countExtraCosts = new short[1];
			if (proc.valueValidateReadLine(ExecutionPhase.EVICT_L1_READ_VALIDATION, l2Line, sharedLine,
					countExtraCosts)) {
				proc.stats.pc_FailedValidations.incr();
				// Before pre-commit
				if (!proc.regionWithExceptions) {
					proc.stats.pc_RegionsWithFRVs.incr();
					if (!proc.regionHasDirtyEvictionBeforeFRV && proc.hasDirtyEviction) {
						proc.stats.pc_RegionHasDirtyEvictionBeforeFRV.incr();
						proc.regionHasDirtyEvictionBeforeFRV = true;
					}

					if (proc.params.restartAtFailedValidationsOrDeadlocks() && !proc.hasDirtyEviction) {
						// Read validation failed, restart.
						proc.stats.pc_TotalRegionRestarts.incr();
						assert !proc.restartRegion;
						proc.restartRegion = true;
						proc.prepareRestart();
						return false;
					} else {
						proc.stats.pc_ExceptionsByFRVs.incr();
						proc.stats.pc_RegionsWithExceptionsByFRVs.incr();

						proc.stats.pc_RegionsWithExceptions.incr();
						proc.regionWithExceptions = true;
						if (proc.params.isHttpd() && proc.inTrans) { // restart transaction
							proc.updateStatsForTransRestart();
						} else {
							proc.updateStatsForReboot();
						}
					}
				}
			}
			// }
		} else { // matched versions
			if (proc.checkIfWriteBitIsSet(sharedLine)) {
				proc.checkPreciseWriteReadConflicts(sharedLine, l2Line, ExecutionPhase.EVICT_L1_READ_VALIDATION);
				if (proc.restartRegion || proc.reRunEvent)
					return false;
			}
		}

		boolean hit = nextCache.updateReadInfoInLLC(proc, l2Line, phase);

		long l2ReadBits = l2Line.getReadEncoding(proc.id);
		l2Line.clearReadEncoding(proc.id);

		if (proc.params.validateL1ReadsAlongWithL2()) {
			l1Line.clearReadEncoding(proc.id);
		} else {
			// Clear only those matching read bits from the L1 line that have already been
			// validated from the L2 line
			long l1ReadBits = l1Line.getReadEncoding(proc.id);
			l1ReadBits = l1ReadBits & (~l2ReadBits);
			l1Line.clearReadEncoding(proc.id);
			l1Line.orReadEncoding(proc.id, l1ReadBits);
			// it's ok not to update read site info
		}
		return hit;
	}

	/**
	 * Insert the incoming L1 line in this L2 cache.
	 */
	private void evictedFromL1Cache(Processor<Line> proc, Line incomingL1Line, ExecutionPhase phase) {
		// search this L2 cache
		Line l2Line = getLine(incomingL1Line);
		assert l2Line != null : "L1 and L2 are inclusive";
		assert l2Line.getVersion() == incomingL1Line.getVersion() : "L1 and L2 versions should match";
		assert l2Line.id() == incomingL1Line.id() && l2Line.getLevel() == CacheLevel.L2;

		// Validate the existing L2 line if write-after-read upgrade
		if (incomingL1Line.isWrittenAfterRead(incomingL1Line.id())) {
			validateReadL2LineOnL1Eviction(proc, incomingL1Line, l2Line, phase);

			// Send read encoding to the LLC for an upgrade so as to eagerly catch
			// read-write conflicts
			// Moved into validateReadL2LineOnL1Eviction.
			// We are not counting the execution cost, since this is not on
			// the critical path

			proc.updateVersionSizeHistogram(l2Line.getVersion());

			if (proc.reRunEvent || proc.restartRegion)
				return;
		}
		updateL2LineWithL1Line(proc, l2Line, incomingL1Line, true);
	}

	private enum DeferredStatus {
		SAME_CORE, REMOTE_CORE, NOT_DEFERRED
	}

	/**
	 * Insert the incoming L2 line in this L3 cache.
	 */
	private void evictedFromL2Cache(Processor<Line> proc, Line incomingL2Line, ExecutionPhase phase) {
		// search this cache
		Line sharedLine = getLine(incomingL2Line);

		if (sharedLine == null) {
			LinkedList<Line> set = sets.get(index(incomingL2Line.lineAddress().get()));
			// fetch the line from memory
			sharedLine = proc.machine.memory.get(incomingL2Line.lineAddress().get());
			assert sharedLine != null : "L1/L2 line should be present either in the LLC or in the memory";
			assert sharedLine.id().get() == 0 && sharedLine.getLevel() == CacheLevel.L3 && !sharedLine.isLineDeferred();

			// Incoming line is not present in the LLC
			if (!proc.params.writebackInMemory()) {
				// Evict LRU line, bring line in from memory so that it can be
				// updated
				Line toEvict = callbacks.eviction(incomingL2Line, set, levelInHierarchy, phase,
						MRUBits[index(incomingL2Line.lineAddress().get())]);

				if (proc.params.usePLRU()) {
					int lineIndex = set.indexOf(toEvict);
					set.set(lineIndex, sharedLine);
					setMRUBit(sharedLine, false);
				} else {
					boolean found = set.remove(toEvict);
					assert found;
					set.addFirst(sharedLine);
				}
				// If we are evicting a valid line that has updated metadata, then need to
				// virtualize the line in memory
				if (toEvict.valid()) {
					evictValidLineFromLLC(proc, toEvict, phase);
					if (proc.params.useAIMCache()) {
						proc.aimcache.evictLine(proc, toEvict);
					}
				}
			}
		}

		DeferredStatus status = DeferredStatus.NOT_DEFERRED;
		if (proc.params.deferWriteBacks()) { // LLC line may be marked deferred
			if (sharedLine.isLineDeferred()) {
				// First fetch the updated values for the deferred LLC line if
				// the owner is a different core
				if (sharedLine.getDeferredLineOwnerID() != proc.id.get()) {
					proc.fetchDeferredLineFromPrivateCache(sharedLine, false, false);
					status = DeferredStatus.REMOTE_CORE;
				} else {
					status = DeferredStatus.SAME_CORE;
					// cannot clear deferred id until writing back deferred values in
					// performInvalidation before restart
				}
			}
		}

		// WAR upgraded offsets have already been taken care of, this only needs to take
		// care of read-only offsets
		// boolean valueValidationRequired = false;
		if (incomingL2Line.hasReadOnlyOffsets(proc.id)) {
			// Check for version mismatch,
			// do precise write-read conflict checking
			// if no conflict, then do value validation
			// If versions match and write bit is set in the shared line,
			// check for precise conflicts

			if (!proc.matchVersions(incomingL2Line, sharedLine)) {
				// Check for precise conflict
				// might save the cost of value validation, but introduce false validation
				// failure when values match
				// boolean conflict = proc.checkPreciseWriteReadConflicts(sharedLine,
				// incomingL2Line,
				// ExecutionPhase.L2_EARLY_READ_VALIDATION, false);

				short[] countExtraCosts = new short[1];
				if (proc.valueValidateReadLine(ExecutionPhase.EVICT_L2_READ_VALIDATION, incomingL2Line, sharedLine,
						countExtraCosts)) {
					proc.stats.pc_FailedValidations.incr();
					if (!proc.regionWithExceptions) {
						proc.stats.pc_RegionsWithFRVs.incr();
						if (!proc.regionHasDirtyEvictionBeforeFRV && proc.hasDirtyEviction) {
							proc.stats.pc_RegionHasDirtyEvictionBeforeFRV.incr();
							proc.regionHasDirtyEvictionBeforeFRV = true;
						}

						if (proc.params.restartAtFailedValidationsOrDeadlocks() && !proc.hasDirtyEviction) {
							// Read validation failed, restart.
							proc.stats.pc_TotalRegionRestarts.incr();
							assert !proc.restartRegion;
							proc.restartRegion = true;
							proc.prepareRestart();
							return;
						} else {
							proc.stats.pc_ExceptionsByFRVs.incr();
							proc.stats.pc_RegionsWithExceptionsByFRVs.incr();

							proc.stats.pc_RegionsWithExceptions.incr();
							proc.regionWithExceptions = true;
							if (proc.params.isHttpd() && proc.inTrans) { // restart transaction
								proc.updateStatsForTransRestart();
							} else {
								proc.updateStatsForReboot();
							}
						}
					}
				}
				// }
			} else { // versions match
				proc.checkPreciseWriteReadConflicts(sharedLine, incomingL2Line,
						ExecutionPhase.EVICT_L2_READ_VALIDATION);
				if (proc.restartRegion || proc.reRunEvent) {
					return;
				}
			}
		}

		// Incoming line from L2, so eagerly check for conflicts with other cores
		if (incomingL2Line.hasWrittenOffsets(proc.id)) {
			proc.checkPreciseConflicts(sharedLine, incomingL2Line, ExecutionPhase.REGION_L2_COMMIT);
			if (proc.restartRegion || proc.reRunEvent) {
				return;
			}
			// Dirty eviction
			proc.hasDirtyEviction = true;
		}

		CpuId cid = proc.id;
		Processor<Line> p = proc.machine.getProc(cid);

		// Update information in the per-core metadata
		PerCoreLineMetadata md = sharedLine.getPerCoreMetadata(cid);
		assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId() : "LLC line epoch cannot be greater.";
		// Update read bits
		sharedLine.orReadEncoding(cid, incomingL2Line.getReadEncoding(cid));
		sharedLine.updateReadSiteInfo(cid, incomingL2Line.getReadEncoding(cid), incomingL2Line.getReadSiteInfo(cid),
				incomingL2Line.getReadLastSiteInfo(cid));
		// Update write bits
		// orWriteEncoding should be performed to clear obsolete encoding even if the
		// incoming line has no written
		// offsets
		sharedLine.orWriteEncoding(cid, incomingL2Line.getWriteEncoding(cid));
		sharedLine.updateWriteSiteInfo(cid, incomingL2Line.getWriteEncoding(cid), incomingL2Line.getWriteSiteInfo(cid),
				incomingL2Line.getWriteLastSiteInfo(cid));
		if (incomingL2Line.hasWrittenOffsets(cid)) {
			sharedLine.incrementVersion();
			sharedLine.setDirty(true);
			if (proc.params.useBloomFilter()) {
				proc.updatePerCoreBloomFilters(incomingL2Line);
			}
		}
		// Deal with values
		if (status == DeferredStatus.NOT_DEFERRED || status == DeferredStatus.REMOTE_CORE) {
			sharedLine.copyWrittenValuesFromSource(incomingL2Line);
		} else {
			sharedLine.clearDeferredLineOwnerID();
			if (proc.params.areDeferredWriteBacksPrecise()) {
				// The line is no longer deferred, hence remove the
				// entry from the set of deferred lines
				Long writeMd = null;
				if (proc.params.areDeferredWriteBacksPrecise()) {
					writeMd = proc.getDeferredWriteMetadata(incomingL2Line);
					assert writeMd != null : incomingL2Line.toString();
					assert Long.bitCount(writeMd) > 0 : "Otherwise it should not have been deferred";
					proc.removeDeferredWriteLine(incomingL2Line);
				}
			}
			// Need to send all values into the LLC, because possibly they are deferred
			// written values from previous
			// regions
			sharedLine.copyAllValues(incomingL2Line);
		}
		sharedLine.setLastWritersFromPrivateLine(incomingL2Line);

		// The lock/atomic line is already in the LLC and the LLC doesn't need to keep
		// the ownerID to communicate with
		// the owner core.
		if (sharedLine.getLockOwnerID() == proc.id.get())
			sharedLine.clearLockOwnerID();
		// obsolete encodings have been cleared by orEncoding() before setEpoch();
		// setEpoch() before getEncoding()
		sharedLine.setEpoch(cid, p.getCurrentEpoch());

		// If both read and write offsets are set in the shared line, then no need to
		// maintain read encoding.
		// Reset read encoding in the LLC line directly. The LLC might have both read
		// and write bits
		// set, which is not required for conflict detection.
		long readEnc = sharedLine.getReadEncoding(cid);
		if (sharedLine.isWrittenAfterRead(cid)) {
			readEnc &= (~sharedLine.getWriteEncoding(cid));
		}
		sharedLine.clearReadEncoding(cid);
		sharedLine.orReadEncoding(cid, readEnc);
		// no need to update read site info

		if (proc.params.useAIMCache()) {
			if (incomingL2Line.hasReadOffsets(cid) || incomingL2Line.hasWrittenOffsets(cid)) {
				// The line might be in the AIM cache
				proc.aimcache.addLineIfNotPresent(proc, incomingL2Line);
			}
		}

		// This is possibly not required, since the L2 line is already being
		// evicted. But still
		// there is no harm in clearing the read bits.
		incomingL2Line.clearReadEncoding(proc.id);
		proc.updateVersionSizeHistogram(sharedLine.getVersion());
	}

	/**
	 * Search for a valid line {@code incoming} in the private L1 cache and return
	 * true. We ignore L2 for now.
	 */
	public boolean searchSharedCache(Line incoming) {
		assert levelInHierarchy == CacheLevel.L3 && incoming.valid();
		LinkedList<Line> set = sets.get(index(incoming.lineAddress().get()));
		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.valid() && line.lineAddress().get() == incoming.lineAddress().get()) {
				return true;
			}
		}
		return false;
	}

	/** Insert line into shared cache, possibly evicting another line to memory. */
	public void insertSharedCache(final Line incoming) {
		assert levelInHierarchy == CacheLevel.L3;

		LinkedList<Line> set = sets.get(index(incoming.lineAddress().get()));
		assert set.size() == assoc;

		// Check if line exists
		Line toEvict = null;
		for (Line l : set) {
			if (l.lineAddress().get() == incoming.lineAddress().get()) {
				toEvict = l;
				break;
			}
		}

		if (toEvict == null) { // Line not already present
			toEvict = set.getLast(); // straight LRU
		}

		// Need not to check for recall
		if (processor.params.usePLRU()) {
			int lineIndex = set.indexOf(toEvict);
			set.set(lineIndex, incoming);
			setMRUBit(incoming, false);
		} else {
			boolean found = set.remove(toEvict);
			assert found;
			set.addFirst(incoming);
		}
	}

	/**
	 * Lookup the given address in the memory hierarchy.
	 *
	 * @param address    the memory address being accessed
	 * @param reorderSet If true and the line is found in this cache, move the line
	 *                   to MRU position. If false, do not modify the set ordering.
	 * @return an AccessReturn object that 1) says where in the memory hierarchy the
	 *         requested line was found and 2) holds a reference to the line (which
	 *         may reside in any level of the hierarchy). If the address is not
	 *         cached, then the line reference is null.
	 */
	public MemoryResponse<Line> search(final DataByteAddress address, final boolean reorderSet) {
		return __search(address, reorderSet);
	}

	private MemoryResponse<Line> __search(final ByteAddress address, final boolean reorderSet) {
		MemoryResponse<Line> ret = new MemoryResponse<Line>();
		final LinkedList<Line> set = sets.get(index(address.get()));

		// search this cache
		for (Line l : set) {

			if (l.valid() && l.contains(address)) {
				// hit!
				ret.lineHit = l;
				ret.whereHit = levelInHierarchy;

				if (reorderSet) {
					if (processor.params.usePLRU()) {
						setMRUBit(l, false);
					} else {
						set.remove(l);
						set.addFirst(l);
					}
				}

				return ret;
			}
		}

		// at this point, we missed in this cache

		if (nextCache != null) {
			return nextCache.__search(address, reorderSet);
		}

		// we're the last cache in the hierarchy
		// line is unmodified
		ret.whereHit = CacheLevel.MEMORY;
		ret.lineHit = null;
		return ret;

	} // end search()

	public interface LineVisitor<Line> {
		public void visit(Line l);
	}

	/**
	 * Calls the given visitor function once on each line in this cache. Lines are
	 * traversed in no particular order.
	 */
	public void visitAllLines(LineVisitor<Line> lv) {
		for (LinkedList<Line> set : sets) {
			for (Line l : set) {
				lv.visit(l);
			}
		}
	}

	/** Verify that each line is indexed into the proper set. */
	public void verifyIndices() {
		for (int i = 0; i < sets.size(); i++) {
			LinkedList<Line> set = sets.get(i);
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
		s.append("cache=" + this.levelInHierarchy + System.getProperty("line.separator"));
		for (LinkedList<Line> set : sets) {
			for (Line l : set) {
				s.append(l.toString() + "\n");
			}
			s.append(System.getProperty("line.separator"));
		}

		if (nextCache != null) {
			s.append(nextCache.toString());
		} else {
			if (processor.params.useAIMCache()) {
				s.append(processor.aimcache.toString());
			}
		}
		return s.toString();
	}

	MemoryResponse<Line> requestWithSpecialInvalidState(Processor<Line> proc, final DataAccess access, boolean read) {
		final ByteAddress address = access.addr();
		int setIndex = index(address.get());
		LinkedList<Line> set = sets.get(setIndex);
		assert set.size() == assoc;

		MemoryResponse<Line> ret = new MemoryResponse<Line>();

		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.valid() && line.contains(address)) {

				if (line.getState() == ViserState.VISER_INVALID_TENTATIVE) {
					assert levelInHierarchy.compareTo(CacheLevel.L3) < 0;
					// Check if the line still contains valid values by
					// comparing versions with shared memory
					MemoryResponse<Line> sharedResp = proc.getLineFromLLCOrMemory(line);
					assert sharedResp.lineHit != null;
					long localVersion = line.getVersion();
					long sharedVersion = sharedResp.lineHit.getVersion();
					assert localVersion <= sharedVersion;
					if (localVersion == sharedVersion) {
						ret.invalidStateHit = true;
						ret.invalidStateSharedHitLevel = sharedResp.whereHit;
						line.changeStateTo(ViserState.VISER_VALID);
						if (levelInHierarchy == CacheLevel.L1) {
							if (proc.params.useL2()) {
								Line l2Line = proc.L2cache.getLine(line);
								assert l2Line.getState() == ViserState.VISER_INVALID_TENTATIVE;
								l2Line.changeStateTo(ViserState.VISER_VALID);
							}
						} else {
							if (ViserSim.assertsEnabled) {
								assert proc.L1cache.getLine(line) == null;
							}
						}
					} else {
						// Consider this to be a miss
						ret.invalidStateFailure = true;
						line.invalidate();
						if (levelInHierarchy == CacheLevel.L1) {
							if (proc.params.useL2()) {
								Line l2Line = proc.L2cache.getLine(line);
								assert l2Line.getState() == ViserState.VISER_INVALID_TENTATIVE;
								l2Line.invalidate();
							}
						} else {
							if (ViserSim.assertsEnabled) {
								assert proc.L1cache.getLine(line) == null;
							}
						}
						continue;
					}
				}

				// hit!
				if (proc.params.usePLRU()) {
					setMRUBit(line, read);
				} else {
					// move this line to the mru spot
					if (line != set.getFirst()) {
						it.remove();
						set.addFirst(line);
					}
				}
				assert line == set.getFirst();
				if (levelInHierarchy == CacheLevel.L3) {
					// If the line is deferred, fetch the updated values from the owner core into
					// the LLC.
					if (proc.params.deferWriteBacks() && line.isLineDeferred()) {
						proc.fetchDeferredLineFromPrivateCache(line, false, false);
					}
				}

				if (levelInHierarchy == CacheLevel.L1) {
					ret.lineHit = line;
				} else if (levelInHierarchy == CacheLevel.L2) { // L2
					// Create a new object instead of a mere reference
					ret.lineHit = lineFactory.create(proc, levelInHierarchy, line);
				} else {
					ret.lineHit = lineFactory.create(processor, levelInHierarchy, line);
				}
				ret.whereHit = this.levelInHierarchy;
				return ret;
			}
		}

		// if we made it here, we missed in this cache

		// check higher-level caches
		Line memLine = null;
		if (nextCache != null) {
			ret = nextCache.requestWithSpecialInvalidState(proc, access, read);
			if (proc.reRunEvent || proc.restartRegion)
				return ret;
			// response should have come from deeper in the hierarchy
			assert ret.whereHit.compareTo(levelInHierarchy) > 0;
		} else { // missed in the LLC
			ret.whereHit = CacheLevel.MEMORY;
			// bring line from memory, this line needs to be owned by P0
			memLine = processor.machine.memory.get(address.lineAddress().get());

			// This is being returned, on behalf of the current processor, P0
			if (memLine == null) {
				memLine = lineFactory.create(processor, levelInHierarchy, address.lineAddress());
				memLine.changeStateTo(ViserState.VISER_VALID);
				if (read) {
					memLine.setValue(access.addr(), access.value());
				}
			} else {
				assert !memLine.isLineDeferred();
				memLine = proc.clearAccessEncoding(memLine); // memLine may get
																// modified
			}

			assert memLine.valid();
			assert memLine.id().equals(new CpuId(0));
			ret.lineHit = lineFactory.create(processor, levelInHierarchy, memLine); // copy a line
		}

		if (levelInHierarchy == CacheLevel.L1) { // miss in L1
			// We don't fetch lines for lock/atomic writes.
			if (!access.isRegularMemAccess() && !read) {
				return ret;
			}

			// evict a line (possibly to next-level cache)
			Line toEvict = callbacks.eviction(ret.lineHit, set, levelInHierarchy, ExecutionPhase.REGION_BODY,
					MRUBits[setIndex]);
			assert toEvict.id() == proc.id
					&& proc.id == processor.id : "The owner of a private line should always be the current core";
			if (toEvict.valid() && ViserSim.assertsEnabled && proc.params.useL2()) {
				assert nextCache.getLine(toEvict) != null;
				assert nextCache.getLine(toEvict).getVersion() == toEvict.getVersion();
			}

			// Write back the line to a lower level cache
			if (nextCache != null) { // Valid check is in the callee
				nextCache.evictedFromHigherCache(proc, toEvict, ExecutionPhase.REGION_BODY);
				if (proc.restartRegion || proc.reRunEvent)
					return ret;
			}

			// Create a copy of the line, not required since ret.lineHit already represents
			// an unique copy, but
			// we want to set the correct owner processor
			Line insert = lineFactory.create(proc, levelInHierarchy, ret.lineHit);
			// remove the evicted line and insert the new line only after a successful
			// eviction
			if (proc.params.usePLRU()) {
				int lineIndex = set.indexOf(toEvict);
				set.set(lineIndex, insert);
				setMRUBit(insert, read);
			} else {
				boolean found = set.remove(toEvict);
				assert found;
				assert insert.id() == processor.id && proc.id == processor.id;
				// NB: only push in the incoming line *after* we've evicted something
				set.addFirst(insert);
			}
			ret.lineHit = insert;
			// l1 line will be updated with access.value() (l2 line won't)

		} else if (levelInHierarchy == CacheLevel.L2) { // miss in L2
			// We don't fetch lines for lock/atomic writes.
			if (!access.isRegularMemAccess() && !read) {
				return ret;
			}

			// evict a possibly VALID line
			Line toEvict = callbacks.eviction(ret.lineHit, set, levelInHierarchy, ExecutionPhase.REGION_BODY,
					MRUBits[setIndex]);
			assert toEvict.id() == proc.id
					&& proc.id == processor.id : "The owner of a private line should always be the current core";

			// Recall lines to satisfy inclusivity between L1 and L2.
			// Before L2 can evict a line, it has to fetch updated access information from
			// the L1.
			// Check if the same line is in higher-level caches. If yes, then the line
			// should
			// also be evicted from all higher-level caches.
			if (toEvict.valid()) {
				// Set the epoch for a line before being evicted from the L2 cache. The
				// following assertion
				// does not work, since a L2 line can get evicted before a region end.
				assert toEvict.getEpoch(proc.id).equals(proc.getCurrentEpoch());
				// Rui: I can't see any benefit of the following statement.
				// toEvict.setEpoch(proc.id, proc.getCurrentEpoch());

				Line l1Line = proc.L1cache.getLine(toEvict);
				if (l1Line != null) {
					assert l1Line.valid();
					assert l1Line.id() == proc.id;
					assert l1Line.getEpoch(proc.id).equals(proc.getCurrentEpoch());
					if (l1Line.isWrittenAfterRead(l1Line.id())) {
						validateReadL2LineOnL1Eviction(proc, l1Line, toEvict, ExecutionPhase.REGION_BODY);

						if (proc.reRunEvent || proc.restartRegion)
							return ret;
					}
					updateL2LineWithL1Line(proc, toEvict, l1Line, false);
					// Only after the L1 line is merged into L2 can we actually recall the L1 line.
					// Otherwise, the core
					// will lose all updated values of the L1 line, causing false races even with
					// the core and itself.
					proc.L1cache.recallFromL1Cache(levelInHierarchy, toEvict);
				}
			}

			// Write back the line to a lower level cache
			if (nextCache != null) { // Valid check is in the callee
				nextCache.evictedFromHigherCache(proc, toEvict, ExecutionPhase.REGION_BODY);
				if (proc.restartRegion || proc.reRunEvent)
					return ret;
			}

			Line insert = lineFactory.create(proc, levelInHierarchy, ret.lineHit);
			if (proc.params.usePLRU()) {
				int lineIndex = set.indexOf(toEvict);
				set.set(lineIndex, insert);
				setMRUBit(insert, read);
			} else {
				// remove the evicted line and insert the new line only after a successful
				// eviction
				boolean found = set.remove(toEvict);
				assert found;

				// Create a copy of the line
				assert ret.lineHit.valid();
				// This does not hold, since we already make a copy of the line
				// assert ret.lineHit.id().get() == 0;
				assert insert.id() == processor.id && proc.id == processor.id;
				// NB: only push in the incoming line *after* we've evicted something
				set.addFirst(insert);
			}
			ret.lineHit = insert;

		} else if (levelInHierarchy == CacheLevel.L3) { // miss in LLC
			// If we come here, it means we were required to fetch a line from memory
			// because of an LLC miss.
			assert ret.whereHit == CacheLevel.MEMORY && memLine != null;
			assert ret.lineHit != null && ret.lineHit.valid();

			Line toEvict = callbacks.eviction(ret.lineHit, set, levelInHierarchy, ExecutionPhase.REGION_BODY,
					MRUBits[setIndex]);

			if (proc.params.usePLRU()) {
				int lineIndex = set.indexOf(toEvict);
				set.set(lineIndex, memLine);
				setMRUBit(memLine, read);
			} else {
				boolean found = set.remove(toEvict);
				assert found;

				// This is not required for correctness, conflicts on private cache lines will
				// be detected lazily.
				// proc.checkPreciseConflicts(memLine, access, read);

				// NB: only push in the incoming line *after* we've evicted something
				set.addFirst(memLine);
			}

			// So we just fetched a line into the LLC, create one in the AIM cache only if
			// the line has valid metadata.
			if (proc.params.useAIMCache()) {
				boolean fetch = false;
				for (int i = 0; i < proc.params.numProcessors(); i++) {
					CpuId cpuId = new CpuId(i);
					Processor<Line> p = proc.machine.getProc(cpuId);
					PerCoreLineMetadata md = memLine.getPerCoreMetadata(cpuId);
					assert md != null;
					assert md.epoch.getRegionId() <= p.getCurrentEpoch().getRegionId();
					if (memLine.hasReadOffsets(cpuId) || memLine.hasWrittenOffsets(cpuId)) {
						fetch = true;
						break;
					}
				}
				if (toEvict.valid()) {
					proc.aimcache.evictLine(proc, toEvict);
				}
				if (fetch) {
					// Had to go to memory, this implies an LLC miss. This means the line must not
					// be present in the AIM
					// cache.
					proc.aimcache.addLineWithoutCheckingForDuplicates(proc, memLine);
				}
			}

			// If we are evicting a valid line, then need to virtualize the line in memory
			if (toEvict.valid()) {
				evictValidLineFromLLC(proc, toEvict, ExecutionPhase.REGION_BODY);
			}

		} else {
			assert false : "Impossible cache level: MEMORY";
		}

		return ret;
	}

	public void setMRUBit(Line cacheLine, boolean read) {
		int setIndex = index(cacheLine.lineAddress().get());
		LinkedList<Line> set = sets.get(setIndex);
		short bits = MRUBits[setIndex];
		int lineIndex = set.indexOf(cacheLine);
		if (lineIndex < 0) {
			System.out.println("Error: can't find a line in the corresponding set.");
			System.exit(-777);
		}

		bits = (short) (bits | (1 << lineIndex));
		if ((bits & assocMask) == assocMask) { // all bits are set
			if (processor.params.evictCleanLineFirst() && levelInHierarchy == CacheLevel.L2) {
				// use modified PLRU on L2
				for (int i = 0; i < set.size(); i++) {
					Line line = set.get(i);
					if (!line.valid()) {
						System.out.println("lines and bits are inconsistent!");
						System.exit(-777);
					}

					// Line l1Line = processor.L1cache.getLine(line);
					CpuId cid = processor.id;
					boolean isDirty = false;
					/*
					 * if (l1Line != null && l1Line.valid()) isDirty = l1Line.hasWrittenOffsets(cid)
					 * || line.hasWrittenOffsets(cid); else
					 */
					isDirty = line.hasWrittenOffsets(cid) || line.l2WriteBit;

					if (i == lineIndex)
						isDirty = isDirty || !read;

					if (!isDirty) { // read line
						bits = (short) (bits & ~(1 << i));
					}
				}
			}

			if ((bits & assocMask) == assocMask || (bits & assocMask) == 0) { // all lines in the set are dirty/clean
				bits = (short) (1 << lineIndex); // keep the last set MRU bit.
			}
		}
		MRUBits[setIndex] = bits;

		/*
		 * if (levelInHierarchy == CacheLevel.L1) { // The corresponding L2 MRU bits
		 * should also be taken care of.
		 * nextCache.setMRUBit(nextCache.getLine(cacheLine), read); }
		 */
	}

	// the line should still be valid when this function is called
	public void resetMRUBit(Line toInvalidate) {
		int setIndex = index(toInvalidate.lineAddress().get());
		List<Line> set = sets.get(setIndex);
		int lineIndex = -1;
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.valid() && line.lineAddress().get() == toInvalidate.lineAddress().get()) {
				lineIndex = set.indexOf(line);
				break;
			}
		}
		if (lineIndex == -1) {
			System.out.println("Can't find the line to reset MRU bit");
			System.exit(-1);
		}
		short bits = MRUBits[setIndex];
		bits = (short) (bits & ~(1 << lineIndex));
		MRUBits[setIndex] = bits;
	}

} // end class HierarchicalCache
