import math
import os
from subprocess import call
import numpy
import scipy.stats

from option.constants import Constants
from result.mergetype import Merge
from result.mergetype import MergeType
from result.resultset import ResultSet


class JGraph(Constants):

    JGRAPH_EXEC = "/usr/bin/jgraph"

    FONTSIZE = 16
    INITIAL_PADDING = 0.5
    BAR_PADDING = 0.1
    BENCH_PADDING = 1.0
    EXTRA_PADDING_FOR_SERVER = 0.2
    # Greater is lighter
    SHADE_START = 0.9
    SHADE_END = 0.4
    MAJOR_STRIDE_THICKNESS = 1.0
    MINOR_STRIDE_THICKNESS = 0.0
    X_TEXT_ANGLE = 35.0

    SUMMARY_PADDING = 0.5
    SUMMARY_MERGE_TYPE = MergeType.MERGE_GEOMEAN

    DRAW_ERROR_BARS = False

    def __init__(self, options, outputPath, htmFile, key, rs, norm):
        self.options = options
        self.outputPath = outputPath
        self.htmFile = htmFile
        self.key = key
        self.rs = rs
        self.name = self.key + ".jgr"
        self.normalize = norm

        self.xInches = 10
        self.xMax = 0.0
        self.xMin = 0.0
        self.xLabel = "simulator configurations"
        self.xMajorStride = 0.0
        self.xMinorStride = 0.0

        self.yInches = 5
        self.yMax = 1000.0
        self.yMin = 0.0
        self.yLabel = key
        self.yNumMajorStrides = 10
        self.yMajorStride = 0
        self.yMinorStride = 0.0

        self.li_OverflownYValues = []

        if options.trials == 1:  # We plan to do only one trial
            JGraph.DRAW_ERROR_BARS = False

    def convertToEPS(self):
        cwd = os.getcwd()
        os.chdir(self.outputPath)

        str_cmdLine = "jgraph " + self.name + " > " + self.key + ".eps"
        call(str_cmdLine, shell=True)

        os.chdir(cwd)

    def convertToPNG(self):
        cwd = os.getcwd()
        os.chdir(self.outputPath)

        str_cmdLine = ("convert -density 300 " + self.key + ".eps" +
                       " -resize 1024x1024 " + self.key + ".png")
        call(str_cmdLine, shell=True)

        os.chdir(cwd)

    # http://stackoverflow.com/questions/20261517/inheritance-of-private-and-protected-methods-in-python
    def _createGraph(self):
        f = open(self.name, "w")
        return f

    def _startGraph(self, f):
        f.write("newgraph\n")
        str_line = "title : Compare "
        if self.normalize:
            str_line += " normalized "
        else:
            str_line += " absolute "
        str_line += " values of " + self.key + " \n\n"
        f.write(str_line)

    def _merge(self, lrs, key):
        """Merge result set over all trials with the given key. This method
        does takes care of benchmarks and configurations/tools."""
        li_union = []
        # Limit based on benchmarks and tools
        for bench in self.options.getBenchTuple():
            for tool in self.options.getSimulatorsTuple():
                di_known = {}
                di_known["bench"] = bench
                di_known["tool"] = tool
                rs = ResultSet.limitResultSetWithDict(lrs, di_known)
                if len(rs) == 0:
                    continue

                di_ms = Merge.merge(rs, key)
                # Union the two dictionaries
                di_known = dict(di_known, **di_ms)
                li_union.append(di_known)
        return li_union

    def _computeAxes(self, li_di_thisKey):
        """Compute the max dimension for the axes."""
        self.xMax = self.BENCH_PADDING * len(self.options.getBenchTuple())
        self.xMax += self.SUMMARY_PADDING + self.BENCH_PADDING

        if self.normalize:
            self.yMax = 2.01
            self.yMajorStride = 0.2
        else:
            li_values = []
            for di_tmp in li_di_thisKey:
                li_values.append(di_tmp.get(self.key))

            if (len(li_values) == 0):
                print(li_values)
                print(self.key)
                print(li_di_thisKey)

            _dec, _in = math.modf(max(li_values))
            self.yMax = _in

            if self.yMax == 0.0:
                self.yMax = 1.01
                self.yMajorStride = 0.1
            elif self.yMax > 10.0:
                self.yMajorStride = int(self.yMax / 10)
            elif self.yMax > 1.0:
                self.yMajorStride = 1.0
            else:
                self.yMajorStride = self.yMax / 10

    def _generateAxes(self, f):
        self.__generateAxis(f, True)
        self.__generateAxis(f, False)
        f.write("hash_labels fontsize " + str(self.FONTSIZE) + "\n\n")
        self.__drawHorizontalLines(f)

    def __drawHorizontalLines(self, f):
        in_yVal = 0.0
        while True:
            f.write("newline poly linethickness " +
                    str(JGraph.MAJOR_STRIDE_THICKNESS) + "\n")
            f.write("  pts " + str(self.xMin) + " " + str(in_yVal) + " " +
                    str(self.xMax) + " " + str(in_yVal) + "\n")
            in_yVal += self.yMajorStride
            if in_yVal > self.yMax:
                break
        f.write("\n")

    def _writeHashLabel(self, f, bench, pos):
        f.write("xaxis hash_label at " + str(pos) + " : " + bench)
        f.write("\n")

    def __generateAxis(self, f, isX):
        if isX:
            f.write("xaxis\n")
            f.write("  min 0.0\n")
            f.write("  max " + str(self.xMax) + "\n")
            f.write("  size " + str(self.xInches) + "\n")
            f.write("  no_auto_hash_labels\n")
            f.write("  no_draw_hash_marks\n")
            f.write("  hash 1.0\n\n")

            f.write("hash_labels hjl vjt rotate -" +
                    str(self.X_TEXT_ANGLE) + " fontsize " +
                    str(self.FONTSIZE) + "\n\n")
        else:
            f.write("yaxis\n")
            f.write("  label : " + self.key + "\n")
            f.write("  fontsize " + str(JGraph.FONTSIZE) + "\n")
            f.write("  min 0.0\n")
            f.write("  max " + str(self.yMax) + "\n")
            f.write("  size " + str(self.yInches) + "\n")
            f.write("  hash " + str(self.yMajorStride) + "\n\n")

    def _customizeLegend(self, f):
        """Write the legends string."""
        f.write("legend defaults fontsize " + str(self.FONTSIZE) + "\n")
        f.write("\n")

    def _plotOverflownYValues(self, f):
        for str_cmd in self.li_OverflownYValues:
            f.write(str_cmd + "\n")
        f.write("\n")

    def _drawTextCommand(self, x_pos, y_pos, str_text):
        return ("newstring vjc hjc x " + str(x_pos) + " y " + str(y_pos) +
                " fontsize " + str(self.FONTSIZE) + " : " + str_text)

    def __mean_confidence_interval(self, data, confidence=0.95):
        """Compute 95% confidence intervals."""
        a = 1.0 * numpy.array(data)
        n = len(a)
        m, se = numpy.mean(a), scipy.stats.sem(a)
        h = se * scipy.stats.t._ppf((1 + confidence) / 2., n - 1)
        return m, m - h, m + h

    def __drawLine(self, f, width, srcX, srcY, destX, destY):
        f.write("newline poly linethickness " + str(width) + "\n")
        f.write("  pts " + str(srcX) + " " + str(srcY) + " " + str(destX) +
                " " + str(destY))
        f.write("\n")

    def _generateErrorBars(self, f, li_bench, fl_barStart, fl_width,
                           str_tool, str_bench):
        di_limit = {}
        di_limit["bench"] = str_bench
        di_limit["tool"] = str_tool
        di_limitedRs = ResultSet.limitResultSetWithDict(self.rs, di_limit)
        li_values = []
        for d in di_limitedRs:
            li_values.append(d.get(self.key))
        m, lci, uci = self.__mean_confidence_interval(li_values)
        if self.normalize:
            if m > 0.0:
                li_actValue = ResultSet.limitResultSetWithDict(
                    li_bench, di_limit)
                assert len(li_actValue) == 1
                di_actValue = li_actValue[0]
                fl_actValue = di_actValue.get(self.key)
                lci = fl_actValue - ((m - lci) / m)
                uci = fl_actValue + ((uci - m) / m)

        # Let us not plot the error bars if the lci is not within the yMax,
        # because otherwise it is going to look weird
        if lci <= self.yMax:
            self.__drawLine(f, fl_width, fl_barStart, lci, fl_barStart, uci)
            self.__drawLine(f, fl_width, fl_barStart - fl_width / 2, lci,
                            fl_barStart + fl_width / 2, lci)
            self.__drawLine(f, fl_width, fl_barStart - fl_width / 2, uci,
                            fl_barStart + fl_width / 2, uci)
