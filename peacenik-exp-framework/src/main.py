#!/usr/bin/env python

import sys

from option.options import Options
from parser.cmdline import CmdLine
from task.tasks import Tasks


def main():
    _options = Options(sys.argv)
    _cmdparser = CmdLine()
    _cmdparser.parse(_options)

    _tasks = Tasks()
    _tasks.runAllTasks(_options)

if __name__ == "__main__":
    main()
