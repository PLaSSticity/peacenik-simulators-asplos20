from option.constants import Constants
from result.result import Result
from result.resultset import ResultSet
from task.collecttask import CollectTask


class ProductTask(Constants):

    @staticmethod
    def __outputPrefix():
        return "[product] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print("\n" + ProductTask.__outputPrefix() +
                  "Executing product task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(ProductTask.__outputPrefix() +
                  "Done executing product task...\n")

    @staticmethod
    def productTask(options, resultsSet):
        ProductTask.__printTaskInfoStart(options)
        # Copy resultsSet so as to have a backup
        workingRS = resultsSet.copy()
        # print(workingRS)
        # Inflate the result set so that each simulator config result includes
        # all the stats keys
        pintoolRS = ResultSet.limitToPintoolResults(workingRS)
        simRS = ResultSet.limitToSimulatorResults(workingRS)
        li_allKeys = ResultSet.getAllKeys(simRS)
        simRS = ResultSet.inflateResultSetWithKeys(simRS, li_allKeys)
        inflatedRS = []
        inflatedRS.extend(pintoolRS)
        inflatedRS.extend(simRS)
        result = Result(options)
        result.generateResult(inflatedRS)
        ProductTask.__printTaskInfoEnd(options)
