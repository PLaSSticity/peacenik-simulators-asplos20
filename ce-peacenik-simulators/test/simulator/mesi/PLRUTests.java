/**
 *
 */
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

public final class PLRUTests {

	static Machine<MESILine> machine;

	static final CpuId P0 = new CpuId(0);
	static final CpuId P1 = new CpuId(1);
	static final CpuId P2 = new CpuId(2);
	static final CpuId P3 = new CpuId(3);

	static final ThreadId T0 = new ThreadId((byte) 0);
	static final ThreadId T1 = new ThreadId((byte) 1);
	static final ThreadId T2 = new ThreadId((byte) 2);
	static final ThreadId T3 = new ThreadId((byte) 3);

	static final int CORES = 4;
	static final int PINTHREADS = 4;
	static final int LINE_SIZE = 4;

	static final int L1_ASSOC = 2;
	static final int L1_CACHE_SIZE = 16; // 4 blocks, 2 sets

	static final int L2_ASSOC = 2;
	static final int L2_CACHE_SIZE = 16; // 4 blocks, 2 sets

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
				return false;
			}

			@Override
			boolean usePLRU() {
				return true;
			}
			
			@Override
			boolean printConflictingSites() {
				return false;
			}

			@Override
			boolean treatAtomicUpdatesAsRegularAccesses() {
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
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 80L, 2);
		// evict the line 80L
		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);

		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(80L)));
	}

	@Test
	public void testEviction2() {
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 80L, 2);
		machine.testCacheMemoryWrite(P1, 64L, 2);
		machine.testCacheMemoryWrite(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		// evict 64L
		// assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(64L)));
		// evict 80L because L2 knows nothing about the L1 hit for 80L
		assertEquals(null, proc1.L2cache.getLine(new DataLineAddress(80L)));
	}
}