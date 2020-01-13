import argparse
import ast

from option.benchmarks import Benchmark
from option.constants import Constants
from option.projects import Project


class CmdLine:
    """Helper class to parser command line arguments and perform limited
sanity checks."""

    allowedTasks = [
        "clean",  # Kill existing related processes that are running
        "sync",  # Sync sources from the source machine
        "build",
        "run",
        "result",
        "email",
        "all",
        # "copy",
    ]
    allowedSizes = [
        "test",
        "simdev",
        "simsmall",
        "simmedium",
        "simlarge",
        "native"
    ]
    allowedTools = [
        "pintool",
        "mesi",
        "mesi16",
        "mesi32",  # 32-way LLC
        "mesi-ce",
        "mesi-ce16",
        "mesi-ce32",  # 32-way LLC
        "mesiregularplru",
        "mesiregularplru16",
        "mesiregularplru32",
        "viserunopt",
        "viserreadonlyopt",
        "viserlastwriteropt",
        "viserupdatewrites",
        "viseruntouchedlinesopt",
        "viserspecialinvalid",
        "viserbloomfilter",
        "viserbloominvalid",
        "viseronebloom",
        "viserselfinvalidationopt",
        "viserdeferwritebacks",
        "viserdeferwritebacksprecise",
        "viserskipvalidatingreadlines",
        "viserignorefetchingreadbits",
        "viseropt",
        "viseroptnoatomicupdates",
        "viseroptignorefetchwritebits",
        "viseroptnodefer",
        "viseroptprecisedefer",
        "viseropt-8Kaim",
        "viseropt-16Kaim",
        "viseropt16",
        "viseropt16-16Kaim",
        "viseropt32",  # 32-way LLC
        "viseropt32-16Kaim",
        "viseroptidealaim",
        "viseropt16idealaim",
        "viseropt32idealaim",
        "viseroptclearaimatregionboundaries",
        "viserignoredeferredlinesduringreadvalidation",
        "viseruserfrs",
        "viseroptevictnonwarlinefirst",
        "viseroptevictcleanlinefirst",
        "viseroptlru",
        "viseroptregularplru",
        "viseroptregularplru16",
        "viseroptregularplru32",
        "viseroptregularplru32cp1k",
        "viseroptregularplru32cp100",
        "viseroptregularplru32cp10",
        "viseroptregularplru32cp1",
        "viseroptmodifiedplru",
        "viserplruatomicasboundary",
        "viserplruatomicasregular",
        "rccsiunopt",
        "rccsiunoptclearreadersfromllc",
        "rccsiusereadonlylineopt",
        "rccsiuselastwriteropt",
        "rccsiusebloomfilter",
        "rccsideferwritebacks",
        "rccsiskipreadvalidation",
        "rccsiopt",
        "rccsiopt16",
        "rccsiopt32",
        "pause",
        "pause16",
        "pause32",
        "restart",
        "restartwaitatreads",
        "restartevictcleanlinesfirst",
        "restartunopt",
        "restartunopt16",
        "restartunopt32",
        "pseudorestartunopt",
        "pseudorestartunopt16",
        "restartopt",
        "restartopt16",
        "restartopt32",
        "pseudorestartopt",
        "pseudorestartopt16",
        "pseudorestartopt32",
        "restartunopt4assoc",
        "restartopt4assoc",
        "restartunopt2assoc",
        "restartopt2assoc",
        "restartunopt16assoc",
        "restartopt16assoc",
        "ce",
        "cepause",
        "cerestartunopt",
        "cerestartopt",
        "ce16",
        "cepause16",
        "cerestartunopt16",
        "cerestartopt16",
        "ce32",
        "cepause32",
        "cerestartunopt32",
        "cerestartopt32",
        "pseudocerestartopt",
        "pseudocerestartopt16",
        "pseudocerestartopt32"
    ]

    def __init__(self):
        self.parser = argparse.ArgumentParser(
            description='Command line options for running experiments',
            conflict_handler='error',
            # allow_abbrev=False
        )
        # Hack to get rid of main.py from the help message
        self.parser.prog = "arc"

        self.parser.add_argument("--tools", help="tools to run", required=True)
        self.parser.add_argument("--tasks", help="tasks to execute",
                                 default="build")
        self.parser.add_argument("--workload", help="workload size",
                                 default="simsmall",
                                 choices=["test", "simdev", "simsmall",
                                          "simmedium", "simlarge", "native"])
        self.parser.add_argument("--trials", help="number of trials",
                                 type=int, default=1)
        self.parser.add_argument("--bench", help="list of benchmarks, or all,"
                                 " or none", default="none")
        self.parser.add_argument("--pinThreads",
                                 help="number of PARSEC benchmark threads",
                                 type=int, default=8)
        self.parser.add_argument("--pid",
                                 help="the pid of application process to be \
                                 attatched",
                                 type=int, default=0)
        self.parser.add_argument("--cores",
                                 help="number of cores in the simulator",
                                 type=int, default=8)
        self.parser.add_argument("--outputDir",
                                 help="output directory relative to"
                                 " ~/exp-output", default="viser-temp")
        self.parser.add_argument("--verbose", help="verbosity level",
                                 default=1, type=int)
        self.parser.add_argument("--printOnly", help="just print the "
                                 "constructed commands, will not execute",
                                 default=False, choices=[False, True],
                                 # type bool causes problems
                                 type=ast.literal_eval)
        self.parser.add_argument("--assert", help="enable running Java "
                                 "asserts in the backend simulator(s)",
                                 default=False, type=ast.literal_eval,
                                 choices=[False, True], required=True,
                                 dest="jassert")
        self.parser.add_argument("--xassert", help="enable running xasserts "
                                 "in the backend simulator(s)", default=False,
                                 type=ast.literal_eval, choices=[False, True],
                                 required=True)
        self.parser.add_argument("--period", help="run xasserts periodically",
                                 type=int, default=1)
        self.parser.add_argument("--roiOnly", help="Should simulation be "
                                 "limited only to the ROI?", default=True,
                                 choices=[False, True], type=ast.literal_eval)
        # Is the framework is running on the SOURCE machine?
        self.parser.add_argument("--sameMachine", help=argparse.SUPPRESS,
                                 type=ast.literal_eval, choices=[False, True],
                                 required=True)
        self.parser.add_argument("--project", help="project name",
                                 default="none")
        self.parser.add_argument("--lockstep",
                                 help="execute the Pintool and the backend in "
                                 "lockstep", default=False,
                                 choices=[False, True],
                                 type=ast.literal_eval)
        self.parser.add_argument("--siteTracking",
                                 help="track site info for each event ",
                                 default=False, choices=[False, True],
                                 type=ast.literal_eval)
        self.parser.add_argument("--attachPid",
                                 help="apply Pin by attaching it to an already"
                                 " running process", default=False,
                                 choices=[False, True],
                                 type=ast.literal_eval)
        self.parser.add_argument("--generateTrace",
                                 help="generate a trace file mostly for "
                                 "debugging purposes",
                                 default=False,
                                 choices=[False, True],
                                 type=ast.literal_eval)
        self.parser.add_argument("--pinTool",
                                 help="choose a pinTool to use (viser or viserST)",
                                 default="viser",
                                 choices=["viser", "viserST"])
        self.parser.add_argument("--confIndex", help=" the index of the conflicting"
                                 " sites to validate with collision analysis ",
                                 type=int, default=-1)
        self.parser.add_argument(
            "--parallelBenches",
            help=" the number (>=0) of the benchmarks"
            " allowed to run parallelly ",
            type=int,
            default=1)

    def parse(self, options):
        # Check if environment variables are properly defined

        di_options = vars(self.parser.parse_args())
        options.setOptions(di_options)
        if options.verbose >= 2:
            options.printOptions()

        # Sanity checks
        if not Project.isSupportedProject(options.getProject()):
            Constants.raiseError(False,
                                 "Invalid project: ",
                                 options.getProject())

        for t in options.getTasksTuple():
            # Allow an empty task, e.g., "clean,"
            if t not in CmdLine.allowedTasks and len(t) > 0:
                Constants.raiseError(False, "Invalid task: ", t)

        for t in options.getToolsTuple():
            if t not in CmdLine.allowedTools:
                Constants.raiseError(False, "Invalid tool: ", t)

        options.checkToolDuplicates()

        options.removeBenchDuplicates()

        for b in options.getBenchTuple():
            if (not Benchmark.isHTTPDBenchmark(b) and
                    not Benchmark.isParsecBenchmark(b) and
                    not Benchmark.isSplash2xBenchmark(b)):
                Constants.raiseError(False, "Invalid bench: ", b)

        if options.parallelBenches > len(options.getBenchTuple()) or options.parallelBenches < 1:
            Constants.raiseError(True, "Invalid parallelBenches (should be within [1,benchNum]): ", str(
                options.parallelBenches))

        for w in options.getWorkloadTuple():
            if w not in CmdLine.allowedSizes:
                Constants.raiseError(False, "Invalid workload size: ", w)

        # if "run" is there in "tasks", then "tools" should have pintool and
        # at least one simulator
        if "run" in options.getTasksTuple():
            if len(options.getSimulatorsTuple()) > 0:
                if "pintool" not in options.getToolsTuple():
                    Constants.raiseError(False, ("The Pintool frontend is  "
                                                 "required to run the backend "
                                                 "simulators."))

        # "Result" task requires bench and trials option
        if "result" in options.getTasksTuple():
            if len(options.getBenchTuple()) == 0:
                Constants.raiseError(False, "No benchmark specified.")
            if options.trials == 0:
                Constants.raiseError(False, "Number of trials unspecified.")
            if len(options.getWorkloadTuple()) == 0:
                Constants.raiseError(False, "No workload size specified.")

        # Limited safety check for matching cores and configurations
        if "run" in options.getTasksTuple():
            if options.pinThreads == 16 or options.pinThreads == 32:
                for t in options.getToolsTuple():
                    if not Constants.isPintool(t):
                        if str(options.pinThreads) not in t:
                            Constants.raiseError(False,
                                                 ("Check tool and threads"
                                                  "  combination: "),
                                                 t, str(options.pinThreads))
#            # SB: Need to polish this more
#             for t in options.getToolsTuple():
#                 if (("16" in t and options.pinThreads != 16) or
#                         ("32" in t and options.pinThreads != 32)):
#                     Constants.raiseError(False,
#                                          ("Check tool and threads"
#                                           " combination: "),
#                                          t, str(options.pinThreads))

        # Lockstep execution only makes sense if there is at least one backend
        # along with the pintool
        if options.lockstep:
            if len(options.getSimulatorsTuple()) == 0:
                Constants.raiseError(False, ("Lockstep execution only makes "
                                             "sense if there is at least one "
                                             "backend."))
