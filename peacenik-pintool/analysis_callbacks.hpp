#ifndef ANALYSIS_CALLBACKS_HPP_
#define ANALYSIS_CALLBACKS_HPP_

#include "pin.H"
#include "viser.hpp"

// This class contains Pin analysis method declarations.

size_t myStrlen(const char *str);

// Parsec "region of interest" - period of parallel execution
VOID roiStart(THREADID tid);
VOID roiEnd(THREADID tid);

// Server "region of interest" - period of being able to processing requests
VOID serverRoiStart(THREADID tid);
VOID serverRoiEnd(THREADID tid);

// Period of processing a request in server
VOID transStart(THREADID tid);
VOID transEnd(THREADID tid);

VOID checkPoint(THREADID tid);

VOID rtnStart(THREADID tid, ADDRINT addr);
VOID rtnFini(THREADID tid, ADDRINT addr, ADDRINT outcome);

// Read/write accesses
VOID readAccess(THREADID tid, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 opcode, UINT16 line, UINT16 fno, UINT16 rtnno, BOOL isAtomic);
VOID beforeWriteAccess(THREADID tid, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 opcode, BOOL isAtomic);
VOID afterWriteAccess(THREADID tid, UINT32 size, UINT16 opcode, UINT16 line, UINT16 fno, UINT16 rtnno);

VOID analyzeSiteInfo(THREADID tid, UINT16 line, UINT16 fno);

// Lock accesses
VOID beforeLockAcquire(THREADID tid, ADDRINT lockAddr);
VOID afterLockAcquire(THREADID tid, ADDRINT lockAddr);
VOID beforeLockRelease(THREADID tid, BOOL isUnlock, ADDRINT lockAddr, ADDRINT addr);

VOID beforeBasicBlock(THREADID tid, CONTEXT *ctxt, UINT32 insnCount);

VOID threadBegin(THREADID tid, CONTEXT *ctxt, INT32 flags, VOID *v);
VOID threadEnd(THREADID tid, const CONTEXT *ctx, INT32 code, VOID *v);

VOID beforePthreadCreate(THREADID tid, ADDRINT thread);

VOID ignoreConflictsBegin(THREADID tid);
VOID ignoreConflictsEnd(THREADID tid);
VOID ignoreConflictsDoubleEnd(THREADID tid);

VOID beforeJoin(THREADID tid, ADDRINT thread);
VOID afterJoin(THREADID tid, ADDRINT thread);

/** Runs before every function call.  See threadBegin() for more details. */
VOID startFunctionCall(THREADID tid, CONTEXT *ctxt);

// Memory allocation events, same analysis callbacks are used for new and delete
VOID beforeMalloc();
VOID afterMalloc();
VOID beforeFree();

VOID beforeSyscall(THREADID tid, CONTEXT *ctx, SYSCALL_STANDARD sys, VOID *unused);
VOID afterSyscall(THREADID tid, CONTEXT *ctx, SYSCALL_STANDARD sys, VOID *unused);

VOID beforeSignal(THREADID tid, CONTEXT_CHANGE_REASON reason, const CONTEXT *from, CONTEXT *to, INT32 info, VOID *v);

VOID prepareAndSendMemoryEvent(THREADID tid, EventType type, ADDRINT addr, UINT32 size, BOOL isStackRef, UINT16 opcode, UINT16 line, UINT16 fno, UINT16 rtnno);
VOID addEvent(Event e);
VOID waitForBackend(Event e);

#endif /* ANALYSIS_CALLBACKS_HPP_ */
