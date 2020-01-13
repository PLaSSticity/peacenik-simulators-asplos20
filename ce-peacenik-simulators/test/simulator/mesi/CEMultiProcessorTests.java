package simulator.mesi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import simulator.mesi.MESISim.PARSEC_PHASE;
import simulator.mesi.Machine.SimulationMode;

public final class CEMultiProcessorTests {

	static Machine<MESILine> machine;

	static final CpuId P0 = new CpuId(0);
	static final CpuId P1 = new CpuId(1);
	static final CpuId P2 = new CpuId(2);
	static final CpuId P3 = new CpuId(3);

	static final ThreadId T0 = new ThreadId(0);
	static final ThreadId T1 = new ThreadId(1);
	static final ThreadId T2 = new ThreadId(2);
	static final ThreadId T3 = new ThreadId(3);
	
	static final int CORES = 4;
	static final int PINTHREADS = 1;
	static final int LINE_SIZE = 4;

	static final int L1_ASSOC = 2;
	static final int L1_CACHE_SIZE = 16; // 4 blocks, 2 sets
	
	static final int L2_ASSOC = 2;
	static final int L2_CACHE_SIZE = 16;  // 4 blocks, 2 sets

	static final int L3_ASSOC = 4;
	static final int L3_CACHE_SIZE = 64;   
	
	static {
		// This will enable all the assertions like cache inclusivity checks
		assert MESISim.XASSERTS;
		MemorySystemConstants.unsafeSetLineSize(LINE_SIZE);
	}
	
	static CacheConfiguration<MESILine> l1config = new CacheConfiguration<MESILine>() {
		{
			cacheSize = L1_CACHE_SIZE;
			lineSize = LINE_SIZE;
			assoc = L1_ASSOC;
			level = CacheLevel.L1;
		}
	};
	
	static CacheConfiguration<MESILine> l2config = new CacheConfiguration<MESILine>() {
		{
			cacheSize = L2_CACHE_SIZE;
			lineSize = LINE_SIZE;
			assoc = L2_ASSOC;
			level = CacheLevel.L2;
		}
	};

	static CacheConfiguration<MESILine> l3config = new CacheConfiguration<MESILine>() {
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
		Machine.MachineParams<MESILine> params = new Machine.MachineParams<MESILine>() {

			@Override
			SimulationMode simulationMode() {
				return SimulationMode.BASELINE;
			}

			@Override
			int numProcessors() {
				return CORES;
			}

			@Override
			int numPinThreads() {
				return PINTHREADS;
			}
			
			@Override
			boolean pintool() {
				return false;
			}
			
			@Override
			CacheConfiguration<MESILine> l1config() {
				return l1config;
			}

			@Override
			boolean useL2() {
				return true;
			}

			@Override
			CacheConfiguration<MESILine> l2config() {
				return l2config;
			}

			@Override
			CacheConfiguration<MESILine> l3config() {
				return l3config;
			}

			LineFactory<MESILine> lineFactory() {
				return new LineFactory<MESILine>() {
					@Override
					public MESILine create(CpuId id, CacheLevel level) {
						return new MESILine(id, level);
					}

					@Override
					public MESILine create(CpuId id, CacheLevel level, LineAddress la) {
						return new MESILine(id, level, la);
					}
					
					@Override
					public MESILine create(CpuId id, CacheLevel level, MESILine l) {
						assert l.valid() : "Source line should be valid.";
						MESILine tmp = new MESILine(id, level, l.lineAddress());
						tmp.changeStateTo(l.getState());
						tmp.setDirty(l.dirty());
						if (conflictExceptions()) {
							tmp.setLocalReads(l.getLocalReads());
							tmp.setLocalWrites(l.getLocalWrites());
							tmp.setRemoteReads(l.getRemoteReads());
							tmp.setRemoteWrites(l.getRemoteWrites());
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
			boolean conflictExceptions() {
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
			boolean usePLRU() {
				return false;
			}

			@Override
			boolean withPacifistBackends() {
				return false;
			}

			@Override
			boolean pauseCoresAtConflicts() {
				return false;
			}

			@Override
			boolean siteTracking() {
				return false;
			}

			@Override
			boolean lockstep() {
				return false;
			}

			@Override
			boolean isHttpd() {
				return false;
			}

			@Override
			boolean dirtyEscapeOpt() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			boolean restartAtFailedValidationsOrDeadlocks() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			boolean evictCleanLineFirst() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			boolean setWriteBitsInL2() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			boolean BackupDeferredWritebacksLasily() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			boolean FalseRestart() {
				// TODO Auto-generated method stub
				return false;
			}

			@Override
			int pausingTimeout() {
				// TODO Auto-generated method stub
				return 0;
			}
		};
		machine = new Machine<MESILine>(params);
		
		// Not sure how we can override JOpt command line
		MESISim.Options = Knobs.parser.parse("--xassert=true --assert-period=1");
		MESISim.setPhase(PARSEC_PHASE.IN_ROI);
		assertTrue(MESISim.XASSERTS);
		assertEquals(1, MESISim.Options.valueOf(Knobs.AssertPeriod).intValue());
		machine.initializeEpochs();
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testWriteReadConflict5() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 66L, 2);
		machine.processSyncOp(P0, T0, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteReadConflict6() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P2, 66L, 2);
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteReadConflict7() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P0, 80L, 2);
		machine.testCacheMemoryRead(P0, 96L, 2);		
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteReadConflict1() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteReadConflict2() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryWrite(P0, 80L, 2);
		machine.testCacheMemoryWrite(P0, 96L, 2);
		proc0.printPerRegionLocalTable();
		// Line 64L gets evicted from the private caches
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteReadConflict3() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 66L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteWriteConflict1() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteWriteConflict2() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryWrite(P0, 80L, 2);
		machine.testCacheMemoryWrite(P0, 96L, 2);
		proc0.printPerRegionLocalTable();
		// Line 64L gets evicted from the private caches
		
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testReadWriteConflict1() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testReadWriteConflict2() {
		Processor<MESILine> proc1 = machine.getProc(P1);
	
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryRead(P0, 64L, 2);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testReadWriteConflict3() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryRead(P2, 80L, 2);
		machine.testCacheMemoryWrite(P2, 96L, 2);
		proc2.printPerRegionLocalTable();
		// Line 64L gets evicted from the private caches
		
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary1() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary2() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryWrite(P1, 66L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
	}

	@Test
	public void testRegionBoundary3() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryRead(P1, 66L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary4() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryRead(P3, 64L, 2);
		machine.testCacheMemoryWrite(P1, 66L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}	
	
	@Test
	public void testRegionBoundary5() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryRead(P3, 64L, 2);
		machine.testCacheMemoryWrite(P1, 66L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.processSyncOp(P3, T3, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary8() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P2, 64L, 2);
		machine.testCacheMemoryRead(P3, 64L, 2);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(2, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary9() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P2, 64L, 2);
		machine.testCacheMemoryRead(P1, 66L, 2);
		machine.processSyncOp(P2, T2, EventType.LOCK_RELEASE, EventType.REG_END);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteReadConflict4() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteWriteConflict3() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testWriteWriteConflict4() {
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryWrite(P0, 80L, 2);
		machine.testCacheMemoryWrite(P0, 96L, 2);
		
		machine.testCacheMemoryWrite(P1, 66L, 2);
		machine.testCacheMemoryWrite(P2, 64L, 2);
		assertEquals(1, proc2.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testReadWriteConflict4() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		
		machine.testCacheMemoryRead(P0, 64L, 2);
		
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testMetadataRestoration1() {
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		// 64L is evicted into memory
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		
		machine.testCacheMemoryWrite(P2, 64L, 2);
		assertEquals(1, proc2.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary6() {
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		// 64L is evicted into memory
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.processSyncOp(P1, T1, EventType.LOCK_RELEASE, EventType.REG_END);
		
		machine.testCacheMemoryWrite(P2, 64L, 2);
		assertEquals(0, proc2.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testRegionBoundary7() {
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		// 64L is evicted into memory

		machine.testCacheMemoryRead(P0, 64L, 2);
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.processSyncOp(P1, T1, EventType.LOCK_RELEASE, EventType.REG_END);
		
		machine.testCacheMemoryWrite(P2, 64L, 2);
		assertEquals(1, proc2.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testDeadlock1() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc0 = machine.getProc(P0);
		
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 66L, 2);
		machine.testCacheMemoryRead(P1, 68L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		machine.testCacheMemoryWrite(P0, 64L, 2);
		assertEquals(1, proc0.stats.pc_PreciseConflicts.get(), 0);
		machine.testCacheMemoryWrite(P0, 66L, 2);
		assertEquals(2, proc0.stats.pc_PreciseConflicts.get(), 0);
		machine.testCacheMemoryWrite(P0, 68L, 2);
		assertEquals(3, proc0.stats.pc_PreciseConflicts.get(), 0);
		
		assertEquals(1, proc0.stats.pc_RegionsWithTolerableConflicts.get(), 0);
		assertEquals(1, proc0.stats.pc_RegionsWithExceptions.get(), 0);
		// No deadlock since pausing is off
		assertEquals(0, proc0.stats.pc_PotentialDeadlocks.get(), 0);
		assertEquals(0, proc0.stats.pc_RegionsWithExceptionsByPotentialDeadlocks.get(), 0);
	}
	
	@Test
	public void testModifiedToInvalid() {
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.testCacheMemoryWrite(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		machine.testCacheMemoryRead(P1, 112L, 2);
		machine.testCacheMemoryRead(P1, 128L, 2);
		// 64L is evicted into memory
		
		machine.testCacheMemoryRead(P1, 64L, 2);
		
		machine.testCacheMemoryWrite(P2, 64L, 2);
		assertEquals(1, proc2.stats.pc_PreciseConflicts.get(), 0);
	}
	
	@Test
	public void testEpochs1() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.printEpochMap();
		proc0.processSyncOp(T0);
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		proc1.processSyncOp(T1);
		machine.testCacheMemoryRead(P2, 64L, 2);
		// P1 has already executed a region boundary, so its bits are cleared
		assertEquals(0, proc2.stats.pc_PreciseConflicts.get(), 0);
		machine.printEpochMap();
	}
	
	@Test
	public void testEpochs2() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);
		
		machine.printEpochMap();
		proc0.processSyncOp(T0);
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_PreciseConflicts.get(), 0);
		proc1.processSyncOp(T1);
		machine.testCacheMemoryRead(P2, 64L, 2);
		// From a correctness perspective, it seems correct to only detect the first conflict
		assertEquals(0, proc2.stats.pc_PreciseConflicts.get(), 0);
		machine.printEpochMap();
	}
}
