import os
from subprocess import call
import subprocess
import time

from option.benchmarks import Benchmark
from option.constants import Constants
from option.conflicts import Conflicts
from task.synctask import SyncTask


class RunTask(Constants):
    """Run the tools."""

    tasksTuple = ()
    toolsTuple = ()
    workloadTuple = ()

    pinIDsList = []
    mesiIDsList = []
    viserIDsList = []
    pauseIDsList = []
    rccsiIDsList = []

    httpdID = 0
    httpClientIDsList = []  # list for client ids

    TIMEOUT = 15  # Because I am impatient
    PINTOOL_ROOT = ""

    @staticmethod
    def createExpOutputDir(options):
        outputDir = options.getExpOutputDir()
        if not os.path.exists(outputDir):
            os.makedirs(outputDir)

    @staticmethod
    def __prepareOutputDirs(options):
        '''This method creates the output directories beforehand based on
        input options.'''
        cwd = os.getcwd()
        os.chdir(options.getExpOutputDir())

        try:
            for w in RunTask.workloadTuple:
                for num in range(1, options.trials + 1):
                    for b in RunTask.benchTuple:
                        path = RunTask.getPathPortion(b, w, num)
                        if not os.path.exists(path):
                            os.makedirs(path)
        finally:
            os.chdir(cwd)

    @staticmethod
    def __outputPrefix():
        return "[run] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print(RunTask.__outputPrefix() + "Executing run task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(RunTask.__outputPrefix() + "Done executing run task...")

    @staticmethod
    def runTask(options):
        RunTask.__printTaskInfoStart(options)

        try:
            RunTask.toolsTuple = options.getToolsTuple()
            RunTask.workloadTuple = options.getWorkloadTuple()
            RunTask.benchTuple = options.getBenchTuple()
            cwd = os.getcwd()

            # Setup root experimental output directory
            RunTask.createExpOutputDir(options)
            RunTask.__prepareOutputDirs(options)
            RunTask.__createRerunFile(options)

            RunTask.PINTOOL_ROOT = (RunTask.ST_PINTOOL_ROOT) if (
                options.pinTool == "viserST") else (RunTask.VS_PINTOOL_ROOT)

            os.chdir(RunTask.PINTOOL_ROOT)

            for w in RunTask.workloadTuple:
                for num in range(1, options.trials + 1):
                    pausePresent = False
                    mesiPresent = False
                    viserPresent = False
                    rccsiPresent = False
                    for proj in options.getSimulatorsTuple():
                        if Constants.isMESIConfig(proj):
                            mesiPresent = True
                        if Constants.isViserConfig(proj):
                            viserPresent = True
                        if Constants.isRCCSIConfig(proj):
                            rccsiPresent = True
                        if Constants.isPauseConfig(proj):
                            pausePresent = True

                    writeFifo = False
                    backendSimPresent = False
                    if (mesiPresent or viserPresent or rccsiPresent
                            or pausePresent):
                        writeFifo = True
                        backendSimPresent = True
                    elif options.generateTrace:
                        writeFifo = True

                    if options.confIndex >= 0:
                        # Do collision analysis
                        assert (not writeFifo and not backendSimPresent)

                    benchNum = len(RunTask.benchTuple)
                    for bStart in range(0, benchNum, options.parallelBenches):
                        bEnd = (bStart + options.parallelBenches) if (
                            bStart + options.parallelBenches <=
                            benchNum) else benchNum

                        # Clear lists of processIDs
                        RunTask.pinIDsList = []
                        RunTask.mesiIDsList = []
                        RunTask.viserIDsList = []
                        RunTask.pauseIDsList = []
                        RunTask.rccsiIDsList = []

                        benchmarks = RunTask.benchTuple[bStart:bEnd]
                        print(RunTask.__outputPrefix() +
                              "Benchmarks to run parallelly: " +
                              ",".join(benchmarks))

                        for b in RunTask.benchTuple[bStart:bEnd]:
                            # Hack for vips, which is missing the ROI
                            # annotation in PARSEC 3.0 beta
                            if b == "vips" and options.roiOnly:
                                print(RunTask.__outputPrefix() + "*WARNING*: "
                                      "vips 3.0-beta is missing ROI "
                                      "annotation, resetting ROI flag.")

                            # Setup output directory for this current trial
                            for tool in RunTask.toolsTuple:
                                if Constants.isPintool(tool):
                                    if not options.generateTrace:
                                        RunTask.__setupFifos(
                                            options, backendSimPresent, b)
                                        time.sleep(1)

                                    if (Benchmark.isHTTPDBenchmark(b)):
                                        if (options.attachPid):
                                            RunTask.__startServer(options, b)

                                    RunTask.__startPintool(
                                        options, b, w, num, writeFifo,
                                        backendSimPresent)
                                    time.sleep(1)
                                    if backendSimPresent and not options.generateTrace:
                                        RunTask.__forkPipes(
                                            options, backendSimPresent, b)
                                        time.sleep(1)

                                if not options.generateTrace:
                                    if Constants.isMESIConfig(tool):
                                        RunTask.__startMesiSimulator(
                                            options, b, w, num, tool)

                                    if Constants.isViserConfig(tool):
                                        RunTask.__startViserSimulator(
                                            options, b, w, num, tool)

                                    if Constants.isRCCSIConfig(tool):
                                        RunTask.__startRCCSISimulator(
                                            options, b, w, num, tool)

                                    if Constants.isPauseConfig(tool):
                                        RunTask.__startPauseSimulator(
                                            options, b, w, num, tool)

                            if not options.printOnly:
                                if b == "mysqld":
                                    if (not options.attachPid):
                                        # wait for the server program to start completely
                                        if backendSimPresent:
                                            time.sleep(18000)
                                        else:
                                            time.sleep(2700)
                                elif b == "httpd":
                                    if (not options.attachPid):
                                        # wait for the server program to start completely
                                        if backendSimPresent:
                                            time.sleep(300)
                                        else:
                                            time.sleep(60)
                                elif b == "memcached":
                                    if (not options.attachPid):
                                        # wait for the server program to start completely
                                        if backendSimPresent:
                                            time.sleep(300)
                                        else:
                                            time.sleep(5)

                                if Benchmark.isHTTPDBenchmark(b):
                                    time.sleep(1)
                                    RunTask.__startClients(options, w, b)
                                    # Check if all clients have terminated
                                    while not RunTask.__checkClients(options):
                                        time.sleep(RunTask.TIMEOUT)
                                    RunTask.__stopServer(options, b)

                        if not options.printOnly:
                            # Check if all the processes have terminated
                            while not RunTask.__isTerminated(options):
                                time.sleep(RunTask.TIMEOUT)

                        print(RunTask.__outputPrefix() + "Done running " +
                              str(RunTask.benchTuple[bStart:bEnd]))

        finally:
            if not options.generateTrace:
                RunTask.__cleanFifos(options, backendSimPresent)

            # Switch back to the start directory
            os.chdir(cwd)

            # if sameMachine is False, then copy the output
            # directory to the source machine specified in config.ini
            if not options.sameMachine:
                SyncTask.syncOutputDir(options)

            RunTask.__printTaskInfoEnd(options)


    @staticmethod
    def __setupFifos(options, backendSimPresent, bench):
        cmdLine = "rm -f " + bench + "." + RunTask.FIFO_PREFIX + "*; "
        cmdLine += "rm -f " + RunTask.FIFO_PREFIX + "*" + bench + "; ";
        cmdLine += ("mkfifo " + bench + "." + RunTask.FIFO_FRONTEND + "; ")
        if backendSimPresent:
            for tool in options.getSimulatorsTuple():
                cmdLine += ("mkfifo " + bench + "." + RunTask.FIFO_PREFIX +
                            tool + "; ")

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            call(cmdLine, shell=True)

        if options.lockstep:
            # SB: We could have done this from Python as well instead of calling a
            # C++ application
            cmdLine = "./namedpipe " + str(5 * options.pinThreads) + " " + bench
            if options.verbose >= 2 or options.printOnly:
                print(RunTask.__outputPrefix() + cmdLine)
            if not options.printOnly:
                subprocess.Popen(cmdLine, shell=True)
                
    @staticmethod
    def __cleanFifos(options, backendSimPresent):
        #   cmdLine = ("rm -f " + RunTask.FIFO_FRONTEND + "; ")

        #   if backendSimPresent:
        #        for tool in options.getSimulatorsTuple():
        #            cmdLine += ("rm -f " + RunTask.FIFO_PREFIX + tool + "; ")
        #
        #       for i in range(5 * options.pinThreads):
        #           cmdLine += ("rm -f " + RunTask.FIFO_PERTHREAD + str(i)
        #                     + "; ")

        cmdLine = "rm -rf *." + RunTask.FIFO_PREFIX + "*"
        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            call(cmdLine, shell=True)

    @staticmethod
    def getPathPortion(bench, size, trial):
        return size + RunTask.FILE_SEP + str(trial) + RunTask.FILE_SEP + bench

    @staticmethod
    def getOutputPath(options, bench, size, trial):
        return (options.getExpOutputDir() + RunTask.FILE_SEP +
                RunTask.getPathPortion(bench, size, trial))

    @staticmethod
    def __startPintool(options, bench, size, trial, writeFifo, backendSimPresent):
        if Benchmark.isHTTPDBenchmark(bench):
            if options.attachPid:
                cmdLine = RunTask.PINBIN
                cmdLine += " -pid " + str(RunTask.httpdID) + " "
                cmdLine += "-t " + RunTask.PINTOOL_ROOT + "/obj-intel64/visersim.so"
            else:
                cmdLine = RunTask.PINBIN
                cmdLine += "-t " + RunTask.PINTOOL_ROOT + "/obj-intel64/visersim.so"
        else:
            cmdLine = RunTask.PARSECMGMT
            if Benchmark.isParsecBenchmark(bench):
                cmdLine += bench
            elif Benchmark.isSplash2xBenchmark(bench):
                cmdLine += ("splash2x." + bench)
            else:
                Constants.raiseError(False, "Invalid bench: ", bench)

            cmdLine += (" " + RunTask.PARSEC_ARGS1 + size + " -n " +
                        str(options.pinThreads) + " " + RunTask.PARSEC_ARGS3
                        + RunTask.PINTOOL_ROOT + RunTask.PARSEC_ARGS4)

        statsFile = (RunTask.getOutputPath(options, bench, size, trial) +
                     RunTask.FILE_SEP + "pintool-stats.output")
        cmdLine += " -sim-stats " + statsFile

        if options.siteTracking:
            cmdLine += " -siteTracking 1"
            cmdLine += " -source-names-index-file " + \
                RunTask.PINTOOL_ROOT + "/" + bench + ".filenames"
            cmdLine += " -routine-names-index-file " + \
                RunTask.PINTOOL_ROOT + "/" + bench + ".rtnnames"
            cmdLine += " -trace-text-file " + RunTask.PINTOOL_ROOT + "/eventTrace.txt"

        if options._isPausePresent:
            cmdLine += " -pausing 1"

        if not writeFifo:
            cmdLine += " -write-fifo 0"
        else:
            cmdLine += " -write-fifo 1"
        # We use a different key other than "threads" since benchmarks like
        # x264 uses that word as an argument
        cmdLine += " -pinThreads " + str(options.pinThreads)
        if options.lockstep:
            cmdLine += " -bench " + bench
            cmdLine += " -lockstep 1"
            cmdLine += " -backends " + str(options.nonMesiSimulators)
        else:
            cmdLine += " -lockstep 0"

        if backendSimPresent:
            cmdLine += (" -tosim-fifo " + RunTask.PINTOOL_ROOT + "/" + bench + "." + 
                    RunTask.FIFO_FRONTEND)
                    
        if options.confIndex >= 0:
            # do collision analysis
            cmdLine += " -enable-collisionAnalysis 1"
            tup_conflict = Conflicts.LI_SITES[options.confIndex]
            cmdLine += " -intstLine0 " + str(tup_conflict[0])
            cmdLine += " -usleep0 " + str(tup_conflict[1])
            cmdLine += " -intstLine1 " + str(tup_conflict[2])
            cmdLine += " -usleep1 " + str(tup_conflict[3])           
        else:
            cmdLine += " -enable-collisionAnalysis 0"
                    
        if (not Benchmark.isHTTPDBenchmark(bench)):
            cmdLine += ''' --"'''
        elif (not options.attachPid):
            if bench == "httpd":
                cmdLine += " -- " + RunTask.HTTPD_DEBUG_START
            elif bench == "mysqld":
                cmdLine += (" -- " + RunTask.MYSQLD_START + 
                        RunTask.MYSQLD_CACHED_THREADS + str(options.cores) +
                        RunTask.MYSQLD_INNODB_THREADS + str(options.cores))

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            RunTask.pinIDsList.append(subprocess.Popen(cmdLine, shell=True))

    @staticmethod
    def __forkPipes(options, backendSimPresent, bench):
        cmdLine = "./pipefork " + bench + "." + RunTask.FIFO_FRONTEND

        if backendSimPresent:
            for tool in options.getSimulatorsTuple():
                cmdLine = cmdLine + " " + bench + "." + RunTask.FIFO_PREFIX + tool

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)

        if not options.printOnly:
            subprocess.Popen(cmdLine, shell=True)

    @staticmethod
    def __addJVMArgs(options):
        cmdLine = "java"
        if options.jassert:
            cmdLine += " -enableassertions"  # "-ea"
        return cmdLine

    @staticmethod
    def __addCommonSimulatorArgs(options, bench):
        cmdLine = ""
        enableXasserts = "false"
        if options.xassert:
            enableXasserts = "true"
        cmdLine += (" --cores " + str(options.cores) +
                    " --pinThreads " + str(options.pinThreads) +
                    " --use-l2 true"
                    " --xassert " + enableXasserts + " --assert-period " +
                    str(options.period))
        # Hack for vips, which is missing the ROI annotation in PARSEC 3.0 beta
        # Also hack for httpd, which doesn't have the ROI annotation
        if bench == "vips" or Benchmark.isHTTPDBenchmark(bench):
            cmdLine += " --model-only-roi false"
        else:
            cmdLine += " --model-only-roi " + \
                ("true" if options.roiOnly else "false")

        # Pass the fact that Pintool is configured
        cmdLine += " --pintool true"
        cmdLine += " "
        return cmdLine

    @staticmethod
    def __startMesiSimulator(options, bench, size, trial, tool):
        cmdLine = RunTask.__addJVMArgs(options)
        cmdLine += (RunTask.MESISIM_CLASSPATH + " --tosim-fifo " + bench + "." + 
                    RunTask.FIFO_PREFIX + tool +
                    " --sim-mode baseline")
        cmdLine += RunTask.__addCommonSimulatorArgs(options, bench)
        # Pass whether the backend needs to execute in lockstep with the
        # Pintool
        if options.lockstep and not options._isViserPresent \
                and not options._isPausePresent and not options._isRCCSIPresent:
            cmdLine += " --lockstep true"
        else:
            cmdLine += " --lockstep false"

        statsFile = (RunTask.getOutputPath(options, bench, size, trial) +
                     RunTask.FILE_SEP + tool + "-stats.py")
        cmdLine += " --stats-file " + statsFile
        
        if options.siteTracking:
            cmdLine += " --site-tracking true"
        
        if options._isPausePresent:
            cmdLine += " --with-pacifist-backends true"

        if "mesi" == tool:
            pass

        elif "mesi16" == tool:
            assert options.cores == 16
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024

        elif "mesi32" == tool:
            assert options.cores == 32
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core

#         elif "mesi32-32wayllc" == tool:
#             assert options.pinThreads == 32
#             cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
#             cmdLine += " --l3-assoc 32"

        elif "mesi-ce" == tool:
            cmdLine += " --conflict-exceptions true"

        elif "mesi-ce16" == tool:
            assert options.cores == 16
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024

        elif "mesi-ce32" == tool:
            assert options.cores == 32
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core

#         elif "mesi-ce32-32wayllc" == tool:
#             assert options.pinThreads == 32
#             cmdLine += " --conflict-exceptions true"
#             cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
#             cmdLine += " --l3-assoc 32"
            
        elif "mesiregularplru" == tool:
            # build on mesi.
            cmdLine += " --use-plru true"

        elif "mesiregularplru32" == tool:
            # build on mesi.
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
        
        elif "mesiregularplru16" == tool:
            assert options.cores == 16
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
        
        else:
            assert(False)
            
        if Benchmark.isHTTPDBenchmark(bench):
            cmdLine += " --is-httpd true"

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            RunTask.mesiID = subprocess.Popen(cmdLine, shell=True)

    @staticmethod
    def __startViserSimulator(options, bench, size, trial, tool):
        cmdLine = RunTask.__addJVMArgs(options)
        cmdLine += (RunTask.VISERSIM_CLASSPATH + " --tosim-fifo " + bench + "." + 
                    RunTask.FIFO_PREFIX + tool +
                    " --sim-mode viser")
        cmdLine += RunTask.__addCommonSimulatorArgs(options, bench)
        # Pass whether the backend needs to execute in lockstep with the
        # Pintool
        if options.lockstep:
            cmdLine += " --lockstep true"
        else:
            cmdLine += " --lockstep false"

        statsFile = (RunTask.getOutputPath(options, bench, size, trial) +
                     RunTask.FILE_SEP + tool + "-stats.py")
        cmdLine += " --stats-file " + statsFile
        if options.siteTracking:
            cmdLine += " --site-tracking true"

        if "viserunopt" == tool:
            # This is the basic design, where we invalidate all private cache
            # lines at every region boundary.
            cmdLine += " --use-aim-cache true"

        elif "viserreadonlyopt" == tool:
            # Successful read validation implies all the read-only lines
            # contain consistent values, so we can avoid invalidating
            # read-only lines.
            # Built on top of viserbasic
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --use-aim-cache true"

        elif "viserlastwriteropt" == tool:
            # Lines whose private and shared version numbers differ by one
            # during post-commit are those that were dirty in the current
            # region (so the current core must be the last writer), and so
            # must contain up-to-date values.
            # Built on top of viserreadonlyopt
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --use-aim-cache true"

        elif "viserupdatewrites" == tool:
            # For lines whose private
            # and shared version numbers differ by more than one, we
            # proactively fetch updated values from the shared memory and
            # update the private lines in the hope that it will lead to hits
            # in the future.
            # Built on top of viserlastwriteropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --update-written-lines-during-version-check true"
            cmdLine += " --use-aim-cache true"

        elif "viseruntouchedlinesopt" == tool:
            # We do not invalidate untouched lines whose shared version number did not change, this
            # implies that the line was not written during the ongoing
            # region.
            # Built on top of viserlastwriteropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --invalidate-untouched-lines-opt true"
            cmdLine += " --use-aim-cache true"

        elif "viserspecialinvalid" == tool:
            # Use a special invalid state to mark untouched lines.
            # Built on top of viserlastwriteropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-aim-cache true"

        elif "viserbloomfilter" == tool:
            # Built on top of viserlastwriteropt.
            # Use Bloom filter to optimize invalidating untouched lines.
            # We use two Bloom functions by default.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"

        elif "viserbloominvalid" == tool:
            # Use both a Bloom filter and a special invalid state. The special invalid state is used for
            # lines which are invalidated because of Bloom filter query.
            # Built on top of viserspecialinvalid/viserbloomfilter
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"

        elif "viseronebloom" == tool:
            # Use only one Bloom filter function.
            # Built on top of viserbloominvalid.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --use-two-bloom-funcs false"

        elif "viserselfinvalidationopt" == tool:
            # Viser with self-invalidation optimizations and a realistic AIM cache.
            # Alias for viserbloominvalid.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"

        elif "viserdeferwritebacks" == tool:
            # Defer write backs at region boundaries.
            # Built on top of viserbloominvalid.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"

        elif "viserdeferwritebacksprecise" == tool:
            # Maintain precise information about dirty offsets that were deferred.
            # Built on top of viserdeferwritebacks.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --defer-write-backs-precise true"

        elif "viserskipvalidatingreadlines" == tool:
            # Skip read validation of lines that are not in the write
            # signature. Need to take care of the atomicity of the write ignature.
            # Built on top of viserdeferwritebacks.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"

        elif "viserignorefetchingreadbits" == tool:
            # Avoid fetching read bits from shared memory into private caches.
            # Built on top of viserskipvalidatingreadlines.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"

        elif "viserignorefetchingwritebits" == tool:
            # Avoid fetching read bits from shared memory into private caches.
            # Built on top of viserignorefetchingreadbits.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseropt" == tool:
            # Alias for viserignorefetchingreadbits.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseroptfetchwritebits" == tool:
            # build on viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits false"

        elif "viseroptnoatomicupdates" == tool:
            # build on viseropt
            # treat atomic updates as regular memory accesses,
            # i.e., not updating values directly into the LLC
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --treat-atomic-updates-as-regular-accesses true"

        elif "viseroptprecisedefer" == tool:
            # Viser with all optimizations and a realistic AIM cache and with
            # precise deferred write backs.
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --defer-write-backs-precise true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseropt-8Kaim" == tool:
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --num-aim-lines 8192"

        elif "viseropt-16Kaim" == tool:
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --num-aim-lines 16384"

        elif "viseropt16" == tool:
            assert options.cores == 16
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt.
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseropt16-16Kaim" == tool:
            assert options.cores == 16
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt.
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --num-aim-lines 16384"

        elif "viseropt16idealaim" == tool:
            assert options.cores == 16
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt16.
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache false"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseropt32" == tool:
            assert options.cores == 32
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt.
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseropt32-16Kaim" == tool:
            assert options.cores == 32
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt.
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --num-aim-lines 16384"

        elif "viseropt32idealaim" == tool:
            assert options.cores == 32
            # Viser with all optimizations and a realistic AIM cache.
            # Built on top of viseropt32.
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache false"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseroptidealaim" == tool:
            # Use an ideal AIM cache.
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache false"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseroptclearaimatregionboundaries" == tool:
            # Clear AIM cache lines at region boundaries.
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --clear-aim-region-boundaries true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viserignoredeferredlinesduringreadvalidation" == tool:
            # Incorrectly ignore fetching updated values for LLC lines during
            # read validation. This configuration is to just get an estimate
            # of the cost.
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --ignore-deferred-lines-read-validation true"

        elif "viseroptnodefer" == tool:
            # Disallow deferring of write backs
            # Built on top of viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs false"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseroptevictnonwarlinefirst" == tool:
            # build on viseropt.
            # evict non war lines first
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --evict-non-WAR-line-first true"

        elif "viseroptevictcleanlinefirst" == tool:
            # build on viseropt.
            # evict clean lines first
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --evict-clean-line-first true"

        elif "viseroptlru" == tool:
            # Alias for viseropt.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"

        elif "viseroptmodifiedplru" == tool:
            # build on viseropt.
            # use modified plru
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"

        elif "viserplruatomicasboundary" == tool:
            # build on viseroptregularplru.
            # treat atomic updates as region boundaries
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --treat-atomic-updates-as-region-boundaries true"

        elif "viserplruatomicasregular" == tool:
            # build on viseroptregularplru.
            # treat atomic updates as regular memory accesses
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --treat-atomic-updates-as-regular-accesses true"

        else:
            assert(False)

        if Benchmark.isHTTPDBenchmark(bench):
            cmdLine += " --is-httpd true"

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            RunTask.viserIDsList.append(subprocess.Popen(cmdLine, shell=True))

    @staticmethod
    def __startPauseSimulator(options, bench, size, trial, tool):
        cmdLine = RunTask.__addJVMArgs(options)
        
        # RZ: treat viseroptregularplru as a pause/restart config because I want
        # to generate graphs for its (estimated) costs of restarting a whole 
        # program at exceptions.
        if "sim" in size:
            if "viseroptregularplru" in tool:
                cmdLine += RunTask.VISERSIM_XMX
            elif "pause" in tool:
                cmdLine += RunTask.PAUSESIM_XMX
            elif "restart" in tool:
                cmdLine += RunTask.RESTARTSIM_XMX
        # else: # test workload
          
        if RunTask.isCEConfig(tool):
          cmdLine += RunTask.MESISIM_CLASSPATH 
          if options._isPausePresent:
            cmdLine += " --with-pacifist-backends true"
        else:
          cmdLine += RunTask.VISERSIM_CLASSPATH
                    
        cmdLine += (" --tosim-fifo " + bench + "." + RunTask.FIFO_PREFIX + tool + 
                    " --sim-mode viser")
        cmdLine += RunTask.__addCommonSimulatorArgs(options, bench)
        # Pass whether the backend needs to execute in lockstep with the
        # Pintool
        if options.lockstep:
            cmdLine += " --lockstep true"
        else:
            cmdLine += " --lockstep false"

        statsFile = (RunTask.getOutputPath(options, bench, size, trial) +
                     RunTask.FILE_SEP + tool + "-stats.py")
        cmdLine += " --stats-file " + statsFile
        if options.siteTracking:
            cmdLine += " --site-tracking true"

        if "pause" == tool:
            # Built on top of viseroptregularplru.
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"
            
        elif "pause16" == tool:
            # Built on top of viseroptregularplru.
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            
        elif "pause32" == tool:
            # Built on top of viseroptregularplru.
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            
        elif "viseroptregularplru" == tool:
            # build on viseropt.
            # use regular plru
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"

        elif "viseroptregularplru16" == tool:
            # build on viseropt.
            # use regular plru
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
        
        elif "viseroptregularplru32" == tool:
            assert options.cores == 32
            # build on viseropt.
            # use regular plru
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
    
        elif "viseroptregularplru32cp1k" == tool:
            assert options.cores == 32
            # build on viseroptregularplru32.
            # set a check point every 100 interations
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --check-pointing-rate 1000"
    
        elif "viseroptregularplru32cp100" == tool:
            assert options.cores == 32
            # build on viseroptregularplru32.
            # set a check point every 100 interations
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --check-pointing-rate 100"
            
        elif "viseroptregularplru32cp10" == tool:
            assert options.cores == 32
            # build on viseroptregularplru32.
            # set a check point every 10 interations
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --check-pointing-rate 10"
            
        elif "viseroptregularplru32cp1" == tool:
            assert options.cores == 32
            # build on viseroptregularplru32.
            # set a check point every 1 interation
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --check-pointing-rate 1"
        
        elif "restart" == tool:
            # Built on top of pause.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"

        elif "restartevictcleanlinesfirst" == tool:
            # Built on top of pause.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --evict-clean-line-first true"

        elif "restartunopt" == tool:
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"

        elif "restartunopt16" == tool:
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            
        elif "restartunopt32" == tool:
            assert options.cores == 32
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            
        elif "pseudorestartunopt" == tool:
            # based on restartunopt, but not save events for actual region restart.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --use-plru true"

        elif "pseudorestartunopt16" == tool:
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024

        elif "restartopt" == tool:
            # use modified PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"

        elif "restartopt16" == tool:
            # use modified PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            
        elif "restartopt32" == tool:
            # use modified PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            
        elif "pseudorestartopt" == tool:
            # based on restartopt, but not save events for actual region restart.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"

        elif "pseudorestartopt16" == tool:
            # based on restartopt, but not save events for actual region restart.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            
        elif "pseudorestartopt32" == tool:
            # based on restartopt, but not save events for actual region restart.
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core

        elif "restartunopt4assoc" == tool:
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --l1-assoc 4"
            cmdLine += " --l2-assoc 4"

        elif "restartopt4assoc" == tool:
            # use modified PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l1-assoc 4"
            cmdLine += " --l2-assoc 4"

        elif "restartunopt2assoc" == tool:
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --l1-assoc 2"
            cmdLine += " --l2-assoc 2"

        elif "restartopt2assoc" == tool:
            # use modified PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l1-assoc 2"
            cmdLine += " --l2-assoc 2"

        elif "restartunopt16assoc" == tool:
            # use regular PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --l1-assoc 16"
            cmdLine += " --l2-assoc 16"

        elif "restartopt16assoc" == tool:
            # use modified PLRU on L2 evictions
            cmdLine += " --always-invalidate-read-only-lines false"
            cmdLine += " --invalidate-written-lines-only-after-version-check" \
                " true"
            cmdLine += " --special-invalid-state true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --use-aim-cache true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-validating-read-lines true"
            cmdLine += " --ignore-fetching-read-bits true"
            cmdLine += " --ignore-fetching-write-bits true"
            cmdLine += " --pause-cores-at-conflicts true"
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --use-plru true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"
            cmdLine += " --l1-assoc 16"
            cmdLine += " --l2-assoc 16"
            
        elif "ce" == tool:
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            
        elif "ce16" == tool:
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            
        elif "ce32" == tool:
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            
        elif "cepause" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
           
        elif "cerestartunopt" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
        
        elif "cerestartopt" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks true" 
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"         
         
        elif "cepause16" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"    
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            
        elif "cerestartunopt16" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
        
        elif "cerestartopt16" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks true" 
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"   
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024      
          
        elif "cepause32" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"    
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core  
        
        elif "cerestartunopt32" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks true"
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core  
            
        elif "cerestartopt32" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks true" 
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"    
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core       

        elif "pseudocerestartopt" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"   
        
        elif "pseudocerestartopt16" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"   
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024      
            
        elif "pseudocerestartopt32" == tool:
            # Built on top of ceplru
            # pause a core at conflict
            cmdLine += " --conflict-exceptions true"
            cmdLine += " --use-plru true"
            cmdLine += " --pause-cores-at-conflicts true"  
            cmdLine += " --restart-at-failed-validations-or-deadlocks false"
            cmdLine += " --false-restart true"
            cmdLine += " --evict-clean-line-first true"
            cmdLine += " --set-write-bits-in-l2 true"    
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core     
            
        else:
            assert(False)

        if Benchmark.isHTTPDBenchmark(bench):
            cmdLine += " --is-httpd true"
            if "mysqld" in bench and RunTask.isCEConfig(tool):
                cmdLine += " --pausing-timeout 50000"

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            RunTask.pauseIDsList.append(subprocess.Popen(cmdLine, shell=True))

    @staticmethod
    def __startRCCSISimulator(options, bench, size, trial, tool):
        cmdLine = RunTask.__addJVMArgs(options)
        cmdLine += (RunTask.RCCSISIM_CLASSPATH + " --tosim-fifo " + bench + "." +
                    RunTask.FIFO_PREFIX + tool +
                    " --sim-mode rccsi")
        cmdLine += RunTask.__addCommonSimulatorArgs(options, bench)
        # Pass whether the backend needs to execute in lockstep with the
        # Pintool
        if options.lockstep:
            cmdLine += " --lockstep true"
        else:
            cmdLine += " --lockstep false"

        statsFile = (RunTask.getOutputPath(options, bench, size, trial) +
                     RunTask.FILE_SEP + tool + "-stats.py")
        cmdLine += " --stats-file " + statsFile

        if "rccsiunopt" == tool:
            # Base configuration for RCC-SI.
            # This is the basic design, where we invalidate all private cache
            # lines at every region boundary. The global reader information is
            # cleared from LLC lines using epochs.
            pass

        elif "rccsiunoptclearreadersfromllc" == tool:
            # Builds on rccsiunopt.
            # This is the basic design, where we invalidate all private cache
            # lines at every region boundary. The global reader information is
            # cleared from LLC lines by iterating over LLC lines.
            cmdLine += " --clear-readers-llc true"

        elif "rccsiusereadonlylineopt" == tool:
            # Builds on rccsiunopt.
            cmdLine += " --read-only-line-opt true"

        elif "rccsiusereadonlylineopt" == tool:
            # Builds on rccsiusereadonlylineopt.
            cmdLine += " --read-only-line-opt true"
            cmdLine += " --last-writer-opt true"

        elif "rccsibloomfilter" == tool:
            # Builds on rccsiusereadonlylineopt.
            cmdLine += " --read-only-line-opt true"
            cmdLine += " --last-writer-opt true"
            cmdLine += " --use-bloom-filter true"

        elif "rccsideferwritebacks" == tool:
            # Builds on rccsibloomfilter.
            cmdLine += " --read-only-line-opt true"
            cmdLine += " --last-writer-opt true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --defer-write-backs true"

        elif "rccsiskipreadvalidation" == tool:
            # Builds on rccsideferwritebacks.
            cmdLine += " --read-only-line-opt true"
            cmdLine += " --last-writer-opt true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-read-validation true"

        elif "rccsiopt" == tool:
            # Alias for rccsiskipreadvalidation.
            cmdLine += " --read-only-line-opt true"
            cmdLine += " --last-writer-opt true"
            cmdLine += " --use-bloom-filter true"
            cmdLine += " --defer-write-backs true"
            cmdLine += " --skip-read-validation true"

        elif "rccsiopt16" == tool:
            assert options.pinThreads == 16
            # RCC-SI with all optimizations.
            cmdLine += " --l3-size 33554432"  # 32 MB = 32 * 1024 * 1024
            cmdLine += " --defer-write-backs true"

        elif "rccsiopt32" == tool:
            assert options.pinThreads == 32
            # RCC-SI with all optimizations.
            cmdLine += " --l3-size 67108864"  # 64 MB = 64 * 1024 * 1024
            cmdLine += " --l3-assoc 32"  # One way per core
            cmdLine += " --defer-write-backs true"

        else:
            assert(False)

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)
        if not options.printOnly:
            RunTask.rccsiIDsList.append(subprocess.Popen(cmdLine, shell=True))

    @staticmethod
    def __startServer(options, bench):
        if bench == "httpd":
            cmdLine = RunTask.HTTPD_START
            rootPath = RunTask.HTTPD_ROOT
        elif bench == "mysqld":
            cmdLine = RunTask.MYSQLD_START
            rootPath = RunTask.MYSQLD_ROOT

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)

        if not options.printOnly:
            subprocess.Popen(cmdLine, shell=True)
            time.sleep(1)
            proc1 = subprocess.Popen(['ps', 'ax'], stdout=subprocess.PIPE)
            proc2 = subprocess.Popen(['grep', bench], stdin=proc1.stdout,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            # Allow proc1 to receive a SIGPIPE if proc2 exits.
            proc1.stdout.close()
            out, _ = proc2.communicate()
            for line in out.splitlines():
                if rootPath in line.decode("utf-8"):
                    print(line)
                    pid = int(line.split(None, 1)[0])
                    if pid > RunTask.httpdID:
                        RunTask.httpdID = pid
            print("serverId:", RunTask.httpdID)

    @staticmethod
    def __writeHttpdPidFile(options):
        if not options.printOnly:
            f = open(RunTask.HTTPD_PID_FILE, 'w')
            time.sleep(1)
            proc1 = subprocess.Popen(['ps', 'ax'], stdout=subprocess.PIPE)
            proc2 = subprocess.Popen(['grep', 'httpd'], stdin=proc1.stdout,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            # Allow proc1 to receive a SIGPIPE if proc2 exits.
            proc1.stdout.close()
            out, _ = proc2.communicate()
            for line in out.splitlines():
                if RunTask.HTTPD_ROOT in line.decode("utf-8"):
                    print(line)
                    pid = int(line.split(None, 1)[0])
                    if pid > RunTask.httpdID:
                        RunTask.httpdID = pid
            print("httpdId:", RunTask.httpdID)
            f.write(str(RunTask.httpdID))

    @staticmethod
    def __stopServer(options, bench):
        if options.attachPid:
            if bench == "httpd":
                cmdLine = RunTask.HTTPD_STOP
            elif bench == "mysqld":
                cmdLine = RunTask.MYSQLD_STOP
        else:
            if bench == "httpd":
                cmdLine = RunTask.HTTPD_DEBUG_STOP
            elif bench == "mysqld":
                cmdLine = RunTask.MYSQLD_STOP

        if options.verbose >= 2 or options.printOnly:
            print(RunTask.__outputPrefix() + cmdLine)

        if not options.printOnly:
            pid = subprocess.Popen(cmdLine, shell=True)
            while pid.poll() is None:
                time.sleep(5)

    @staticmethod
    def __startClients(options, workload, bench):
        clients = options.cores
        if (workload == 'test'):
            size = 3  # 5 requests from each client
        elif (workload == 'simsmall'):
            if bench == "httpd":
                size = int((65536  * 2 / clients) + 1) # 65536 * 2 requests in total
            elif bench == "mysqld":
                size = int((256 / clients) + 1) # 1024 requests in total
            
        elif (workload == 'simmedium'):
            size = 10001  # 10000 requests from each client

        for i in range(0, clients // 2):
            if bench == "httpd":
                cmdLine = RunTask.HTTP_CLIENT0 + " " + str(size)
            elif bench == "mysqld":
                cmdLine = RunTask.MYSQL_CLIENT0 + " " + str(size)

            if options.verbose >= 2 or options.printOnly:
                print(RunTask.__outputPrefix() + cmdLine)
            if not options.printOnly:
                RunTask.httpClientIDsList.append(subprocess.Popen(cmdLine, shell=True))
            if bench == "httpd":
                cmdLine = RunTask.HTTP_CLIENT1 + " " + str(size)
            elif bench == "mysqld":
                cmdLine = RunTask.MYSQL_CLIENT1 + " " + str(size)

            if options.verbose >= 2 or options.printOnly:
                print(RunTask.__outputPrefix() + cmdLine)
            if not options.printOnly:
                RunTask.httpClientIDsList.append(
                    subprocess.Popen(cmdLine, shell=True))

    @staticmethod
    def __checkClients(options):
        for pid in RunTask.httpClientIDsList:
            # print("check pid: ", pid.pid)
            if pid.poll() is None:
                return False
        return True

    @staticmethod
    def __isTerminated(options):
        assert options.processPintool(), (
            "Pintool is expected to be run" + "along with the simulators.")
        for pinID in RunTask.pinIDsList:
            if pinID.poll() is None:
                return False

            if options.processMESISim():
                for mesiID in RunTask.mesiIDsList:
                    if mesiID.poll() is None:
                        return False

            if options.processViserSim():
                for viserID in RunTask.viserIDsList:
                    if viserID.poll() is None:
                        return False

            if options.processPauseSim():
                for pauseID in RunTask.pauseIDsList:
                    if pauseID.poll() is None:
                        return False

            if options.processRCCSISim():
                for rccsiID in RunTask.rccsiIDsList:
                    if rccsiID.poll() is None:
                        return False

        return True

    @staticmethod
    def __createRerunFile(options):
        cwd = os.getcwd()
        os.chdir(options.getExpOutputDir())
        try:
            options.createRerunFile()
        finally:
            os.chdir(cwd)
