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

public final class RegularTests {

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
	public void testWriteWriteConflict() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		// System.out.println(proc0.L1cache);
		machine.testCacheMemoryWrite(P0, 82L, 2, 82, T0);
		// System.out.println(proc0.L1cache);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 96, T0);
		// 80L is evicted to the LLC
		// System.out.println(proc0.L1cache);
		// System.out.println(proc0.L3cache);
		// System.out.println(proc0.L3cache.getLine((new DataByteAddress(80L).lineAddress())).getPerCoreMetadata(P0));

		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T1);
		/*		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
				assertEquals(1, proc1.stats.pc_l2d.pc_WriteMisses.get(), 0);
				assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);*/
		// The LLC need not eagerly check for conflicts on a fetch
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
		// System.out.println(proc1.L1cache);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
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
		// evict 80L to the LLC

		// System.out.println(proc0.L3cache.getLine((new DataByteAddress(80L).lineAddress())).getPerCoreMetadata(P0));

		machine.testCacheMemoryRead(P1, 80L, 2, 3, T1);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);

		machine.testCacheMemoryWrite(P1, 64L, 2, 64, T1);
		machine.testCacheMemoryWrite(P1, 96L, 2, 96, T1);
		// evict 80L to the llc
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
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
	public void testReadWriteConflict0() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryRead(P0, 96L, 2, 96, T0);
		machine.testCacheMemoryRead(P0, 64L, 2, 64, T0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 96, T1);
		machine.testCacheMemoryRead(P1, 64L, 2, 64, T1);
		// System.out.println(proc1.L1cache);

		// machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}

	@Test
	public void testReadWriteConflict1() {
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
	public void testReadValidationConflict0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		// The following read will get the value 0 from the simulator memory instead of the given value 996
		// since the line has been initialized when P1 reads 64L.
		machine.testCacheMemoryRead(P1, 66L, 2, 996, T1);

		machine.testCacheMemoryWrite(P0, 64L, 2, 64, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryWrite(P2, 66L, 2, 66, T2);
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
	public void testReadValidationConflict1() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryWrite(P0, 66L, 2, 64, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testReadValidationConflict2() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		// Such "out-of-thin-air" behavior might occur in real executions
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
		// Such "out-of-thin-air" behavior might occur in real executions
		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		machine.testCacheMemoryRead(P1, 66L, 2, 88, T1);
		// This read should get 0 because memline has been created at P1's first read

		machine.testCacheMemoryWrite(P0, 66L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 82L, 2, 64, T0);
		machine.testCacheMemoryWrite(P0, 98L, 2, 64, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_FailedValidations.get(), 0);
	}

	// A read-only line should still be invalidated if it has untouched offsets and might have been written by the llc.
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
	 * The optimization of avoiding invalidating touched lines would still introduce false data race
	 * even after the previous fix (testReadValidationConflict4 and testReadValidationConflict5)
	 * since region boundaries in RCC are not exactly where locks are acquired/released.
	 *
	 *  T0			T1
	 *  			wr/rd x
	 *  			-----
	 *  			rel()
	 *  			-----
	 *  -----
	 *  acq()
	 *  wr x
	 *	-----
	 *	rel()
	 *				acq()
	 *				rd x
	 *				-----
	 *				rel()
	 *
	 *	acq() and rel() denote where a lock is actually acquired and released, respectively.
	 *	False data race will be reported between T0's write and T1's second read although
	 *	they are well synchronized.
	 *
	 *	Putting extra region boundaries after acq() might solve the problem.
	 */

	@Test
	public void testReadValidationConflict6() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 64L, 2, 990, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P0, 64L, 2, 992, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_RELEASE);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryRead(P1, 64, 2, 990, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testReadValidationConflict7() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P1, 64L, 2, 990, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P0, 64L, 2, 992, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_RELEASE);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryRead(P1, 64, 2, 990, T1);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_RELEASE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	/*	// Should not exist in real executions considering memory alignment
		@Test
		public void testReadValidationConflict2() {
			Processor<ViserLine> proc1 = machine.getProc(P1);
	
			machine.testCacheMemoryRead(P1, 65L, 1, -6138712L, T1);
			machine.testCacheMemoryWrite(P0, 64L, 2, 664, T0);
			machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
			machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
	
			assertEquals(1, proc1.stats.pc_FailedValidations.get(), 0);
		}
		// Should not exist in real executions considering memory alignment
		@Test
		public void testReadValidationConflict3() {
			Processor<ViserLine> proc1 = machine.getProc(P1);
			machine.testCacheMemoryWrite(P0, 65L, 2, 0, T0);
			machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
			// Write a new value 0 to overwrite the initial value -6138712.
			// Otherwise, no false positive even if I don't fix the set-value-only-to-the-first-offset bug,
			// because of my previous fix that the simulator only reports read validation failure
			// at non-initial values (testReadValidationConflict1).
	
			machine.testCacheMemoryRead(P1, 65L, 1, 664, T1);
			machine.testCacheMemoryWrite(P0, 64L, 2, 664, T0);
			machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
			machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
	
			assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
		}*/

	@Test
	public void testWARUpgrade0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);
		machine.testCacheMemoryWrite(P1, 82L, 2, 82, T1);

		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0);
		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		// 2 failed validations on the same line
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
		assertEquals(100, proc0.L3cache.getLine(new DataLineAddress(80L)).getValue(0));
		assertEquals(82, proc0.L3cache.getLine(new DataLineAddress(80L)).getValue(2));
	}

	@Test
	public void testWARUpgrade1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 0, T1);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testWARUpgrade2() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T1);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 80, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testWARUpgrade3() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testWARUpgrade4() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 20, T1);
		machine.testCacheMemoryWrite(P2, 80L, 2, 0, T1);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testProcessRegionBoundary(P2, T2, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testWARUpgrade5() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 82L, 2, 92, T1);
		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testWARUpgrade6() {
		Processor<ViserLine> proc2 = machine.getProc(P2);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P2, 80L, 2, 80, T2);

		machine.testCacheMemoryRead(P1, 80L, 2, 0, T1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 100, T1);

		// evict 80L
		machine.testCacheMemoryRead(P1, 64L, 2, 0, T1);
		machine.testCacheMemoryRead(P1, 96L, 2, 0, T1);
		ViserLine llcline = proc1.L3cache.getLine(new DataLineAddress(80L));
		// read coding will be cleared if write coding is also set on L2 eviction.
		assertEquals(0, llcline.getReadEncoding(proc1.id), 0);

		machine.testProcessRegionBoundary(P2, T2, EventType.LOCK_ACQUIRE);
		// machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc2.stats.pc_PreciseConflicts.get(), 0);
	}

	@Test
	public void testWARUpgrade7() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		machine.testCacheMemoryWrite(P1, 82L, 2, 92, T1);
		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 100, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	// A shared line's epoch should be updated only after its obsolete encodings are cleared.
	/*
	 * (x0 and x1 shared the same cache line.)
	 * 		T1			T0
	 * 		wr x0
	 * 		-----
	 * 		rd x1
	 * 		(evict)
	 * 					-----
	 * 					wr x0
	 * 					----- ** false data race detected on x0!
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

	// A shared line's epoch should be updated only after its obsolete encodings are cleared.
	/*
	 * (x0 and x1 shared the same cache line.)
	 * 		T1			T0
	 * 		wr x0
	 * 		-----
	 * 		rd x1
	 * 		wr x1
	 * 		(evict)
	 * 					-----
	 * 					wr x0
	 * 					----- ** false data race detected on x0!
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

	@Test
	public void testReadValue() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 80L, 2, 90, T1);
		machine.testCacheMemoryRead(P1, 80L, 2, 0, T1);
		assertEquals(90, proc1.L1cache.getLine(new DataLineAddress(80L)).getValue(0), 0);
		machine.testCacheMemoryWrite(P1, 80L, 2, 92, T1);
		assertEquals(92, proc1.L1cache.getLine(new DataLineAddress(80L)).getValue(0), 0);
	}

	@Test
	public void testFRVCounters() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryRead(P1, 70L, 2, 90, T1);
		machine.testCacheMemoryRead(P1, 78L, 2, 90, T1);
		// the above 2 lines are in the same set of L1/L2

		machine.testCacheMemoryRead(P1, 82L, 2, 92, T1);
		machine.testCacheMemoryWrite(P1, 82L, 2, 99, T1);
		// evict 82L, dirty eviction
		machine.testCacheMemoryRead(P1, 66L, 2, 92, T1);
		machine.testCacheMemoryRead(P1, 98L, 2, 92, T1);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);

		machine.testCacheMemoryWrite(P0, 78L, 2, 100, T0);
		machine.testCacheMemoryWrite(P0, 70L, 2, 100, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
		assertEquals(2, proc1.stats.pc_FailedValidations.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionsWithFRVs.get(), 0);
		assertEquals(1, proc1.stats.pc_RegionHasDirtyEvictionBeforeFRV.get(), 0);
	}

	// Untouched lines might have values deferred from previous regions.
	@Test
	public void testOptimizations0() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(ViserState.VISER_VALID, proc1.L1cache.getLine(new DataLineAddress(80L)).getState());

		// evicted 80L
		machine.testCacheMemoryWrite(P1, 64L, 2, 90, T0);
		machine.testCacheMemoryWrite(P1, 96L, 2, 90, T0);
		// write metadata lost
		assertEquals(80, proc1.L3cache.getLine(new DataLineAddress(80L)).getValue(0), 0);
		// System.out.println(proc1.L3cache.getLine(new DataLineAddress(80L)).getLastWriter(0));
	}

	// A similar test to testOptimizations0(), but the evicted line is not totally untouched on eviction.
	// Untouched offsets of a line might have values deferred from previous regions.
	@Test
	public void testOptimizations3() {
		Processor<ViserLine> proc1 = machine.getProc(P1);
		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(ViserState.VISER_VALID, proc1.L1cache.getLine(new DataLineAddress(80L)).getState());

		// machine.testCacheMemoryWrite(P1, 82L, 2, 82, T0);
		machine.testCacheMemoryRead(P1, 82L, 2, 82, T0);
		// evicted 80L
		machine.testCacheMemoryWrite(P1, 64L, 2, 90, T0);
		machine.testCacheMemoryWrite(P1, 96L, 2, 90, T0);
		// write metadata lost
		assertEquals(80, proc1.L3cache.getLine(new DataLineAddress(80L)).getValue(0), 0);
		// System.out.println(proc1.L3cache.getLine(new DataLineAddress(80L)).getLastWriter(0));
	}

	@Test
	public void testOptimizations1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T0);

		machine.testCacheMemoryWrite(P0, 82L, 2, 82, T0);
		machine.testCacheMemoryWrite(P0, 66L, 2, 82, T0);
		machine.testCacheMemoryWrite(P0, 98L, 2, 82, T0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryRead(P0, 82L, 2, 90, T0);
		assertEquals(82, proc0.L1cache.getLine(new DataLineAddress(80L)).getValue(2), 0);
		assertEquals(false, proc0.L3cache.getLine(new DataLineAddress(80L)).isLineDeferred());
	}

	@Test
	public void testOptimizations2() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 82L, 2, 82, T0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryWrite(P1, 82L, 2, 0, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		if (proc0.params.restartAtFailedValidationsOrDeadlocks())
			assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
		else
			assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	/*
	 * WAR upgrades on a deferred line would cause false races detected between a core and itself.
	 * We solve the issue by clearing write/read bits when a deferred line is fetched and not committing
	 * and validating those deferred values any more at region boundaries.
	 * 
	 * (x0 == 0 initially and x0 and x1 share the same cache line L)
	 * T0				T1
	 * x0 = 1
	 * -----
	 * r = x0 (==1)
	 * x0 = 2
	 * 					x1 = 3
	 * 					-----
	 * -----
	 */
	@Test
	public void testOptimizations4() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		// 80L is deferred
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		// WAR upgrade
		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 2, T0);

		// 80L is fetched from the owner P1. Values and metadata should be written back.
		machine.testCacheMemoryWrite(P1, 82L, 2, 3, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testAtomicAccesses0() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 80L, 2, 800, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 800, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 800, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE); // clear W bits
		// 80L is evicted and 96L and 64L are deferred.

		machine.testCacheMemoryRead(P0, 80L, 2, 800, T0);
		assertEquals(800, proc0.L1cache.getLine(new DataLineAddress(80L)).getValue(0), 0);

		// An atomic read is expected to happen before an atomic read
		machine.cacheRead(P1, 80L, 2, 0, T1, 0, 0, MemoryAccessType.ATOMIC_READ);
		// atomic write
		machine.cacheWrite(P1, 80L, 2, 900, T1, 0, 0, MemoryAccessType.ATOMIC_WRITE);

		// regular write, increase version
		machine.testCacheMemoryWrite(P1, 82L, 2, 920, T1);

		// 80L in the LLC shouldn't be affected by atomic accesses since we do atomic accesses in dedicated lines.
		ViserLine llcLine = proc0.L3cache.getLine(new DataLineAddress(80L));
		assertEquals(800, llcLine.getValue(0), 0);
		assertEquals(false, llcLine.isLineDeferred());

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testAtomicAccesses1() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 80L, 2, 800, T0);
		// 80L is deferred
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P0, 80L, 2, 800, T0);
		assertEquals(800, proc0.L1cache.getLine(new DataLineAddress(80L)).getValue(0), 0);

		// An atomic read is expected to happen before an atomic read
		machine.cacheRead(P1, 80L, 2, 0, T1, 0, 0, MemoryAccessType.ATOMIC_READ);
		// atomic write, not increase version
		machine.cacheWrite(P1, 80L, 2, 900, T1, 1, 1, MemoryAccessType.ATOMIC_WRITE);

		// regular write, increase version
		machine.testCacheMemoryWrite(P1, 82L, 2, 920, T1);

		// 80L in the LLC shouldn't be affected by atomic accesses since we do atomic accesses in dedicated lines.
		ViserLine llcLine = proc0.L3cache.getLine(new DataLineAddress(80L));
		assertEquals(800, llcLine.getValue(0), 0);
		assertEquals(false, llcLine.isLineDeferred());

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	/*
	 * A dirty line can be deferred *only* if it has up-to-date values or its version matches shared line's.
	 * Otherwise, values in the shared line may risk being overwritten with obsolete values of the 
	 * private line when it is fetched, as in the following example.
	 * 
	 * T0
	 * wr x0
	 * 					T1
	 * 					wr x1
	 * 					-----
	 * -----
	 * 					rd x1
	 * 					-----
	 * 
	 * If T0 defers the line at the region boundary, x1's value in the llc will be overwritten with the value
	 * in T0's private line when T1 fetches the line to validate its read at x1 and cause validation failure.
	 */
	@Test
	public void testOptimizations5() {
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		// 80L is deferred
		// machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P1, 82L, 2, 2, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P1, 82L, 2, 2, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testOptimizations6() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0);

		machine.testCacheMemoryWrite(P1, 80L, 2, 80, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		// machine.testCacheMemoryRead(P1, 80L, 2, 2, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 880, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		// System.out.println(proc0.L3cache.getLine(new DataLineAddress(80)).getValue(0));
		// System.out.println(proc0.L1cache.getLine(new DataLineAddress(80)).getValue(0));
		machine.testCacheMemoryWrite(P1, 82L, 2, 84, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		// machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testOptimizations7() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testCacheMemoryRead(P0, 82L, 2, 0, T0);
		// 80L is deferred
		// machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryWrite(P1, 80L, 2, 2, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);

		machine.testCacheMemoryWrite(P1, 82L, 2, 82, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P0, 80L, 2, 0, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc0.stats.pc_FailedValidations.get(), 0);
	}

	/*
	 * should backup deferred write-backs when the same location is written again
	 * for the restart config.	
	 */
	@Test
	public void testOptimizations8() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 82L, 2, 1, T0);
		// deferred
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		machine.testCacheMemoryWrite(P0, 80L, 2, 2, T0);

		machine.testCacheMemoryRead(P1, 80L, 2, 1, T0);
		machine.testCacheMemoryRead(P1, 82L, 2, 1, T0);

		ViserLine l1line = proc1.L1cache.getLine(new DataLineAddress(80L));
		if (proc0.params.restartAtFailedValidationsOrDeadlocks())
			assertEquals(1, l1line.getValue(0), 0);
		else
			assertEquals(2, l1line.getValue(0), 0);
		assertEquals(1, l1line.getValue(2), 0);

		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);

		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
		assertEquals(0, proc1.stats.pc_FailedValidations.get(), 0);
	}

	/*
	 * (x0 == 0 initially and x0 and x1 share the same cache line L)
	 * T0				T1
	 * x0 = 1
	 * -----
	 * r = x0 (==1)
	 * x0 = 2
	 * 					x0 = 3
	 * 					-----
	 * -----
	 */
	@Test
	public void testOptimizations9() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		// 80L is deferred
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		// WAR upgrade
		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 2, T0);

		// 80L is fetched from the owner P1. Values and metadata should be written back.
		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	/*
	 * (x0 == 0 initially and x0 and x1 share the same cache line L)
	 * T0				T1
	 * x0 = 1
	 * -----
	 * r = x0 (==1)
	 * 					x0 = 3
	 * 					-----
	 * -----
	 */
	@Test
	public void testOptimizations10() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		Processor<ViserLine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		// 80L is deferred
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);

		// 80L is fetched from the owner P1. Values and metadata should be written back.
		machine.testCacheMemoryWrite(P1, 80L, 2, 3, T0);
		machine.testProcessRegionBoundary(P1, T1, EventType.LOCK_ACQUIRE);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}

	@Test
	public void testFetchingWriteBits() {
		Processor<ViserLine> proc0 = machine.getProc(P0);
		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 64L, 2, 1, T0);
		machine.testCacheMemoryWrite(P0, 96L, 2, 1, T0);
		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		ViserLine llcline = proc0.L3cache.getLine(new DataLineAddress(80L));
		assertEquals(-1, llcline.getDeferredLineOwnerID());
		machine.testCacheMemoryRead(P1, 80L, 2, 1, T0);
	}

	@Test
	public void testLastWriter() {
		Processor<ViserLine> proc0 = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 80L, 2, 1, T0);
		machine.testCacheMemoryRead(P1, 82L, 2, 0, T0);
		// ViserLine l1line = proc1.L1cache.getLine(new DataLineAddress(80L));
		// System.out.println(l1line.getValue(0));
		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);

		machine.testCacheMemoryRead(P0, 80L, 2, 1, T0);

		machine.testCacheMemoryRead(P1, 66L, 2, 0, T0);
		machine.testCacheMemoryRead(P1, 98L, 2, 0, T0);
		// evict 82L
		machine.testCacheMemoryWrite(P2, 82L, 2, 1, T0);// inc version at line 80L

		// ViserLine llcline = proc0.L3cache.getLine(new DataLineAddress(80L));
		// System.out.println(llcline.getValue(0));
		machine.testProcessRegionBoundary(P2, T2, EventType.LOCK_ACQUIRE);

		machine.testProcessRegionBoundary(P0, T0, EventType.LOCK_ACQUIRE);
		assertEquals(0, proc0.stats.pc_FailedValidations.get(), 0);
	}
}
