from result.statskeys import PintoolKeys


class Pintool:

    PIN_DEQUEUE = "[DEQUEUE]"

    @staticmethod
    def parseStats(fileName, dic):
        f = open(fileName)  # 'r' is implicit if omitted
        for line in f:
            d = Pintool.__processLine(line)
            dic.update(d)
        return dic

    @staticmethod
    def __processLine(line):
        """Currently we only process DEQUEUE stats, it is supposed to match
    with ENQUEUE stats, otherwise the Pintool should throw an error."""
        d = {}
        if Pintool.PIN_DEQUEUE in line:
            if PintoolKeys.TOTAL_EVENTS in line:
                d[PintoolKeys.TOTAL_EVENTS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.ROI_START in line:
                d[PintoolKeys.ROI_START_KEY] = Pintool.__getValue(line)
            if PintoolKeys.ROI_END in line:
                d[PintoolKeys.ROI_END_KEY] = Pintool.__getValue(line)
            if PintoolKeys.THREAD_BEGIN in line:
                d[PintoolKeys.THREAD_BEGIN_KEY] = Pintool.__getValue(line)
            if PintoolKeys.THREAD_END in line:
                d[PintoolKeys.THREAD_END_KEY] = Pintool.__getValue(line)
            if PintoolKeys.MEMORY_EVENTS in line:
                d[PintoolKeys.MEMORY_EVENTS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.READ_EVENTS in line:
                d[PintoolKeys.READ_EVENTS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.WRITE_EVENTS in line:
                d[PintoolKeys.WRITE_EVENTS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.LOCK_ACQS in line:
                d[PintoolKeys.LOCK_ACQS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.LOCK_RELS in line:
                d[PintoolKeys.LOCK_RELS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.BASIC_BLOCKS in line:
                d[PintoolKeys.BASIC_BLOCKS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.THREAD_SPAWN in line:
                d[PintoolKeys.THREAD_SPAWN_KEY] = Pintool.__getValue(line)
            if PintoolKeys.THREAD_JOIN in line:
                d[PintoolKeys.THREAD_JOIN_KEY] = Pintool.__getValue(line)
            if PintoolKeys.ATOMIC_READS in line:
                d[PintoolKeys.ATOMIC_READS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.ATOMIC_WRITES in line:
                d[PintoolKeys.ATOMIC_WRITES_KEY] = Pintool.__getValue(line)
            if PintoolKeys.LOCK_ACQ_READS in line:
                d[PintoolKeys.LOCK_ACQ_READS_KEY] = Pintool.__getValue(line)
            if PintoolKeys.LOCK_ACQ_WRITES in line:
                d[PintoolKeys.LOCK_ACQ_WRITES_KEY] = Pintool.__getValue(line)
            if PintoolKeys.LOCK_REL_WRITES in line:
                d[PintoolKeys.LOCK_REL_WRITES_KEY] = Pintool.__getValue(line)

        return d

    @staticmethod
    def __getValue(line):
        val = line.split(":")[1].strip()
        return float(val)
