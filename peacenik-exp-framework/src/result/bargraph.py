from math import log10, floor
import os

from option.constants import Constants
from result.jgraph import JGraph
from result.mergetype import Merge
from result.mergetype import MergeType
from result.resultset import ResultSet


class BarGraph(JGraph, Constants):

    def __init__(self, options, outputPath, htmFile, key, rs, norm):
        super(BarGraph, self).__init__(
            options, outputPath, htmFile, key, rs, norm)

    def generateFile(self):
        """Create a jgraph file with the given data in the directory
        specified."""
        cwd = os.getcwd()
        os.chdir(self.outputPath)

        try:
            f = self._createGraph()
            self._startGraph(f)
            li_di_thisKey = self._merge(self.rs, self.key)
            self._computeAxes(li_di_thisKey)
            self._generateAxes(f)
            self._customizeLegend(f)
            self.__plotBenchData(li_di_thisKey, f)
            self.__plotSummary(li_di_thisKey, f)
            self._plotOverflownYValues(f)
        finally:
            f.close()
            os.chdir(cwd)

    def __plotSummary(self, data, f):
        """Plot summary results, based on tool as the key."""
        str_type = ""
        if JGraph.SUMMARY_MERGE_TYPE == MergeType.MERGE_GEOMEAN:
            str_type = "geomean"
        else:
            str_type = "average"

        fl_barStart = self.xMax - self.BENCH_PADDING + self.SUMMARY_PADDING
        self._writeHashLabel(f, str_type, fl_barStart)

        in_numBars = len(self.options.getSimulatorsTuple())
        fl_shadeInc = ((self.SHADE_END - self.SHADE_START) / in_numBars)
        fl_shadeStart = self.SHADE_START

        fl_width = ((self.BENCH_PADDING - (2 * self.BAR_PADDING)) /
                    in_numBars)
        fl_barStart = (fl_barStart - ((in_numBars - 1) * fl_width / 2))

        b_firstTool = True
        fl_Value = 0.0
        toolCount = 1
        for tool in self.options.getSimulatorsTuple():
            di_tool = {}
            di_tool["tool"] = tool
            li_di_bench = ResultSet.limitResultSetWithDict(data, di_tool)
            di_merged = {}
            if (len(li_di_bench) == 0):
                di_merged[self.key] = 0
            else:
                di_merged = Merge.merge(li_di_bench, self.key,
                                        JGraph.SUMMARY_MERGE_TYPE)

            if self.normalize:
                if b_firstTool:
                    fl_Value = di_merged.get(self.key)
                    b_firstTool = False
                    if fl_Value > 0.0:
                        di_merged[self.key] = 1.0
                    else:
                        fl_Value = 0.0
                else:
                    if fl_Value > 0.0:
                        _factor = di_merged.get(self.key) / fl_Value
                    else:
                        _factor = 0.0
                    di_merged[self.key] = _factor

            self.__startBar(fl_shadeStart, fl_width, f, False, tool)
            self.__generateBar(
                f, None, fl_barStart, None, True, di_merged, toolCount)
            fl_barStart += fl_width
            fl_shadeStart += fl_shadeInc
            toolCount += 1

        f.write("\n")

    def __plotBenchData(self, data, f):
        """Write jgraph strings for individual benchmarks."""

        fl_benchLabelPos = JGraph.INITIAL_PADDING
        fl_shadeStart = self.SHADE_START
        fl_shadeInc = ((self.SHADE_END - self.SHADE_START) /
                       (len(self.options.getSimulatorsTuple())))
        b_printLabel = True

        for bench in self.options.getBenchTuple():
            self._writeHashLabel(f, bench, fl_benchLabelPos)
            di_bench = {}
            di_bench["bench"] = bench
            li_bench = ResultSet.limitResultSetWithDict(data, di_bench)

            if self.normalize:  # Normalize the data
                str_firstTool = self.options.getSimulatorsTuple()[0]
                di_lookup = {}
                di_lookup["tool"] = str_firstTool
                li_div = ResultSet.limitResultSetWithDict(li_bench, di_lookup)
                assert len(li_div) == 1
                if li_div[0].get(self.key) > 0:
                    for tmp in li_bench:
                        tmp[self.key] = (
                            tmp[self.key] / li_div[0].get(self.key))
                else:
                    for tmp in li_bench:
                        tmp[self.key] = float("inf")

            # li_bench now has normalized data if enabled

            in_numBars = len(li_bench)
            fl_width = ((self.BENCH_PADDING - (2 * self.BAR_PADDING)) /
                        in_numBars)

            fl_barStart = (fl_benchLabelPos -
                           ((in_numBars - 1) * fl_width / 2))
            fl_shadeStart = self.SHADE_START
            toolCount = 1

            for tool in self.options.getSimulatorsTuple():
                for di_entry in li_bench:
                    if di_entry.get("tool") == tool:
                        self.__startBar(fl_shadeStart, fl_width, f,
                                        b_printLabel, tool)
                        self.__generateBar(f, li_bench, fl_barStart, tool,
                                           False, None, toolCount)
                        if self.DRAW_ERROR_BARS:
                            self._generateErrorBars(f, li_bench, fl_barStart,
                                                    fl_width / 2, tool,
                                                    bench)
                        break
                fl_barStart += fl_width
                fl_shadeStart += fl_shadeInc
                toolCount += 1

            if b_printLabel:
                b_printLabel = False
            fl_benchLabelPos += self.BENCH_PADDING
            f.write("\n")

    def __generateBar(self, f, li_bench, fl_barStart, str_tool, b_summary,
                      val, toolCount):
        di_tool = {}
        if not b_summary:
            di_tool["tool"] = str_tool
            li_tool = ResultSet.limitResultSetWithDict(li_bench, di_tool)
            assert len(li_tool) == 1
            di_onlyResult = li_tool[0]
        else:
            di_onlyResult = val

        if di_onlyResult[self.key] > self.yMax:
            self.__graphPoint(f, fl_barStart, self.yMax)
            str_text = str(round(di_onlyResult[self.key],
                                 JGraph.PRECISION_DIGITS))
            fl_yPos = self.yMax + \
                (self.yMax - self.yMin) * 0.05 * (toolCount - 1)
            str_cmd = self._drawTextCommand(fl_barStart, fl_yPos,
                                            str_text)
            self.li_OverflownYValues.append(str_cmd)
        else:
            self.__graphPoint(f, fl_barStart, di_onlyResult[self.key])

    def __graphPoint(self, f, fl_barStart, y):
        f.write("  pts " + str(round(fl_barStart, self.PRECISION_DIGITS)) +
                " " + str(round(y, self.PRECISION_DIGITS)) + "\n")

    def __startBar(self, fl_shade, fl_width, f, b_print, str_tool):
        str_shade = str(round(fl_shade, self.PRECISION_DIGITS))
        f.write("newcurve marktype xbar cfill " + str_shade + " " + str_shade +
                " " + str_shade + " marksize " +
                str(round(fl_width, self.PRECISION_DIGITS)) +
                " linethickness 0")
        if b_print:
            f.write(" label : " + str_tool)
        f.write("\n")

    def round_to_1(self, x):
        """http://stackoverflow.com/questions/3410976/how-to-round-a-number-"""
        """to-figures-in-python"""
        return round(x, -int(floor(log10(x))))
