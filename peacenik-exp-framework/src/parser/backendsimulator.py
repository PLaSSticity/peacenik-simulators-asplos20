import ast
from option.constants import Constants


class BackendSimulator(Constants):

    CPUID_KEY = "cpuid"
    GLOBAL_CPUID_VAL = -1

    @staticmethod
    def parseStats(fileName, di_store):
        try:
            f = open(fileName)  # 'r' is implicit if omitted
            for line in f:
                d = BackendSimulator.__processLine(line)
                di_store.update(d)
        except Exception as e:
            # Does not catch all exceptions
            # http://stackoverflow.com/questions/18982610/difference-between-except-and-except-exception-as-e-in-python
            print(fileName)
            print(line)
            Constants.raiseError(True, repr(e))
        return di_store

    @staticmethod
    def __processLine(line):
        """We only process the summed up stats, with cpuid -1, for per-core
    stats. Otherwise, we always process global stats."""
        d = {}
        line = line.strip()
        # We have added histograms to the output file for Viser
        if not line.startswith("#"):
            tmp = ast.literal_eval(line)
            if BackendSimulator.CPUID_KEY in tmp:
                val = tmp[BackendSimulator.CPUID_KEY]
                if val == -1:
                    d = tmp
            else:  # Should be a global stat
                d = tmp
        return d
