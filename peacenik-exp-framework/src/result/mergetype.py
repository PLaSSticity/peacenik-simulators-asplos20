from enum import Enum
import numpy
import scipy.stats

from option.constants import Constants


class MergeType(Enum):
    MERGE_MAX = 1
    MERGE_MIN = 2
    MERGE_AVG = 3
    MERGE_MED = 4
    MERGE_GEOMEAN = 5


class Merge(Constants):

    @staticmethod
    def __mergeAverage(rs, key):
        """Merge the result set, and return the average."""
        fl_sum = 0.0
        tmp = {}
        try:
            for d in rs:
                fl_sum += d.get(key)
            tmp[key] = fl_sum / len(rs)
        except (ZeroDivisionError, TypeError) as e:
            Constants.raiseError(True, repr(e) + ",", "Key:", key)
        return tmp

    @staticmethod
    def __mergeMedian(rs, key):
        """Merge the result set, and return the median."""
        lst = []
        for d in rs:
            lst.append(d.get(key))
        tmp = {}
        tmp[key] = numpy.median(numpy.array(lst))
        return tmp

    # LATER: One option is to ignore zero values and then compute geomean for
    # the rest.
    @staticmethod
    def __mergeGeoMean(rs, key):
        """Merge the result set, and return the geomean."""
        b_zeroFound = False
        lst = []
        for d in rs:
            if d.get(key) == 0.0:
                b_zeroFound = True
                break
            lst.append(d.get(key))
        tmp = {key: 0.0}
        if not b_zeroFound:
            tmp[key] = scipy.stats.gmean(numpy.array(lst))
        return tmp

    @staticmethod
    def __mergeMin(rs, key):
        """Merge the result set, and return the min."""
        lst = []
        for d in rs:
            lst.append(d.get(key))
        tmp = {}
        tmp[key] = min(lst)
        return tmp

    @staticmethod
    def __mergeMax(rs, key):
        """Merge the result set, and return the max."""
        lst = []
        for d in rs:
            lst.append(d.get(key))
        tmp = {}
        tmp[key] = max(lst)
        return tmp

    @staticmethod
    def merge(rs, key, mergeType=MergeType.MERGE_AVG):
        """There should be less non-determinism in results from running the
            simulators, unlike Jikes. Hence, it is okay to use average.
        Returns a dictionary object."""
        if mergeType == MergeType.MERGE_AVG:
            return Merge.__mergeAverage(rs, key)
        elif mergeType == MergeType.MERGE_MED:
            return Merge.__mergeMedian(rs, key)
        elif mergeType == MergeType.MERGE_GEOMEAN:
            return Merge.__mergeGeoMean(rs, key)
        elif mergeType == MergeType.MERGE_MIN:
            return Merge.__mergeMin(rs, key)
        elif mergeType == MergeType.MERGE_MAX:
            return Merge.__mergeMax(rs, key)
        else:
            raise(ValueError)
