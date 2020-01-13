from option.constants import Constants


class ResultSet:
    '''
    Helper functions.
    '''

    @staticmethod
    def limitResultSetWithKey(resultsSet, key):
        """Limit the incoming results set (which is a list of dictionaries) to a
        sublist of dictionaries that have the key. """
        tmp = []
        for dic in resultsSet:
            if key in dic:
                tmp.append(dic)
        return tmp

    @staticmethod
    def limitResultSetWithDict(rs, di_val):
        """Limit incoming list of dictionaries rs to only those entries that
        contain the given dictionary di_val."""
        li_tmp = []
        for d in rs:
            if all(item in d.items() for item in di_val.items()):
                li_tmp.append(d.copy())
        return li_tmp

    @staticmethod
    def limitToPintoolResults(rs):
        """Restrict complete result set to only contain dictionaries for
        simulator entries."""
        li_simRS = []
        for di_Result in rs:
            tool = di_Result.get("tool")
            if Constants.isPintool(tool):
                li_simRS.append(di_Result)
        return li_simRS

    @staticmethod
    def limitToSimulatorResults(rs):
        """Restrict complete result set to only contain dictionaries for
        simulator entries."""
        li_simRS = []
        for di_Result in rs:
            tool = di_Result.get("tool")
            if Constants.isSimulatorConfig(tool):
                li_simRS.append(di_Result)
        return li_simRS

    # TODO: Restrict to only simulator keys
    @staticmethod
    def getAllKeys(rs):
        """Take the union of keys across all benchmarks and simulators."""
        return list(set().union(*(d.keys() for d in rs)))

    @staticmethod
    def inflateResultSetWithKeys(rs, li_keys):
        """Make sure that every entry in the result set has every pair in
        keys."""
        for di_Result in rs:
            for key in li_keys:
                if key not in di_Result:
                    di_Result[key] = 0.0
        return rs
