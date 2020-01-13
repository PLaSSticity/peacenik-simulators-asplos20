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

public class MESIMultiProcessorTests {

	static Machine<MESILine> machine;

	static final CpuId P0 = new CpuId(0);
	static final CpuId P1 = new CpuId(1);
	static final CpuId P2 = new CpuId(2);
	static final CpuId P3 = new CpuId(3);

	static final int CORES = 4;
	static final int PINTHREADS = 1;
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
			boolean printConflictingSites() {
				return false;
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
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

	@Test
	public void testRead() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 80L, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		machine.testCacheMemoryRead(P0, 82L, 2); // same line
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		machine.testCacheMemoryRead(P1, 72L, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		machine.testCacheMemoryRead(P1, 80L, 2); // LLC hit, need to evict from private L1 cache
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P0, 80L, 2); // LLC hit, no need to evict from private L1 cache
		assertEquals(2, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		System.out.println(proc0.L1cache);
	}

	@Test
	public void testWrite() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 80L, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P0, 64L, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P0, 82L, 2);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 72L, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P1, 88L, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P1, 80L, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		// 72 is evicted from p1's private cache, but it is marked as valid in the LLC.
		// So this access should be a hit in the LLC.
		machine.testCacheMemoryWrite(P0, 72L, 2);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
	}

	@Test
	public void testLLCHit() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);

		// bring 0d into the cache
		machine.testCacheMemoryRead(P0, 0, 1);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		System.out.println(proc0.L1cache);

		// fetch 16d and 32d which map to set 0; this evicts 0d to the L3
		for (int i = 1; i <= L1_ASSOC; i++) {
			machine.testCacheMemoryRead(P0, L1_CACHE_SIZE * i, 1);
			assertEquals(1 + i, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		}
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);

		// fetch 0d again, from the L3. This is a miss since the LLC line was
		// invalidated when evicting the
		// private L1 line.
		machine.testCacheMemoryRead(P1, 0, 1);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryRead(P2, 0, 1);
		assertEquals(0, proc2.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc2.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc2.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc2.stats.pc_l3d.pc_LineEvictions.get(), 0);
	}

	@Test
	public void testCrossLineRead() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		// this crosses a line boundary
		machine.testCacheMemoryRead(P0, 0, 8);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P0, 8, 4);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 2, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P1, 6, 4);
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
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		// this crosses a line boundary
		machine.testCacheMemoryWrite(P0, 0, 8);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P0, 8, 4);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryWrite(P1, 2, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P1, 6, 4);
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
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryRead(P0, 4L, 2);
		assertEquals(1, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		// System.out.println(proc0.L1cache);

		// This is a hit in the shared cache
		machine.testCacheMemoryWrite(P1, 4L, 2);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(4L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;

		// machine.cacheWrite(P2, 4, 2);
		// assertEquals(0, proc2.stats.pc_l1d.pc_WriteHits.get());
		// assertEquals(1, proc2.stats.pc_l1d.pc_WriteMisses.get());
		// assertEquals(0, proc2.stats.pc_l1d.pc_LineEvictions.get());
		// assertEquals(1, proc2.stats.pc_l3d.pc_WriteHits.get());
		// assertEquals(0, proc2.stats.pc_l3d.pc_WriteMisses.get());
		// assertEquals(0, proc2.stats.pc_l3d.pc_LineEvictions.get());
		//// System.out.println(proc0.L1cache);
		//// System.out.println(proc1.L1cache);
		//// System.out.println(proc2.L1cache);

		machine.testCacheMemoryRead(P0, 4, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
	}

	@Test
	public void testWriteRead() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);
		Processor<MESILine> proc3 = machine.getProc(P3);

		machine.testCacheMemoryWrite(P0, 4, 2);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 4, 2);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P2, 4, 2);
		assertEquals(1, proc2.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc2.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc2.stats.pc_l3d.pc_ReadHits.get(), 0);
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);
		// System.out.println(proc2.L1cache);

		machine.testCacheMemoryWrite(P3, 4, 2);
		assertEquals(1, proc3.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc3.stats.pc_l2d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc3.stats.pc_l3d.pc_WriteHits.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
		System.out.println(proc3.L1cache);
	}

	@Test
	public void testEviction() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 44, 2);
		machine.testCacheMemoryRead(P0, 12, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 44, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P1, 12, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P0, 76, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc0.stats.pc_l3d.pc_ReadMisses.get(), 0);
		// An L1 line 44 is evicted, but the line is marked as invalid in the LLC.
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		// machine.cacheRead(P1, 76, 2);
		// assertEquals(0, proc1.stats.pc_l1d.pc_ReadHits.get());
		// assertEquals(3, proc1.stats.pc_l1d.pc_ReadMisses.get());
		// assertEquals(1, proc1.stats.pc_l1d.pc_LineEvictions.get());
		// assertEquals(3, proc1.stats.pc_l3d.pc_ReadHits.get());
		// assertEquals(0, proc1.stats.pc_l3d.pc_ReadMisses.get());
		// // An L1 line 44 is evicted, but the line is marked as invalid in the LLC.
		// assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get());
		// System.out.println(proc0.L1cache);
		// System.out.println(proc1.L1cache);
	}

	/** Test eviction of an LLC line and invalidation. */
	@Test
	public void testLLCEviction() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryWrite(P0, 40, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P1, 56, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 72, 2);
		assertEquals(0, proc0.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc0.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc0.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P1, 88, 2);
		assertEquals(0, proc1.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc1.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc1.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryWrite(P2, 104, 2);
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

	/** Test remote invalidation. */
	@Test
	public void testRemoteInvalidation1() {
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 0, 2);
		machine.testCacheMemoryWrite(P1, 2, 2);
		assertEquals(1, proc1.stats.pc_MESIWriteRemoteHits.get(), 0);
	}

	@Test
	public void testRemoteInvalidation2() {
		Processor<MESILine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryRead(P0, 0, 2);
		machine.testCacheMemoryRead(P1, 1, 2);
		machine.testCacheMemoryWrite(P2, 2, 2);
		assertEquals(2, proc2.stats.pc_MESIWriteRemoteHits.get(), 0);
	}

	@Test
	public void testRemoteInvalidation3() {
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryRead(P0, 0, 2);
		machine.testCacheMemoryWrite(P1, 1, 2);
		assertEquals(1, proc1.stats.pc_MESIWriteRemoteHits.get(), 0);
		machine.testCacheMemoryRead(P0, 1, 2);
		machine.testCacheMemoryWrite(P2, 2, 2);
		assertEquals(2, proc2.stats.pc_MESIWriteRemoteHits.get(), 0);
	}

	@Test
	public void testRemoteInvalidation4() {
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 0, 2);
		machine.testCacheMemoryWrite(P1, 1, 2);
		assertEquals(1, proc1.stats.pc_MESIWriteRemoteHits.get(), 0);
		machine.testCacheMemoryRead(P0, 1, 2);
		machine.testCacheMemoryWrite(P1, 2, 2);
		assertEquals(2, proc1.stats.pc_MESIWriteRemoteHits.get(), 0);
	}

	@Test
	public void testLLCHit2() {
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 0, 2);
		machine.testCacheMemoryRead(P0, 8, 2);
		machine.testCacheMemoryRead(P0, 16, 2);
		machine.testCacheMemoryWrite(P1, 0, 2);
		assertEquals(1, proc1.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc1.stats.pc_MESIWriteRemoteHits.get(), 0);
	}

	@Test
	public void testLLCHit3() {
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 0, 2);
		machine.testCacheMemoryRead(P1, 0, 2);
		machine.testCacheMemoryRead(P1, 0, 2);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(0, proc1.stats.pc_MESIWriteRemoteHits.get(), 0);
		assertEquals(1, proc1.stats.pc_MESIReadRemoteHits.get(), 0);
	}

	@Test
	public void testDirtyBit() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P0, 64L, 2);
		System.out.println(proc0.L1cache);
		machine.testCacheMemoryRead(P0, 80L, 2);
		machine.testCacheMemoryRead(P0, 96L, 2);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 64L, 2);
		assertEquals(1, proc1.stats.pc_l3d.pc_ReadHits.get(), 0);
		machine.testCacheMemoryWrite(P1, 112L, 2);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P1, 128L, 2);
		System.out.println(proc1.L1cache);
	}

	@Test
	public void testIToSTransition1() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryRead(P0, 64L, 2);
		System.out.println(proc0.L1cache);

		machine.testCacheMemoryRead(P1, 64L, 2);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);

		machine.testCacheMemoryRead(P2, 64L, 2);
		System.out.println(proc0.L1cache);
		System.out.println(proc1.L1cache);
		System.out.println(proc2.L1cache);
	}

	@Test
	public void testReadIToSTransition2() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 64L, 2);
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;

		machine.testCacheMemoryRead(P0, 64L, 2);
		llcLine = proc0.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_SHARED;
		MESILine privLine = proc1.L1cache.getLine(llcLine);
		assert privLine.getState() == MESIState.MESI_SHARED;
	}

	@Test
	public void testReadIToE() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		// Evict 64L to LLC
		MESILine privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_INVALID;

		machine.testCacheMemoryRead(P0, 64L, 2);
		llcLine = proc0.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
		privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_EXCLUSIVE;
	}

	@Test
	public void testWriteIToM1() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2);
		machine.testCacheMemoryRead(P1, 80L, 2);
		machine.testCacheMemoryRead(P1, 96L, 2);
		// Evict 64L to LLC
		MESILine privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_INVALID;

		machine.testCacheMemoryWrite(P0, 64L, 2);
		privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_MODIFIED;
		llcLine = proc0.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;
	}

	@Test
	public void testWriteIToM2() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryWrite(P1, 64L, 2);
		MESILine privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_MODIFIED;
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;

		machine.testCacheMemoryWrite(P0, 64L, 2);
		privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_MODIFIED;
		privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
		llcLine = proc0.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;
	}

	@Test
	public void testWriteIToM3() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P1, 64L, 2);
		MESILine privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_EXCLUSIVE;
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;

		machine.testCacheMemoryWrite(P0, 64L, 2);
		privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_MODIFIED;
		privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
		llcLine = proc0.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;
	}

	@Test
	public void testWriteIToM4() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);
		Processor<MESILine> proc2 = machine.getProc(P2);

		machine.testCacheMemoryRead(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 64L, 2);
		MESILine privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_SHARED;
		privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_SHARED;
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_SHARED;

		machine.testCacheMemoryWrite(P2, 64L, 2);
		llcLine = proc2.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;
		privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
		privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
	}

	@Test
	public void testWriteIToM5() {
		Processor<MESILine> proc0 = machine.getProc(P0);
		Processor<MESILine> proc1 = machine.getProc(P1);

		machine.testCacheMemoryRead(P0, 64L, 2);
		machine.testCacheMemoryRead(P1, 64L, 2);
		MESILine privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_SHARED;
		privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_SHARED;
		MESILine llcLine = proc1.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_SHARED;

		machine.testCacheMemoryWrite(P0, 64L, 2);
		llcLine = proc0.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;
		privLine = proc0.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_MODIFIED;
		privLine = proc1.L1cache.getLine(new DataLineAddress(64L));
		assert privLine == null;
	}
}
