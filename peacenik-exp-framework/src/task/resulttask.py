from option.constants import Constants
from task.collecttask import CollectTask
from task.producttask import ProductTask
from task.synctask import SyncTask


class ResultTask(Constants):
    """Collect results and generate plots."""

    @staticmethod
    def __outputPrefix():
        return "[result] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print("\n" + ResultTask.__outputPrefix() +
                  "Executing result task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(ResultTask.__outputPrefix() +
                  "Done executing result task...\n")

    @staticmethod
    def resultTask(options):
        """Results are generated in the same machine where the simulators were
        executed."""
        ResultTask.__printTaskInfoStart(options)
        # resultsSet contains all parsed results
        resultsSet = CollectTask.collectTask(options)
        ProductTask.productTask(options, resultsSet)

        # if sameMachine is False, then copy the output and the products
        # directory to the source machine specified in config.ini
        if not options.sameMachine:
            if ("run" not in options.getTasksTuple() or
                    "all" not in options.getTasksTuple()):
                SyncTask.syncOutputDir(options)
            SyncTask.syncProductsDir(options)

        ResultTask.__printTaskInfoEnd(options)
