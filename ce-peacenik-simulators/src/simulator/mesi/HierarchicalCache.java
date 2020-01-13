package simulator.mesi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

interface CacheCallbacks<Line extends MESILine> {
	/**
	 * Called whenever a line needs to be evicted.
	 * 
	 * @param set   the set from which we need to evict something; the list is
	 *              ordered from MRU (front) to LRU (back).
	 * @param level the level of the cache where the eviction is happening
	 * @return the line to evict
	 */
	Line eviction(final Line incoming, final LinkedList<Line> set, CacheLevel level, short bits);
}

class CacheConfiguration<Line extends MESILine> {
	public int cacheSize;
	public int assoc;
	public int lineSize;
	public CacheLevel level;
}

enum CacheLevel {
	L1, L2, L3, MEMORY
};

/** The response from sending a request to the memory hierarchy */
class MemoryResponse<Line extends MESILine> {
	/** The level of the cache hierarchy where the hit occurred */
	public CacheLevel whereHit;
	/** The line containing the requested address. */
	public Line lineHit;
	public int ceNumReturnDataBytes;

	@Override
	public String toString() {
		return whereHit.toString() + " " + String.valueOf(lineHit);
	}
}

public class HierarchicalCache<Line extends MESILine> {

	protected CacheLevel levelInHierarchy = CacheLevel.L1;

	/** associativity */
	protected int assoc;
	/** log_2(line size) */
	protected short lineOffsetBits;
	/** log_2(num sets) */
	protected short indexBits;
	/** mask used to clear out the tag bits */
	protected long indexMask;
	protected short assocMask;

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

	// Conflict Exceptions, only used for private caches
	/** Local thread is currently in an active region */
	boolean inRegion = false;
	/**
	 * Summarize whether any cache line accesses with the active region was supplied
	 * to another cache. We only use the L2 cache's bit.
	 */
	boolean supplied = false;
	/**
	 * Indicate whether any line with non-null access bits was evicted. We only use
	 * the L2 cache's bit.
	 */
	boolean outOfCache = false;

	/** Return the cache index for the given address */
	protected int index(long address) {
		return (int) ((address >> lineOffsetBits) & indexMask);
	}

	/**
	 * Update the incoming line in this cache. The line should already be present
	 * because of inclusivity.
	 * 
	 * @param incoming the line being evicted from a lower-level cache
	 */
	protected void evictedFromHigherCache(Processor<Line> proc, final Line incoming) {
		assert levelInHierarchy == CacheLevel.L2 || levelInHierarchy == CacheLevel.L3;

		if (incoming.invalid()) {
			// ignore the eviction of invalid lines
			return;
		}

		// The incoming line should already be present in the current cache level
		Line mesiLine = getLine(incoming);
		assert mesiLine != null : "Cache inclusivity violated";

		// Eviction from an L1 cache need not invalidate the line in the L2 cache
		if (levelInHierarchy == CacheLevel.L2) {
			assert proc.params.useL2() && mesiLine.getLevel() == CacheLevel.L2;
			assert ((MESILine) incoming).getState() == mesiLine.getState();

			if (processor.params.restartAtFailedValidationsOrDeadlocks() || processor.params.FalseRestart()) {
				if (incoming.hasWrittenOffsets() && mesiLine.hasDirtyValuesFromPriorRegions()) {
					// write dirty values to a deferred l2line from L1
					Line llcLine = proc.L3cache.getLine(mesiLine);
					assert llcLine != null && llcLine.valid();
					// llcLine.copyAllValues(mesiLine);
					if (!incoming.isDeferredWriteBitSet()) {
						mesiLine.resetDeferredWriteBit();
					}
				} else if (!incoming.hasWrittenOffsets() && incoming.isDeferredWriteBitSet()) {
					mesiLine.setDeferredWriteBit();
				}
			}

			long enc = incoming.getLocalWrites();
			mesiLine.orLocalWrites(enc);
			// write info already updates at write
			// mesiLine.updateWriteSiteInfo(enc, incoming.writeSiteInfo);

			return;
		}

		// Incoming line from L2 to LLC
		assert mesiLine.getLevel() == CacheLevel.L3;
		// Change state
		switch (mesiLine.getState()) {
		case MESI_SHARED: {
			// Continue with shared if there is at least one remaining sharer
			int count = 0;
			for (Processor<Line> p : proc.allProcessors) {
				if (p == proc) { // Do not compare with this.processor, since L3 cache is on behalf on PO.
					continue;
				}

				if (p.L1cache.searchGivenPrivateCache(incoming) || p.L2cache.searchGivenPrivateCache(incoming)) {
					count++;
				}
			}
			if (count > 0) {
				// Implies at least one sharer, continue with shared
			} else {
				mesiLine.changeStateTo(MESIState.MESI_INVALID);
				if (MESISim.enableXasserts()) {
					proc.Verify.verifyInvalidLinesInLLC();
				}
			}
			break;
		}
		case MESI_MODIFIED: {
			mesiLine.changeStateTo(MESIState.MESI_INVALID);
			break;
		}
		case MESI_EXCLUSIVE: {
			mesiLine.changeStateTo(MESIState.MESI_INVALID);
			break;
		}
		default: {
			assert false : "State of incoming line: " + incoming;
		}
		}
		// Note that the LLC line continues to remain valid.
		if (incoming.getState() == MESIState.MESI_MODIFIED) {
			// The current LLC line could possibly be marked INVALID and dirty
			mesiLine.setDirty(true);
		}
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

		sets = new ArrayList<LinkedList<Line>>(numSets);
		MRUBits = new short[numSets];

		for (int i = 0; i < numSets; i++) {
			LinkedList<Line> set = new LinkedList<Line>();
			for (int j = 0; j < thisConfig.assoc; j++) {
				Line line = lineFactory.create(processor.id, levelInHierarchy);
				set.add(line);
			}
			sets.add(set);
			MRUBits[i] = 0;
		}

	} // end ctor

	/**
	 * Access the line containing the given address, bringing it into the L1 cache
	 * if it was not already present there.
	 * 
	 * While this function is recursing, each intermediate lines returned (if not
	 * already in the L1) has been removed from its containing cache, and is ready
	 * to be inserted into the L1.
	 * 
	 * @param address the memory address being accessed
	 * @return an AccessReturn object that 1) says where in the memory hierarchy the
	 *         requested line was found and 2) holds a reference to the new line
	 *         (now in the L1)
	 */
	public MemoryResponse<Line> request(Processor<Line> proc, final ByteAddress address, boolean read) {
		int setIndex = index(address.get());
		LinkedList<Line> set = sets.get(setIndex);
		assert set.size() == assoc;

		MemoryResponse<Line> ret = new MemoryResponse<Line>();

		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();

			if (line.valid() && line.contains(address)) {
				// hit! move this line to the mru spot
				if (levelInHierarchy == CacheLevel.L1) {
					assert line.getState() != MESIState.MESI_INVALID;
					// hit!
					if (proc.params.usePLRU()) {
						setMRUBit(line, read);
					} else if (line != set.getFirst()) {
						// move this line to the mru spot
						it.remove();
						set.addFirst(line);
						assert line == set.getFirst();
					}
					ret.lineHit = line;
				} else if (levelInHierarchy == CacheLevel.L2) { // L2 cache
					assert line.lineAddress().get() == address.lineAddress().get() : "Line addresses do not match.";
					assert line.getState() != MESIState.MESI_INVALID;
					// Create a new object instead of a mere reference
					ret.lineHit = lineFactory.create(proc.id, levelInHierarchy, line);
				} else {
					// It is possible for an LLC line to be both valid and MESI_INVALID.
					assert levelInHierarchy == CacheLevel.L3;
					// Create a new object instead of a mere reference
					ret.lineHit = lineFactory.create(proc.id, levelInHierarchy, line);

					if (proc.params.conflictExceptions()) {
						// TODO: When is the inMemory bit cleared? Possibly on an LLC eviction. It
						// probably does not matter any
						// more since we now check for ongoing regions.
						if (line.inMemory()) {
							CEPerLineMetadata<Line> md = proc.machine.globalTable.get(line.lineAddress().get());
							assert md != null : "In-memory bit should have been unset.";
							for (int i = 0; i < proc.params.numProcessors(); i++) {
								// Other threads' local bits are retrieved from memory by accessing the global
								// table
								// and the corresponding supplied bit is set
								if (i != proc.id.get()) {
									CpuId cpuID = new CpuId(i);
									Processor<Line> p = proc.machine.getProc(cpuID);
									CEGlobalTableValue val = md.getPerCoreMetadata(p);
									assert val.regionID <= p.getCurrentEpoch().getRegionId();
									if (val.regionID == p.getCurrentEpoch().getRegionId()) {
										ret.lineHit.orRemoteReads(val.localReads);
										ret.lineHit.orRemoteWrites(val.localWrites);
										if (val.localReads != 0 || val.localWrites != 0) {
											val.supplied = true;
										}
									}
								}
							}
						}

						// Some request from Core 1 hits in the LLC does not necessarily imply that the
						// outOfCache bit for
						// Core 1 will be set.
						if (proc.L2cache.outOfCache) {
							CEPerLineMetadata<Line> md = proc.machine.globalTable.get(line.lineAddress().get());
							if (md != null) {
								CEGlobalTableValue val = md.getPerCoreMetadata(proc);
								assert val.regionID <= proc.getCurrentEpoch().getRegionId();
								if (val.regionID == proc.getCurrentEpoch().getRegionId()) {
									Line l = ret.lineHit;
									l.orLocalReads(val.localReads);
									l.orLocalWrites(val.localWrites);
									l.updateReadSiteInfo(val.localReads, val.readSiteInfo);
									l.updateWriteSiteInfo(val.localWrites, val.writeSiteInfo);
									if (val.supplied) {
										l.setSupplied();
									} else {
										l.clearSupplied();
									}
								}
							}
						}
					}
				}
				ret.whereHit = this.levelInHierarchy;
				assert ((MESILine) ret.lineHit).id() == proc.id;
				return ret;
			}
		}

		// if we made it here, we missed in this cache

		// check higher-level caches
		if (nextCache != null) {
			ret = nextCache.request(proc, address, read);
			// response should have come from deeper in the hierarchy
			assert ret.whereHit.compareTo(levelInHierarchy) > 0 && ret.lineHit.valid();
		} else { // missed in the LLC
			ret.whereHit = CacheLevel.MEMORY;
			// bring line from memory
			Line memLine = lineFactory.create(proc.id, levelInHierarchy, address.lineAddress());
			MESIState newMesiState = (read) ? MESIState.MESI_EXCLUSIVE : MESIState.MESI_MODIFIED;
			memLine.changeStateTo(newMesiState);

			// Check global table. CE incurs LLC-to-memory even on a LLC hit. We account for
			// the traffic in the
			// read()/write() methods.
			if (proc.params.conflictExceptions()) {
				long lineAddr = memLine.lineAddress().get();
				CEPerLineMetadata<Line> md = proc.machine.globalTable.get(lineAddr);
				int numReturnBytes = 0;
				if (md != null) {
					for (int i = 0; i < proc.params.numProcessors(); i++) {
						CpuId id = new CpuId(i);
						Processor<Line> p = proc.machine.getProc(id);
						// There is no need to account for extra traffic if the outOfCache bit is not
						// set. Seems unfair.
						if (p.L2cache.outOfCache) {
							if (id.equals(proc.id)) {
								CEGlobalTableValue val = md.getPerCoreMetadata(p);
								assert val.regionID <= p.getCurrentEpoch().getRegionId();
								if (val.regionID == p.getCurrentEpoch().getRegionId()) {
									memLine.setLocalReads(val.localReads);
									memLine.setLocalWrites(val.localWrites);
									if (val.supplied) {
										memLine.setSupplied();
									}
									numReturnBytes += (MemorySystemConstants.CE_READ_METADATA_BYTES
											+ MemorySystemConstants.CE_WRITE_METADATA_BYTES);
								}
							} else {
								CEGlobalTableValue val = md.getPerCoreMetadata(p);
								assert val.regionID <= p.getCurrentEpoch().getRegionId();
								if (val.regionID == p.getCurrentEpoch().getRegionId()) {
									if (val.localReads != 0 || val.localWrites != 0) {
										val.supplied = true;
									}
									memLine.orRemoteReads(val.localReads);
									memLine.orRemoteWrites(val.localWrites);
									numReturnBytes += (MemorySystemConstants.CE_READ_METADATA_BYTES
											+ MemorySystemConstants.CE_WRITE_METADATA_BYTES);
								}
							}
						}
					}
				}
				ret.ceNumReturnDataBytes = numReturnBytes;
			}

			ret.lineHit = memLine;
		}

		// evict a line (possibly to next-level cache)
		if (levelInHierarchy == CacheLevel.L1) {
			Line toEvict = callbacks.eviction(ret.lineHit, set, levelInHierarchy, MRUBits[setIndex]);
			assert ((MESILine) toEvict).id() == proc.id
					&& proc.id == processor.id : "The owner of a private line should always be the current core";

			// Create a copy of the line
			assert ret.lineHit.valid();
			Line insert = lineFactory.create(proc.id, levelInHierarchy, ret.lineHit);
			assert ((MESILine) insert).id() == processor.id && proc.id == processor.id;
			if (proc.params.usePLRU()) {
				int lineIndex = set.indexOf(toEvict);
				set.set(lineIndex, insert);
				setMRUBit(insert, read);
			} else {
				boolean found = set.remove(toEvict);
				assert found;
				// NB: only push in the incoming line *after* we've evicted something
				set.addFirst(insert);
			}
			ret.lineHit = insert;

			if (nextCache != null) {
				nextCache.evictedFromHigherCache(proc, toEvict);
			}
		} else if (levelInHierarchy == CacheLevel.L2) {
			// evict a line (possibly to next-level cache)
			Line toEvict = callbacks.eviction(ret.lineHit, set, levelInHierarchy, MRUBits[setIndex]);
			assert ((MESILine) toEvict).id() == proc.id
					&& proc.id == processor.id : "The owner of a private line should always be the current core";

			// Check if the same line is in higher-level caches. If yes, then the line
			// should
			// also be evicted from all higher-level caches. Possibly called "Recall".
			if (toEvict.valid()) {
				recallFromL1Cache(proc, toEvict);
			}

			if (proc.params.conflictExceptions()) {
				// If a cache line being evicted has a supplied or
				// local bit set, the line address is saved in the local table and local
				// access/supplied bits are saved in the global table.
				if (toEvict.isSupplied() || toEvict.isRead() || toEvict.isWritten()) {
					proc.perRegionLocalTable.add(toEvict.lineAddress().get());
					CEGlobalTableValue val = new CEGlobalTableValue(toEvict.getLocalReads(), toEvict.readSiteInfo,
							toEvict.getLocalWrites(), toEvict.writeSiteInfo, toEvict.isSupplied(),
							proc.getCurrentEpoch().getRegionId());
					CEPerLineMetadata<Line> md = proc.machine.globalTable.get(toEvict.lineAddress().get());
					if (md == null) {
						md = new CEPerLineMetadata<Line>(proc.params.numProcessors());
					}
					md.setPerCoreMetadata(proc.id, val);
					proc.machine.globalTable.put(toEvict.lineAddress().get(), md);

					// The out-of-cache bit is then set and the directory is notified to set
					// the in-memory bit for the corresponding line.
					if (proc.params.useL2()) {
						proc.L2cache.outOfCache = true;
					} else {
						proc.L1cache.outOfCache = true;
					}
					if (toEvict.valid()) {
						assert proc.L3cache.getLine(toEvict) != null;
						proc.L3cache.getLine(toEvict).setInMemoryBit();
						if (toEvict.isWritten()) {
							proc.hasDirtyEviction = true;
						}
					}
				}
			}

			// Create a copy of the line
			assert ret.lineHit.valid();
			Line insert = lineFactory.create(proc.id, levelInHierarchy, ret.lineHit);

			// // When a miss is serviced and the line arrives in the
			// // local cache, the access bits of the incoming line are accumulated
			// // into the remote access bits with a bitwise OR. The only exception is
			// // when an incoming write bit is set and the corresponding local write
			// // bit is also set, in which case the remote access bit is not set. This is
			// // necessary to keep invariant 5 in Table 3. On global read misses, if
			// // any other cache indicates it has local read bits set for the requested
			// // line, the line is brought from memory in shared state, instead of
			// // exclusive. This is necessary to keep invariant 3 in Table 3.
			// insert.setRemoteReads(insert.getLocalReads() | insert.getRemoteReads());
			// insert.setRemoteWrites(insert.getLocalWrites() | insert.getRemoteWrites());

			assert ((MESILine) insert).id() == processor.id && proc.id == processor.id;
			if (proc.params.usePLRU()) {
				int lineIndex = set.indexOf(toEvict);
				set.set(lineIndex, insert);
				setMRUBit(insert, read);
			} else {
				boolean found = set.remove(toEvict);
				assert found;
				// NB: only push in the incoming line *after* we've evicted something
				set.addFirst(insert);
			}

			if (nextCache != null) {
				nextCache.evictedFromHigherCache(proc, toEvict);
			}
		} else if (levelInHierarchy == CacheLevel.L3) {
			// If we are evicting a line from a lower-level cache, then we should also evict
			// the line from all
			// higher level caches

			assert ret.whereHit == CacheLevel.MEMORY;
			assert ret.lineHit.valid();

			LineAddress la = address.lineAddress();

			set = sets.get(index(la.get()));
			Line toEvict = callbacks.eviction(ret.lineHit, set, levelInHierarchy, MRUBits[setIndex]);

			// Check if the same line is in higher-level caches. If yes, then the line
			// should
			// also be evicted from all higher-level caches. Possibly called "Recall".
			// LLC line can be valid but MESI_INVALID at the same time.
			if (toEvict.valid() && toEvict.getState() != MESIState.MESI_INVALID) {
				recallFromHigherLevelPrivateCaches(toEvict);
				// We do not require an ACK, and inclusivity is optional.
				// Although we account for L2 evictions separately, this is safe since an L2
				// eviction would mean
				// that the L1/L2 lines are already invalid. So we need not worry about counting
				// twice.
			}

			if (proc.params.usePLRU()) {
				int lineIndex = set.indexOf(toEvict);
				set.set(lineIndex, ret.lineHit);
				setMRUBit(ret.lineHit, read);
			} else {
				boolean found = set.remove(toEvict);
				assert found;
				// NB: add the incoming line *after* the eviction handler runs
				set.addFirst(ret.lineHit);
			}

		} else {
			assert false : "Impossible cache level: MEMORY";
		}

		return ret;

	} // end request()

	/**
	 * Iterate over all processors and remove the line {@code toEvict} from all
	 * private L1/L2 caches. This method is called from L3.
	 */
	@SuppressWarnings("unchecked")
	private RecallResponse recallFromHigherLevelPrivateCaches(Line toEvict) {
		assert levelInHierarchy == CacheLevel.L3 && toEvict.valid();
		boolean modified = false;
		RecallResponse resp = new RecallResponse();
		int numInvalidations = 0;

		for (Processor<Line> p : processor.allProcessors) {
			HashSet<Long> evictedL1Lines = new HashSet<>();
			LinkedList<Line> set = p.L1cache.sets.get(p.L1cache.index(toEvict.lineAddress().get()));

			// search this cache
			boolean foundL1 = false, foundL2 = false;

			for (Iterator<Line> it = set.iterator(); it.hasNext();) {
				MESILine line = it.next();
				if (line.valid() && line.lineAddress().get() == toEvict.lineAddress().get()) {
					if (line.getState() == MESIState.MESI_MODIFIED) {
						modified = true;
					}

					if (p.params.conflictExceptions()) {
						// If a cache line being evicted has a supplied or local bit set, the line
						// address
						// is saved in the local table and local access/supplied bits are saved in the
						// global
						// table.
						if (line.isSupplied() || line.isRead() || line.isWritten()) {
							long lineAddr = line.lineAddress().get();
							p.perRegionLocalTable.add(lineAddr);
							CEPerLineMetadata<Line> md = p.machine.globalTable.get(lineAddr);
							if (md == null) {
								md = new CEPerLineMetadata<Line>(p.params.numProcessors());
							}
							CEGlobalTableValue val = md.getPerCoreMetadata(p);
							val.localReads += line.getLocalReads();
							val.localWrites += line.getLocalWrites();
							val.setSiteInfo(toEvict.readSiteInfo, toEvict.writeSiteInfo);
							if (line.isSupplied()) {
								val.supplied = true;
							}
							val.regionID = p.getCurrentEpoch().getRegionId();
							md.setPerCoreMetadata(p.id, val);
							p.machine.globalTable.put(lineAddr, md);
							p.L2cache.outOfCache = true;
							// If an L1 line is backed up, then we can skip backing up the L2 line
							evictedL1Lines.add(lineAddr);
						}
					}

					line.invalidate((Machine<MESILine>) processor.machine);
					foundL1 = true;
					break;
				}
			}

			// For this processor p, line toEvict was present in the L1, so it should be
			// present in L2 as well.
			// Even otherwise, we need to check the L2.
			set = p.L2cache.sets.get(p.L2cache.index(toEvict.lineAddress().get()));

			for (Iterator<Line> it = set.iterator(); it.hasNext();) {
				MESILine line = it.next();
				if (line.valid() && line.lineAddress().get() == toEvict.lineAddress().get()) {
					if (line.getState() == MESIState.MESI_MODIFIED) {
						modified = true;
					}

					if (p.params.conflictExceptions()) {
						if (line.isSupplied() || line.isRead() || line.isWritten()) {
							long lineAddr = line.lineAddress().get();
							// If an L1 line is backed up, then we can skip backing up the L2 line
							if (foundL1) {
								assert evictedL1Lines.contains(lineAddr);
							} else {
								p.perRegionLocalTable.add(lineAddr);
								CEPerLineMetadata<Line> md = p.machine.globalTable.get(lineAddr);
								if (md == null) {
									md = new CEPerLineMetadata<Line>(p.params.numProcessors());
								}
								CEGlobalTableValue val = md.getPerCoreMetadata(p);
								val.localReads += line.getLocalReads();
								val.localWrites += line.getLocalWrites();
								val.setSiteInfo(toEvict.readSiteInfo, toEvict.writeSiteInfo);
								if (line.isSupplied()) {
									val.supplied = true;
								}
								val.regionID = p.getCurrentEpoch().getRegionId();
								md.setPerCoreMetadata(p.id, val);
								p.machine.globalTable.put(lineAddr, md);

								p.L2cache.outOfCache = true;
							}
						}
					}

					line.invalidate((Machine<MESILine>) processor.machine);
					foundL2 = true;
					numInvalidations++;
					break;
				}
			}

			if (foundL1) {
				assert foundL2 : "L1 and L2 inclusivity is violated";
			}
		}

		switch (toEvict.getState()) {
		case MESI_EXCLUSIVE: {
			assert numInvalidations == 1;
			break;
		}
		case MESI_MODIFIED: {
			assert numInvalidations == 1;
			assert modified == true;
			break;
		}
		case MESI_SHARED: {
			assert numInvalidations > 0;
			break;
		}
		case MESI_INVALID:
		default: {
			assert false;
		}
		}

		resp.dirty = modified;
		resp.numInvalidations = numInvalidations;
		return resp;
	}

	class RecallResponse {
		boolean dirty;
		int numInvalidations;
	}

	/**
	 * Called on behalf of an L2 cache, when a valid line is being evicted. Note
	 * that the line may not be present in the L1, since the L1 could be smaller.
	 */
	@SuppressWarnings("unchecked")
	private void recallFromL1Cache(Processor<Line> proc, Line toEvict) {
		assert levelInHierarchy == CacheLevel.L2 && toEvict.valid();

		LinkedList<Line> set = processor.L1cache.sets.get(processor.L1cache.index(toEvict.lineAddress().get()));
		assert set.size() == processor.L1cache.assoc;
		// search this cache
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			MESILine line = it.next();
			if (line.valid() && line.lineAddress().get() == toEvict.lineAddress().get()) {
				assert ((MESILine) toEvict).getState() == line.getState();
				assert ((MESILine) toEvict).dirty() == line.dirty();
				if (proc.params.conflictExceptions()) {
					// Sync local reads and writes
					toEvict.orLocalReads(line.getLocalReads());
					toEvict.orLocalWrites(line.getLocalWrites());
					toEvict.updateReadSiteInfo(line.getLocalReads(), line.readSiteInfo);
					toEvict.updateWriteSiteInfo(line.getLocalWrites(), line.writeSiteInfo);
					if (line.isSupplied()) {
						toEvict.setSupplied();
					}
				}
				line.invalidate((Machine<MESILine>) proc.machine);
				break;
			}
		}
	}

	/**
	 * Search for a valid line {@code incoming} in a private cache and return true.
	 */
	public boolean searchGivenPrivateCache(Line incoming) {
		assert levelInHierarchy == CacheLevel.L1 || levelInHierarchy == CacheLevel.L2;
		return getLine(incoming) != null;
	}

	/** Just get the corresponding line, from any cache. */
	public Line getLine(Line line) {
		return getLine(line.lineAddress());
	}

	/** Just get the corresponding line, from any cache. */
	public Line getLine(LineAddress addr) {
		LinkedList<Line> set = sets.get(index(addr.get()));
		for (Iterator<Line> it = set.iterator(); it.hasNext();) {
			Line line = it.next();
			if (line.valid()) {
				if (line.lineAddress().get() == addr.get()) {
					return line;
				}
			}
		}
		return null;
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
						setMRUBit(l, true);
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

	public MemoryResponse<Line> ceSearchPrivateCaches(final DataByteAddress address, final boolean reorderSet) {
		assert processor.params.conflictExceptions();
		return __ceSearchPrivateCaches(address, reorderSet);
	}

	private MemoryResponse<Line> __ceSearchPrivateCaches(final ByteAddress address, final boolean reorderSet) {
		MemoryResponse<Line> ret = new MemoryResponse<Line>();
		final LinkedList<Line> set = sets.get(index(address.get()));

		// search this cache
		for (Line l : set) {
			// CE: IMP: It does not matter if the line is invalid
			if (l.lineAddress() != null && l.contains(address)) {
				// hit!
				ret.lineHit = l;
				ret.whereHit = levelInHierarchy;

				if (reorderSet) {
					if (processor.params.usePLRU()) {
						setMRUBit(l, true);
					} else {
						set.remove(l);
						set.addFirst(l);
					}
				}
				return ret;
			}
		}

		// at this point, we missed in this cache
		if (nextCache != null && nextCache.levelInHierarchy.compareTo(processor.llc()) < 0) {
			return nextCache.__ceSearchPrivateCaches(address, reorderSet);
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
		}
		return s.toString();
	}

	public void setMRUBit(Line cacheLine, boolean read) {
		int setIndex = index(cacheLine.lineAddress().get());
		LinkedList<Line> set = sets.get(setIndex);
		short bits = MRUBits[setIndex];
		int lineIndex = set.indexOf(cacheLine);
		if (lineIndex < 0) {
			throw new RuntimeException("Error: can't find a line in the corresponding set.");
		}

		bits = (short) (bits | (1 << lineIndex));
		if ((bits & assocMask) == assocMask) { // all bits are set
			if (processor.params.evictCleanLineFirst() && levelInHierarchy == CacheLevel.L2) {
				// use modified PLRU on L2
				for (int i = 0; i < set.size(); i++) {
					Line line = set.get(i);
					if (!line.valid()) {
						throw new RuntimeException("lines and bits are inconsistent!");
					}

					boolean isDirty = false;
					/*
					 * if (l1Line != null && l1Line.valid()) isDirty = l1Line.hasWrittenOffsets(cid)
					 * || line.hasWrittenOffsets(cid); else
					 */
					isDirty = (line.getLocalWrites() != 0) || line.l2WriteBit;

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
