import traceback
import sys
from option.constants import Constants


def checkEnvVariables():
    # SB: These checks seem to be not working as intended
    if (Constants.PIN_ROOT is None or Constants.VS_PINTOOL_ROOT is None or Constants.PARSEC_ROOT is None or
            Constants.MESISIM_ROOT is None or Constants.VISERSIM_ROOT is None or Constants.VISER_EXP is None or
            Constants.RCCSISIM_ROOT is None):
        raiseError("One or more environment variables are not set.")


def raiseError(*args, stack=False):
    """Helper method to raise errors and exit.  stack is a ‘keyword-only’ argument, meaning that it can only be used
    as a keyword rather than a positional argument.
    """
    if stack:
        traceback.print_stack()
    stmt = "[error] "
    for s in args:
        stmt += s + " "
    sys.exit(stmt)
