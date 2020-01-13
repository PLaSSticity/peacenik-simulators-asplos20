import os
import psutil
import signal
from subprocess import call
import subprocess

from option.constants import Constants


class CleanTask(Constants):
    '''Clean up old processes with standard names (e.g., pipefork) if any.'''

    tasks = ["pipefork", "parsecmgmt", "blackscholes", "bodytrack", "canneal",
             "dedup", "facesim", "ferret", "fluidanimate", "raytrace",
             "streamcluster", "swaptions", "vips", "x264", "tee", "python3",
             "run.sh", "java", "httpd", "trigger-con0.sh", "trigger-con1.sh",
             "mysqld"]

    @staticmethod
    def __outputPrefix():
        return "[clean] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print(
                "\n" + CleanTask.__outputPrefix() + "Executing clean task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(
                CleanTask.__outputPrefix() + "Done executing clean task...\n")

    @staticmethod
    def cleanTask(options):
        CleanTask.__printTaskInfoStart(options)
        CleanTask.__usingPsutil(options)
        CleanTask.__printTaskInfoEnd(options)

    @staticmethod
    def __usingPs(options):
        ret = subprocess.Popen(['ps', '-A'], stdout=subprocess.PIPE)
        out, _ = ret.communicate()
        for line in out.splitlines():
            for name in CleanTask.tasks:
                if name in line.decode("utf-8"):
                    pid = int(line.split(None, 1)[0])
                    if pid != os.getpid():
                        print("Killing task", name, " with pid:", pid)
                        print(call(["ps", str(pid)]))
                        print(line)
                        if not options.printOnly:
                            if options.sameMachine:
                                print(CleanTask.__outputPrefix() +
                                      "Do you want to continue? [y/n]")
                                # This is an important operation, confirm with
                                # the user before executing.
                                ans = input()
                            else:  # Ignore if a remote machine
                                ans = "y"
                            if ans.lower() == "y" or ans.lower() == "yes":
                                try:
                                    os.kill(pid, signal.SIGKILL)
                                except ProcessLookupError:
                                    return False

    @staticmethod
    def __usingPsutil(options):
        cid = os.getpid()
        try:
            for proc in psutil.process_iter():
                for name in CleanTask.tasks:
                    # print("Checking for process named ", name)
                    if proc.name() == name and proc.pid != cid:
                        kill = True
                        # There could be other "python3" processes in the
                        # system
                        if proc.name() == "python3":
                            li_procCmdLine = proc.cmdline()
                            str_cmdLine = CleanTask.VISER_EXP + "/src/main.py"
                            if (str_cmdLine not in li_procCmdLine):
                                kill = False
                        elif proc.name() == "java":
                            # Kill simulator backends
                            li_procCmdLine = proc.cmdline()
                            found = False
                            for elem in li_procCmdLine:
                                if ((CleanTask.VISERSIM_ROOT in elem) or
                                        (CleanTask.MESISIM_ROOT in elem) or
                                        (CleanTask.RCCSISIM_ROOT in elem)):
                                    found = True
                            kill = found

                        if kill:
                            if not options.printOnly:
                                # Always print before killing a process
                                print("** Killing task", name, "with pid:",
                                      proc.pid)
                                call(["ps", str(proc.pid)])
                                if options.sameMachine:
                                    # print(CleanTask.__outputPrefix() +
                                    #       "Do you want to continue? [y/n]")
                                    ans = "y"  # input()
                                else:
                                    ans = "y"
                                if ans.lower() == "y" or ans.lower() == "yes":
                                    try:
                                        proc.kill()
                                    except psutil.NoSuchProcess:
                                        print("NoSuchProcess")
                                    except psutil.ZombieProcess:
                                        print("ZombieProcess")
                                    except psutil.AccessDenied:
                                        print("AccessDenied")
                                    except psutil.TimeoutExpired:
                                        print("TimeoutExpired")
                        else:
                            print("# Skipping process", name, "with pid:",
                                  proc.pid)
                            call(["ps", str(proc.pid)])
                    else:
                        # print("# Skipping process", name, "with pid:",
                        #       proc.pid)
                        # call(["ps", str(proc.pid)])
                        pass
        except psutil.NoSuchProcess:
            pass
