#include <errno.h>
#include <fcntl.h>
#include <fstream>
#include <iostream>
#include <queue>
#include <string>
#include <sys/stat.h>

#include "boost/circular_buffer.hpp"
#include "boost/lexical_cast.hpp"

#include "analysis_callbacks.hpp"
#include "config.hpp"
#include "event.hpp"
#include "instlib.H"
#include "pinlock.hpp"
#include "viser.hpp"

INSTLIB::ICOUNT icount;

// Statistics

UINT64 g_enqEvents = 0;       // All events
UINT64 g_enqMemoryEvents = 0; // Memory events
UINT64 g_enqRoiStart = 0;
UINT64 g_enqRoiEnd = 0;
UINT64 g_enqThreadStarts = 0;
UINT64 g_enqThreadEnds = 0;
UINT64 g_enqReads = 0;
UINT64 g_enqWrites = 0;
UINT64 g_enqThreadBlocked = 0;
UINT64 g_enqThreadUnblocked = 0;
UINT64 g_enqBBs = 0;
UINT64 g_enqLockAcquires = 0;
UINT64 g_enqLockReleases = 0;
UINT64 g_enqThreadJoins = 0;
UINT64 g_enqThreadSpawns = 0;
UINT64 g_enqIgnoreConflictsBegins = 0;
UINT64 g_enqIgnoreConflictsEnds = 0;
UINT64 g_enqAtomicReads = 0;
UINT64 g_enqAtomicWrites = 0;
UINT64 g_enqTransStarts = 0;
UINT64 g_enqTransEnds = 0;
UINT64 g_enqServerRoiStarts = 0;
UINT64 g_enqServerRoiEnds = 0;
UINT64 g_enqLockAcqReads = 0;
UINT64 g_enqLockAcqWrites = 0;
UINT64 g_enqLockRelWrites = 0;
UINT64 g_enqCheckPoints = 0;

UINT64 g_enqSTOSB = 0;

// Tracking stats from the ioThread is single-threaded, so we do not require a lock
UINT64 g_deqEvents = 0;
UINT64 g_deqMemoryEvents = 0;
UINT64 g_deqRoiStart = 0;
UINT64 g_deqRoiEnd = 0;
UINT64 g_deqThreadStarts = 0;
UINT64 g_deqThreadEnds = 0;
UINT64 g_deqReads = 0;
UINT64 g_deqWrites = 0;
UINT64 g_deqThreadBlocked = 0;
UINT64 g_deqThreadUnblocked = 0;
UINT64 g_deqBBs = 0;
UINT64 g_deqLockAcquires = 0;
UINT64 g_deqLockReleases = 0;
UINT64 g_deqThreadJoins = 0;
UINT64 g_deqThreadSpawns = 0;
UINT64 g_deqIgnoreConflictsBegins = 0;
UINT64 g_deqIgnoreConflictsEnds = 0;
UINT64 g_deqAtomicReads = 0;
UINT64 g_deqAtomicWrites = 0;
UINT64 g_deqTransStarts = 0;
UINT64 g_deqTransEnds = 0;
UINT64 g_deqServerRoiStarts = 0;
UINT64 g_deqServerRoiEnds = 0;
UINT64 g_deqLockAcqReads = 0;
UINT64 g_deqLockAcqWrites = 0;
UINT64 g_deqLockRelWrites = 0;
UINT64 g_deqCheckPoints = 0;

UINT64 numRegionBoundaries = 0;

PIN_THREAD_UID g_ioThreadId;

boost::circular_buffer<Event> g_cbEventQ(MAX_CIRCULAR_BUFFER_SIZE);

// Declaring PinLocks also initializes the locks
PinLock g_Lock;           // Global lock to sync all events and instrumentation
PinLock g_eventQLock;     // Synchronize accesses to the event queue with the IO thread
PinLock g_waitingMapLock; // Used for collision analysis

KNOB<string> statsFile(KNOB_MODE_WRITEONCE, "pintool", "sim-stats", "output",
                       "The name of the output file to write to.");
KNOB<BOOL> writeToFifo(KNOB_MODE_WRITEONCE, "pintool", "write-fifo", "1",
                       "Control whether the event stream should be written to the trace file or the FIFO.");
KNOB<string> simulatorFifo(KNOB_MODE_WRITEONCE, "pintool", "tosim-fifo", "fifo.frontend",
                           "The named fifo used to log events to later pass on to the simulator backend.");
KNOB<BOOL> ignoreReps(KNOB_MODE_WRITEONCE, "pintool", "reps", "0",
                      "Add count with REP prefixed instructions counted only once.");

KNOB<string> nameFile(KNOB_MODE_WRITEONCE, "pintool", "source-names-index-file", "filename.out",
                      "The filename map used to locate conflicting sites.");
KNOB<string> rnameFile(KNOB_MODE_WRITEONCE, "pintool", "routine-names-index-file", "routinename.out",
                       "The routinename map used to locate conflicting sites.");
KNOB<string> traceTextFile(KNOB_MODE_WRITEONCE, "pintool", "trace-text-file", "trace.txt",
                           "Event trace text file.");
// We use a different key other than "threads" since benchmarks like x264 uses that word as an argument
KNOB<UINT32> pinThreads(KNOB_MODE_WRITEONCE, "pintool", "pinThreads", "8",
                        "The minimum number of application threads to be spawned.");
KNOB<UINT32> backends(KNOB_MODE_WRITEONCE, "pintool", "backends", "0",
                      "The number of non-MESI simulators to be executed in lockstep.");
KNOB<BOOL> lockstep(KNOB_MODE_WRITEONCE, "pintool", "lockstep", "0",
                    "Should a Pin thread wait at synchronization operations for the backend to catch up?");
KNOB<BOOL> pausing(KNOB_MODE_WRITEONCE, "pintool", "pausing", "0",
                   "The Pintool does not wait for the pausing/restarting backend at lock releases.");
KNOB<BOOL> siteTracking(KNOB_MODE_WRITEONCE, "pintool", "siteTracking", "0",
                        "Record the names of files and routines that Pin has instrumented.");
KNOB<BOOL> ignorePthreadRTNs(KNOB_MODE_WRITEONCE, "pintool", "ignore-pthread-rtns", "1",
                             "The Pintool does not instrument any memory accesses and nested function within pthread functions.");

// For collision analysis
KNOB<BOOL> enableCollisionAnalysis(KNOB_MODE_WRITEONCE, "pintool", "enable-collisionAnalysis", "0",
                                   "The Pintool does collision analysis to validate a pair of conflicting sites?");
KNOB<UINT32> intstLine0(KNOB_MODE_WRITEONCE, "pintool", "intstLine0", "0",
                        "The first line number of the interested conflicting sites for collision analysis.");
KNOB<UINT32> intstLine1(KNOB_MODE_WRITEONCE, "pintool", "intstLine1", "0",
                        "The second line number of the interested conflicting sites for collision analysis.");
KNOB<UINT32> usleep0(KNOB_MODE_WRITEONCE, "pintool", "usleep0", "0",
                     "Sleep time (microseconds) for intstLine0 during collision analysis.");
KNOB<UINT32> usleep1(KNOB_MODE_WRITEONCE, "pintool", "usleep1", "0",
                     "Sleep time (microseconds) for intstLine1 during collision analysis.");

KNOB<string> bench(KNOB_MODE_WRITEONCE, "pintool", "bench", "",
                   "Benchmark name.");

UINT64 waitedEvents = 0;
UINT64 checkedEvents = 0;

// FIFO or file where to write the event streams
ofstream eventTrace;
ofstream eventTraceTextFile;
ofstream outFile;
ofstream rtnNameFile;

int fifoFd;

// Map to index file name for sites tracking because I find no way to pass the filename string directly to the backend as a parameter of analysis callback
map<string, int> filenameMap;
map<string, int> rtnnameMap;

// Create an array of per-thread FIFOs
ifstream *perThreadFifos;
int *perThreadFifoIDs;

// Use these to track the correctness of events enqueued and dequeued
string deqFileName = "dequeue.txt";
string enqFileName = "enqueue.txt";
ofstream dequeue;
ofstream enqueue;

string perThreadFIFOPrefix("fifo.tid");

INT32 Usage() {
    cout << "This PIN tool is a frontend for the Viser simulator." << endl
         << KNOB_BASE::StringKnobSummary() << endl;
    return -1;
}

//  This method aims to tally the enqueue and dequeue stats for correctness. Meaningful only if USE_SYNC_CIRCULAR_BUFFER and TRACK_STATS are both true.
VOID matchStats() {
    // For raytrace, it seems thread 0 finishes first, so thread end enqueue and dequeue events do not match
    // assert(g_enqEvents == g_deqEvents);
    assert(g_enqMemoryEvents == g_deqMemoryEvents);
    assert(g_enqRoiStart == g_deqRoiStart);
    assert(g_enqRoiEnd == g_deqRoiEnd);
    assert(g_enqThreadStarts == g_deqThreadStarts);
    assert(g_enqReads == g_deqReads);
    assert(g_enqWrites == g_deqWrites);
    assert(g_enqThreadBlocked == g_deqThreadBlocked);
    assert(g_enqThreadUnblocked == g_deqThreadUnblocked);
    assert(g_enqBBs == g_deqBBs);
    assert(g_enqLockAcquires == g_deqLockAcquires);
    assert(g_enqLockReleases == g_deqLockReleases);
    assert(g_enqThreadJoins == g_deqThreadJoins);
    assert(g_enqThreadSpawns == g_deqThreadSpawns);
    assert(g_enqIgnoreConflictsEnds == g_deqIgnoreConflictsEnds);
    assert(g_enqIgnoreConflictsBegins == g_deqIgnoreConflictsBegins);
    assert(g_enqAtomicReads == g_deqAtomicReads);
    assert(g_enqAtomicWrites == g_deqAtomicWrites);
    assert(g_enqTransStarts == g_deqTransStarts);
    if (g_enqTransStarts != g_enqTransEnds)
        cout << "g_enqTransStarts and g_enqTransEnds: " << g_enqTransStarts << ", " << g_enqTransEnds << endl;
    assert(g_enqTransEnds == g_deqTransEnds);
    assert(g_enqServerRoiStarts == g_deqServerRoiStarts);
    assert(g_enqServerRoiEnds == g_deqServerRoiEnds);
    assert(g_enqServerRoiStarts == g_deqServerRoiEnds || (g_enqServerRoiStarts > 0 && g_deqServerRoiEnds > 0));
    assert(g_enqTransStarts == g_enqTransEnds);
    assert(g_enqIgnoreConflictsBegins == g_enqIgnoreConflictsEnds);
    assert(g_deqCheckPoints == g_enqCheckPoints);
    // would failed if functions without exit point (such as start_thread())
}

// This is in a single-threaded context
VOID initSimulator() {
    cout << "[pintool] Starting..." << endl;

    initInstrCallback();
    initAnalysisCallback();

    // Collision analysis requires siteTracking and valid line numbers and sleep times.
    assert(!enableCollisionAnalysis || (siteTracking && intstLine0 != 0 && intstLine1 != 0 && usleep0 != 0 && usleep1 != 0));

    // Opening a named pipe has issues related to blocking. See here:
    // http://pubs.opengroup.org/onlinepubs/007908799/xsh/open.html
    // http://stackoverflow.com/questions/5782279/why-does-a-read-only-open-of-a-named-pipe-block
    // http://stackoverflow.com/questions/2746168/how-to-construct-a-c-fstream-from-a-posix-file-descriptor
    if (writeToFifo) {
        // Create the trace file
        cout << "[pintool] Trace file:" << simulatorFifo.Value().c_str() << endl;
        eventTrace.open(simulatorFifo.Value().c_str(), ios::out | ios::binary);
        assert(eventTrace.good());
    }
    if (siteTracking) {
        cout << "[pintool] Source files are indexed in:" << nameFile.Value().c_str() << endl;
        outFile.open(nameFile.Value().c_str(), ios::out);
        cout << "[pintool] Routines are indexed:" << rnameFile.Value().c_str() << endl;
        rtnNameFile.open(rnameFile.Value().c_str(), ios::out);
        eventTraceTextFile.open(traceTextFile.Value().c_str(), ios::out);
    }

    if (enableCollisionAnalysis) {
        cout << "[pintool] Collision analyzing lines " << intstLine0 << " (each waits " << usleep0 << " ms) "
             << "and " << intstLine1 << " (each waits " << usleep1 << " ms) ..." << endl;
    }

    char *pinToolPath = getenv("ST_PINTOOL_ROOT");
    if (constants::DEBUG) {
        enqueue.open((string(pinToolPath) + "/" + enqFileName).c_str(), ios::out);
        dequeue.open((string(pinToolPath) + "/" + deqFileName).c_str(), ios::out);
    }

    // Create per-core fifo, the count of fifos correspond to the number of threads.
    if (lockstep) {
        UINT32 size = 5 * pinThreads.Value(); // Maximum number of threads spawned in PARSEC
        perThreadFifos = new ifstream[size];
        perThreadFifoIDs = new int[size];
        for (UINT32 i = 0; i < size; i++) {
            string fifoName = string(pinToolPath) + "/" + perThreadFIFOPrefix + boost::lexical_cast<string>(i) + bench.Value();
            if (false) {
                cout << "[pintool] Creating fifo:" << fifoName << endl;
                int ret = mkfifo(fifoName.c_str(), 0666); // Create the FIFO
                if (ret == -1) {
                    handleMkfifoError(fifoName, ret);
                    exit(EXIT_FAILURE);
                }
            }
            perThreadFifos[i].open(fifoName.c_str(), ios::in | ios::binary); // The frontend/Pintool opens the fifos only for reading
            if (constants::DEBUG_LOCKSTEP) {
                cout << "[pintool] Opened per-thread fifo for reading:" << fifoName << " " << perThreadFifos[i] << endl;
            }
            assert(perThreadFifos[i].good());
            assert(perThreadFifos[i].is_open());
            if (constants::DEBUG_LOCKSTEP) {
                cout << "[pintool] Address during init:" << perThreadFifos[i] << endl;
            }
        }
    }
}

void handleMkfifoError(string fifoName, int ret) {
    cerr << "mkfifo has failed for fifo:" << fifoName
         << " Error: " << errno << " ";
    switch (errno) {
    case EACCES: {
        cout << "EACCESS";
        break;
    }
    case EDQUOT: {
        cout << "EDQUOT";
        break;
    }
    case EEXIST: {
        cout << "EEXIST";
        break;
    }
    case ENAMETOOLONG: {
        cout << "ENAMETOOLONG";
        break;
    }
    case ENOENT: {
        cout << "ENOENT";
        break;
    }
    case ENOSPC: {
        cout << "ENOSPC";
        break;
    }
    case ENOTDIR: {
        cout << "ENOTDIR";
        break;
    }
    case EROFS: {
        cout << "EROFS";
        break;
    }
    default: {
        cout << "Unknown error type";
    }
    }
    cout << endl;

    // Close the per-thread fifos from the backend simulator since it is slower
    for (UINT32 i = 0; i < 5 * pinThreads.Value(); i++) {
        close(perThreadFifoIDs[i]);
        string fifoName = string(getenv("PINTOOL_ROOT")) + "/" + perThreadFIFOPrefix + boost::lexical_cast<string>(i);
        if (constants::DEBUG_LOCKSTEP) {
            cout << "[pintool] Closing fifo: " << fifoName << " " << perThreadFifos[i] << endl;
        }
        unlink(fifoName.c_str());
    }
}

VOID pinExitHelper(INT32 code, VOID *v) {
    // wait for the IO thread to terminate, returns true if the thread terminates, returns false if the wait times out
    bool done = PIN_WaitForThreadTermination(g_ioThreadId, PIN_INFINITE_TIMEOUT, NULL);
    assert(done); // get a core dump if the IO thread times out

    // Close binary file/fifo
    eventTrace.flush();
    eventTrace.close();
    eventTraceTextFile.close();

    if (false && lockstep) { // Close the per-thread fifos from the backend simulator since it is slower
        for (UINT32 i = 0; i < 5 * pinThreads.Value(); i++) {
            close(perThreadFifoIDs[i]);
            string fifoName = string(getenv("PINTOOL_ROOT")) + "/" + perThreadFIFOPrefix + boost::lexical_cast<string>(i);
            if (constants::DEBUG_LOCKSTEP) {
                cout << "[pintool] Closing fifo: " << fifoName << " " << perThreadFifos[i] << endl;
            }
            unlink(fifoName.c_str());
        }
    }

    if (constants::TRACK_STATS) {
        ofstream stats;
        stats.open(statsFile.Value().c_str(), ios::binary);

        stats << "Instruction count:" << icount.Count() << endl;
        if (ignoreReps) {
            stats << "Instruction count (single REPs) " << icount.CountWithoutRep() << endl;
        }

        stats << "[ENQUEUE] total events:" << g_enqEvents << endl
              << "[ENQUEUE] roi start:" << g_enqRoiStart << endl
              << "[ENQUEUE] roi end:" << g_enqRoiEnd << endl
              << "[ENQUEUE] server boot start:" << g_enqServerRoiStarts << endl
              << "[ENQUEUE] server boot end:" << g_enqServerRoiEnds << endl
              << "[ENQUEUE] trans start:" << g_enqTransStarts << endl
              << "[ENQUEUE] trans end:" << g_enqTransEnds << endl
              << "[ENQUEUE] thread begin:" << g_enqThreadStarts << endl
              << "[ENQUEUE] thread end:" << g_enqThreadEnds << endl
              << "[ENQUEUE] thread blocked:" << g_enqThreadBlocked << endl
              << "[ENQUEUE] thread unblocked:" << g_enqThreadUnblocked << endl
              << "[ENQUEUE] memory events:" << g_enqMemoryEvents << endl
              << "[ENQUEUE] reads:" << g_enqReads << endl
              << "[ENQUEUE] writes:" << g_enqWrites << endl
              << "[ENQUEUE] atomic reads:" << g_enqAtomicReads << endl
              << "[ENQUEUE] atomic writes:" << g_enqAtomicWrites << endl
              << "[ENQUEUE] basic blocks:" << g_enqBBs << endl
              << "[ENQUEUE] lock acquires:" << g_enqLockAcquires << endl
              << "[ENQUEUE] lock releases:" << g_enqLockReleases << endl
              << "[ENQUEUE] lock acquire reads:" << g_enqLockAcqReads << endl
              << "[ENQUEUE] lock acquire writes:" << g_enqLockAcqWrites << endl
              << "[ENQUEUE] lock release writes:" << g_enqLockRelWrites << endl
              << "[ENQUEUE] thread joins:" << g_enqThreadJoins << endl
              << "[ENQUEUE] thread spawns:" << g_enqThreadSpawns << endl
              << "[ENQUEUE] potential check points:" << g_enqCheckPoints << endl
              << "[ENQUEUE] STOSB:" << g_enqSTOSB << endl
              << endl
              << endl;

        stats << "[DEQUEUE] total events:" << g_deqEvents << endl
              << "[DEQUEUE] roi start:" << g_deqRoiStart << endl
              << "[DEQUEUE] roi end:" << g_deqRoiEnd << endl
              << "[DEQUEUE] thread begin:" << g_deqThreadStarts << endl
              << "[DEQUEUE] thread end:" << g_deqThreadEnds << endl
              << "[DEQUEUE] thread blocked:" << g_deqThreadBlocked << endl
              << "[DEQUEUE] thread unblocked:" << g_deqThreadUnblocked << endl
              << "[DEQUEUE] memory events:" << g_deqMemoryEvents << endl
              << "[DEQUEUE] reads:" << g_deqReads << endl
              << "[DEQUEUE] writes:" << g_deqWrites << endl
              << "[DEQUEUE] atomic reads:" << g_deqAtomicReads << endl
              << "[DEQUEUE] atomic writes:" << g_deqAtomicWrites << endl
              << "[DEQUEUE] basic blocks:" << g_deqBBs << endl
              << "[DEQUEUE] lock acquires:" << g_deqLockAcquires << endl
              << "[DEQUEUE] lock releases:" << g_deqLockReleases << endl
              << "[DEQUEUE] lock acquire reads:" << g_deqLockAcqReads << endl
              << "[DEQUEUE] lock acquire writes:" << g_deqLockAcqWrites << endl
              << "[DEQUEUE] lock release writes:" << g_deqLockRelWrites << endl
              << "[DEQUEUE] thread joins:" << g_deqThreadJoins << endl
              << "[DEQUEUE] thread spawns:" << g_deqThreadSpawns << endl
              << endl;

        stats.flush();
        stats.close();
    }

    if (constants::DEBUG) {
        enqueue.flush();
        enqueue.close();
        dequeue.flush();
        dequeue.close();
    }

    // Debugging code, but want it enabled more often
    if (constants::TRACK_STATS) {
        if (constants::USE_SYNC_CIRCULAR_BUFFER) {
            matchStats();
        }
    }

    cout << "[pintool] Finished" << endl;
}

// FIXME: This is not called for /bin/ls.
VOID pinUnlockedFini(INT32 code, VOID *v) {
    cout << "[pintool] pinUnlockedFini: " << code << endl;
    pinExitHelper(code, v);
}

// FIXME: This is not called for /bin/ls.
VOID pinFini(INT32 code, VOID *v) {
    if (siteTracking) {
        for (std::map<string, int>::iterator it = filenameMap.begin(); it != filenameMap.end(); ++it) {
            outFile << it->first << " " << it->second << " \n";
        }
        outFile.close();
        for (std::map<string, int>::iterator it = rtnnameMap.begin(); it != rtnnameMap.end(); ++it) {
            rtnNameFile << it->first << " " << it->second << " \n";
        }
        rtnNameFile.close();
    }
    if (enableCollisionAnalysis) {
        cout << "[pintool] Total waited events " << waitedEvents << endl;
        cout << "[pintool] Total checked events " << checkedEvents << endl;
    }
    cout << "[pintool] pinFini: " << code << endl;
    pinExitHelper(code, v);
}

VOID trackDequeueStats(Event e) {
    g_deqEvents++;
    // This is single-threaded, so we should not require the stats lock
    switch (e.m_eventType) {
    case ROI_START: {
        g_deqRoiStart++;
        break;
    }
    case ROI_END: {
        g_deqRoiEnd++;
        break;
    }
    case TRANS_START: {
        g_deqTransStarts++;
        break;
    }
    case TRANS_END: {
        g_deqTransEnds++;
        break;
    }
    case THREAD_START: {
        g_deqThreadStarts++;
        break;
    }
    case THREAD_FINISH: {
        g_deqThreadEnds++;
        break;
    }
    case THREAD_BLOCKED: {
        assert(false);
        g_deqThreadBlocked++;
        break;
    }
    case THREAD_UNBLOCKED: {
        assert(false);
        g_deqThreadUnblocked++;
        break;
    }
    case MEMORY_READ: {
        g_deqReads++;
        g_deqMemoryEvents++;
        break;
    }
    case MEMORY_WRITE: {
        g_deqWrites++;
        g_deqMemoryEvents++;
        break;
    }
    case BASIC_BLOCK: {
        g_deqBBs++;
        break;
    }
    case ATOMIC_READ: {
        g_deqAtomicReads++;
        break;
    }
    case ATOMIC_WRITE: {
        g_deqAtomicWrites++;
        break;
    }
    case LOCK_ACQUIRE: {
        g_deqLockAcquires++;
        break;
    }
    case LOCK_RELEASE: {
        g_deqLockReleases++;
        break;
    }
    case LOCK_ACQ_READ: {
        g_deqLockAcqReads++;
        break;
    }
    case LOCK_ACQ_WRITE: {
        g_deqLockAcqWrites++;
        break;
    }
    case LOCK_REL_WRITE: {
        g_deqLockRelWrites++;
        break;
    }
    case THREAD_JOIN: {
        g_deqThreadJoins++;
        break;
    }
    case THREAD_SPAWN: {
        g_deqThreadSpawns++;
        break;
    }
    case IGNORE_CONFLICTS_BEGIN: {
        g_deqIgnoreConflictsBegins++;
        break;
    }
    case IGNORE_CONFLICTS_END: {
        g_deqIgnoreConflictsEnds++;
        break;
    }
    case SERVER_ROI_START: {
        g_deqServerRoiStarts++;
        break;
    }
    case SERVER_ROI_END: {
        g_deqServerRoiEnds++;
        break;
    }
    case CHECK_POINT: {
        g_deqCheckPoints++;
        break;
    }
    default: {
        cout << e.m_eventType << endl;
        assert(false);
        // Do nothing
    }
    }
}

/* A separate internal thread to write events to a file/fifo. */
VOID ioThread(VOID *unused) {
    // cout << "[pintool] ioThread has started running:" << PIN_ThreadId() << endl;

    // Priorities range from -20 to +19, where positive values represent a lower priority (highest niceness). The default is usually zero.
    // We want the simulator process(es) to run with higher priority than the frontend, useful if we are using a named pipe.
    // http://bencane.com/2013/09/09/setting-process-cpu-priority-with-nice-and-renice/
    // FIXME: In Linux this niceness value can be ignored by the scheduler
    // http://linux.die.net/man/3/nice
    // int r = nice(-5);
    // assert(r != 1 );
    // cout << "[pintool] New nice value: " << r << endl;

    Event e;

    while (true) {
        if (constants::USE_SYNC_CIRCULAR_BUFFER) {
            g_eventQLock.lock(g_ioThreadId);

            if (!g_cbEventQ.empty()) {
                // At least one element
                assert(g_cbEventQ.size() > 0);
                e = g_cbEventQ.front(); // Get the first element
                g_cbEventQ.pop_front(); // Pop the beginning of the circular buffer
                g_eventQLock.unlock();

                if (e.m_eventType == INVALID) {
                    cerr << "[pintool] *** Invalid event:" << e.toString() << " ***" << endl;
                    assert(false);
                    break;
                }

                if (constants::DEBUG) {
                    dequeue << /*"[DEQUEUE] " <<*/ e.toString() << endl;
                    cout << "[DEQUEUE] " << e.toString() << endl;
                }

                // Write to a binary event trace file/fifo
                assert(eventTrace.good());
                if (writeToFifo) {
                    e.send(eventTrace);
                }

                if (constants::TRACK_STATS) {
                    trackDequeueStats(e);
                }

                if (e.m_eventType == THREAD_FINISH && e.m_tid == 0) {
                    // when main thread exits, tear down simulation
                    break; // this just breaks out of the loop
                }

            } else {
                // Queue is empty
                g_eventQLock.unlock();
            }
        } // if (constants::USE_SYNC_CIRCULAR_BUFFER)
    }     // while (true)

    cout << "[pintool] ioThread exiting" << endl;
    cout << "[pintool] "
         << "numRegionBoundaries: " << numRegionBoundaries << endl;
    PIN_ExitThread(0);
}

// SB: FIXME: It might be better to make this method part of the constants namespace, but then it leads to "multiple
// definition" errors.
// This method checks for correct setup of config parameters.
BOOL checkConfig() {
    if (constants::USE_SYNC_CIRCULAR_BUFFER && constants::WRITE_TRACE_FILE) {
        cerr << "[pintool] Using a circular buffer and writing to a trace file are both enabled at the same time." << endl;
    }
    return true;
}

int main(int argc, char *argv[]) {
    if (PIN_Init(argc, argv)) {
        return Usage();
    }
    PIN_InitSymbols();

    // Check for correct setup of configuration constants
    checkConfig();
    initSimulator();

    // Remember to instrument basic blocks, we need it since we bill one cycle for all instructions. Apparently,
    // instrumenting traces seem to generate slightly more memory events and it is easy to instrument basic blocks, so
    // that is why I am going for it instead of instrumenting individual instructions.
    TRACE_AddInstrumentFunction(instrumentTrace, NULL);
    // INS_AddInstrumentFunction(instrumentInstruction, 0);

    IMG_AddInstrumentFunction(instrumentImage, NULL);

    if (constants::USE_SYNC_CIRCULAR_BUFFER) {
        THREADID tid = PIN_SpawnInternalThread(ioThread, NULL, 0, &g_ioThreadId);
        assert(tid != INVALID_THREADID);
    }

    // Register a notification function that is called when a thread starts executing in the application. The call-back
    // happens even for the application's root (initial) thread.
    PIN_AddThreadStartFunction(threadBegin, NULL);
    PIN_AddThreadFiniFunction(threadEnd, NULL);

    PIN_AddContextChangeFunction(beforeSignal, NULL);
    PIN_AddSyscallEntryFunction(beforeSyscall, NULL);
    PIN_AddSyscallExitFunction(afterSyscall, NULL);

    // Register a function to be called when the application is about to exit. The registered function will be executed in
    // a thread that does not hold any thread synchronization lock in Pin. It means that this callback function can be
    // executed concurrently with other Pin callbacks and APIs. All callbacks registered by this function will be executed
    // before any callback registered by the PIN_AddFiniFunction() function.
    //PIN_AddFiniUnlockedFunction(pinUnlockedFini, NULL);

    // Call func immediately before the application exits. The function is not an instrumentation function--it cannot
    // insert instrumentation. There can be more than one Fini function.
    // Register pinFini to be called when the application exits
    PIN_AddFiniFunction(pinFini, 0);

    // SB: TODO: What is this?
    if (!CODECACHE_ChangeMaxInsPerTrace(4096 * 1024)) {
        cerr << "*** TLSProf::CODECACHE_ChangeMaxInsPerTrace failed. ***" << endl;
    }

    // Activate instruction counter
    if (ignoreReps) {
        icount.Activate(INSTLIB::ICOUNT::ModeBoth);
    } else {
        icount.Activate(INSTLIB::ICOUNT::ModeNormal);
    }

    PIN_StartProgram();
    return 0;
}
