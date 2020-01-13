from option.constants import Constants
from parser.config import Config


class Options(Constants):
    """This class encapsulates all the experimental properties."""

    def __init__(self, tup_cmd):
        self._tup_cmdLine = tuple(i for i in tup_cmd if "sameMachine" not in i)

        self._str_workload = ""
        self._tup_workload = ()

        self._str_tools = ""
        self._tup_tools = ()
        self._tup_simulators = ()

        self._str_tasks = ""
        self._tup_tasks = ()

        self._str_bench = ""
        self._tup_bench = ()

        self.trials = 0
        self.pid = 0
        self.pinThreads = 0
        self.cores = 0
        self.str_output = ""
        self.verbose = 0
        self.normalize = False
        self.roiOnly = False
        self.project = ""
        self.lockstep = False
        self.siteTracking = False
        self.attachPid = False
        self.generateTrace = False
        self.confIndex = -1
        # Allow [parallelBenches] benchmarks to run parallelly
        self.parallelBenches = 1

        self.pinTool = ""

        self.nonMesiSimulators = 0

        self._isMesiPresent = False
        self._isViserPresent = False
        self._isRCCSIPresent = False
        self._isPausePresent = False

        self.config = Config()

    def setOptions(self, di_options):
        self._str_tools = di_options["tools"]
        self._tup_tools = tuple(self._str_tools.split(","))
        # Allow trailing commas in the tools list, having a trailing comma
        # basically creates an empty string in the tools list
        self._tup_tools = list(filter(None, self._tup_tools))
        # We can ignore pintool from the simulators
        self._tup_simulators = [x for x in self._tup_tools if x != "pintool"]

        self._str_tasks = di_options["tasks"]
        self._tup_tasks = tuple(self._str_tasks.split(","))

        self._str_workload = di_options["workload"]
        self._tup_workload = tuple(self._str_workload.split(","))

        self._str_bench = di_options["bench"]
        self._tup_bench = tuple(self._str_bench.split(","))

        self.trials = di_options["trials"]
        self.pinThreads = di_options["pinThreads"]
        self.pid = di_options["pid"]
        self.cores = di_options["cores"]
        self.output = di_options["outputDir"]
        self.verbose = di_options["verbose"]
        self.printOnly = di_options["printOnly"]
        self.jassert = di_options["jassert"]
        self.xassert = di_options["xassert"]
        self.period = di_options["period"]
        self.sameMachine = di_options["sameMachine"]
        self.roiOnly = di_options["roiOnly"]
        self.project = di_options["project"]
        self.lockstep = di_options["lockstep"]
        self.siteTracking = di_options["siteTracking"]
        self.attachPid = di_options["attachPid"]
        self.generateTrace = di_options["generateTrace"]
        self.pinTool = di_options["pinTool"]
        self.confIndex = di_options["confIndex"]
        self.parallelBenches = di_options["parallelBenches"]

        self.nonMesiSimulators = len(self.getSimulatorsTuple())
        for proj in self.getSimulatorsTuple():
            if Constants.isMESIConfig(proj):
                self._isMesiPresent = True
                self.nonMesiSimulators -= 1
            if Constants.isViserConfig(proj):
                self._isViserPresent = True
            if Constants.isRCCSIConfig(proj):
                self._isRCCSIPresent = True
            if Constants.isPauseConfig(proj):
                self._isPausePresent = True

    def printOptions(self):
        print("Tools:", self._str_tools)
        print("Tasks:", self._str_tasks)
        print("Bench:", self._str_bench)
        print("Parallel benches allowed:", self.parallelBenches)
        print("Workload:", self._str_workload)

        print("Pin Threads:", self.pinThreads)
        print("Pid to be attached:", self.pid)
        print("Cores:", self.cores)
        print("Trials:", self.trials)
        print("Output:", self.output)
        print("Verbose:", self.verbose)
        print("Print only:", self.printOnly)
        print("ROI only:", self.roiOnly)
        print("Project:", self.project)

    def getCmdListTuple(self):
        return self._tup_cmdLine

    def getWorkloadTuple(self):
        return self._tup_workload

    def getToolsTuple(self):
        return self._tup_tools

    def getSimulatorsTuple(self):
        return self._tup_simulators

    def getTasksTuple(self):
        return self._tup_tasks

    def getBenchTuple(self):
        return self._tup_bench

    def checkToolDuplicates(self):
        "Check duplicate tools"
        beforeSorting = list(self.getToolsTuple())
        oldLen = len(beforeSorting)

        tmp = list(set(self.getToolsTuple()))
        if oldLen != len(tmp):
            print("[error] duplicate tools exist, please check!")
            assert(False)

    def removeBenchDuplicates(self):
        "Remove duplicate benchmarks"
        beforeSorting = list(self.getBenchTuple())
        oldLen = len(beforeSorting)

        tmp = list(set(self.getBenchTuple()))
        if (oldLen != len(tmp) and self.verbose >= 1):
            print("[warning] removed duplicate benchmark")

        tmp = list(set(self.getBenchTuple()))
        tmp.sort()  # Sorting the list blindly

        # separate server programs from others
        if "httpd" in tmp:
            del tmp[tmp.index("httpd")]
            tmp.append("httpd")
        if "mysqld" in tmp:
            del tmp[tmp.index("mysqld")]
            tmp.append("mysqld")

        match = [i for i, j in zip(beforeSorting, tmp) if i == j]
        if (len(match) != len(tmp)):
            print("[warning] sorted benchmark list")
            print(tmp)

        self._tup_bench = tuple(tmp)

    def processPintool(self):
        return "pintool" in self._str_tools

    def processMESISim(self):
        return "mesi" in self._str_tools

    def processViserSim(self):
        return (not("viseroptregularplru" in self._str_tools)
                and "viser" in self._str_tools)
        # RZ: treat viseroptregularplru as a pause/restart config because I want
        # to generate graphs for its (estimated) costs of restarting a whole
        # program at exceptions.

    def processRCCSISim(self):
        return "rccsi" in self._str_tools

    def processPauseSim(self):
        return ("pause" in self._str_tools or "restart" in self._str_tools
                or "viseroptregularplru" in self._str_tools)

    def getExpOutputDir(self):
        return (Options.EXP_OUTPUT + self.output)

    def getExpProductsDir(self):
        return (Options.EXP_PRODUCTS + self.output)

    def getExpCommand(self):
        """Return the EXP command line."""
        tup_cmd = []
        # Leave out the script name
        for i in range(1, len(self.getCmdListTuple())):
            tup_cmd.append(self.getCmdListTuple()[i])
        str_cmdLine = ""
        if self.sameMachine:
            str_cmdLine = str_cmdLine + Options.VISER_EXP_LOCAL
        else:
            str_cmdLine = str_cmdLine + Options.VISER_EXP_REMOTE
        str_cmdLine = str_cmdLine + " " + ' '.join(tup_cmd)
        return str_cmdLine

    def createRerunFile(self):
        """Create a "rerun" command history file."""
        rerun = open("rerun", "w")
        rerun.write(Options.BASH_SHEBANG + "\n\n")
        str_cmdLine = self.getExpCommand()
        rerun.write(str_cmdLine)
        rerun.close()

    def getProject(self):
        return self.project

    def isMESIPresent(self):
        "Is a MESI config present in this experiment?"
        return self._isMesiPresent

    def isViserPresent(self):
        "Is a Viser config present in this experiment?"
        return self._isViserPresent

    def isRCCSIPresent(self):
        "Is RCC-SI config present in this experiment?"
        return self._isRCCSIPresent

    def isPausePresent(self):
        "Is a RCC w/ pause config present in this experiment?"
        return self._isPausePresent

    def isServerPresent(self):
        return "httpd" in self.getBenchTuple() or "mysqld" in self.getBenchTuple()
