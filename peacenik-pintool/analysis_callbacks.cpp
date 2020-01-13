#include <bitset>
#include <unistd.h>

#include "analysis_callbacks.hpp"
#include "config.hpp"
#include "event.hpp"
#include "pinlock.hpp"
#include "viser.hpp"

#include "boost/circular_buffer.hpp"
#include "boost/lexical_cast.hpp"
#include "boost/lockfree/policies.hpp"
#include "boost/lockfree/queue.hpp"

// This class contains Pin callback method definitions.

/** function pointer to pthread_self() */
AFUNPTR realPthreadSelf = NULL;

static int insCount = 0;
extern ofstream enqueue;
extern KNOB<UINT32> backends;

// For collision analysis
extern KNOB<BOOL> enableCollisionAnalysis;
extern KNOB<UINT16> intstLine0;
extern KNOB<UINT16> intstLine1;
extern KNOB<UINT32> usleep0;
extern KNOB<UINT32> usleep1;
extern UINT64 waitedEvents;
extern UINT64 checkedEvents;

// FIFO or file where to write the event streams
extern ofstream eventTrace;
extern ofstream eventTraceTextFile;

extern ifstream *perThreadFifos;

extern KNOB<BOOL> lockstep;
extern string perThreadFIFOPrefix;

extern PinLock g_Lock;
extern PinLock g_eventQLock;
extern PinLock g_waitingMapLock;

extern UINT64 g_enqEvents;
extern UINT64 g_enqMemoryEvents;
extern UINT64 g_enqRoiStart;
extern UINT64 g_enqRoiEnd;
extern UINT64 g_enqThreadStarts;
extern UINT64 g_enqThreadEnds;
extern UINT64 g_enqReads;
extern UINT64 g_enqWrites;
extern UINT64 g_enqThreadBlocked;
extern UINT64 g_enqThreadUnblocked;
extern UINT64 g_enqBBs;
extern UINT64 g_enqLockAcquires;
extern UINT64 g_enqLockReleases;
extern UINT64 g_enqThreadJoins;
extern UINT64 g_enqThreadSpawns;
extern UINT64 g_enqIgnoreConflictsBegins;
extern UINT64 g_enqIgnoreConflictsEnds;
extern UINT64 g_enqAtomicReads;
extern UINT64 g_enqAtomicWrites;
extern UINT64 g_enqServerRoiStarts;
extern UINT64 g_enqServerRoiEnds;
extern UINT64 g_enqTransStarts;
extern UINT64 g_enqTransEnds;
extern UINT64 g_enqLockAcqReads;
extern UINT64 g_enqLockAcqWrites;
extern UINT64 g_enqLockRelWrites;
extern UINT64 g_enqCheckPoints;

extern UINT64 g_enqSTOSB;

extern UINT64 numRegionBoundaries;

extern boost::circular_buffer<Event> g_cbEventQ;

static TLS_KEY s_writeAddr;
static list<Event> waitingMap;

VOID initAnalysisCallback() {
    s_writeAddr = PIN_CreateThreadDataKey(NULL);
}

size_t myStrlen(const char *str) {
    // cout << "my strlen: " << str << endl;
    int i;
    for (i = 0; str[i]; i++)
        ;
    return i;
}

static BOOL isLockAcquire(const char *rtnName) {
    return strstr(rtnName, "pthread_mutex_lock") ||
           strstr(rtnName, "pthread_mutex_timedlock") ||
           strstr(rtnName, "pthread_rwlock_rdlock") ||
           strstr(rtnName, "pthread_rwlock_wrlock") ||
           strstr(rtnName, "pthread_mutex_trylock") ||
           strstr(rtnName, "pthread_rwlock_tryrdlock") ||
           strstr(rtnName, "pthread_rwlock_trywrlock") ||
           // for mysqld
           ((strstr(rtnName, "PolicyMutex") || strstr(rtnName, "TrxInInnoDB")) && strstr(rtnName, "enter")) ||
           strstr(rtnName, "ACQUIRE_FENCE"); //custom hook for canneal

    // In its mbuffer.c file, dedup uses spin locks in default, but can be configured to use mutexes only.
    // strstr(rtnName, "pthread_spin_lock") ||
    // strstr(rtnName, "pthread_spin_trylock") ||

    // strstr(rtnName, "start_thread") ||
    // We have already had THREAD_START, which is considered as a aquire.
}

static BOOL isLockRelease(const char *rtnName) {
    return strstr(rtnName, "pthread_mutex_unlock") ||
           strstr(rtnName, "pthread_rwlock_unlock") ||
           // We don't consider the following two pthread functions lock operations in the simulator since they
           // don't manipulate locks. It should also be safe to not consider them region boundaries since they tend to
           // appear near critical sections bordered by real lock operations.
           // strstr(rtnName, "pthread_cond_broadcast") ||
           // strstr(rtnName, "pthread_cond_signal") ||
           // for mysqld
           ((strstr(rtnName, "PolicyMutex") || strstr(rtnName, "TrxInInnoDB")) && strstr(rtnName, "exit")) ||
           strstr(rtnName, "RELEASE_FENCE"); // custom hook for canneal

    // strstr(rtnName, "pthread_create") ||
    // we have already consider pthread_create as a release (THREAD_SPAWN)
}

static BOOL isLockAcqAndRls(const char *rtnName) {
    return strstr(rtnName, "pthread_cond_timedwait") ||
           // As discussed, we can still treat pthread_barrier_wait() as a release PLUS an acquire.
           strstr(rtnName, "pthread_barrier_wait") ||
           strstr(rtnName, "pthread_cond_wait");
}

VOID roiStart(THREADID tid) {
    // The benchmark is single-threaded at this point, so the lock is not required
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, ROI_START));
    if (constants::DEBUG) {
        cout << "[pintool] ROI Start" << endl;
    }
    g_Lock.unlock();
}

VOID roiEnd(THREADID tid) {
    // The benchmark is single-threaded at this point, so the lock is not required
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, ROI_END));
    if (constants::DEBUG) {
        cout << "[pintool] ROI End" << endl;
    }
    g_Lock.unlock();
}

VOID serverRoiStart(THREADID tid) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, SERVER_ROI_START));
    cout << "[pintool] SERVER ROI Start" << endl;
    g_Lock.unlock();
}

VOID serverRoiEnd(THREADID tid) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, SERVER_ROI_END));
    cout << "[pintool] SERVER ROI End" << endl;
    g_Lock.unlock();
}

VOID transStart(THREADID tid) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, TRANS_START));
    if (constants::DEBUG_LOCKSTEP) {
        cout << "[pintool] TRANS Start" << endl;
    }
    g_Lock.unlock();
}

VOID transEnd(THREADID tid) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, TRANS_END));
    if (constants::DEBUG_LOCKSTEP) {
        cout << "[pintool] TRANS End" << endl;
    }
    g_Lock.unlock();
}

VOID checkPoint(THREADID tid) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    addEvent(Event::ROIEvent(tid, CHECK_POINT));
    if (constants::DEBUG_LOCKSTEP) {
        cout << "[pintool] CHECK POINT" << endl;
    }
    g_Lock.unlock();
}

// Executed if constants::PRINT_RTN_NAMES is true
VOID rtnStart(THREADID tid, ADDRINT addr) {
    g_Lock.lock(tid); // Synchronization is quite costly, but needed for printing readability
    assert(tid >= 0);
    const char *rtnName = RTN_FindNameByAddress(addr).c_str();
    ;
    if (isLockAcquire(rtnName) || isLockRelease(rtnName) || isLockAcqAndRls(rtnName)) {
        cout << "tid: " << tid << " rtnStart: " << rtnName << endl;
    }
    g_Lock.unlock();
}

// Executed if constants::PRINT_RTN_NAMES is true
VOID rtnFini(THREADID tid, ADDRINT addr, ADDRINT outcome) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    const char *rtnName = RTN_FindNameByAddress(addr).c_str();
    ;
    if (isLockAcquire(rtnName) || isLockRelease(rtnName) || isLockAcqAndRls(rtnName)) {
        cout << "tid: " << tid << " rtnFini: " << rtnName << " with return value " << outcome << endl;
    }
    g_Lock.unlock();
}

VOID prepareAndSendMemoryEvent(THREADID tid, EventType type, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 opcode, UINT16 line, UINT16 fno, UINT16 rtnno, UINT16 lastLine, UINT16 lastFno) {
    // Dereference the memory address to get the value, but it is not recommended. Instead, we should use PIN_SafeCopy()
    switch (size) { // in bytes
    case 0: {
        addEvent(Event::MemoryEvent(tid, type, addr, size, isStackRef, line, fno, rtnno, lastLine, lastFno, opcode));
        break;
    }
    case 1: {
        UINT8 value;
        size_t bytesRead = PIN_SafeCopy(&value, static_cast<UINT8 *>(Addrint2VoidStar(addr)), 1);
        assert(bytesRead == 1);
        Event e = Event::MemoryEvent(tid, type, addr, size, isStackRef, line, fno, rtnno, lastLine, lastFno, opcode);
        e.m_value = value;
        addEvent(e);
        break;
    }
    case 2: {
        UINT16 value;
        size_t bytesRead = PIN_SafeCopy(&value, static_cast<UINT16 *>(Addrint2VoidStar(addr)), 2);
        assert(bytesRead == 2);
        Event e = Event::MemoryEvent(tid, type, addr, size, isStackRef, line, fno, rtnno, lastLine, lastFno, opcode);
        e.m_value = value;
        addEvent(e);
        break;
    }
    case 4: {
        UINT32 value;
        size_t bytesRead = PIN_SafeCopy(&value, static_cast<UINT32 *>(Addrint2VoidStar(addr)), 4);
        assert(bytesRead == 4);
        Event e = Event::MemoryEvent(tid, type, addr, size, isStackRef, line, fno, rtnno, lastLine, lastFno, opcode);
        e.m_value = value;
        addEvent(e);
        break;
    }
    case 8: {
        UINT64 value;
        size_t bytesRead = PIN_SafeCopy(&value, static_cast<UINT64 *>(Addrint2VoidStar(addr)), 8);
        assert(bytesRead == 8);
        Event e = Event::MemoryEvent(tid, type, addr, size, isStackRef, line, fno, rtnno, lastLine, lastFno, opcode);
        e.m_value = value;
        addEvent(e);
        break;
    }
    case 16:   // XORPS, MOVDQA, MOVDQU
    case 32:   // VMOVDQA
    case 64: { // PREFETCHT0
        // A 16 byte access will be broken up into two 8 byte accesses, on a 64-bit machine
        // Intel machines are little endian, and we are assuming a 64-bit architecture

        for (UINT32 s = 0; s < size; s += 8) {
            UINT64 eightBytes;
            size_t bytesRead = PIN_SafeCopy(&eightBytes, static_cast<UINT64 *>(Addrint2VoidStar(addr + s)), 8);
            assert(bytesRead == 8);
            Event e = Event::MemoryEvent(tid, type, addr + s, 8, isStackRef, line, fno, rtnno, lastLine, lastFno, opcode);
            e.m_value = eightBytes;
            addEvent(e);
        }
        break;
    }
    default: {
        cerr << "Size not handled:" << size << " Instruction:" << OPCODE_StringShort((OPCODE)opcode) << endl;
        assert(false);
    }
    }
}

VOID ignoreConflictsBegin(THREADID tid) {
    assert(tid >= 0);
    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    assert(tdata->m_tid == tid);
    assert(tdata->ignoredFuncs >= 0);
    tdata->ignoredFuncs++;
    if (constants::DEBUG_IGFUNCS && tid == 0)
        cout << tid << " ig begin " << tdata->ignoredFuncs << endl;
}

VOID ignoreConflictsEnd(THREADID tid) {
    assert(tid >= 0);
    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    assert(tdata->m_tid == tid);
    assert(tdata->ignoredFuncs > 0);
    tdata->ignoredFuncs--;
    if (constants::DEBUG_IGFUNCS && tid == 0)
        cout << tid << " ig end " << tdata->ignoredFuncs << endl;
}

// Decrease the depth counter twice at __pthread_rwlock_wrlock_slow to resolve the assertion failure with the counter
// at thread ends (observed in mysqld).
VOID ignoreConflictsDoubleEnd(THREADID tid) {
    assert(tid >= 0);
    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    assert(tdata->m_tid == tid);
    assert(tdata->ignoredFuncs > 0);
    tdata->ignoredFuncs--;
    tdata->ignoredFuncs--;
    // if (constants::DEBUG_IGFUNCS)
    cout << tid << " ig double end " << tdata->ignoredFuncs << endl;
}

BOOL isIgnored(THREADID tid) {
    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    assert(tdata->m_tid == tid);
    assert(tdata->ignoredFuncs >= 0);
    return (tdata->ignoredFuncs > 0);
}

BOOL isInterestingSite(int16_t tid, UINT64 value, UINT16 line, UINT16 fno, UINT16 rtnno) {
    return (line == intstLine0 || line == intstLine1);
}

VOID doCollisionAnalysis(int16_t tid, EventType type, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 line, UINT16 fno, UINT16 rtnno) {
    assert(tid >= 0);
    if (isIgnored(tid))
        return;
    if (line == 0 || fno == 0)
        return;
    // if (isStackRef) return;
    UINT64 value = 0;
    if (!isInterestingSite(tid, value, line, fno, rtnno))
        return;
    if (!(size != 1 || size != 2 || size != 4 || size != 8 || size != 64))
        return;

    // detect conflicts

    size_t bytes = PIN_SafeCopy(&value, static_cast<UINT64 *>(Addrint2VoidStar(addr)), size);
    assert(bytes == size);
    Event e1 = Event::MemoryEvent(tid, type, addr, size, isStackRef, line, fno, rtnno, line, fno, 0);
    e1.m_value = value;
    Event e;
    if (line == intstLine1) // only check the second site
    {
        g_waitingMapLock.lock(tid);
        checkedEvents++;
        for (list<Event>::iterator it = waitingMap.begin(); it != waitingMap.end(); it++) {
            e = *it;
            //cout << "In map: " << e.toString() << endl;
            assert(e.m_tid != tid);
            if ((e.m_eventType != type || type == MEMORY_WRITE) && ((e.m_addr <= addr && addr < e.m_addr + e.m_memOpSize) || (e.m_addr < addr + size && addr + size <= e.m_addr + e.m_memOpSize))) { // addr overlapping
                cout << "Data race: " << endl;
                cout << "Previous: " << e.toString() << endl;
                cout << "current: " << e1.toString() << endl;
                cout << "==================================================================================" << endl;
                assert(false);
            }
        }
        g_waitingMapLock.unlock();
    }
    if (line == intstLine0) // only block the first site
    {
        g_waitingMapLock.lock(tid);
        waitingMap.push_front(e1);
        waitedEvents++;
        g_waitingMapLock.unlock();
        // cout << "waiting at " << e1.toString() << endl;
        PIN_Yield();
        if (line == intstLine0) {
            usleep(usleep0);
        }
        //else{
        //  usleep(usleep1);
        //}
        //clear map
        g_waitingMapLock.lock(tid);
        for (list<Event>::iterator it = waitingMap.begin(); it != waitingMap.end(); it++)
            if (it->m_tid == tid) {
                waitingMap.erase(it);
                break;
            }
        g_waitingMapLock.unlock();
    }
}

VOID readAccess(THREADID tid, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 opcode, UINT16 line, UINT16 fno, UINT16 rtnno, BOOL isAtomic) {
    // if (enableCollisionAnalysis && !isAtomic) {
    if (enableCollisionAnalysis) {
        doCollisionAnalysis(tid, MEMORY_READ, addr, size, isStackRef, line, fno, rtnno);
        return;
    }
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }

    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));

    if (isAtomic) {
        // An atomic read is basically two events - a read followed by a write
        prepareAndSendMemoryEvent(tid, ATOMIC_READ, addr, size, isStackRef, opcode, line, fno, rtnno, tdata->lastLine, tdata->lastSrcFile);
        // R: The write is not required, because Pin always identifies an atomic access
        // as both read and write, and calls both readAccess() and afterWriteAccess() for it.
        // prepareAndSendMemoryEvent(tid, ATOMIC_WRITE, addr, size, isStackRef, opcode, line, fno, rtnno);
    } else {
        prepareAndSendMemoryEvent(tid, MEMORY_READ, addr, size, isStackRef, opcode, line, fno, rtnno, tdata->lastLine, tdata->lastSrcFile);
    }
    g_Lock.unlock();
}

// From DebugTrace/debugtrace.cpp: VOID ShowN(UINT32 n, VOID *ea)
VOID handleOtherSizes(ADDRINT addr, UINT32 size) {
    UINT8 b[512];
    UINT8 *x;
    if (size > 512) {
        x = new UINT8[size];
    } else {
        x = b;
    }
    size_t bytesRead = PIN_SafeCopy(x, static_cast<UINT8 *>(Addrint2VoidStar(addr)), size);
    assert(bytesRead == size);
}

VOID beforeWriteAccess(THREADID tid, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 opcode, BOOL isAtomic) {
    g_Lock.lock(tid);
    assert(tid >= 0 && addr > 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }

    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    if (isAtomic)
        tdata->m_eventType = ATOMIC_WRITE;
    else
        tdata->m_eventType = MEMORY_WRITE;

    assert(tdata->m_tid == tid);

    tdata->m_addr = addr;
    tdata->m_memOpSize = size;
    tdata->m_stackRef = isStackRef;
    g_Lock.unlock();
}

/* Required for getting the updated value after a write. */
VOID afterWriteAccess(THREADID tid, UINT32 size, UINT16 opcode, UINT16 line, UINT16 fno, UINT16 rtnno) {
    if (enableCollisionAnalysis) {
        thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
        // if (tdata->m_eventType != ATOMIC_WRITE) {
        doCollisionAnalysis(tdata->m_tid, tdata->m_eventType, tdata->m_addr, tdata->m_memOpSize, tdata->m_stackRef, line, fno, rtnno);
        return;
        // }
    }

    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }

    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    assert(tid >= 0 && tdata->m_tid == tid);
    assert(tdata->m_eventType == MEMORY_WRITE || tdata->m_eventType == ATOMIC_WRITE);
    assert(tdata->m_memOpSize == size);
    assert(tdata->m_addr != 0);

    if (constants::IGNORE_STOSB && (OPCODE)opcode == XED_ICLASS_STOSB) {
        g_enqSTOSB++;
    } else {
        prepareAndSendMemoryEvent(tdata->m_tid, tdata->m_eventType, tdata->m_addr, tdata->m_memOpSize, tdata->m_stackRef, opcode, line, fno, rtnno, tdata->lastLine, tdata->lastSrcFile);
    }
    g_Lock.unlock();
}

VOID analyzeSiteInfo(THREADID tid, UINT16 line, UINT16 fno) {
    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    tdata->lastLine = line;
    tdata->lastSrcFile = fno;
}

VOID afterLockAcquire(THREADID tid, ADDRINT lockAddr) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }
    if (constants::DEBUG_IGFUNCS && tid == 0)
        cout << tid << " after_lock_acq" << endl;
    Event start = Event::LockEvent(tid, LOCK_ACQUIRE, REG_BEGIN, lockAddr);
    addEvent(start);
    g_Lock.unlock();
}

VOID beforeLockAcquire(THREADID tid, ADDRINT lockAddr) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }

    thread_local_data_t *tdata = static_cast<thread_local_data_t *>(PIN_GetThreadData(s_writeAddr, tid));
    assert(tdata != NULL);
    assert(tdata->m_tid == tid);
    assert(tdata->activeAcqs >= 0);
    tdata->activeAcqs++;

    Event end = Event::LockEvent(tid, LOCK_ACQUIRE, REG_END, lockAddr);
    addEvent(end);
    g_Lock.unlock();
    waitForBackend(end);
}

VOID beforeLockRelease(THREADID tid, BOOL isUnlock, ADDRINT lockAddr, ADDRINT addr) {
    g_Lock.lock(tid);
    assert(tid >= 0);

    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }

    Event end = Event::LockEvent(tid, LOCK_RELEASE, REG_END, lockAddr);
    addEvent(end);
    g_Lock.unlock();
    waitForBackend(end);
}

VOID beforeBasicBlock(THREADID tid, CONTEXT *ctxt, UINT32 insnCount) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }
    addEvent(Event::BasicBlockEvent(tid, BASIC_BLOCK, insnCount));
    g_Lock.unlock();
}

// This is called whenever a thread is created in the system (including pthread_create() calls and the main thread)
VOID threadBegin(THREADID tid, CONTEXT *ctxt, INT32 flags, VOID *v) {
    g_Lock.lock(tid);
    assert(tid >= 0);

    // Create a thread-local TLS key, start with default values
    thread_local_data_t *tdata = new thread_local_data_t;
    tdata->m_tid = tid;
    BOOL ok = PIN_SetThreadData(s_writeAddr, tdata, tid);
    assert(ok);

    // This is a region boundary, but is called only on behalf of the newly created thread. So this boundary is only for
    // the current thread, but we should also create a boundary for the parent thread (if applicable).
    Event e = Event(Event::ThreadEvent(tid, THREAD_START, REG_END));
    addEvent(e);
    if (constants::DEBUG) {
        cout << "[pintool] Thread start:" << tid << endl;
    }
    g_Lock.unlock();
}

VOID threadEnd(THREADID tid, const CONTEXT *ctx, INT32 code, VOID *v) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        cout << "T" << tid << " has non-zero ignoredFuncs." << endl;
    }
    // assert (!isIgnored(tid));

    // This is a region boundary, but not strictly a LOCK_RELEASE
    Event e = Event::ThreadEvent(tid, THREAD_FINISH, REG_END);
    addEvent(e);
    if (constants::DEBUG) {
        cout << "[pintool] Thread end:" << tid << endl;
    }
    g_Lock.unlock();
    // we do not wait at lock release so as to avoid getting stuck with the pausing implementation in lockstep
}

// SB: Radish uses these methods to establish happens-before edges between the parent and the child threads.
// We can ignore pthread_exit() since threadEnd() will create a region boundary.
VOID beforePthreadCreate(THREADID tid, ADDRINT thread) { // tid is the id of the parent thread
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }
    if (constants::DEBUG_IGFUNCS && tid == 0)
        cout << tid << " pthread_create" << endl;
    // But we need to create a region boundary in the parent thread
    Event end = Event::ThreadEvent(tid, THREAD_SPAWN, REG_END);
    addEvent(end);
    g_Lock.unlock();
    // we do not wait at lock release so as to avoid getting stuck with the pausing implementation in lockstep
}

VOID afterJoin(THREADID tid, ADDRINT thread) {
    g_Lock.lock(tid);
    assert(tid >= 0);
    if (isIgnored(tid)) {
        g_Lock.unlock();
        return;
    }
    // This is a region boundary, but not strictly a LOCK_ACQUIRE
    Event end = Event::ThreadEvent(tid, THREAD_JOIN, REG_END);
    addEvent(end);
    if (constants::DEBUG) {
        cout << "[pintool] After join:" << tid << endl;
    }
    g_Lock.unlock();
}

/** Runs before every function call.  See threadBegin() for more details. */
VOID startFunctionCall(THREADID tid, CONTEXT *ctxt) {
    assert(tid >= 0);
}

// Currently unused systemcall hooks. Used to detect when threads are blocked, which isn't really necessary with the
// single-queue model.

VOID beforeSyscall(THREADID tid, CONTEXT *ctx, SYSCALL_STANDARD sys, VOID *unused) {
    assert(tid >= 0);
}

VOID afterSyscall(THREADID tid, CONTEXT *ctx, SYSCALL_STANDARD sys, VOID *unused) {
    assert(tid >= 0);
}

VOID beforeSignal(THREADID tid, CONTEXT_CHANGE_REASON reason, const CONTEXT *from, CONTEXT *to, INT32 info, VOID *v) {
    assert(tid >= 0);
}

VOID checkEvent(Event e) {
    switch (e.m_eventType) {
    case THREAD_BLOCKED:
    case THREAD_UNBLOCKED: {
        assert(false);
        break;
    }
    case ROI_START:
    case ROI_END:
    case THREAD_START:
    case THREAD_FINISH:
    case MEMORY_READ:
    case MEMORY_WRITE:
    case MEMORY_ALLOC:
    case MEMORY_FREE:
    case BASIC_BLOCK:
    case LOCK_ACQUIRE:
    case ATOMIC_READ:
    case ATOMIC_WRITE:
    case LOCK_RELEASE:
    case IGNORE_CONFLICTS_BEGIN:
    case IGNORE_CONFLICTS_END:
    case TRANS_START:
    case SERVER_ROI_START:
    case SERVER_ROI_END:
    case TRANS_END:
    case CHECK_POINT:
    case LOCK_ACQ_READ:
    case LOCK_ACQ_WRITE:
    case LOCK_REL_WRITE:
    case THREAD_JOIN:
    case THREAD_SPAWN: {
        break;
    }
    case INVALID:
    default: {
        cerr << "*** Invalid event:" << e.toString() << " ***" << endl;
        assert(false);
    }
    }
}

VOID trackEnqueueStats(Event e) {
    // Acquire lock to avoid data races, g_lock is already acquired
    switch (e.m_eventType) {
    case ROI_START: {
        g_enqRoiStart++;
        break;
    }
    case ROI_END: {
        g_enqRoiEnd++;
        break;
    }
    case TRANS_START: {
        g_enqTransStarts++;
        break;
    }
    case TRANS_END: {
        g_enqTransEnds++;
        break;
    }
    case THREAD_START: {
        g_enqThreadStarts++;
        break;
    }
    case THREAD_FINISH: {
        g_enqThreadEnds++;
        break;
    }
    case MEMORY_READ: {
        g_enqReads++;
        g_enqMemoryEvents++;
        break;
    }
    case MEMORY_WRITE: {
        g_enqWrites++;
        g_enqMemoryEvents++;
        break;
    }
    case THREAD_BLOCKED: {
        g_enqThreadBlocked++;
        break;
    }
    case THREAD_UNBLOCKED: {
        g_enqThreadUnblocked++;
        break;
    }
    case BASIC_BLOCK: {
        g_enqBBs++;
        break;
    }
    case LOCK_ACQUIRE: {
        g_enqLockAcquires++;
        break;
    }
    case ATOMIC_READ: {
        g_enqAtomicReads++;
        break;
    }
    case ATOMIC_WRITE: {
        g_enqAtomicWrites++;
        break;
    }
    case LOCK_RELEASE: {
        g_enqLockReleases++;
        break;
    }
    case THREAD_JOIN: {
        g_enqThreadJoins++;
        break;
    }
    case THREAD_SPAWN: {
        g_enqThreadSpawns++;
        break;
    }
    case IGNORE_CONFLICTS_BEGIN: {
        g_enqIgnoreConflictsBegins++;
        break;
    }
    case IGNORE_CONFLICTS_END: {
        g_enqIgnoreConflictsEnds++;
        break;
    }
    case SERVER_ROI_START: {
        g_enqServerRoiStarts++;
        break;
    }
    case SERVER_ROI_END: {
        g_enqServerRoiEnds++;
        break;
    }
    case LOCK_ACQ_READ: {
        g_enqLockAcqReads++;
        break;
    }
    case LOCK_ACQ_WRITE: {
        g_enqLockAcqWrites++;
        break;
    }
    case LOCK_REL_WRITE: {
        g_enqLockRelWrites++;
        break;
    }
    case CHECK_POINT: {
        g_enqCheckPoints++;
        break;
    }
    default: {
        assert(false);
        // Do nothing
    }
    }
    g_enqEvents++; // Count all events
}

VOID addEvent(Event e) {
    checkEvent(e);
    if (constants::DEBUG) {
        enqueue << "[ENQUEUE] " << e.toString() << endl;
        cout << "[ENQUEUE] " << e.toString() << endl;
    }

    if (constants::USE_SYNC_CIRCULAR_BUFFER) {
        // Synchronize on the lock
        g_eventQLock.lock(e.m_tid);
        while (g_cbEventQ.full()) {
            assert(g_cbEventQ.size() == MAX_CIRCULAR_BUFFER_SIZE);
            // Queue is full, wait till there are empty slots
            g_eventQLock.unlock();
            PIN_Yield();
            g_eventQLock.lock(e.m_tid);
        }
        // The lock is acquired
        e.m_iid = ++insCount;
        g_cbEventQ.push_back(e); // Add at the end of the circular buffer
        g_eventQLock.unlock();
        if (constants::WRITE_EVENT_FILE) {
            // Should be invoked before e.send() because the latter would endian_swap m_tid
            e.send2TextFile(eventTraceTextFile);
        }
    }

    // Directly write to the trace, synchronized with g_lock
    if (constants::WRITE_TRACE_FILE) {
        e.send(eventTrace);
    }

    // Measure statistics
    if (constants::TRACK_STATS) {
        trackEnqueueStats(e);
    }

    // cout << "[pintool] Pushed an element to the queue" << g_enqEvents
    // 	  << e.toString() << endl;
}

/* Block to read from the named pipe */
VOID waitForBackend(Event e) {
    if (lockstep) {
        // eventTrace.flush();
        assert(e.m_tid != 1); // IO thread
        int fifoID = e.m_tid;
        if (!perThreadFifos[fifoID].good() || !perThreadFifos[fifoID].is_open()) {
            cout << "Thread id:" << fifoID << endl;
        }
        assert(perThreadFifos[fifoID].good());
        assert(perThreadFifos[fifoID].is_open());
        string fifoName = string(getenv("PINTOOL_ROOT")) + "/" + perThreadFIFOPrefix + boost::lexical_cast<string>(fifoID);
        char buffer[2];
        if (constants::DEBUG_LOCKSTEP) {
            cout << "[pintool] Pin thread " << e.m_tid << " is blocking " // "from the per-thread fifo:" << fifoName
                 << " to receive notification from the backend, Event: " << g_enqEvents << " Event type:" << e.m_eventType << endl;
        }

        // SB: If we are reading a single char, i.e., 8 bytes, we do not need byte swapping.
        // sleep(2); // Sleep for some time to help with debugging
        for (unsigned int i = 0; i < backends; i++) {
            perThreadFifos[fifoID].read(buffer, sizeof(buffer));

            unsigned char low = (unsigned char)buffer[0];
            unsigned char hi = (unsigned char)buffer[1];
            short number = (short)(low << 8 | hi);

            if (constants::DEBUG_LOCKSTEP) {
                cout << "Thread:" << e.m_tid << " Buffer[0]:" << low << "    " << bitset<8>(low)
                     << " Buffer[1]:" << hi << "    " << bitset<8>(hi) << " Number after typecasting:" << number << endl;

                cout << "[pintool] Pin thread " << e.m_tid << " received notification from the backend:" << number << endl;
            }
            if (e.m_tid != number) {
                cout << "Thread id:" << e.m_tid << " Notification received over the pipe:" << bitset<16>(number) << endl;
            }
            assert(e.m_tid == number);
        }
    }
    numRegionBoundaries++;
}
