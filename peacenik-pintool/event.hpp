#ifndef EVENT_HPP_
#define EVENT_HPP_

#include <cassert>
#include <execinfo.h>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdint.h>
#include <string>

#include "pin.H"

// SB: From stackoverflow, does not print the method name for me.
inline void bar() {
    void *callstack[128];
    int i, frames = backtrace(callstack, 128);
    char **strs = backtrace_symbols(callstack, frames);
    for (i = 0; i < frames; ++i) {
        printf("%s\n", strs[i]);
    }
    free(strs);
}

enum EventType {
    INVALID = 0,
    ROI_START = 1,
    ROI_END = 2,
    THREAD_START = 3,
    THREAD_FINISH = 4,
    THREAD_BLOCKED = 5,
    THREAD_UNBLOCKED = 6,
    MEMORY_READ = 7,
    MEMORY_WRITE = 8,
    MEMORY_ALLOC = 9,
    MEMORY_FREE = 10,
    BASIC_BLOCK = 11,
    LOCK_ACQUIRE = 12,
    LOCK_RELEASE = 13,
    THREAD_JOIN = 14,
    THREAD_SPAWN = 15,
    IGNORE_CONFLICTS_BEGIN = 16,
    IGNORE_CONFLICTS_END = 17,
    ATOMIC_READ = 18,
    ATOMIC_WRITE = 19,
    LOCK_ACQ_READ = 20,
    LOCK_ACQ_WRITE = 21,
    LOCK_REL_WRITE = 22,
    REG_BEGIN = 23,
    REG_END = 24,
    SERVER_ROI_START = 25,
    SERVER_ROI_END = 26,
    TRANS_START = 27,
    TRANS_END = 28,
    CHECK_POINT = 29
};

static string eventTypes[] =
    {
        "INVALID",
        "ROI_START",
        "ROI_END",
        "THREAD_START",
        "THREAD_FINISH",
        "THREAD_BLOCKED",
        "THREAD_UNBLOCKED",
        "MEMORY_READ",
        "MEMORY_WRITE",
        "MEMORY_ALLOC",
        "MEMORY_FREE",
        "BASIC_BLOCK",
        "LOCK_ACQUIRE",
        "LOCK_RELEASE",
        "THREAD_JOIN",
        "THREAD_SPAWN",
        "IGNORE_CONFLICTS_BEGIN",
        "IGNORE_CONFLICTS_END",
        "ATOMIC_READ",
        "ATOMIC_WRITE",
        "LOCK_ACQ_READ",
        "LOCK_ACQ_WRITE",
        "LOCK_REL_WRITE",
        "REG_BEGIN",
        "REG_END",
        "SERVER_ROI_START",
        "SERVER_ROI_END",
        "TRANS_START",
        "TRANS_END",
        "CHECK_POINT"};

inline void endian_swap(int16_t &x) {
    x = (x >> 8) |
        (x << 8);
}

inline void endian_swap(int32_t &x) {
    x = (x >> 24) |
        ((x << 8) & 0x00FF0000) |
        ((x >> 8) & 0x0000FF00) |
        (x << 24);
}

inline void endian_swap(uint64_t &x) {
    x = (x >> 56) |
        ((x << 40) & 0x00FF000000000000) |
        ((x << 24) & 0x0000FF0000000000) |
        ((x << 8) & 0x000000FF00000000) |
        ((x >> 8) & 0x00000000FF000000) |
        ((x >> 24) & 0x0000000000FF0000) |
        ((x >> 40) & 0x000000000000FF00) |
        (x << 56);
}

class Event {
public:
    EventType m_eventType;    // Actual event
    EventType m_regSemantics; // Semantics
    int16_t m_tid;
    uint64_t m_addr; // Effective address
    int32_t m_memOpSize;
    BOOL m_stackRef;
    uint64_t m_value;    // value stored at the effective address
    int32_t m_insnCount; // Used for BASIC_BLOCK event type

    // For site tracking: 14 bytes
    int16_t m_line;
    int16_t m_fno;
    int16_t m_rno;
    uint32_t m_iid; //instruction id
    int16_t m_lastLine;
    int16_t m_lastFno;
    int16_t m_opcode;

    // Implicit copy ctor and dtor should be sufficient.

    // We need the endian swapping for binary files. See http://www.cplusplus.com/articles/DzywvCM9/
    void send(std::ofstream &os) {
        char t = m_eventType;
        os.write(&t, sizeof(char));

        char s = m_regSemantics;
        os.write(&s, sizeof(char));

        endian_swap(m_tid);
        // Need gcc 4.8 for the 16 bit counterpart
        //int16_t rev_tid = __builtin_bswap16(m_tid);
        os.write(reinterpret_cast<const char *>(&m_tid), sizeof(int16_t));

        //endian_swap(m_addr);
        uint64_t rev_addr = __builtin_bswap64(m_addr);
        os.write(reinterpret_cast<const char *>(&rev_addr), sizeof(uint64_t));

        //endian_swap(m_memOpSize);
        int32_t rev_op = __builtin_bswap32(m_memOpSize);
        os.write(reinterpret_cast<const char *>(&rev_op), sizeof(int32_t));

        char b = 0;
        if (m_stackRef) {
            b |= 1;
        }
        // No need to swap bytes
        os.write(reinterpret_cast<const char *>(&b), sizeof(bool));

        //endian_swap(m_value);
        uint64_t rev_value = __builtin_bswap64(m_value);
        os.write(reinterpret_cast<const char *>(&rev_value), sizeof(uint64_t));

        //endian_swap(m_insnCount);
        int32_t rev_count = __builtin_bswap32(m_insnCount);
        os.write(reinterpret_cast<const char *>(&rev_count), sizeof(int32_t));

        endian_swap(m_line);
        os.write(reinterpret_cast<const char *>(&m_line), sizeof(int16_t));

        endian_swap(m_fno);
        os.write(reinterpret_cast<const char *>(&m_fno), sizeof(int16_t));

        endian_swap(m_rno);
        os.write(reinterpret_cast<const char *>(&m_rno), sizeof(int16_t));

        uint32_t rev_iid = __builtin_bswap32(m_iid);
        os.write(reinterpret_cast<const char *>(&rev_iid), sizeof(uint32_t));

        endian_swap(m_lastLine);
        os.write(reinterpret_cast<const char *>(&m_lastLine), sizeof(int16_t));

        endian_swap(m_lastFno);
        os.write(reinterpret_cast<const char *>(&m_lastFno), sizeof(int16_t));

        // This is important to ensure event communication over pipes on a per-event basis and not in
        // blocks of certain sizes
        os.flush();
    }

    void send2TextFile(std::ofstream &os) {
        os << "[" + eventTypes[m_eventType] + "] ";
        os << "opcode: " << OPCODE_StringShort(m_opcode) << " ";
        os << "region semantics:" << m_regSemantics << " ";
        os << "tid: " << m_tid << " ";

        os << "addr: " << m_addr << " ";
        os << "memOpSize: " << m_memOpSize << " ";
        os << "isStackRef: " << m_stackRef << " ";

        os << "value: " << m_value << " ";
        os << "insnCount: " << m_insnCount << " ";
        os << "line: " << m_line << " ";
        os << "fno: " << m_fno << " ";
        os << "rno: " << m_rno << " ";
        os << "lastLine: " << m_lastLine << " ";
        os << "lastFno: " << m_lastFno << " ";
        os << "iid: " << m_iid << "\n";
    }

    std::string toString() {
        std::stringstream ss;

        switch (m_eventType) {
        case INVALID: {
            ss << "INVALID EVENT";
            break;
        }
        case ROI_START: {
            ss << "ROI_START";
            break;
        }
        case ROI_END: {
            ss << "ROI_END";
            break;
        }
        case THREAD_START: {
            ss << "THREAD_START";
            break;
        }
        case THREAD_FINISH: {
            ss << "THREAD_FINISH";
            break;
        }
        case THREAD_BLOCKED: {
            assert(false);
            ss << "THREAD_BLOCKED";
            break;
        }
        case THREAD_UNBLOCKED: {
            assert(false);
            ss << "THREAD_UNBLOCKED";
            break;
        }
        case MEMORY_READ: {
            ss << "MEMORY_READ";
            break;
        }
        case MEMORY_WRITE: {
            ss << "MEMORY_WRITE";
            break;
        }
        case MEMORY_ALLOC: {
            ss << "MEMORY_ALLOC";
            break;
        }
        case MEMORY_FREE: {
            ss << "MEMORY_FREE";
            break;
        }
        case BASIC_BLOCK: {
            ss << "BASIC_BLOCK";
            break;
        }
        case LOCK_ACQUIRE: {
            ss << "LOCK_ACQUIRE";
            break;
        }
        case ATOMIC_READ: {
            ss << "ATOMIC_READ";
            break;
        }
        case ATOMIC_WRITE: {
            ss << "ATOMIC_WRITE";
            break;
        }
        case LOCK_RELEASE: {
            ss << "LOCK_RELEASE";
            break;
        }
        case THREAD_JOIN: {
            ss << "THREAD_JOIN";
            break;
        }
        case THREAD_SPAWN: {
            ss << "THREAD_SPAWN";
            break;
        }
        case IGNORE_CONFLICTS_BEGIN: {
            ss << "IGNORE_CONFLICTS_BEGIN";
            break;
        }
        case IGNORE_CONFLICTS_END: {
            ss << "IGNORE_CONFLICTS_END";
            break;
        }
        case LOCK_ACQ_READ: {
            ss << "LOCK_ACQ_READ";
            break;
        }
        case LOCK_ACQ_WRITE: {
            ss << "LOCK_ACQ_WRITE";
            break;
        }
        case LOCK_REL_WRITE: {
            ss << "LOCK_REL_WRITE";
            break;
        }
        case REG_BEGIN: {
            ss << "REG_BEGIN";
            break;
        }
        case REG_END: {
            ss << "REG_END";
            break;
        }
        default: {
            cout << " iid=" << m_iid << endl;
            assert(false);
        }
        }

        assert(m_tid >= 0);
        assert(m_addr >= 0);
        assert(m_insnCount >= 0);
        assert(m_memOpSize >= 0);
        assert(m_eventType >= INVALID);
        assert(m_regSemantics == INVALID || m_regSemantics == REG_BEGIN || m_regSemantics == REG_END);

        ss << " region semantics=" << m_regSemantics
           << " tid=" << m_tid
           << hex << " addr=0x" << m_addr
           << " size=" << m_memOpSize
           << std::boolalpha << " stack=" << m_stackRef
           << " value=" << m_value
           << dec << " insncount=" << m_insnCount
           << " file=" << m_fno
           << " line=" << m_line
           << " routine=" << m_rno
           << " lastFile=" << m_lastFno
           << " lastLine=" << m_lastLine
           << " iid=" << m_iid
           << std::endl;
        return ss.str();
    }

    void constructor() {
        m_eventType = INVALID;
        m_regSemantics = INVALID;
        m_tid = -1;
        m_insnCount = 0;
        m_addr = 0;
        m_stackRef = false;
        m_memOpSize = 0;
        m_value = 0;

        m_fno = 0;
        m_rno = 0;
        m_line = 0;
        m_iid = -1;
        m_lastLine = 0;
        m_lastFno = 0;
        m_opcode = 0;
    }

    Event() {
        constructor();
    }

    Event(int16_t tid, EventType type) {
        constructor();
        m_tid = tid;
        m_eventType = type;
    }

    Event(int16_t tid, EventType type, EventType regSemType) {
        constructor();
        m_tid = tid;
        m_eventType = type;
        m_regSemantics = regSemType;
    }

    static Event ROIEvent(int16_t tid, EventType type) {
        assert(type == ROI_START || type == ROI_END || type == SERVER_ROI_START || type == SERVER_ROI_END || type == TRANS_START || type == TRANS_END || type == CHECK_POINT);
        return Event(tid, type);
    }

    static Event ThreadEvent(int16_t tid, EventType type, EventType regSemType) {
        assert(type == THREAD_START || type == THREAD_FINISH ||
               type == THREAD_JOIN || type == THREAD_SPAWN || type == IGNORE_CONFLICTS_BEGIN ||
               type == IGNORE_CONFLICTS_END);
        assert(regSemType == REG_BEGIN || regSemType == REG_END);
        return Event(tid, type, regSemType);
    }

    static Event LockEvent(int16_t tid, EventType type, EventType regSemType, ADDRINT lockAddr) {
        assert(type == LOCK_ACQUIRE || type == LOCK_RELEASE);
        assert(regSemType == REG_BEGIN || regSemType == REG_END);
        Event e = Event(tid, type, regSemType);
        e.m_addr = lockAddr;
        return e;
    }

    static Event BasicBlockEvent(int16_t tid, EventType type, int32_t insnCount) {
        assert(type == BASIC_BLOCK);
        assert(insnCount >= 0);
        Event e = Event(tid, type);
        e.m_insnCount = insnCount;
        return e;
    }

    static Event LockAccessEvent(int16_t tid, EventType type, ADDRINT addr) {
        assert(type == LOCK_ACQ_READ || type == LOCK_ACQ_WRITE || type == LOCK_REL_WRITE);
        Event e = Event(tid, type);
        e.m_addr = addr;
        return e;
    }

    static Event MemoryEvent(int16_t tid, EventType type, ADDRINT addr, int32_t memOpSize, bool stackRef, uint16_t line, uint16_t fno, uint16_t rno, uint16_t lastLine, uint16_t lastFno, uint16_t opcode) {
        assert(type == MEMORY_READ || type == MEMORY_WRITE || type == ATOMIC_READ || type == ATOMIC_WRITE);
        assert(addr >= 0 && memOpSize >= 0);
        Event e = Event(tid, type);
        e.m_addr = addr;
        e.m_memOpSize = memOpSize;
        e.m_stackRef = stackRef;
        e.m_line = line;
        e.m_fno = fno;
        e.m_rno = rno;
        e.m_lastLine = lastLine;
        e.m_lastFno = lastFno;
        e.m_opcode = opcode;
        return e;
    }

    static Event AllocationEvent(int16_t tid, EventType type, uint64_t startAddr, int32_t extent = 0) {
        assert(type == MEMORY_ALLOC || type == MEMORY_FREE);
        assert(startAddr >= 0);
        if (extent == 0) {
            assert(MEMORY_FREE == type);
        }
        Event e = Event(tid, type);
        e.m_addr = startAddr;
        e.m_memOpSize = extent;
        return e;
    }
};

#endif // EVENT_HPP_
