import os
from subprocess import CalledProcessError
import subprocess

from option.constants import Constants


class BuildTask(Constants):
    """This class encapsulates build commands."""
    PINTOOL_ROOT = ""

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print(
                "\n" + BuildTask.__outputPrefix() + "Executing build task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(
                BuildTask.__outputPrefix() + "Done executing build task...\n")

    @staticmethod
    def buildTask(options):
        BuildTask.__printTaskInfoStart(options)
        
        BuildTask.PINTOOL_ROOT = (BuildTask.ST_PINTOOL_ROOT) if (options.pinTool 
            == "viserST") else (BuildTask.VS_PINTOOL_ROOT)

        cwd = os.getcwd()  # get current directory
        try:
            toolsTuple = options.getToolsTuple()
            # We can build the projects in any order
            for proj in toolsTuple:
                if Constants.isPintool(proj):
                    BuildTask.__buildPintool(options)
                elif Constants.isMESIConfig(proj) or Constants.isCEConfig(proj):
                    BuildTask.__buildMESISim(options)
                elif (Constants.isPauseConfig(proj) or
                      Constants.isViserConfig(proj)):
                    BuildTask.__buildViserSim(options)
                elif Constants.isRCCSIConfig(proj):
                    BuildTask.__buildRCCSISim(options)
                else:
                    assert(False)
            if (options.processMESISim() or options.processViserSim() or
                    options.processRCCSISim()) or options.processPauseSim():
                if options.lockstep:
                    BuildTask.__buildNamedPipe(options)
                BuildTask.__buildPipefork(options)
        finally:
            os.chdir(cwd)
            BuildTask.__printTaskInfoEnd(options)

    @staticmethod
    def __outputPrefix():
        return "[build] "

    @staticmethod
    def __buildPintool(options):
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Building Pintool.")
        os.chdir(BuildTask.PINTOOL_ROOT)
        try:
            subprocess.check_call(["make"])
        except CalledProcessError:
            Constants.raiseError(False, "Building Pintool failed.")
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Done building Pintool.")

    @staticmethod
    def __buildMESISim(options):
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Building MESI simulator.")
        BuildTask.__buildSimulatorHelper(BuildTask.MESISIM_ROOT)
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Done building MESI simulator.")

    @staticmethod
    def __buildViserSim(options):
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Building Viser simulator.")
        BuildTask.__buildSimulatorHelper(BuildTask.VISERSIM_ROOT)
        if options.verbose >= 1:
            print(
                BuildTask.__outputPrefix() + "Done building Viser simulator.")

    @staticmethod
    def __buildRCCSISim(options):
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Building RCC-SI simulator.")
        BuildTask.__buildSimulatorHelper(BuildTask.RCCSISIM_ROOT)
        if options.verbose >= 1:
            print(
                BuildTask.__outputPrefix() + "Done building RCC-SI simulator.")

    @staticmethod
    def __buildSimulatorHelper(path):
        os.chdir(path)
        rc = os.system("ant build")
        if rc != 0:
            Constants.raiseError(False, "Failed building the simulator.")

    @staticmethod
    def __buildPipefork(options):
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Building pipefork.")
        os.chdir(BuildTask.PINTOOL_ROOT)
        try:
            subprocess.check_call(["make", "pipefork"])
        except CalledProcessError:
            Constants.raiseError(False, "Building pipefork failed.")
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Done building pipefork.")

    @staticmethod
    def __buildNamedPipe(options):
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Building namedpipe.")
        os.chdir(BuildTask.PINTOOL_ROOT)
        try:
            subprocess.check_call(["make", "namedpipe"])
        except CalledProcessError:
            Constants.raiseError(False, "Building namedpipe failed.")
        if options.verbose >= 1:
            print(BuildTask.__outputPrefix() + "Done building namedpipe.")
