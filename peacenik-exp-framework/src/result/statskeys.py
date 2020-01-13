import collections

from option.constants import Constants


class PintoolKeys(Constants):
    TOTAL_EVENTS_KEY = "totalEvents"
    TOTAL_EVENTS = "total events"

    ROI_START_KEY = "roiStart"
    ROI_START = "roi start"

    ROI_END_KEY = "roiEnd"
    ROI_END = "roi end"

    THREAD_BEGIN_KEY = "threadBegins"
    THREAD_BEGIN = "thread begin"

    THREAD_END_KEY = "threadEnd"
    THREAD_END = "thread end"

    MEMORY_EVENTS_KEY = "memoryEvents"
    MEMORY_EVENTS = "memory events"

    READ_EVENTS_KEY = "readEvents"
    READ_EVENTS = "reads"

    WRITE_EVENTS_KEY = "writeEvents"
    WRITE_EVENTS = "writes"

    BASIC_BLOCKS_KEY = "basicBlocks"
    BASIC_BLOCKS = "basic blocks"

    LOCK_ACQS_KEY = "lockAcqs"
    LOCK_ACQS = "lock acquires"

    LOCK_RELS_KEY = "lockRels"
    LOCK_RELS = "lock releases"

    LOCK_ACQ_READS_KEY = "lockAcqReads"
    LOCK_ACQ_READS = "lock acquire reads"

    LOCK_ACQ_WRITES_KEY = "lockAcqWrites"
    LOCK_ACQ_WRITES = "lock acquire writes"

    LOCK_REL_WRITES_KEY = "lockRelWrites"
    LOCK_REL_WRITES = "lock release writes"

    ATOMIC_READS_KEY = "atomicReads"
    ATOMIC_READS = "atomic reads"

    ATOMIC_WRITES_KEY = "atomicWrites"
    ATOMIC_WRITES = "atomic writes"

    THREAD_SPAWN_KEY = "threadSpawns"
    THREAD_SPAWN = "thread spawns"

    THREAD_JOIN_KEY = "threadJoins"
    THREAD_JOIN = "thread joins"


class SimKeys(Constants):
    """These are simulator stats keys"""

    L1_READ_HITS_KEY = "g_Data_L1ReadHits"
    L1_READ_MISSES_KEY = "g_Data_L1ReadMisses"
    L1_WRITE_HITS_KEY = "g_Data_L1WriteHits"
    L1_WRITE_MISSES_KEY = "g_Data_L1WriteMisses"
    L1_LINE_EVICITIONS_KEY = "g_Data_L1LineEvictions"
    L1_ATOMIC_READ_HITS_KEY = "g_Data_L1AtomicReadHits"
    L1_ATOMIC_READ_MISSES_KEY = "g_Data_L1AtomicReadMisses"
    L1_ATOMIC_WRITE_HITS_KEY = "g_Data_L1AtomicWriteHits"
    L1_ATOMIC_WRITE_MISSES_KEY = "g_Data_L1AtomicWriteMisses"
    L1_LOCK_READ_HITS_KEY = "g_Data_L1LockReadHits"
    L1_LOCK_READ_MISSES_KEY = "g_Data_L1LockReadMisses"
    L1_LOCK_WRITE_HITS_KEY = "g_Data_L1LockWriteHits"
    L1_LOCK_WRITE_MISSES_KEY = "g_Data_L1LockWriteMisses"

    L2_READ_HITS_KEY = "g_Data_L2ReadHits"
    L2_READ_MISSES_KEY = "g_Data_L2ReadMisses"
    L2_WRITE_HITS_KEY = "g_Data_L2WriteHits"
    L2_WRITE_MISSES_KEY = "g_Data_L2WriteMisses"
    L2_LINE_EVICITIONS_KEY = "g_Data_L2LineEvictions"
    L2_ATOMIC_READ_HITS_KEY = "g_Data_L2AtomicReadHits"
    L2_ATOMIC_READ_MISSES_KEY = "g_Data_L2AtomicReadMisses"
    L2_ATOMIC_WRITE_HITS_KEY = "g_Data_L2AtomicWriteHits"
    L2_ATOMIC_WRITE_MISSES_KEY = "g_Data_L2AtomicWriteMisses"
    L2_LOCK_READ_HITS_KEY = "g_Data_L2LockReadHits"
    L2_LOCK_READ_MISSES_KEY = "g_Data_L2LockReadMisses"
    L2_LOCK_WRITE_HITS_KEY = "g_Data_L2LockWriteHits"
    L2_LOCK_WRITE_MISSES_KEY = "g_Data_L2LockWriteMisses"

    L3_READ_HITS_KEY = "g_Data_L3ReadHits"
    L3_READ_MISSES_KEY = "g_Data_L3ReadMisses"
    L3_WRITE_HITS_KEY = "g_Data_L3WriteHits"
    L3_WRITE_MISSES_KEY = "g_Data_L3WriteMisses"
    L3_LINE_EVICITIONS_KEY = "g_Data_L3LineEvictions"
    L3_ATOMIC_READ_HITS_KEY = "g_Data_L3AtomicReadHits"
    L3_ATOMIC_READ_MISSES_KEY = "g_Data_L3AtomicReadMisses"
    L3_ATOMIC_WRITE_HITS_KEY = "g_Data_L3AtomicWriteHits"
    L3_ATOMIC_WRITE_MISSES_KEY = "g_Data_L3AtomicWriteMisses"
    L3_LOCK_READ_HITS_KEY = "g_Data_L3LockReadHits"
    L3_LOCK_READ_MISSES_KEY = "g_Data_L3LockReadMisses"
    L3_LOCK_WRITE_HITS_KEY = "g_Data_L3LockWriteHits"
    L3_LOCK_WRITE_MISSES_KEY = "g_Data_L3LockWriteMisses"

    TOTAL_READS_KEY = "g_TotalDataReads"
    TOTAL_WRITES_KEY = "g_TotalDataWrites"
    TOTAL_MEMORY_ACCESSES_KEY = "g_TotalMemoryAccesses"

    TOTAL_ATOMIC_READS_KEY = "g_TotalAtomicReads"
    TOTAL_ATOMIC_WRITES_KEY = "g_TotalAtomicWrites"
    TOTAL_ATOMIC_ACCESSES_KEY = "g_TotalAtomicAccesses"

    TOTAL_LOCK_READS_KEY = "g_TotalLockReads"
    TOTAL_LOCK_WRITES_KEY = "g_TotalLockWrites"
    TOTAL_LOCK_ACCESSES_KEY = "g_TotalLockAccesses"

    EXECUTION_CYCLE_COUNT_KEY = "max_ExecutionDrivenCycleCount"
    # For MESI, exec-driven and bandwidth-driven should be the same
    BANDWIDTH_CYCLE_COUNT_KEY = "max_BandwidthDrivenCycleCount"

    REGION_BOUNDARIES_KEY = "g_RegionBoundaries"
    REGION_WITH_WRITES_KEY = "g_RegionsWithWrites"

    RUNNING_TIME_KEY = "SimulationRunningTimeMins"
    MEMORY_USAGE_KEY = "MemUsageGB"

    TOTAL_EVENTS_KEY = "TotalEvents"
    STACK_ACCESSES_KEY = "StackAccesses"
    REGION_SIZE_KEY = "AverageRegionSize"
    BASIC_BLOCKS_KEY = "BasicBlocks"
    INSTRUCTIONS_KEY = "Instructions"  # We bill one cycle for all instructions


class MESISimKeys(Constants):
    REMOTE_READ_HITS_KEY = "g_MESIReadRemoteHits"
    REMOTE_WRITE_HITS_KEY = "g_MESIWriteRemoteHits"
    UPGRADE_MISSES_KEY = "g_MESIUpgradeMisses"

    # We do not need the bandwidth-variants for this for MESI
    MEM_EXEC_CYCLE_COUNT_KEY = "dep_MESIMemSystemExecDrivenCycleCount"
    RATIO_MEM_EXEC_CYCLE_COUNT_KEY = (
        "ratioMESIMemSystemExecDrivenCycleCount")
    COHERENCE_EXEC_CYCLE_COUNT_KEY = (
        "dep_MESICoherenceExecDrivenCycleCount")
    RATIO_COHERENCE_EXEC_CYCLE_COUNT_KEY = (
        "ratioMESICoherenceExecDrivenCycleCount")


class PauseSimKeys(Constants):
    TOTAL_MEMORY_ACCESSES_SPECIAL_INVALID_KEY = (
        "g_TotalMemoryAccessesSpecialInvalidState")
    WRITE_AFTER_READ_UPGRADES_KEY = "g_ViserWARUpgrades"

    AIM_READ_HITS_KEY = "g_AIMCacheReadHits"
    AIM_READ_MISSES_KEY = "g_AIMCacheReadMisses"
    AIM_WRITE_HITS_KEY = "g_AIMCacheWriteHits"
    AIM_WRITE_MISSES_KEY = "g_AIMCacheWriteMisses"
    AIM_LINE_EVICTIONS_KEY = "g_AIMCacheLineEvictions"

    # bw driven cycles
    # total request restart costs among all cores
    REQUEST_RESTART_BW_CYCLE_COUNT_KEY = "g_BandwidthDrivenCycleCountForRequestRestart"
    REBOOT_BW_CYCLE_COUNT_KEY = ("dep_ViserRebootBWDrivenCycleCount")
    RATIO_REBOOT_BW_CYCLE_COUNT_KEY = ("ratioViserRebootBWDrivenCycleCount")
    REGION_RESTART_BW_CYCLE_COUNT_KEY = (
        "dep_ViserRegionRestartBWDrivenCycleCount")
    RATIO_REGION_RESTART_BW_CYCLE_COUNT_KEY = (
        "ratioViserRegionRestartBWDrivenCycleCount")
    PAUSE_BW_CYCLE_COUNT_KEY = ("dep_ViserPauseBWDrivenCycleCount")
    RATIO_PAUSE_BW_CYCLE_COUNT_KEY = ("ratioViserPauseBWDrivenCycleCount")
    NORMAL_EXEC_BW_CYCLE_COUNT_KEY = "dep_ViserNormalExecBWDrivenCycleCount"
    RATIO_NORMAL_EXEC_BW_CYCLE_COUNT_KEY = (
        "ratioViserNormalExecBWDrivenCycleCount")

    EXCLUDE_REBOOT_BW_CYCLE_COUNT_KEY = "dep_ViserExcludeRebootBWDrivenCycleCount"

    li_bwCyclePhaseKeys = [
        NORMAL_EXEC_BW_CYCLE_COUNT_KEY,
        PAUSE_BW_CYCLE_COUNT_KEY,
        REGION_RESTART_BW_CYCLE_COUNT_KEY,
        REBOOT_BW_CYCLE_COUNT_KEY]

    '''
    REG_EXEC_BW_CYCLE_COUNT_KEY = "dep_ViserRegExecBWDrivenCycleCount"
    RATIO_REG_EXEC_BW_CYCLE_COUNT_KEY = ("ratioViserRegExecBWDrivenCycleCount")
    PRE_COMMIT_BW_CYCLE_COUNT_KEY = ("dep_ViserPreCommitBWDrivenCycleCount")
    RATIO_PRE_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioViserPreCommitBWDrivenCycleCount")
    READ_VALIDATION_BW_CYCLE_COUNT_KEY = (
        "dep_ViserReadValidationBWDrivenCycleCount")
    RATIO_READ_VALIDATION_BW_CYCLE_COUNT_KEY = (
        "ratioViserReadValidationBWDrivenCycleCount")
    POST_COMMIT_BW_CYCLE_COUNT_KEY = ("dep_ViserPostCommitBWDrivenCycleCount")
    RATIO_POST_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioViserPostCommitBWDrivenCycleCount")    
    '''

    NUM_SCAVENGES_KEY = "g_NumScavenges"

    SCAVENGE_TIME_KEY = "ScavengeRunningTimeMins"

    # regions
    CONFLICTED_REGIONS_KEY = "g_RegionsWithTolerableConflicts"
    DEADLOCKED_REGIONS_KEY = "g_RegionsWithPotentialDeadlocks"
    DEADLOCKED_REGIONS_WITH_DIRTY_EVICTION_KEY = (
        "g_RegionHasDirtyEvictionBeforeDL")
    VALIDATION_FAILED_REGIONS_KEY = "g_RegionsWithFRVs"
    VALIDATION_FAILED_REGIONS_WITH_DIRTY_EVICTION_KEY = (
        "g_RegionHasDirtyEvictionBeforeFRV")
    AVOIDABLE_DL_REGION_KEY = "g_RegionHasDLAvoidableByEvictionOpt"
    HAS_LONG_PAUSING_WAITERS_REGION_KEY = "g_RegionsWithLongPausingCores"
    TERM_WAITERS_WO_TIMEOUT_REGION_KEY = "g_RegionsTermPausingCoresWoTimeout"

    # max_core request restarts
    DEP_REQUEST_REPROCESSING_KEY = "dep_RequestRestarts"
    # restarts
    OLD_WHOLE_APP_RESTARTS_KEY = "g_TotalWholeAppRestarts"
    WHOLE_APP_RESTARTS_KEY = "g_TotalReboots"
    REQUEST_REPROCESSING_KEY = "g_TotalRequestRestarts"
    REGION_RESTARTS_KEY = "g_TotalRegionRestarts"

    # conflicts
    VALIDATION_ATTEMPTS_KEY = "g_ValidationAttempts"
    FAILED_VALIDATION_KEY = "g_FailedValidations"
    PRECISE_CONFLICTS_KEY = "g_PreciseConflicts"
    DEAD_LOCKS_KEY = "g_PotentialDeadlocks"

    L2_DIRTY_EVICTION_KEY = "g_DirtyL2Evictions"

    POTENTIAL_CHECK_POINTS = "g_PotentialCheckPoints"
    TOTAL_CHECK_POINTS = "g_CheckPoints"
    CHECK_POINTS_RESTORES = "g_TotalCheckPointRestores"


class ViserSimKeys(Constants):
    TOTAL_MEMORY_ACCESSES_SPECIAL_INVALID_KEY = (
        "g_TotalMemoryAccessesSpecialInvalidState")
    WRITE_AFTER_READ_UPGRADES_KEY = "g_ViserWARUpgrades"

    AIM_READ_HITS_KEY = "g_AIMCacheReadHits"
    AIM_READ_MISSES_KEY = "g_AIMCacheReadMisses"
    AIM_WRITE_HITS_KEY = "g_AIMCacheWriteHits"
    AIM_WRITE_MISSES_KEY = "g_AIMCacheWriteMisses"
    AIM_LINE_EVICTIONS_KEY = "g_AIMCacheLineEvictions"

    REG_EXEC_EXEC_CYCLE_COUNT_KEY = (
        "dep_ViserRegExecExecDrivenCycleCount")
    RATIO_REG_EXEC_EXEC_CYCLE_COUNT_KEY = (
        "ratioViserRegExecExecDrivenCycleCount")
    PRE_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "dep_ViserPreCommitExecDrivenCycleCount")
    RATIO_PRE_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "ratioViserPreCommitExecDrivenCycleCount")
    READ_VALIDATION_EXEC_CYCLE_COUNT_KEY = (
        "dep_ViserReadValidationExecDrivenCycleCount")
    RATIO_READ_VALIDATION_EXEC_CYCLE_COUNT_KEY = (
        "ratioViserReadValidationExecDrivenCycleCount")
    POST_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "dep_ViserPostCommitExecDrivenCycleCount")
    RATIO_POST_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "ratioViserPostCommitExecDrivenCycleCount")

    REG_EXEC_BW_CYCLE_COUNT_KEY = "dep_ViserRegExecBWDrivenCycleCount"
    RATIO_REG_EXEC_BW_CYCLE_COUNT_KEY = (
        "ratioViserRegExecBWDrivenCycleCount")
    PRE_COMMIT_BW_CYCLE_COUNT_KEY = (
        "dep_ViserPreCommitBWDrivenCycleCount")
    RATIO_PRE_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioViserPreCommitBWDrivenCycleCount")
    READ_VALIDATION_BW_CYCLE_COUNT_KEY = (
        "dep_ViserReadValidationBWDrivenCycleCount")
    RATIO_READ_VALIDATION_BW_CYCLE_COUNT_KEY = (
        "ratioViserReadValidationBWDrivenCycleCount")
    POST_COMMIT_BW_CYCLE_COUNT_KEY = (
        "dep_ViserPostCommitBWDrivenCycleCount")
    RATIO_POST_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioViserPostCommitBWDrivenCycleCount")

    VALIDATION_ATTEMPTS_KEY = "g_ValidationAttempts"
    PRECISE_CONFLICTS_KEY = "g_PreciseConflicts"

    NUM_SCAVENGES_KEY = "g_NumScavenges"

    SCAVENGE_TIME_KEY = "ScavengeRunningTimeMins"


class RCCSISimKeys(Constants):
    WRITE_AFTER_READ_UPGRADES_KEY = "g_RCCSIWARUpgrades"

    REG_EXEC_EXEC_CYCLE_COUNT_KEY = (
        "dep_RCCSIRegExecExecDrivenCycleCount")
    RATIO_REG_EXEC_EXEC_CYCLE_COUNT_KEY = (
        "ratioRCCSIRegExecExecDrivenCycleCount")
    PRE_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "dep_RCCSIPreCommitExecDrivenCycleCount")
    RATIO_PRE_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "ratioRCCSIPreCommitExecDrivenCycleCount")
    READ_VALIDATION_EXEC_CYCLE_COUNT_KEY = (
        "dep_RCCSIReadValidationExecDrivenCycleCount")
    RATIO_READ_VALIDATION_EXEC_CYCLE_COUNT_KEY = (
        "ratioRCCSIReadValidationExecDrivenCycleCount")
    STALL_EXEC_CYCLE_COUNT_KEY = ("dep_RCCSIStallExecDrivenCycleCount")
    RATIO_STALL_EXEC_CYCLE_COUNT_KEY = (
        "ratioRCCSIStallExecDrivenCycleCount")
    COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "dep_RCCSICommitExecDrivenCycleCount")
    RATIO_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "ratioRCCSICommitExecDrivenCycleCount")
    POST_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "dep_RCCSIPostCommitExecDrivenCycleCount")
    RATIO_POST_COMMIT_EXEC_CYCLE_COUNT_KEY = (
        "ratioRCCSIPostCommitExecDrivenCycleCount")

    REG_EXEC_BW_CYCLE_COUNT_KEY = "dep_RCCSIRegExecBWDrivenCycleCount"
    RATIO_REG_EXEC_BW_CYCLE_COUNT_KEY = (
        "ratioRCCSIRegExecBWDrivenCycleCount")
    PRE_COMMIT_BW_CYCLE_COUNT_KEY = (
        "dep_RCCSIPreCommitBWDrivenCycleCount")
    RATIO_PRE_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioRCCSIPreCommitBWDrivenCycleCount")
    READ_VALIDATION_BW_CYCLE_COUNT_KEY = (
        "dep_RCCSIReadValidationBWDrivenCycleCount")
    RATIO_READ_VALIDATION_BW_CYCLE_COUNT_KEY = (
        "ratioRCCSIReadValidationBWDrivenCycleCount")
    STALL_BW_CYCLE_COUNT_KEY = ("dep_RCCSIStallBWDrivenCycleCount")
    RATIO_STALL_BW_CYCLE_COUNT_KEY = (
        "ratioRCCSIStallBWDrivenCycleCount")
    COMMIT_BW_CYCLE_COUNT_KEY = (
        "dep_RCCSICommitBWDrivenCycleCount")
    RATIO_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioRCCSICommitBWDrivenCycleCount")
    POST_COMMIT_BW_CYCLE_COUNT_KEY = (
        "dep_RCCSIPostCommitBWDrivenCycleCount")
    RATIO_POST_COMMIT_BW_CYCLE_COUNT_KEY = (
        "ratioRCCSIPostCommitBWDrivenCycleCount")

    PRECISE_CONFLICTS_KEY = "g_PreciseConflicts"
    PRECISE_WRWR_CONFLICTS_KEY = "g_PreciseWrWrConflicts"
    PRECISE_WRRD_CONFLICTS_KEY = "g_PreciseWrRdConflicts"
    PRECISE_RDVAL_CONFLICTS_KEY = "g_PreciseReadValidationConflicts"


class StackedKeys(Constants):

    # This is a mapping of the following form
    # {KEY: [[MESI_keys], [Viser_keys]]
    MESI_OFFSET = 0
    VISER_OFFSET = 1
    RCCSI_OFFSET = 2
    PAUSE_OFFSET = 3

    LEN_VALUES = 4  # MESI, Viser, RCC-SI and PAUSE/RESTART

    # The iteration order is deterministic
    di_stackedKeys = collections.OrderedDict()

    # Exec cycle overheads
    MESI_EXEC_CYCLE_PROP_KEYS = []
    MESI_EXEC_CYCLE_PROP_KEYS.append(
        SimKeys.BANDWIDTH_CYCLE_COUNT_KEY)

    # Bandwidth cycle overheads
    VISER_BW_CYCLE_PROPS_KEY = []
    VISER_BW_CYCLE_PROPS_KEY.append(
        ViserSimKeys.REG_EXEC_BW_CYCLE_COUNT_KEY)
    VISER_BW_CYCLE_PROPS_KEY.append(
        ViserSimKeys.PRE_COMMIT_BW_CYCLE_COUNT_KEY)
    VISER_BW_CYCLE_PROPS_KEY.append(
        ViserSimKeys.READ_VALIDATION_BW_CYCLE_COUNT_KEY)
    VISER_BW_CYCLE_PROPS_KEY.append(
        ViserSimKeys.POST_COMMIT_BW_CYCLE_COUNT_KEY)

    RCCSI_BW_CYCLE_PROP_KEYS = []
    RCCSI_BW_CYCLE_PROP_KEYS.append(
        RCCSISimKeys.REG_EXEC_BW_CYCLE_COUNT_KEY)
    RCCSI_BW_CYCLE_PROP_KEYS.append(
        RCCSISimKeys.PRE_COMMIT_BW_CYCLE_COUNT_KEY)
    RCCSI_BW_CYCLE_PROP_KEYS.append(
        RCCSISimKeys.READ_VALIDATION_BW_CYCLE_COUNT_KEY)
    RCCSI_BW_CYCLE_PROP_KEYS.append(RCCSISimKeys.STALL_BW_CYCLE_COUNT_KEY)
    RCCSI_BW_CYCLE_PROP_KEYS.append(RCCSISimKeys.COMMIT_BW_CYCLE_COUNT_KEY)
    RCCSI_BW_CYCLE_PROP_KEYS.append(
        RCCSISimKeys.POST_COMMIT_BW_CYCLE_COUNT_KEY)

    PAUSE_BW_CYCLE_PROPS_KEY = []
    PAUSE_BW_CYCLE_PROPS_KEY.append(
        PauseSimKeys.NORMAL_EXEC_BW_CYCLE_COUNT_KEY)
    PAUSE_BW_CYCLE_PROPS_KEY.append(
        PauseSimKeys.PAUSE_BW_CYCLE_COUNT_KEY)
    PAUSE_BW_CYCLE_PROPS_KEY.append(
        PauseSimKeys.REGION_RESTART_BW_CYCLE_COUNT_KEY)
    PAUSE_BW_CYCLE_PROPS_KEY.append(
        PauseSimKeys.REBOOT_BW_CYCLE_COUNT_KEY)

    di_stackedKeys[
        SimKeys.BANDWIDTH_CYCLE_COUNT_KEY] = [
        MESI_EXEC_CYCLE_PROP_KEYS,
        VISER_BW_CYCLE_PROPS_KEY,
        RCCSI_BW_CYCLE_PROP_KEYS,
        PAUSE_BW_CYCLE_PROPS_KEY]
