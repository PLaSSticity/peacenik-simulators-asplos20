/**
 *
 */
package simulator.viser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import simulator.viser.Machine.SimulationMode;
import simulator.viser.ViserSim.PARSEC_PHASE;

public final class PLRUTests {

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

	static final int L2_ASSOC = 2;
	static final int L2_CACHE_SIZE = 16; // 4 blocks, 2 sets

	static final int L3_ASSOC = 4;
	static final int L3_CACHE_SIZE = 64;

	static {
		// This will enable all the assertions like cache inclusivity checks
		assert ViserSim.XASSERTS;
		MemorySystemConstants.unsafeSetLineSize(LINE_SIZE);
	}

	static CacheConfiguration<ViserLine> l1config=new CacheConfiguration<ViserLine>(){{cacheSize=L1_CACHE_SIZE;lineSize=LINE_SIZE;assoc=L1_ASSOC;level=CacheLevel.L1;}};

	static CacheConfiguration<ViserLine> l2config=new CacheConfiguration<ViserLine>(){{cacheSize=L2_CACHE_SIZE;lineSize=LINE_SIZE;assoc=L2_ASSOC;level=CacheLevel.L2;}};

	static CacheConfiguration<ViserLine> l3config=new CacheConfiguration<ViserLine>(){{cacheSize=L3_CACHE_SIZE;lineSize=LINE_SIZE;assoc=L3_ASSOC;level=CacheLevel.L3;}};

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
				return false;
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

	/*
	 * (x0 and x1 shared the same cache line.)
	 * 		T1			T0
	 * 		wr x0
	 * 		-----
	 * 		rd x1
	 * 		(evict)
	 * 					-----
	 * 					wr x0
	 * 					----- 
	 */
	@Test
	public void testEviction0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 82L, 2, 92, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryRead(P1, 80L, 2, 0, T1);
		// evict the line 80L
		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);

		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(80L)));

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P0, 82L, 2, 100, T0);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_PreciseConflicts.get(), 0);
	}

	@Test
	public void testEviction1() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);
		// evict 64L since 80L is a written line
		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(64L)));
	}

	@Test
	public void testEviction2() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryWrite(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);
		// evict 80L
		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(80L)));
	}

	@Test
	public void testEviction3() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryWrite(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);
		// evict 64L
		// assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(64L)));
		// evict 80L because L2 knows nothing about the L1 hit for 80L
		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(80L)));
	}

	@Test
	public void testFetchingWriteBits0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 1, T0);
		// evict 80L
		assertEquals(true, proc0.hasDirtyEviction);
		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(false, proc0.hasDirtyEviction);
		ViserLine llcline = proc0.L3cache.getLine(new DataLineAddress(80L));
		assertEquals(-1, llcline.getDeferredLineOwnerID());
		machine.testCacheMemoryRead(P1, 80L, 2, 1, T0);
		ViserLine line = proc1.L1cache.getLine(new DataLineAddress(80L));
		assertEquals(1, line.getValue(0));
	}

	@Test
	public void testFetchingWriteBits1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryRead(P0, 64L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 1, T0);
		// evict 64L
		assertEquals(false, proc0.hasDirtyEviction);
		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		ViserLine llcline = proc0.L3cache.getLine(new DataLineAddress(80L));
		assertEquals(0, llcline.getDeferredLineOwnerID());
		machine.testCacheMemoryRead(P1, 80L, 2, 1, T0);
		ViserLine line = proc1.L1cache.getLine(new DataLineAddress(80L));
		assertEquals(1, line.getValue(0));
		assertEquals(-1, llcline.getDeferredLineOwnerID());
	}
}