import getpass
import socket
import subprocess

import paramiko

from option.constants import Constants


class CopyTask(Constants):
    '''Copy files to the result machine, renaming around conflicts.
    This is difficult to automate since we need to avoid overwriting
    files being copied by other instances of copy tasks.'''

    @staticmethod
    def __outputPrefix():
        return "[copy] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print(CopyTask.__outputPrefix() + "Executing copy task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(CopyTask.__outputPrefix() + "Done executing copy task...")

    @staticmethod
    def copyTask(options):
        if options.sameMachine:
            return  # Nothing to be done

        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(options.config.getResultsMachine())
        outputDir = options.getExpOutputDir()
        # Test if outputDir exists on results machine
        print("[ -f " + outputDir + " ]")
        _, ssh_stdout, _ = ssh.exec_command(
            "[ -f " + outputDir + " ]")
        try:
            print(ssh_stdout.read())
            assert ssh_stdout.read()
        except AssertionError:
            print("Dir does not exist, creating")
            cmd = "mkdir " + outputDir
            _, ssh_stdout, _ = ssh.exec_command(cmd)

        cmd = ("scp -r " + options.getExpOutputDir() + Constants.FILE_SEP +
               " " +
               getpass.getuser() + "@" + options.config.getResultsMachine() +
               ":" + options.getExpOutputDir() + Constants.FILE_SEP +
               socket.gethostname())
        subprocess.Popen(cmd, shell=True)
