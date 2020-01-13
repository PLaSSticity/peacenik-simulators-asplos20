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

public class MESIUniProcessorTests {

	static Machine<MESILine> machine;

	static final CpuId P0 = new CpuId(0);
	static final int CORES = 1;
	static final int PINTHREADS = 1;
	static final int LINE_SIZE = 4;

	// 2 sets each with 2 lines for a total of 4 lines, each of size 4 bytes
	static final int L1_ASSOC = 2;
	static final int L1_CACHE_SIZE = 16;

	static final int L2_ASSOC = 2;
	static final int L2_CACHE_SIZE = 16; // 4 blocks, 2 sets

	// 4 sets each with 4 lines for a total of 16 lines, each of size 4 bytes
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
		Processor<MESILine> proc = machine.getProc(P0);

		// Miss in L1 cache, miss in LLC
		machine.testCacheMemoryRead(P0, 80L, 2);
		machine.testCacheMemoryRead(P0, 64L, 2);
		assertEquals(2, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(2, proc.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);

		// Hit in L1 cache, no need to go to the LLC
		machine.testCacheMemoryRead(P0, 82L, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(2, proc.stats.pc_l2d.pc_ReadMisses.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);

		// Miss in L1 cache, miss in LLC
		machine.testCacheMemoryRead(P0, 72L, 2);
		machine.testCacheMemoryRead(P0, 84L, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(4, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(4, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		// Miss in L1 cache, miss in LLC
		machine.testCacheMemoryRead(P0, 88L, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(5, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(5, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);
	}

	@Test
	public void testWrite() {
		Processor<MESILine> proc = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 80L, 2);
		assertEquals(0, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(1, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 64L, 2);
		assertEquals(0, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 82L, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 72L, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 84L, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(4, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(4, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 86L, 2);
		assertEquals(2, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(4, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(4, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);

		machine.testCacheMemoryWrite(P0, 88L, 2);
		assertEquals(2, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(5, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(5, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
	}

	@Test
	public void testLLCHit() {
		Processor<MESILine> proc = machine.getProc(P0);

		// bring 0d into the cache
		machine.testCacheMemoryRead(P0, 0, 1);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		System.out.println(proc.L1cache);

		// fetch 16d and 32d which map to set 0; this evicts 0d to the L3
		for (int i = 1; i <= L1_ASSOC; i++) {
			machine.testCacheMemoryRead(P0, L1_CACHE_SIZE * i, 1);
			assertEquals(1 + i, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		}
		System.out.println(proc.L1cache);

		// fetch 0d again, from the L3. This is a miss since the LLC line was
		// invalidated when evicting the
		// private L1 line.
		machine.testCacheMemoryRead(P0, 0, 1);
		assertEquals(1, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		System.out.println(proc.L1cache);
	}

	@Test
	public void testCrossLineRead() {
		Processor<MESILine> proc = machine.getProc(P0);

		// this crosses a line boundary
		machine.testCacheMemoryRead(P0, 0, 8);
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 8, 4);
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 2, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 6, 4);
		assertEquals(3, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);
	}

	@Test
	public void testCrossLineWrite() {
		Processor<MESILine> proc = machine.getProc(P0);

		// this crosses a data line boundary, so it definitely crosses a md line
		// boundary
		machine.testCacheMemoryWrite(P0, 0, 8);
		assertEquals(0, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(2, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryWrite(P0, 8, 4);
		assertEquals(0, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryWrite(P0, 2, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryWrite(P0, 6, 4);
		assertEquals(3, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);
	}

	@Test
	public void testReadWrite() {
		Processor<MESILine> proc = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 4, 2);
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		// This is a hit in the private cache
		machine.testCacheMemoryWrite(P0, 4, 2);
		assertEquals(1, proc.stats.pc_l1d.pc_WriteHits.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteHits.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_WriteMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);
	}

	@Test
	public void testEviction() {
		Processor<MESILine> proc = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 44, 2);
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(1, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(1, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 12, 2);
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(2, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(2, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 28, 2); // 44L is evicted from L1/L2, but is valid/MESI_INVALID in LLC
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(3, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 60, 2); // 44L and 12L are evicted from L1/L2, but are valid/MESI_INVALID in LLC
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(4, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(4, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);

		machine.testCacheMemoryRead(P0, 76, 2);
		assertEquals(0, proc.stats.pc_l1d.pc_ReadHits.get(), 0);
		assertEquals(5, proc.stats.pc_l1d.pc_ReadMisses.get(), 0);
		assertEquals(0, proc.stats.pc_l1d.pc_LineEvictions.get(), 0);
		assertEquals(0, proc.stats.pc_l3d.pc_ReadHits.get(), 0);
		assertEquals(5, proc.stats.pc_l3d.pc_ReadMisses.get(), 0);
		assertEquals(1, proc.stats.pc_l3d.pc_LineEvictions.get(), 0);
		System.out.println(proc.L1cache);
	}

	/** A dirty line is marked INVALID in the LLC and then evicted. */
	@Test
	public void testDirtyBit2() {
		Processor<MESILine> proc = machine.getProc(P0);
		machine.testCacheMemoryWrite(P0, 64L, 2);
		machine.testCacheMemoryRead(P0, 80L, 2);
		machine.testCacheMemoryRead(P0, 96L, 2);
		System.out.println(proc.L1cache);
	}

	@Test
	public void testReadIToE() {
		Processor<MESILine> proc = machine.getProc(P0);

		machine.testCacheMemoryRead(P0, 64L, 2);
		MESILine privLine = proc.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_EXCLUSIVE;
		MESILine llcLine = proc.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_EXCLUSIVE;
	}

	@Test
	public void testWriteIToM() {
		Processor<MESILine> proc = machine.getProc(P0);

		machine.testCacheMemoryWrite(P0, 64L, 2);
		MESILine privLine = proc.L1cache.getLine(new DataLineAddress(64L));
		assert privLine.getState() == MESIState.MESI_MODIFIED;
		MESILine llcLine = proc.L3cache.getLine(new DataLineAddress(64L));
		assert llcLine.getState() == MESIState.MESI_MODIFIED;
	}

}
