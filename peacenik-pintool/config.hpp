#ifndef CONFIG_HPP_
#define CONFIG_HPP_

namespace constants {

// IMP: Disable USE_SYNC_CIRCULAR_BUFFER to write to a trace file.
// Use a circular buffer as the queue for storing events, and synchronize access with a lock.
const BOOL USE_SYNC_CIRCULAR_BUFFER = true;
// Directly write to the trace file with a lock. In that case, the IO thread is redundant.
const BOOL WRITE_TRACE_FILE = false;

const BOOL IGNORE_STOSB = false;
// Instrumenting PREFETCHxx instructions seems to invoke an INVALID instruction in
// the analysis routines.
const BOOL IGNORE_PREFETCH = true;

// Enable debugging code
const BOOL DEBUG = false;
const BOOL DEBUG_IGFUNCS = false;  // Enable print debugging messages for ignoring pthread functions
const BOOL DEBUG_LOCKSTEP = false; // Enable print statements for execution in locksteps

// Measure statistics
const BOOL TRACK_STATS = true;

// The following two should only be enabled with siteTracking enabled.
// RZ: Both set to true will possibly get the Pintool stuck. I don't know why.
const BOOL WRITE_EVENT_FILE = false;
const BOOL PRINT_RTN_NAMES = false;
} // namespace constants

#endif // CONFIG_HPP_
