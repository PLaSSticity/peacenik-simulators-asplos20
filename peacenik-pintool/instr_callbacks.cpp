#include "analysis_callbacks.hpp"
#include "config.hpp"
#include "viser.hpp"

extern AFUNPTR realPthreadSelf;
extern map<string, int> filenameMap;
extern map<string, int> rtnnameMap;
extern KNOB<BOOL> ignorePthreadRTNs;

BOOL isLockAcquire(const char *rtnName) {
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

BOOL isLockRelease(const char *rtnName) {
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

BOOL isLockAcqAndRls(const char *rtnName) {
    return strstr(rtnName, "pthread_cond_timedwait") ||
           // As suggested by Mike, we can still treat pthread_barrier_wait() as a release PLUS an acquire.
           strstr(rtnName, "pthread_barrier_wait") ||
           strstr(rtnName, "pthread_cond_wait");
}

// functions besides the sync functions above
// where we don't want to instrument memory accesses and report races
// Note that the functions should have an exit.
BOOL needIgnore(const char *rtnName) {
    return false;
}

// extra lib functions where we don't want to instrument memory accesses and report races
// these functions can have no exit.
BOOL isLibFunction(const char *rtnName) {
    return false;
}

VOID initInstrCallback() {
}

UINT32 getIndexNumber(map<string, int> &nameMap, string name) {
    map<string, int>::iterator it;
    UINT32 no = 0;
    if (!name.empty()) {
        it = nameMap.find(name);
        if (it != nameMap.end()) {
            no = it->second;
        } else {
            no = nameMap.size() + 1;
            nameMap[name] = no;
        }
    }
    return no;
}

VOID instrumentTrace(TRACE trace, VOID *v) {
    UINT32 fno;
    INT32 line = 0;
    string filename;
    string rtnname;
    UINT32 rtnno;
    RTN rtn;

    for (BBL bbl = TRACE_BblHead(trace); BBL_Valid(bbl); bbl = BBL_Next(bbl)) {
        INS ins = BBL_InsHead(bbl);

        // This is always required, since we bill one cycle for all instructions.
        INS_InsertCall(ins, IPOINT_BEFORE, (AFUNPTR)beforeBasicBlock, IARG_THREAD_ID, IARG_CONTEXT, IARG_UINT32, BBL_NumIns(bbl), IARG_END);

        for (; INS_Valid(ins); ins = INS_Next(ins)) {
            PIN_GetSourceLocation(INS_Address(ins), NULL, &line, &filename);
            fno = getIndexNumber(filenameMap, filename);
            if (line != 0) {
                INS_InsertCall(ins, IPOINT_BEFORE, (AFUNPTR)analyzeSiteInfo, IARG_THREAD_ID, IARG_UINT32, line, IARG_UINT32, fno, IARG_END);
            }
            rtn = INS_Rtn(ins);
            if (RTN_Valid(rtn)) {
                rtnname = RTN_Name(rtn);
                if (isLibFunction(rtnname.c_str()))
                    continue;
                rtnno = getIndexNumber(rtnnameMap, rtnname);
            } else {
                rtnno = 0;
            }

            // Prefetch instructions might access addresses which are invalid.
            if (!constants::IGNORE_PREFETCH || !INS_IsPrefetch(ins)) {
                OPCODE op = INS_Opcode(ins);

                if (INS_IsMemoryRead(ins) && INS_IsStandardMemop(ins) /*&& op != XED_ICLASS_MOVDQA*/) {
                    if (INS_IsAtomicUpdate(ins)) {

                        INS_InsertPredicatedCall(ins, IPOINT_BEFORE, (AFUNPTR)readAccess, IARG_THREAD_ID, IARG_MEMORYREAD_EA,
                                                 IARG_MEMORYREAD_SIZE, IARG_BOOL, INS_IsStackRead(ins), IARG_UINT32, op, IARG_UINT32, line, IARG_UINT32, fno, IARG_UINT32, rtnno, IARG_BOOL, true, IARG_END);
                    } else
                        INS_InsertPredicatedCall(ins, IPOINT_BEFORE, (AFUNPTR)readAccess, IARG_THREAD_ID, IARG_MEMORYREAD_EA,
                                                 IARG_MEMORYREAD_SIZE, IARG_BOOL, INS_IsStackRead(ins), IARG_UINT32, op, IARG_UINT32, line, IARG_UINT32, fno, IARG_UINT32, rtnno, IARG_BOOL, false, IARG_END);
                }

                if (INS_HasMemoryRead2(ins) && INS_IsStandardMemop(ins) /*&& op != XED_ICLASS_MOVDQA*/) {
                    if (INS_IsAtomicUpdate(ins)) {

                        INS_InsertPredicatedCall(ins, IPOINT_BEFORE, (AFUNPTR)readAccess, IARG_THREAD_ID, IARG_MEMORYREAD2_EA,
                                                 IARG_MEMORYREAD_SIZE, IARG_BOOL, INS_IsStackRead(ins), IARG_UINT32, op, IARG_UINT32, line, IARG_UINT32, fno, IARG_UINT32, rtnno, IARG_BOOL, true, IARG_END);
                    } else
                        INS_InsertPredicatedCall(ins, IPOINT_BEFORE, (AFUNPTR)readAccess, IARG_THREAD_ID, IARG_MEMORYREAD2_EA,
                                                 IARG_MEMORYREAD_SIZE, IARG_BOOL, INS_IsStackRead(ins), IARG_UINT32, op, IARG_UINT32, line, IARG_UINT32, fno, IARG_UINT32, rtnno, IARG_BOOL, false, IARG_END);
                }

                if (INS_IsMemoryWrite(ins) && INS_IsStandardMemop(ins) /*&& op != XED_ICLASS_MOVDQA && op != XED_ICLASS_STOSB*/) {
                    if (INS_IsAtomicUpdate(ins)) {
                        INS_InsertPredicatedCall(ins, IPOINT_BEFORE, (AFUNPTR)beforeWriteAccess, IARG_THREAD_ID, IARG_MEMORYWRITE_EA,
                                                 IARG_MEMORYWRITE_SIZE, IARG_BOOL, INS_IsStackWrite(ins), IARG_UINT32, op, IARG_BOOL, true, IARG_END);
                    } else
                        INS_InsertPredicatedCall(ins, IPOINT_BEFORE, (AFUNPTR)beforeWriteAccess, IARG_THREAD_ID, IARG_MEMORYWRITE_EA,
                                                 IARG_MEMORYWRITE_SIZE, IARG_BOOL, INS_IsStackWrite(ins), IARG_UINT32, op, IARG_BOOL, false, IARG_END);

                    // Instrument after the write, so that we can get the updated value. This is required in Viser for value validation.
                    if (INS_HasFallThrough(ins)) {
                        INS_InsertPredicatedCall(ins, IPOINT_AFTER, (AFUNPTR)afterWriteAccess, IARG_THREAD_ID, IARG_MEMORYWRITE_SIZE, IARG_UINT32,
                                                 op, IARG_UINT32, line, IARG_UINT32, fno, IARG_UINT32, rtnno, IARG_END);
                    } else if (INS_IsBranchOrCall(ins)) {
                        INS_InsertPredicatedCall(ins, IPOINT_TAKEN_BRANCH, (AFUNPTR)afterWriteAccess, IARG_THREAD_ID, IARG_MEMORYWRITE_SIZE, IARG_UINT32,
                                                 op, IARG_UINT32, line, IARG_UINT32, fno, IARG_UINT32, rtnno, IARG_END);
                    }
                }
            }
        }
    }
}

// LATER: Use fork() callbacks for cleaner code
// https://software.intel.com/sites/landingpage/pintool/docs/71313/Pin/html/index.html#FollowChild

// SB: We do not need to handle memory allocation as memory accesses. Radish needs it to create happens-before relations
// between free and a later allocation.
VOID instrumentImage(IMG img, VOID *v) {
    for (SEC sec = IMG_SecHead(img); SEC_Valid(sec); sec = SEC_Next(sec)) {
        for (RTN rtn = SEC_RtnHead(sec); RTN_Valid(rtn); rtn = RTN_Next(rtn)) {
            const char *rtnName = RTN_Name(rtn).c_str();

            // Enable the following block if rtn names are needed
            if (constants::PRINT_RTN_NAMES) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)rtnStart, IARG_THREAD_ID, IARG_ADDRINT,
                               RTN_Address(rtn), IARG_END);
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)rtnFini, IARG_THREAD_ID, IARG_ADDRINT,
                               RTN_Address(rtn), IARG_FUNCRET_EXITPOINT_VALUE, IARG_END);
                RTN_Close(rtn);
            }

            if (strstr(rtnName, "__parsec_roi_begin")) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)roiStart, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "__parsec_roi_end")) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)roiEnd, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "malloc")) { // in g++, new does not call malloc()
            } else if (strstr(rtnName, "_Znwm")) {  // g++'s new
            } else if (strstr(rtnName, "free")) {   // in g++, delete does not call free()
            } else if (strstr(rtnName, "_ZdlPv")) { // g++'s delete
            } else if (strstr(rtnName, "pthread_self")) {
                assert(RTN_Valid(rtn));
                realPthreadSelf = (AFUNPTR)RTN_Address(rtn);
            } else if (strstr(rtnName, "pthread_create")) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)beforePthreadCreate, IARG_THREAD_ID,
                               IARG_END);
                if (ignorePthreadRTNs) {
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)ignoreConflictsBegin,
                                   IARG_THREAD_ID, IARG_END);
                    RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsEnd, IARG_THREAD_ID, IARG_END);
                }
                RTN_Close(rtn);
            } else if (strstr(rtnName, "pthread_join")) {
                RTN_Open(rtn);
                if (ignorePthreadRTNs) {
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)ignoreConflictsBegin,
                                   IARG_THREAD_ID, IARG_END);
                    RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsEnd, IARG_THREAD_ID,
                                   IARG_END);
                }
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)afterJoin, IARG_THREAD_ID,
                               IARG_FUNCARG_ENTRYPOINT_VALUE, 0, // the pthread_t*
                               IARG_END);
                RTN_Close(rtn);
            } else if (isLockAcquire(rtnName)) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)beforeLockAcquire, IARG_THREAD_ID,
                               IARG_FUNCARG_ENTRYPOINT_VALUE, 0, IARG_END);
                if (ignorePthreadRTNs) {
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)ignoreConflictsBegin,
                                   IARG_THREAD_ID, IARG_END);
                    // address the tail call elimination issue for mysqld
                    if (!strcmp(rtnName, "__pthread_rwlock_wrlock_slow")) {
                        RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsDoubleEnd, IARG_THREAD_ID,
                                       IARG_END);
                    } else {
                        RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsEnd, IARG_THREAD_ID,
                                       IARG_END);
                    }
                }
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)afterLockAcquire, IARG_THREAD_ID,
                               IARG_FUNCARG_ENTRYPOINT_VALUE, 0, IARG_END);
                RTN_Close(rtn);
            } else if (isLockAcqAndRls(rtnName)) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)beforeLockAcquire, IARG_THREAD_ID,
                               IARG_FUNCARG_ENTRYPOINT_VALUE, 1, IARG_END);
                if (ignorePthreadRTNs) {
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)ignoreConflictsBegin,
                                   IARG_THREAD_ID, IARG_END);
                    RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsEnd, IARG_THREAD_ID,
                                   IARG_END);
                }
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)afterLockAcquire, IARG_THREAD_ID,
                               IARG_FUNCARG_ENTRYPOINT_VALUE, 1, IARG_END);
                RTN_Close(rtn);
            } else if (isLockRelease(rtnName)) {
                RTN_Open(rtn);
                if (strstr(rtnName, "_cond_"))
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)beforeLockRelease,
                                   IARG_THREAD_ID, IARG_BOOL, false, IARG_FUNCARG_ENTRYPOINT_VALUE, 0, IARG_ADDRINT, RTN_Address(rtn), IARG_END);
                else
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)beforeLockRelease,
                                   IARG_THREAD_ID, IARG_BOOL, true, IARG_FUNCARG_ENTRYPOINT_VALUE, 0, IARG_ADDRINT, RTN_Address(rtn), IARG_END);
                if (ignorePthreadRTNs) {
                    RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)ignoreConflictsBegin,
                                   IARG_THREAD_ID, IARG_END);
                    RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsEnd, IARG_THREAD_ID,
                                   IARG_END);
                }
                RTN_Close(rtn);
            } else if (needIgnore(rtnName) && ignorePthreadRTNs) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)ignoreConflictsBegin,
                               IARG_THREAD_ID, IARG_END);
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)ignoreConflictsEnd, IARG_THREAD_ID,
                               IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "__do_check_point")) {
                cout << "[pintool] __do_check_point: " << rtnName << endl;
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)checkPoint, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            }
            /* for mysqld */
            else if (strstr(rtnName, "mysql_execute_command")) {
                cout << "[pintool] trans: " << rtnName << endl;
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)transStart, IARG_THREAD_ID, IARG_END);
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)transEnd, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "listen_for_connection_event")) {
                cout << "[pintool] instrument: " << rtnName << endl;
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)serverRoiStart, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "terminate_compress_gtid_table_thread")) {
                cout << "[pintool] instrument: " << rtnName << endl;
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)serverRoiEnd, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            }
            /* for httpd */
            else if (!strcmp(rtnName, "listener_thread")) {
                cout << "[pintool] instrument: " << rtnName << endl;
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)serverRoiStart, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (!strcmp(rtnName, "ap_close_listeners_ex")) {
                cout << "[pintool] instrument: " << rtnName << endl;
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_AFTER, (AFUNPTR)serverRoiEnd, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "__transaction_begin")) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)transStart, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strstr(rtnName, "__transaction_end")) {
                RTN_Open(rtn);
                RTN_InsertCall(rtn, IPOINT_BEFORE, (AFUNPTR)transEnd, IARG_THREAD_ID, IARG_END);
                RTN_Close(rtn);
            } else if (strcmp(rtnName, "strlen") == 0) {
                RTN_Open(rtn);
                RTN_Replace(rtn, (AFUNPTR)myStrlen);
                RTN_Close(rtn);
            }
        }
    }
}
