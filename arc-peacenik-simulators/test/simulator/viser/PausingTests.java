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

public final class PausingTests {

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
			boolean pauseCoresAtConflicts() {
				return true;
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
			int numPinThreads() {
				return numProcessors();
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
				return false;
			}

			@Override
			boolean usePLRU() {
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
	public void testReadValidationConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		// Read validation of the read from 64L should fail since the value
		// written by P0 is different
		assertEquals(1, proc1.stats.pc_FailedValidations.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionsWithFRVs.get(), 0);

		// Due to read validation from Core 1, Core 0's deferred write should
		// now be visible
		ViserLine sharedLine = proc0.L3cache.getLine((new DataByteAddress(64L).lineAddress()));
		assertEquals(64, sharedLine.getValue(0)); // Value written by Core 0 is
													// 64
		// System.out.println(proc0.L1cache);
	}

	@Test
	public void testReadValidationConflict0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1, 0, 0);
		// The following read will get the value 0 from the simulator memory instead of the given value 996
		// since the line has been initialized when P1 reads 64L.
		machine.testCacheMemoryRead(P1, 66L, 2, 996, T1, 1, 1);

		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0, 0, 0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryWrite(P2, 66L, 2, 66, T2, 1, 1);
		machine.testProcessRegionBoundary(P2, T2, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		// Read validation of both the reads at 64L and 66L should fail, but the counter increase by 1 at most per line.
		assertEquals(1, proc1.stats.pc_FailedValidations.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionsWithFRVs.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionsWithExceptions.get(), 0);
		assertEquals(0, proc1.stats.pc_RegionHasDirtyEvictionBeforeFRV.get(), 0);

		// Due to read validation from Core 1, Core 0's deferred write should
		// now be visible
		ViserLine sharedLine = proc0.L3cache.getLine((new DataByteAddress(64L).lineAddress()));
		assertEquals(64, sharedLine.getValue(0)); // Value written by Core 0 is 64
		assertEquals(66, sharedLine.getValue(2)); // Value written by Core 0 is 64
	}

	@Test
	public void testWARUpgrade() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		// System.out.println(proc0.L1cache);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		// assertEquals(1, proc0.stats.pc_potentialWrRdValConflicts.get(), 0);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
		assertEquals(1, proc0.stats.pc_RegionsWithFRVs.get(), 0);
	}

	@Test
	public void testL1ReadsDuringL2Validation1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 5, T0);

		machine.testCacheMemoryWrite(P1, 82L, 2, -9, T1);
		machine.testCacheMemoryRead(P1, 112L, 2, 112, T1);
		machine.testCacheMemoryRead(P1, 128L, 2, 128, T1);
		// Evict 82L to the LLC

		machine.testCacheMemoryRead(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		// Evict 80L to the LLC

		if (proc0.params.validateL1ReadsAlongWithL2()) {
			// Implies the read of P0 from 82L will also get validated
			// immediately
			assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
			assertEquals(1, proc0.stats.pc_RegionsWithFRVs.get(), 0);
		} else {
			// Implies the read of P0 from 82L will not get validated
			// immediately.
			// The first conflict is detected when the read L2 line containing
			// 80L is *VALUE* validated against the shared line. (A conflict is
			// still detected
			// on 82L since L2's value is zero, but doesn't increase the
			// counter)
			// The second conflict is detected via *write-read conflicts
			// detection* since the L2 line (into which L1 line has been merged)
			// is to be evicted. This requires
			// validating read-only bytes (82L) from the L2 line.
			assertEquals(2, proc0.stats.pc_FailedValidations.get(), 0);
			assertEquals(1, proc0.stats.pc_RegionsWithFRVs.get(), 0);
			// No write-write conflcit is detected for P1 doesn't write 80L
			assertEquals(0, proc0.stats.pc_PreciseConflicts.get(), 0);
			assertEquals(0, proc0.stats.pc_RegionsWithTolerableConflicts.get(), 0);
		}
	}

	@Test
	public void testWriteWriteConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);

		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		// 80L is evicted to the LLC

		machine.testCacheMemoryWrite(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryWrite(P1, 64L, 2, 64, T1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T1);
		// 96L is evicted.

		// The LLC need not eagerly check for conflicts on a fetch
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
		assertEquals(0, proc1.stats.pc_RegionsWithTolerableConflicts.get(), 0);

		// System.out.println(proc1.L1cache);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		// A WW conflict is detected on 80L and P1 pauses without committing 64L (96L has already been evicted).
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionsWithTolerableConflicts.get(), 0);
		assertEquals(0, proc1.stats.pc_PotentialDeadlocks.get(), 0);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		// A WW conflict is detected on 96L
		assertEquals(1, proc0.stats.pc_PreciseConflicts.get(), 0);
		assertEquals(1, proc0.stats.pc_RegionsWithTolerableConflicts.get(), 0);
		assertEquals(1, proc0.stats.pc_PotentialDeadlocks.get(), 0);
	}

	@Test
	public void testWriteReadConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		// evict 80L to the LLC

		machine.testCacheMemoryWrite(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryWrite(P1, 64L, 2, 64, T1);
		machine.testCacheMemoryRead(P1, 80L, 2, 3, T1);
		// 96L was evicted
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

			// post-commit not reached and so 64L is not committed.
			assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
			assertEquals(1, proc1.stats.pc_RegionsWithTolerableConflicts.get(), 0);
			assertEquals(0, proc1.stats.pc_PotentialDeadlocks.get(), 0);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
			// deadlocks detected, not terminate
			assertEquals(1, proc0.stats.pc_PreciseConflicts.get(), 0);
			assertEquals(1, proc0.stats.pc_RegionsWithTolerableConflicts.get(), 0);
			assertEquals(1, proc0.stats.pc_PotentialDeadlocks.get(), 0);
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
		// System.out.println(proc0.L1cache);
		// System.out.println(proc0.L3cache.getLine((new DataByteAddress(80L).lineAddress())).getPerCoreMetadata(P0));

		machine.testCacheMemoryRead(P1, 82L, 2, 3, T1);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_RegionsWithFRVs.get(), 0);
		// System.out.println(proc1.L1cache);
	}

	@Test
	public void testReadWriteConflict() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		machine.testCacheMemoryRead(P0, 64L, 2, 64, T0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryRead(P1, 64L, 2, 64, T1);
		// System.out.println(proc1.L1cache);

		// machine.testProcessRegionBoundary(P0, T0, -1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionsWithTolerableConflicts.get(), 0);
	}
}
