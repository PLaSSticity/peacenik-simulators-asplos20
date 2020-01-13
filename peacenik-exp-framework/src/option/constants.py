import os
import sys
import traceback


class Constants(object):
    """Define constants to different project directories and executables."""

    # Paths to various directories
    EXP_ROOT = os.getenv('VISER_EXP')
    PIN_ROOT = os.getenv('PIN_ROOT')
    VS_PINTOOL_ROOT = os.getenv('PINTOOL_ROOT')  # the default Viser pintool
    ST_PINTOOL_ROOT = os.getenv('ST_PINTOOL_ROOT')  # the ViserST pintool
    ST_PINTOOL_ROOT = (PIN_ROOT + "/source/tools/ViserST") if (ST_PINTOOL_ROOT is
                                                               None) else ST_PINTOOL_ROOT
    PARSEC_ROOT = os.getenv('PARSEC_ROOT')

    # SB: FIXME: These are project-specific, e.g., httpd is not required by
    # RCC/RCCSI as is. So we could perhaps encapsulate these checks with
    # project checks.

    MESISIM_ROOT = os.getenv('MESISIM_ROOT')
    VISERSIM_ROOT = os.getenv('VISERSIM_ROOT')
    VISER_EXP = os.getenv('VISER_EXP')
    RCCSISIM_ROOT = os.getenv('RCCSISIM_ROOT')

    HTTPD_ROOT = os.getenv('HTTPD_ROOT')
    MYSQLD_ROOT = os.getenv('MYSQLD_ROOT')

    EXP_OUTPUT = os.getenv('HOME') + "/exp-output/"
    EXP_PRODUCTS = os.getenv('HOME') + "/exp-products/"

    PYTHON_EXEC = "python3"
    BASH_SHEBANG = "#!/bin/bash"
    VISER_EXP_LOCAL = "viser-local"
    VISER_EXP_REMOTE = "viser-remote"

    CONFIG = "config.ini"

    # Named pipes
    FIFO_PREFIX = "fifo."
    FIFO_FRONTEND = FIFO_PREFIX + "frontend"
    FIFO_PERTHREAD = FIFO_PREFIX + "tid"

    # Pintool
    PARSECMGMT = PARSEC_ROOT + "/bin/parsecmgmt -a run -p "
    PARSEC_ARGS1 = "-c gcc-pthreads-hooks -i "
    PARSEC_ARGS3 = ('''-s "''' + PIN_ROOT +
                    "/pin.sh -ifeellucky -injection child -t ")
    PARSEC_ARGS4 = ("/obj-intel64/visersim.so")

    PINBIN = PIN_ROOT + "/pin.sh -ifeellucky -injection child "
    # PIN_ARG = "-t " + PINTOOL_ROOT + "/obj-intel64/visersim.so"

    GUAVA_JAR = "/lib/guava-18.0.jar:"
    JOPTSIMPLE_JAR = "/lib/jopt-simple-5.0.2.jar"

    MESISIM_CLASSPATH = (" -classpath " + MESISIM_ROOT + "/bin/:" +
                         MESISIM_ROOT + GUAVA_JAR + MESISIM_ROOT +
                         JOPTSIMPLE_JAR + " simulator.mesi.MESISim")

    VISERSIM_CLASSPATH = (" -classpath " + VISERSIM_ROOT + "/bin/:" +
                          VISERSIM_ROOT + GUAVA_JAR + VISERSIM_ROOT +
                          JOPTSIMPLE_JAR + " simulator.viser.ViserSim")

    RCCSISIM_CLASSPATH = (" -Xmx30g -classpath " + RCCSISIM_ROOT + "/bin/:" +
                          RCCSISIM_ROOT + GUAVA_JAR + RCCSISIM_ROOT +
                          JOPTSIMPLE_JAR + " simulator.rccsi.RCCSISim")

    VISERSIM_XMX = " -Xmx20g"

    PAUSESIM_XMX = " -Xmx25g"

    RESTARTSIM_XMX = " -Xmx35g"

    VISERSIM_XMX_TEST = " -Xmx1g"

    PAUSESIM_XMX_TEST = " -Xmx2g"

    RESTARTSIM_XMX_TEST = " -Xmx2g"

    # httpd constants
    HTTPD_PID_FILE = (HTTPD_ROOT + "/logs/httpd.pid") if (HTTPD_ROOT is not
                                                          None) else None
    HTTPD_START = (HTTPD_ROOT + "/bin/apachectl start") if (HTTPD_ROOT is not
                                                            None) else None
    HTTPD_STOP = (HTTPD_ROOT + "/bin/apachectl stop") if (HTTPD_ROOT is not
                                                          None) else None
    HTTP_CLIENT0 = (HTTPD_ROOT + "/trigger-con0.sh") if (HTTPD_ROOT is not
                                                         None) else None
    HTTP_CLIENT1 = (HTTPD_ROOT + "/trigger-con1.sh") if (HTTPD_ROOT is not
                                                         None) else None

    HTTPD_DEBUG_START = (HTTPD_ROOT + "/bin/httpd -X -k start") if (HTTPD_ROOT is not
                                                                    None) else None
    HTTPD_DEBUG_STOP = (HTTPD_ROOT + "/bin/httpd -X -k stop") if (HTTPD_ROOT is not
                                                                  None) else None

    # mysqld constants
    MYSQLD_START = (MYSQLD_ROOT + "/bin/mysqld " +
                    # "--max_connections=8 --innodb-read-io-threads=1 " +
                    "--innodb-read-io-threads=1 --skip-innodb_adaptive_hash_index " +
                    "--innodb-lru-scan-depth=256 --innodb-lock-wait-timeout=1073741820 " +
                    "--innodb-write-io-threads=1 " +
                    "--basedir=" + MYSQLD_ROOT +
                    " --datadir=" + MYSQLD_ROOT + "/data --plugin-dir=" +
                    MYSQLD_ROOT + "/lib/plugin") if (
        MYSQLD_ROOT is not None) else None
    MYSQLD_CACHED_THREADS = " --thread_cache_size="
    MYSQLD_INNODB_THREADS = " --innodb-thread-concurrency="
    MYSQLD_STOP = (MYSQLD_ROOT + "/support-files/mysql.server stop") if (
        MYSQLD_ROOT is not None) else None
    MYSQL_CLIENT0 = (MYSQLD_ROOT + "/trigger-con0.sh") if (MYSQLD_ROOT is not
                                                           None) else None
    MYSQL_CLIENT1 = (MYSQLD_ROOT + "/trigger-con1.sh") if (MYSQLD_ROOT is not
                                                           None) else None

    # OS-level constants
    FILE_SEP = "/"

    # Number of digits after decimal
    PRECISION_DIGITS = 3

    @staticmethod
    def checkEnvVariables():
        # SB: These checks seem to be not working as intended
        if (Constants.PIN_ROOT is None or
                Constants.PINTOOL_ROOT is None or
                Constants.PARSEC_ROOT is None or
                Constants.MESISIM_ROOT is None or
                Constants.VISERSIM_ROOT is None or
                Constants.VISER_EXP is None or
                Constants.RCCSISIM_ROOT is None):
            Constants.raiseError(False,
                                 "One or more environment variables are not "
                                 "set.")

    @staticmethod
    def raiseError(stack, *args):
        """Helper method to raise errors and exit."""
        if stack:
            traceback.print_stack()
        stmt = "[error] "
        for s in args:
            stmt += s + " "
        sys.exit(stmt)

    @staticmethod
    def isPintool(name):
        return "pintool" in name

    @staticmethod
    def isMESIConfig(name):
        return "mesi" in name

    @staticmethod
    def isCEConfig(name):
        return ("ce" in name and not "mesi" in name)

    @staticmethod
    def isViserConfig(name):
        return (not("viseroptregularplru" in name) and "viser" in name)
        # RZ: treat viseroptregularplru as a pause/restart config because I want
        # to generate graphs for its (estimated) costs of restarting a whole
        # program at exceptions.
        # For the same reason, treat ce configs as pause config below.

    @staticmethod
    def isPauseConfig(name):
        return ("pause" in name or "restart" in name
                or "viseroptregularplru" in name or Constants.isCEConfig(name))

    @staticmethod
    def isRCCSIConfig(name):
        return "rccsi" in name

    @staticmethod
    def isSimulatorConfig(name):
        return (Constants.isMESIConfig(name) or
                Constants.isViserConfig(name) or
                Constants.isRCCSIConfig(name) or
                Constants.isPauseConfig(name) or
                Constants.isCEConfig(name))
