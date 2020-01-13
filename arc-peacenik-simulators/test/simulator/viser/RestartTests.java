/**
 *
 */
package simulator.viser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedList;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import simulator.viser.Machine.SimulationMode;
import simulator.viser.ViserSim.PARSEC_PHASE;

public final class RestartTests {

	static Machine<ViserLine> machine;

	static final CpuId P0 = new CpuId(0);
	static final CpuId P1 = new CpuId(1);
	static final CpuId P2 = new CpuId(2);
	static final CpuId P3 = new CpuId(3);

	static final ThreadId T0 = new ThreadId((byte) 0);
	static final ThreadId T1 = new ThreadId((byte) 1);
	static final ThreadId T2 = new ThreadId((byte) 2);
	static final ThreadId T3 = new ThreadId((byte) 3);

	static final int CORES = 4;
	static final int LINE_SIZE = 4;

	static final int L1_ASSOC = 2;
	static final int L1_CACHE_SIZE = 16; // 4 blocks, 2 sets

	static final int L2_ASSOC = 4;
	static final int L2_CACHE_SIZE = 32; // 8 blocks, 2 sets

	static final int L3_ASSOC = 4;
	static final int L3_CACHE_SIZE = 64;

	static {
		// This will enable all the assertions like cache inclusivity checks
		assert ViserSim.XASSERTS;
		MemorySystemConstants.unsafeSetLineSize(LINE_SIZE);
	}

	static CacheConfiguration<ViserLine> l1config = new CacheConfiguration<ViserLine>() {
		{
			cacheSize = L1_CACHE_SIZE;
			lineSize = LINE_SIZE;
			assoc = L1_ASSOC;
			level = CacheLevel.L1;
		}
	};

	static CacheConfiguration<ViserLine> l2config = new CacheConfiguration<ViserLine>() {
		{
			cacheSize = L2_CACHE_SIZE;
			lineSize = LINE_SIZE;
			assoc = L2_ASSOC;
			level = CacheLevel.L2;
		}
	};

	static CacheConfiguration<ViserLine> l3config = new CacheConfiguration<ViserLine>() {
		{
			cacheSize = L3_CACHE_SIZE;
			lineSize = LINE_SIZE;
			assoc = L3_ASSOC;
			level = CacheLevel.L3;
		}
	};

	/**
	 * @throws java.lang.Exception
	 */
	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		Machine.MachineParams<ViserLine> params = new Machine.MachineParams<ViserLine>() {

			@Override
			SimulationMode simulationMode() {
				return SimulationMode.VISER;
			}

			@Override
			int numProcessors() {
				return CORES;
			}

			@Override
			boolean pintool() {
				return false;
			}

			@Override
			CacheConfiguration<ViserLine> l1config() {
				return l1config;
			}

			@Override
			boolean useL2() {
				return true;
			}

			@Override
			CacheConfiguration<ViserLine> l2config() {
				return l2config;
			}

			@Override
			CacheConfiguration<ViserLine> l3config() {
				return l3config;
			}

			@Override
			LineFactory<ViserLine> lineFactory() {
				return new LineFactory<ViserLine>() {
					@Override
					public ViserLine create(Processor<ViserLine> proc, CacheLevel level) {
						ViserLine line = new ViserLine(proc, level);
						line.setEpoch(proc.id, proc.getCurrentEpoch());
						return line;
					}

					@Override
					public ViserLine create(Processor<ViserLine> proc, CacheLevel level, LineAddress la) {
						ViserLine line = new ViserLine(proc, level, la);
						line.setEpoch(proc.id, proc.getCurrentEpoch());
						return line;
					}

					@Override
					public ViserLine create(Processor<ViserLine> proc, CacheLevel level, ViserLine l) {
						if (!l.valid()) {
							throw new RuntimeException("Source line should be VALID.");
						}
						ViserLine tmp = new ViserLine(proc, level, l.lineAddress());
						tmp.changeStateTo(l.getState());
						tmp.setVersion(l.getVersion());
						tmp.copyAllValues(l);
						tmp.setLastWriters(l.getLastWriters());
						tmp.setLockOwnerID(l.getLockOwnerID());
						// We do not update deferred owner id from here.
						if (level.compareTo(proc.llc()) < 0) { // private line
							CpuId cid = proc.id;
							tmp.orWriteEncoding(cid, l.getWriteEncoding(cid));
							tmp.orReadEncoding(cid, l.getReadEncoding(cid));
							tmp.updateWriteSiteInfo(cid, l.getWriteEncoding(cid), l.getWriteSiteInfo(cid),
									l.getWriteLastSiteInfo(cid));
							tmp.updateReadSiteInfo(cid, l.getReadEncoding(cid), l.getReadSiteInfo(cid),
									l.getReadLastSiteInfo(cid));
							tmp.setEpoch(proc.id, proc.getCurrentEpoch());
						} else {
							for (int i = 0; i < proc.params.numProcessors(); i++) {
								CpuId cpuId = new CpuId(i);
								PerCoreLineMetadata tmpMd = l.getPerCoreMetadata(cpuId);
								// We do not bother with epoch here, since it should be taken care of automatically
								// later
								PerCoreLineMetadata md = new PerCoreLineMetadata(tmpMd.epoch, tmpMd.writeEncoding,
										tmpMd.readEncoding, tmpMd.writeSiteInfo, tmpMd.readSiteInfo,
										tmpMd.writeLastSiteInfo, tmpMd.readLastSiteInfo);
								tmp.setPerCoreMetadata(cpuId, md);
							}
						}
						return tmp;
					}
				};
			}

			@Override
			boolean ignoreStackReferences() {
				return false;
			}

			@Override
			boolean remoteAccessesAffectLRU() {
				return false;
			}

			@Override
			boolean writebackInMemory() {
				return false;
			}

			@Override
			boolean alwaysInvalidateReadOnlyLines() {
				return false;
			}

			@Override
			boolean invalidateWrittenLinesOnlyAfterVersionCheck() {
				return true;
			}

			@Override
			boolean updateWrittenLinesDuringVersionCheck() {
				return false;
			}

			@Override
			boolean invalidateUntouchedLinesOptimization() {
				return false;
			}

			@Override
			boolean useSpecialInvalidState() {
				return true;
			}

			@Override
			boolean useBloomFilter() {
				return true;
			}

			@Override
			boolean useAIMCache() {
				return false; // Reduce the default AIM cache size before
								// setting this to true
			}

			@Override
			boolean deferWriteBacks() {
				return true;
			}

			@Override
			boolean areDeferredWriteBacksPrecise() {
				return false;
			}

			@Override
			boolean skipValidatingReadLines() {
				return true;
			}

			@Override
			boolean ignoreFetchingDeferredLinesDuringReadValidation() {
				return false;
			}

			@Override
			boolean clearAIMCacheAtRegionBoundaries() {
				return false;
			}

			@Override
			boolean ignoreFetchingReadBits() {
				return true;
			}

			@Override
			boolean validateL1ReadsAlongWithL2() {
				return true;
			}

			@Override
			boolean siteTracking() {
				return true;
			}

			@Override
			int numPinThreads() {
				return 0;
			}

			@Override
			boolean lockstep() {
				return false;
			}

			@Override
			boolean pauseCoresAtConflicts() {
				return false;
			}

			@Override
			boolean restartAtFailedValidationsOrDeadlocks() {
				return true;
			}

			@Override
			boolean treatAtomicUpdatesAsRegularAccesses() {
				return false;
			}

			@Override
			boolean ignoreFetchingWriteBits() {
				return true;
			}

			@Override
			boolean printConflictingSites() {
				return true;
			}

			@Override
			boolean isHttpd() {
				return false;
			}

			@Override
			boolean evictCleanLineFirst() {
				return true;
			}

			@Override
			boolean usePLRU() {
				return true;
			}

			@Override
			boolean setWriteBitsInL2() {
				return true;
			}

			@Override
			boolean treatAtomicUpdatesAsRegionBoundaries() {
				return false;
			}

			@Override
			boolean BackupDeferredWritebacksLasily() {
				return false;
			}

			@Override
			boolean FalseRestart() {
				return false;
			}
			
			@Override
            int checkPointingRate() {
                return 0;
            }
		};

		// Not sure how we can override JOpt command line
		ViserSim.Options = Knobs.parser.parse("--xassert=true --assert-period=1");
		ViserSim.setPARSECPhase(PARSEC_PHASE.IN_ROI);
		assertTrue(ViserSim.XASSERTS);
		assertEquals(1, ViserSim.Options.valueOf(Knobs.AssertPeriod).intValue());

		machine = new Machine<ViserLine>(params);
		machine.initializeEpochs();
		machine.siteInfo.add(new SiteInfoEntry((short) 7, (short) 77, (short) 777));
		machine.siteInfo.add(new SiteInfoEntry((short) 9, (short) 99, (short) 999));
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testBackupDeferredWritebacksEagerly0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		// deferred
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		ViserLine l2Line = proc1.L2cache.getLine(new DataLineAddress(80L));
		assertEquals(true, l2Line.isDeferredWriteBitSet());
		assertEquals(1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		assertEquals(80, l2Line.getValue(0), 0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 84, T1);

		// fetched
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		assertEquals(false, l2Line.isDeferredWriteBitSet());
		assertEquals(-1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		ViserLine l1Line = proc0.L1cache.getLine(new DataLineAddress(80L));
		assertEquals(80, l1Line.getValue(0), 0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);

		// deferred again
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		assertEquals(true, l2Line.isDeferredWriteBitSet());
		assertEquals(1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		assertEquals(84, l2Line.getValue(0), 0);
	}

	@Test
	public void testBackupDeferredWritebacksEagerly1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		// deferred
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		ViserLine l2Line = proc1.L2cache.getLine(new DataLineAddress(80L));
		assertEquals(true, l2Line.isDeferredWriteBitSet());
		assertEquals(1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		assertEquals(80, l2Line.getValue(0), 0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 84, T1);
		machine.testCacheMemoryWrite(P1, 96L, 2, 82, T0);
		machine.testCacheMemoryRead(P1, 64L, 2, 82, T0);
		// evict 80L to L2
		assertEquals(null, proc1.L1cache.getLine(l2Line));
		assertEquals(false, l2Line.isDeferredWriteBitSet());
		assertEquals(-1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);

		// fetched
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		assertEquals(false, l2Line.isDeferredWriteBitSet());
		assertEquals(-1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		ViserLine l1Line = proc0.L1cache.getLine(new DataLineAddress(80L));
		assertEquals(80, l1Line.getValue(0), 0);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);

		// deferred again
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		assertEquals(true, l2Line.isDeferredWriteBitSet());
		assertEquals(1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		assertEquals(84, l2Line.getValue(0), 0);
	}

	@Test
	public void testBackupDeferredWritebacksEagerly2() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		// deferred
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		ViserLine l2Line = proc1.L2cache.getLine(new DataLineAddress(80L));
		assertEquals(true, l2Line.isDeferredWriteBitSet());
		assertEquals(1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		assertEquals(80, l2Line.getValue(0), 0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 84, T1);
		machine.testCacheMemoryWrite(P1, 96L, 2, 82, T0);
		machine.testCacheMemoryRead(P1, 64L, 2, 82, T0);
		// evict 80L to L2
		assertEquals(null, proc1.L1cache.getLine(l2Line));
		assertEquals(false, l2Line.isDeferredWriteBitSet());
		assertEquals(-1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 86, T1);
		// fetch 80L from the L2; evict 96L since we doesn't use modified PLRU in L1
		assertEquals(null, proc1.L1cache.getLine(new DataLineAddress(96L)));

		// fetched
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		assertEquals(false, l2Line.isDeferredWriteBitSet());
		assertEquals(-1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		ViserLine l1Line = proc0.L1cache.getLine(new DataLineAddress(80L));
		assertEquals(80, l1Line.getValue(0), 0);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);

		// deferred again
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE, new LinkedList<Event>(), 0);
		assertEquals(true, l2Line.isDeferredWriteBitSet());
		assertEquals(1, proc1.L3cache.getLine(l2Line).getDeferredLineOwnerID(), 0);
		assertEquals(86, l2Line.getValue(0), 0);
	}

	@Test
	public void testModifiedPLRU() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 80L, 2, 84, T1);
		machine.testCacheMemoryWrite(P1, 96L, 2, 82, T0);
		machine.testCacheMemoryWrite(P1, 48L, 2, 82, T0);
		// evict 80L to L2
		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(64L)));

		machine.testCacheMemoryRead(P1, 64L, 2, 82, T0);

		machine.testCacheMemoryWrite(P1, 32L, 2, 82, T0);
		// evict 64L to L3
		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(64L)));
	}
}
