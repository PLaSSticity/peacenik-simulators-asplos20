import getpass
from subprocess import call

from option.constants import Constants


class SyncTask(Constants):

    @staticmethod
    def __outputPrefix():
        return "[sync] "

    @staticmethod
    def __printTaskInfoStart(options):
        if options.verbose >= 1:
            print(SyncTask.__outputPrefix() + "Executing sync task...")

    @staticmethod
    def __printTaskInfoEnd(options):
        if options.verbose >= 1:
            print(SyncTask.__outputPrefix() + "Done executing sync task...")

    @staticmethod
    def syncTask(options):
        SyncTask.__printTaskInfoStart(options)
        str_sourceMachine = options.config.getSourceMachine()
        str_user = getpass.getuser()

        if not options.sameMachine:
            SyncTask.__syncPintool(options, str_sourceMachine, str_user)
            if options.processMESISim():
                SyncTask.__syncMesiSim(options, str_sourceMachine, str_user)
            if options.processViserSim():
                SyncTask.__syncViserSim(options, str_sourceMachine, str_user)
            if options.processRCCSISim():
                SyncTask.__syncRCCSISim(options, str_sourceMachine, str_user)

        SyncTask.__printTaskInfoEnd(options)

    @staticmethod
    def __syncPintool(options, str_sourceMachine, str_user):
        str_cmd = ("rsync -az --exclude=.svn " + str_user + "@" +
                   str_sourceMachine + ":" +
                   SyncTask.PIN_ROOT + SyncTask.FILE_SEP + " " +
                   SyncTask.PIN_ROOT + SyncTask.FILE_SEP)
        if options.verbose >= 1:
            print(SyncTask.__outputPrefix() + "Syncing Pintool...")
        if options.printOnly:
            print(str_cmd)
            return
        call(str_cmd, shell=True)

    @staticmethod
    def __syncMesiSim(options, str_sourceMachine, str_user):
        str_cmd = ("rsync -az --exclude=.svn " + str_user + "@" +
                   str_sourceMachine + ":" +
                   SyncTask.MESISIM_ROOT + SyncTask.FILE_SEP + " " +
                   SyncTask.MESISIM_ROOT + SyncTask.FILE_SEP)
        if options.verbose >= 1:
            print(SyncTask.__outputPrefix() + "Syncing MESI simulator...")
        if options.printOnly:
            print(str_cmd)
            return
        call(str_cmd, shell=True)

    @staticmethod
    def __syncViserSim(options, str_sourceMachine, str_user):
        str_cmd = ("rsync -az --exclude=.svn " + str_user + "@" +
                   str_sourceMachine + ":" +
                   SyncTask.VISERSIM_ROOT + SyncTask.FILE_SEP + " " +
                   SyncTask.VISERSIM_ROOT + SyncTask.FILE_SEP)
        if options.verbose >= 1:
            print(SyncTask.__outputPrefix() + "Syncing Viser simulator...")
        if options.printOnly:
            print(str_cmd)
            return
        call(str_cmd, shell=True)

    @staticmethod
    def __syncRCCSISim(options, str_sourceMachine, str_user):
        str_cmd = ("rsync -az --exclude=.svn " + str_user + "@" +
                   str_sourceMachine + ":" +
                   SyncTask.RCCSISIM_ROOT + SyncTask.FILE_SEP + " " +
                   SyncTask.RCCSISIM_ROOT + SyncTask.FILE_SEP)
        if options.verbose >= 1:
            print(SyncTask.__outputPrefix() + "Syncing RCC-SI simulator...")
        if options.printOnly:
            print(str_cmd)
            return
        call(str_cmd, shell=True)

    @staticmethod
    def syncOutputDir(options):
        outputDir = options.getExpOutputDir()
        str_resultsMachine = options.config.getResultsMachine()
        str_cmd = ("rsync -az " + outputDir + " " + getpass.getuser() + "@" +
                   str_resultsMachine + ":" + SyncTask.EXP_OUTPUT)
        if options.printOnly:
            print(str_cmd)
            return
        call(str_cmd, shell=True)

    @staticmethod
    def syncProductsDir(options):
        productDir = options.getExpProductsDir()
        str_resultsMachine = options.config.getResultsMachine()
        str_cmd = ("rsync -az " + productDir + " " + getpass.getuser() + "@" +
                   str_resultsMachine + ":" + SyncTask.EXP_PRODUCTS)
        if options.printOnly:
            print(str_cmd)
            return
        call(str_cmd, shell=True)
