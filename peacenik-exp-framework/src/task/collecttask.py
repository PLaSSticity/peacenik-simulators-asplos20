from enum import Enum
import os.path

from option import util
from option.constants import Constants
from parser.backendsimulator import BackendSimulator
from parser.pintool import Pintool
from task.runtask import RunTask
from result.statskeys import SimKeys, ViserSimKeys, PauseSimKeys
import sys


class SimulatorType(Enum):
    MESI = 1
    VISER = 2
    RCCSI = 3
    PAUSE = 4


class CollectTask(Constants):

    @staticmethod
    def __outputPrefix():
        return "[collect] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print("\n" + CollectTask.__outputPrefix() +
                  "Executing collect task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(CollectTask.__outputPrefix() +
                  "Done executing collect task...\n")

    @staticmethod
    def collectTask(options):
        CollectTask.__printTaskInfoStart(options)

        cwd = os.getcwd()
        os.chdir(options.getExpOutputDir())
        resSet = []  # list of dictionaries

        try:
            workloadTuple = options.getWorkloadTuple()
            benchTuple = options.getBenchTuple()
            for w in workloadTuple:
                for num in range(1, options.trials + 1):
                    for b in tuple(benchTuple):
                        path = RunTask.getPathPortion(b, w, num)
                        if not os.path.exists(path):
                            Constants.raiseError(False,
                                                 ("Output file path not "
                                                  "present: "),
                                                 options.getExpOutputDir() +
                                                 CollectTask.FILE_SEP + path)

                        dic = {}
                        # Create key/value pairs and add to the dict
                        dic["bench"] = b
                        dic["trial"] = str(num)
                        dic["workload"] = w
                        for tool in options.getToolsTuple():
                            resSet = CollectTask.__collectResult(path,
                                                                 tool,
                                                                 dic,
                                                                 resSet)
        finally:
            os.chdir(cwd)
            CollectTask.__printTaskInfoEnd(options)
            return resSet

    @staticmethod
    def __collectResult(path, tool, dic, resultsSet):
        if Constants.isPintool(tool):
            pinDic = dic.copy()
            pinDic["tool"] = tool
            pinDic = CollectTask.__processPintoolOutput(path, tool, pinDic)
            resultsSet.append(pinDic)

        elif (Constants.isSimulatorConfig(tool)):
            simDic = dic.copy()
            simDic["tool"] = tool
            if Constants.isMESIConfig(tool):
                configType = SimulatorType.MESI
            elif Constants.isViserConfig(tool):
                configType = SimulatorType.VISER
            elif Constants.isRCCSIConfig(tool):
                configType = SimulatorType.RCCSI
            elif Constants.isPauseConfig(tool):
                configType = SimulatorType.PAUSE
            simDic = CollectTask.__processSimOutput(path, tool, simDic,
                                                    configType)
            resultsSet.append(simDic)
        else:
            Constants.raiseError(True,
                                 "Illeagal tool: ",
                                 tool)

        return resultsSet

    @staticmethod
    def __processPintoolOutput(path, tool, di_stats):
        """Parse the given output file and populate and return di_stats."""
        _str_fileName = path + CollectTask.FILE_SEP + tool + "-stats.output"
        if not os.path.isfile(_str_fileName):
            Constants.raiseError(False,
                                 "Pintool stats file not present: ",
                                 _str_fileName)
        di_stats = Pintool.parseStats(_str_fileName, di_stats)
        return di_stats

    @staticmethod
    def __processSimOutput(path, tool, di_stats, simType):
        _str_fileName = path + CollectTask.FILE_SEP + tool + "-stats.py"
        if not os.path.isfile(_str_fileName):
            configType = ""
            if simType == SimulatorType.MESI:
                configType = "MESI"
            elif simType == SimulatorType.VISER:
                configType = "Viser"
            elif simType == SimulatorType.RCCSI:
                configType = "RCC-SI"
            elif simType == SimulatorType.PAUSE:
                configType = "Pause"
            Constants.raiseError(False,
                                 configType +
                                 " simulator stats file not present:",
                                 _str_fileName)

        di_stats = BackendSimulator.parseStats(_str_fileName, di_stats)

        # compute bw cycles and on-chip traffic exclu. reboots
        if simType == SimulatorType.PAUSE:
            di_stats[PauseSimKeys.EXCLUDE_REBOOT_BW_CYCLE_COUNT_KEY] = (di_stats[SimKeys.BANDWIDTH_CYCLE_COUNT_KEY]
                                                                        - di_stats[PauseSimKeys.REBOOT_BW_CYCLE_COUNT_KEY])
        elif simType == SimulatorType.MESI:
            di_stats[PauseSimKeys.EXCLUDE_REBOOT_BW_CYCLE_COUNT_KEY] = di_stats[SimKeys.BANDWIDTH_CYCLE_COUNT_KEY]
        else:
            Constants.raiseError(
                False, " invalid simType for Peacenik exps:", simType)
        return di_stats
