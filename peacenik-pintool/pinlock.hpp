#ifndef PIN_LOCK_HPP_
#define PIN_LOCK_HPP_

#include "pin.H"

// Wrapper class, avoids false sharing by padding
class PinLock {
    PIN_LOCK _m_lock;
    char padding[64 - sizeof(PIN_LOCK)]; // pad to 64B cache line

public:
    // Method definitions
    PinLock() {
        PIN_InitLock(&_m_lock);
    }

    ~PinLock() {
    }

    inline void lock(int id = 1) {
        PIN_GetLock(&_m_lock, id);
    }

    inline void unlock() {
        PIN_ReleaseLock(&_m_lock);
    }
};

#endif // PIN_LOCK_HPP_
