/**
 * 
 */
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

public final class MultiProcessorTests {

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
								// We do not bother with epoch here, since it should be taken care of
								// automatically
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
				return false; // Reduce the default AIM cache size before setting this to true
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
				return false;
			}

			@Override
			boolean validateL1ReadsAlongWithL2() {
				return true;
			}

			@Override
			boolean ignoreFetchingWriteBits() {
				return false;
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
			boolean siteTracking() {
				return true;
			}

			@Override
			boolean printConflictingSites() {
				return true;
			}

			@Override
			boolean treatAtomicUpdatesAsRegularAccesses() {
				return false;
			}

			@Override
			boolean evictCleanLineFirst() {
				return false;
			}

			@Override
			boolean usePLRU() {
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
			boolean isHttpd() {
				return false;
			}

			@Override
			boolean setWriteBitsInL2() {
				return false;
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
	public void testRead() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0); // same line
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);

		// machine.cacheRead(P1, 72L, 2, 0, T1);
		// assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get());
		// assertEquals(2, proc1.stats.pc_l1d.pc_ReadMisses.get());
		// assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get());
		// assertEquals(0, proc1.stats.pc_l3d.pc_ReadHits.get());
		// assertEquals(2, proc1.stats.pc_l3d.pc_ReadMisses.get());
		// // Need to evict 64L from private L1 cache, but need to invalidate LLC cache
		// line state
		// machine.cacheRead(P1, 80L, 2, 0, T1);
		// assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get());
		// assertEquals(3, proc1.stats.pc_l1d.pc_ReadMisses.get());
		// assertEquals(1, proc1.stats.pc_l1d.pc_LineEvictions.get());
		// assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get());
		// assertEquals(2, proc1.stats.pc_l3d.pc_ReadMisses.get());
		// System.out.println(proc1.L1cache);
		//
		// machine.cacheRead(P0, 72L, 2, 0, T0);
		// assertEquals(1, proc0.stats.pc_l1d.pc_ReadHits.get());
		// assertEquals(2, proc0.stats.pc_l1d.pc_ReadMisses.get());
		// assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get());
		// assertEquals(1, proc0.stats.pc_l3d.pc_ReadHits.get());
		// assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get());
		// System.out.println(proc0.L1cache);
	}

	@Test
	public void testWriteWriteConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryWrite(P0, 82L, 2, 82, T0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		// 80L is evicted to the LLC
		System.out.println(proc0.L1cache);
		System.out.println(proc0.L3cache.getLine((new DataByteAddress(80L).lineAddress())).getPerCoreMetadata(P0));

		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T1);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		// The LLC need not eagerly check for conflicts on a fetch
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testWriteReadConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryWrite(P0, 82L, 2, 82, T0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		System.out.println(proc0.L1cache);
		System.out.println(proc0.L3cache.getLine((new DataByteAddress(80L).lineAddress())).getPerCoreMetadata(P0));

		machine.testCacheMemoryRead(P1, 80L, 2, 3, T1);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		// The LLC need not eagerly check for conflicts on a fetch
		// assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testWriteReadNoConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 82, T0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		System.out.println(proc0.L1cache);
		System.out.println(proc0.L3cache.getLine((new DataByteAddress(80L).lineAddress())).getPerCoreMetadata(P0));

		machine.testCacheMemoryRead(P1, 82L, 2, 3, T1);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testReadWriteConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryRead(P1, 64L, 2, 64, T1);
		// System.out.println(proc1.L1cache);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testReadWriteSerializable1() {
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryRead(P1, 64L, 2, 64, T1);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
	}

	@Test
	public void testReadWriteSerializable2() {
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryRead(P1, 64L, 2, 64, T1);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
	}

	@Test
	public void testReadValidationConflict0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		// Read validation of the read from 64L should fail since the value written by
		// P0 is different
		assertEquals(1, proc1.stats.pc_FailedValidations.get(), 0);

		// Due to read validation from Core 1, Core 0's deferred write should now be
		// visible
		ViserLine sharedLine = proc0.L3cache.getLine((new DataByteAddress(64L).lineAddress()));
		assertEquals(64, sharedLine.getValue(0)); // Value written by Core 0 is 64
		System.out.println(proc0.L1cache);
	}

	@Test
	public void testReadValidationConflict1() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		machine.testCacheMemoryWrite(P0, 66L, 2, 64, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testReadValidationConflict2() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);

		machine.testCacheMemoryWrite(P0, 66L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 82L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 98L, 2, 64, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testReadValidationConflict3() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		machine.testCacheMemoryRead(P1, 66L, 2, 88, T1);
		// This read should get 0 because memline has been created at P1's first read

		machine.testCacheMemoryWrite(P0, 66L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 82L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 98L, 2, 64, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_FailedValidations.get(), 0);
	}

	// A read-only line should still be invalidated if it has untouched offsets and
	// might have been written by the llc.
	@Test
	public void testReadValidationConflict4() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);

		machine.testCacheMemoryWrite(P0, 66L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 0, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P1, 66L, 2, 0, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);

		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testReadValidationConflict5() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);

		machine.testCacheMemoryWrite(P0, 66L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 0, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P1, 66L, 2, 0, T1);
		// not the last writer, so the line would be invalidated.
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);

		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	/*
	 * The optimization of avoiding invalidating touched lines would still introduce
	 * false data race even after the previous fix (testReadValidationConflict4 and
	 * testReadValidationConflict5) since region boundaries in RCC are not exactly
	 * where locks are acquired/released.
	 * 
	 * T0 T1 wr/rd x ----- rel() ----- ----- acq() wr x ----- rel() acq() rd x -----
	 * rel()
	 * 
	 * acq() and rel() denote where a lock is actually acquired and released,
	 * respectively. False data race will be reported between T0's write and T1's
	 * second read although they are well synchronized.
	 * 
	 * 
	 * @Test public void testReadValidationConflict6() { Processor<ViserLine> proc1
	 * = machine.getProc(P1); machine.cacheRead(P1, 64L, 2, 990, T1);
	 * machine.processRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
	 * machine.processRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
	 * 
	 * machine.cacheWrite(P0, 64L, 2, 992, T0); machine.processRegionBoundary(P0,
	 * T0, EventType.LOCK_RELEASE);
	 * 
	 * machine.cacheRead(P1, 64, 2, 990, T1); machine.processRegionBoundary(P1, T1,
	 * EventType.LOCK_RELEASE); assertEquals(0,
	 * proc1.stats.pc_PreciseConflicts.get(), 0); }
	 * 
	 * @Test public void testReadValidationConflict7() { Processor<ViserLine> proc1
	 * = machine.getProc(P1); machine.cacheWrite(P1, 64L, 2, 990, T1);
	 * machine.processRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
	 * machine.processRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
	 * 
	 * machine.cacheWrite(P0, 64L, 2, 992, T0); machine.processRegionBoundary(P0,
	 * T0, EventType.LOCK_RELEASE);
	 * 
	 * machine.cacheRead(P1, 64, 2, 990, T1); machine.processRegionBoundary(P1, T1,
	 * EventType.LOCK_RELEASE); assertEquals(0,
	 * proc1.stats.pc_PreciseConflicts.get(), 0); }
	 */

	// Should not exist in real executions considering memory alignment
	/*
	 * @Test public void testReadValidationConflict2() { Processor<ViserLine> proc1
	 * = machine.getProc(P1);
	 * 
	 * machine.cacheRead(P1, 65L, 1, -6138712L, T1); machine.cacheWrite(P0, 64L, 2,
	 * 664, T0); machine.processRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
	 * machine.processRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
	 * 
	 * assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0); }
	 * 
	 * @Test public void testReadValidationConflict3() { Processor<ViserLine> proc1
	 * = machine.getProc(P1); machine.cacheWrite(P0, 65L, 2, 0, T0);
	 * machine.processRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE); // Write a new
	 * value 0 to overwrite the initial value -6138712. // Otherwise, no false
	 * positive even if I don't fix the set-value-only-to-the-first-offset bug, //
	 * because of my previous fix that the simulator only reports read validation
	 * failure // at non-initial values (testReadValidationConflict1).
	 * 
	 * machine.cacheRead(P1, 65L, 1, 664, T1); machine.cacheWrite(P0, 64L, 2, 664,
	 * T0); machine.processRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
	 * machine.processRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
	 * 
	 * assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0); }
	 */
	@Test
	public void testWriteReadConflictViaMemory() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		machine.testCacheMemoryRead(P0, 112L, 2, 112, T0);
		machine.testCacheMemoryRead(P0, 128L, 2, 112, T0);
		System.out.println(proc0.L1cache);
		machine.dumpMachineMemory();

		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		// The conflict is detected by the LLC, and LLC is owned by proc0, so we need to
		// be careful
		// The LLC need not eagerly check for conflicts on a fetch
		// assertEquals(0, proc0.stats.pc_ConflictingRegions.get(), 0);
		// assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}

	@Test
	public void testWriteReadNoConflictViaMemory() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		machine.testCacheMemoryRead(P0, 112L, 2, 112, T0);
		machine.testCacheMemoryRead(P0, 128L, 2, 112, T0);
		System.out.println(proc0.L1cache);
		machine.dumpMachineMemory();

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 64L, 2, 0, T0);
	}

	@Test
	public void testLLCHit() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		Processor<ViserLine> proc2 = machine.getProc(P2);

		// bring 0d into the cache
		machine.testCacheMemoryRead(P0, 0, 1, 0, T0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);

		// fetch 16d and 32d which map to set 0; this evicts 0d to the L3
		for (int i = 1; i <= L1_ASSOC; i++) {
			machine.testCacheMemoryRead(P0, L1_CACHE_SIZE * i, 1, 0, T0);
			assertEquals(1 + i, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		}
		System.out.println(proc0.L1cache);
		assertEquals(1, proc0.stats.pc_l2d.pc_LineEvictions.get(), 0);

		// fetch 0d again, from the L3. This is a miss since the LLC line was
		// invalidated when evicting the
		// private L1 line.
		machine.testCacheMemoryRead(P1, 0, 1, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryRead(P2, 0, 1, 0, T2);
		assertEquals(0, proc2.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc2.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc2.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_LineEvictions.get(), 0);
	}

	@Test
	public void testCrossLineRead() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		// this crosses a line boundary
		machine.testCacheMemoryRead(P0, 0, 8, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P0, 8, 4, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 2, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P1, 6, 4, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(3, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testCrossLineWrite() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		// this crosses a line boundary
		machine.testCacheMemoryWrite(P0, 0, 8, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P0, 8, 4, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 2, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P1, 6, 4, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(3, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testReadWrite() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		Processor<ViserLine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryRead(P0, 4, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		// This is a hit in the shared cache
		machine.testCacheMemoryWrite(P1, 4, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P2, 4, 2, 0, T2);
		assertEquals(0, proc2.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc2.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc2.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);

		machine.testCacheMemoryRead(P0, 4, 2, 0, T0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
	}

	@Test
	public void testWriteRead() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		Processor<ViserLine> proc2 = machine.getProc(P2);
		Processor<ViserLine> proc3 = machine.getProc(P3);

		machine.testCacheMemoryWrite(P0, 4, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 4, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P2, 4, 2, 0, T2);
		assertEquals(0, proc2.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc2.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc2.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
		System.out.println(proc3.L1cache);

		machine.testCacheMemoryWrite(P3, 4, 2, 0, T3);
		assertEquals(0, proc3.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc3.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc3.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc3.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc3.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc3.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
		System.out.println(proc3.L1cache);
	}

	@Test
	public void testEviction() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 44, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P0, 12, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 44, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P1, 12, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P0, 76, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		// An L1 line 44 is evicted, but the line is marked as invalid in the LLC.
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P1, 76, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(3, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		// An L1 line 44 is evicted, but the line is marked as invalid in the LLC.
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
	}

	// A shared line's epoch should be updated only after its obsolete encodings are
	// cleared.
	/*
	 * (x0 and x1 shared the same cache line.) T1 T0 wr x0 ----- rd x1 (evict) -----
	 * wr x0 ----- ** false data race detected on x0!
	 */
	@Test
	public void testEviction0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 82L, 2, 92, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryRead(P1, 80L, 2, 0, T1);
		// evict the line 80L
		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryWrite(P0, 82L, 2, 100, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_PreciseConflicts.get(), 0);
	}

	// A shared line's epoch should be updated only after its obsolete encodings are
	// cleared.
	/*
	 * (x0 and x1 shared the same cache line.) T1 T0 wr x0 ----- rd x1 wr x1 (evict)
	 * ----- wr x0 ----- ** false data race detected on x0!
	 */
	@Test
	public void testEviction1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 82L, 2, 92, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryRead(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 90, T1);

		// evict the line 80L
		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryWrite(P0, 82L, 2, 100, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_PreciseConflicts.get(), 0);
	}

	/** Test eviction of an LLC line and invalidation. */
	@Test
	public void testLLCEviction() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		Processor<ViserLine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryWrite(P0, 40, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 56, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P0, 72, 2, 0, T0);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 88, 2, 0, T1);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P2, 104, 2, 0, T2);
		assertEquals(0, proc2.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc2.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc2.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
	}

	@Test
	public void testVersionMismatch() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 40, 2, 1, T0);
		machine.testCacheMemoryWrite(P1, 40, 2, 2, T1);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_RELEASE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
		// No read line, no validation
		assertEquals(0, proc1.stats.pc_ValidationAttempts.get(), 0);
	}

	@Test
	public void testDirtyBit() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 64L, 2, 64, T1);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		machine.testCacheMemoryWrite(P1, 112L, 2, 112, T1);
		// System.out.println(proc1.L1cache);

		// It is 80L that is getting evicted from L3
		machine.testCacheMemoryRead(P1, 128L, 2, 128, T1);

		// System.out.println(proc1.L1cache);
		// It is okay for 80L to not have updated metadata currently, since the L3 in
		// Viser is not
		// inclusive.
		machine.dumpMachineMemory();

		// Evict 80L from P0
		machine.testCacheMemoryWrite(P0, 48L, 2, 48, T0);
		System.out.println(proc0.L1cache);
		ViserLine line = proc0.L3cache.getLine(new DataLineAddress(80L));
		assertEquals(3, line.getReadEncoding(P0));
	}

	@Test
	public void testBloomFilter() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		machine.testCacheMemoryWrite(P1, 112L, 2, 96, T1);
		machine.testCacheMemoryWrite(P1, 128L, 2, 96, T1);
		machine.testCacheMemoryWrite(P1, 144L, 2, 96, T1);
		// Indicates write backs to the LLC
		System.out.println(proc0.bf);
		System.out.println(proc1.bf);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_RELEASE);
		System.out.println(proc0.bf);
		System.out.println(proc1.bf);
	}

	@Test
	public void testDeferWriteBack() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P0, 64L, 2, 640, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_RELEASE);
		// The owner core for the LLC line should be set, but the value should still be
		// zero.
		System.out.println(proc0.L1cache);
		machine.testCacheMemoryWrite(P1, 64L, 2, 4, T0);
		// The write from P0 should now be globally visible, but not the write from P1.
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testPotentialWriteReadConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		// Write to the same set twice to evict 80L to the LLC
		machine.testCacheMemoryWrite(P0, 96L, 2, 80, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 80, T0);
		System.out.println(proc0.L1cache);

		// This reads the line with version 1
		machine.testCacheMemoryRead(P1, 80L, 2, 3, T1);
		System.out.println(proc1.L1cache);

		// We are interested in read validation
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_potentialWrRdValConflicts.get(), 0);
	}

	@Test
	public void testDeferredWriteAndBloomFilter() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		assertEquals(0, proc0.bf.cardinality());
		proc0.bf.print();
		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		// Proc 0's BF should not be empty, even with deferred write enabled
		assertTrue(proc0.bf.cardinality() > 0);
		proc0.bf.print();
	}

	@Test
	public void testEagerReadEviction() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryRead(P1, 112L, 2, 112, T1);
		// Evict 80L

		// Read the latest 80L, no eager conflict detection while fetching
		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryRead(P0, 128L, 2, 128, T0);
		assertEquals(1, proc0.stats.pc_PreciseConflicts.get(), 0);
	}

	@Test
	public void testReadValidationDuringEviction() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);

		machine.testCacheMemoryWrite(P1, 80L, 2, -9, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		machine.testCacheMemoryRead(P0, 64L, 2, 64, T0);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testL1ReadsDuringL2Validation1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0);

		machine.testCacheMemoryWrite(P1, 82L, 2, -9, T1);
		machine.testCacheMemoryRead(P1, 112L, 2, 112, T1);
		machine.testCacheMemoryRead(P1, 128L, 2, 128, T1);
		// Evict 82L to the LLC

		machine.testCacheMemoryRead(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		// Evict 80L to the LLC

		if (proc0.params.validateL1ReadsAlongWithL2()) {
			// Implies the read of P0 from 82L will also get validated immediately
			assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
		} else {
			// Implies the read of P0 from 82L will not get validated immediately.
			// The conflict (failed validation) is detected since the L2 line is to be
			// evicted after being
			// updated with L1. This requires validating read-only bytes (82L) from the L2
			// line.
			// No read-write conflict is reported since we only validate values for a line
			// with mismatch version
			// with the LLC line.
			assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
			assertEquals(0, proc0.stats.pc_PreciseConflicts.get(), 0);
		}
	}
}
