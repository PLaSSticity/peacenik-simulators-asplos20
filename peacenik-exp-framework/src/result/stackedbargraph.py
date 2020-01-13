import collections
import copy
import itertools
import math
import os

from option.constants import Constants
from option.benchmarks import Benchmark
from result.jgraph import JGraph
from result.mergetype import Merge
from result.mergetype import MergeType
from result.resultset import ResultSet
from result.statskeys import StackedKeys


class StackedBarGraph(JGraph, Constants):
    def __init__(self, options, outputPath, htmFile, key, rs, di_stackedKeys):
        super(StackedBarGraph, self).__init__(options, outputPath, htmFile,
                                              key, rs, True)

        # Since we want are generating stacked bars, we want to ideally see
        # every component of the stacked bars. This implies that the
        # max y-axis value should be accommodate all values. This can only be
        # done after normalizing the data for each individual
        # benchmark and configuration.
        self.fl_computedYMax = 0.0  # for this key

        # Get number of components for this key
        self.li_components = di_stackedKeys.get(self.key)
        assert len(self.li_components) == StackedKeys.LEN_VALUES
        # Get the number of components for this tool
        numMesiComps = len(self.li_components[StackedKeys.MESI_OFFSET])
        numViserComps = len(self.li_components[StackedKeys.VISER_OFFSET])
        numRCCSIComps = len(self.li_components[StackedKeys.RCCSI_OFFSET])
        numPAUSEComps = len(self.li_components[StackedKeys.PAUSE_OFFSET])
        self.numComps = (
            numMesiComps + numViserComps + numRCCSIComps + numPAUSEComps)
        # Get the color increments at the very beginning
        # The shade range is divided into the number of bars, and each bar
        # is again divided based on the number of components
        self.numShades = self.numComps + len(self.options.getSimulatorsTuple())
        self.fl_shadeInc = (self.SHADE_END - self.SHADE_START) / self.numShades
        self.fl_MESIStart = self.SHADE_START
        self.fl_ViserStart = self.fl_MESIStart + \
            self.fl_shadeInc * numMesiComps
        self.fl_RCCSIStart = self.fl_ViserStart + \
            self.fl_shadeInc * (numMesiComps + numViserComps)
        self.fl_PAUSEStart = self.fl_RCCSIStart + \
            self.fl_shadeInc * (numMesiComps + numViserComps + numRCCSIComps)

    def generateFile(self):
        """Create a stacked Jgraph file with the given data in the directory
        specified."""

        cwd = os.getcwd()
        os.chdir(self.outputPath)

        try:
            li_MergedWithKey = self._merge(self.rs, self.key)

            file = self._createGraph()
            self._startGraph(file)

            li_benchNormData = self.__plotData(file, li_MergedWithKey, True)

            self.__computeAxes()
            self._generateAxes(file)
            self._customizeLegend(file)

            self.__plotData(file, li_MergedWithKey, False)
            self.__plotSummary(file, li_MergedWithKey, li_benchNormData)
            self._plotOverflownYValues(file)

        finally:
            file.close()
            os.chdir(cwd)

    def __computeAxes(self):
        """Compute the max dimension for the axes."""

        self.xMax = self.BENCH_PADDING * len(self.options.getBenchTuple())
        self.xMax += self.SUMMARY_PADDING + self.BENCH_PADDING
        if (self.options.isServerPresent()):
            self.xMax += self.EXTRA_PADDING_FOR_SERVER

        # We add an epsilon so that floating point less than comparisons
        # succeed
        self.yMax = math.ceil(self.fl_computedYMax) + 0.001
        # Since MESI is usually normalized to one

        # RZ: With estimated costs of restarting a whole program included, yMax
        # can be very large.

        if "BandwidthDriven" in self.key:
            maxY = 2.0
        elif "OnChip" in self.key:
            maxY = 3.5
        else:
            maxY = 5.0
        '''
        if "BandwidthDriven" in self.key:
            maxY = 1.5
        elif "OnChip" in self.key:
            maxY = 1.5
        else:
            maxY = 2.0
        '''
        if self.yMax > maxY:
            self.yMax = maxY
            if self.yMax >= 4:
                self.yMajorStride = 1.0
            else:
                self.yMajorStride = 0.5
        elif self.yMax > 1.0:
            self.yMajorStride = 1.0
        else:
            self.yMajorStride = (math.ceil(self.fl_computedYMax) / 10)

    def __plotData(self, f, li_keyData, b_computeMaxY):
        """Write Jgraph strings for individual benchmarks and configs."""

        fl_benchLabelPos = JGraph.INITIAL_PADDING
        b_printLabel = True  # just for the first benchmark
        isFirstBench = True
        isFirstServer = True

        # Normalized to the first configuration's "NormalExec" component?
        isNorm2Comp = False

        li_NormData = []
        for bench in self.options.getBenchTuple():
            if not b_computeMaxY:
                self._writeHashLabel(f, bench, fl_benchLabelPos)

            di_bench = {}
            di_bench["bench"] = bench
            # li_keyData includes results from all benchmarks for the given key
            li_benchRawData = ResultSet.limitResultSetWithDict(
                li_keyData, di_bench)

            li_benchNormData = copy.deepcopy(li_benchRawData)

            di_firstTool = {}
            di_firstTool["tool"] = self.options.getSimulatorsTuple()[0]
            if Constants.isPauseConfig(di_firstTool["tool"]):
                # Normalize the data wrt the first configuration's *normal execution*
                # if the first configuration is ARC or Pacifist.
                isNorm2Comp = True
                normKey = self.li_components[StackedKeys.PAUSE_OFFSET][0]
                # print(normKey)
                # print(self.rs)
                li_di_compKey = self._merge(self.rs, normKey)
                # limit to current benchmark and the first configuration
                di_extractor = {}
                di_extractor["tool"] = di_firstTool["tool"]
                di_extractor["bench"] = bench
                li_comp = ResultSet.limitResultSetWithDict(
                    li_di_compKey, di_extractor)
                assert len(li_comp) == 1
                di_compValue = li_comp[0]
                divValue = float(di_compValue[normKey])
                # print("Normalized to " + di_firstTool["tool"] + ":" + normKey)

            if divValue > 0:
                for tmp in li_benchNormData:
                    tmp[self.key] = (
                        tmp[self.key] / divValue)
            else:
                for tmp in li_benchNormData:
                    tmp[self.key] = float("inf")

            # li_benchNormData now has normalized data
            for d in li_benchNormData:
                li_NormData.append(d)

            in_numConfigs = len(li_benchNormData)
            # Dimension of each bar
            if Benchmark.isHTTPDBenchmark(bench):
                if (not isFirstBench and isFirstServer):
                    fl_benchLabelPos += self.EXTRA_PADDING_FOR_SERVER
                isFirstServer = False
            isFirstBench = False

            fl_barWidth = ((self.BENCH_PADDING - (2 * self.BAR_PADDING)) /
                           in_numConfigs)

            fl_barStart = (fl_benchLabelPos -
                           ((in_numConfigs - 1) * fl_barWidth / 2))

            toolCount = 1
            b_mesiTool = True
            b_viserTool = True
            b_rccsiTool = True
            b_pauseTool = True
            for tool in self.options.getSimulatorsTuple():
                di_tool = {}
                di_tool["tool"] = tool
                li_toolNormData = ResultSet.limitResultSetWithDict(
                    li_benchNormData, di_tool)
                assert len(li_toolNormData) == 1
                li_toolRawData = ResultSet.limitResultSetWithDict(
                    li_benchRawData, di_tool)
                assert len(li_toolRawData) == 1
                # So we have the raw and normalized data corresponding to the
                # benchmark, configuration and the key

                # get the number of components for this tool
                li_comps = []
                if Constants.isMESIConfig(tool):
                    li_comps = self.li_components[StackedKeys.MESI_OFFSET]
                    fl_shadeStart = self.fl_MESIStart
                elif Constants.isViserConfig(tool):
                    li_comps = self.li_components[StackedKeys.VISER_OFFSET]
                    fl_shadeStart = self.fl_ViserStart
                elif Constants.isRCCSIConfig(tool):
                    li_comps = self.li_components[StackedKeys.RCCSI_OFFSET]
                    fl_shadeStart = self.fl_RCCSIStart
                elif Constants.isPauseConfig(tool):
                    li_comps = self.li_components[StackedKeys.PAUSE_OFFSET]
                    fl_shadeStart = self.fl_PAUSEStart
                else:
                    assert(False)

                di_compProps = collections.OrderedDict()
                # Because of the inadequacy with JGraph, we first compute the y
                # proportions for each component and save them, and only then
                # think about writing to the .jgr file
                for i in range(0, len(li_comps)):
                    str_compKey = li_comps[i]
                    self.__computeProps(di_compProps, str_compKey,
                                        li_toolRawData, li_toolNormData, bench,
                                        tool)

                # The sum of all the values should be one, but it is not
                # because we take into account the multiplier

                di_compSums = collections.OrderedDict().fromkeys(
                    di_compProps, 0.0)

                i = 1
                for key in di_compProps:
                    fl_sum = 0
                    di_tmp = itertools.islice(di_compProps.items(), 0, i)
                    for key, value in di_tmp:
                        fl_sum += value
                    di_compSums[key] = fl_sum
                    i = i + 1

                # Now reverse the ordered dict
                li_items = list(di_compSums.items())
                li_items.reverse()
                di_compJGRPositions = collections.OrderedDict(li_items)

                patternCounter = 0
                for str_compKey in di_compJGRPositions:
                    fl_yPos = di_compJGRPositions.get(str_compKey)
                    if fl_yPos > self.fl_computedYMax:
                        self.fl_computedYMax = fl_yPos
                    if not b_computeMaxY:
                        self.__drawWhiteXBar(fl_barWidth, f)
                        self.__generatePoints(
                            f, fl_barStart, fl_yPos, toolCount)
                        if (b_printLabel and
                            ((Constants.isMESIConfig(tool) and
                              b_mesiTool) or
                             (Constants.isViserConfig(tool) and
                              b_viserTool) or
                             (Constants.isRCCSIConfig(tool) and
                              b_rccsiTool) or
                             (Constants.isPauseConfig(tool) and
                              b_pauseTool))):
                            self.__startXBar(fl_shadeStart, fl_barWidth, f,
                                             True, patternCounter,
                                             str_compKey)
                        else:
                            self.__startXBar(fl_shadeStart, fl_barWidth, f,
                                             False, patternCounter,
                                             str_compKey)
                        self.__generatePoints(
                            f, fl_barStart, fl_yPos, toolCount)
                    patternCounter = patternCounter + 1
                    if ("RegionRestartOnChip" in str_compKey
                            or "RegionRestartOffChip" in str_compKey):
                        patternCounter = patternCounter + 1
                    fl_shadeStart += self.fl_shadeInc

                    if not b_computeMaxY and self.DRAW_ERROR_BARS:
                        self._generateErrorBars(f, li_benchNormData,
                                                fl_barStart,
                                                fl_barWidth / 2, tool,
                                                bench)

                fl_barStart += fl_barWidth
                toolCount += 1
                if Constants.isMESIConfig(tool):
                    b_mesiTool = False
                elif Constants.isViserConfig(tool):
                    b_viserTool = False
                elif Constants.isRCCSIConfig(tool):
                    b_rccsiTool = False
                elif Constants.isPauseConfig(tool):
                    b_pauseTool = False
            if b_printLabel:
                b_printLabel = False
            fl_benchLabelPos += self.BENCH_PADDING
            if not b_computeMaxY:
                f.write("\n")
        if isNorm2Comp:
            return li_NormData
        else:
            return None

    def __drawWhiteXBar(self, fl_width, f):
        str_shade = "1.0 1.0 1.0"
        str_write = ("newcurve marktype xbar cfill " + str_shade)
        str_write += (" marksize " + str(round(fl_width,
                                               self.PRECISION_DIGITS)) +
                      " linethickness 0")
        f.write(str_write + "\n")

    def __computeProps(self, di_compProps, str_compKey, li_toolRawData,
                       li_toolNormData, str_bench, str_tool):
        li_di_compKey = self._merge(self.rs, str_compKey)
        # limit to current benchmark and configuration
        di_extractor = {}
        di_extractor["tool"] = str_tool
        di_extractor["bench"] = str_bench
        li_comp = ResultSet.limitResultSetWithDict(li_di_compKey, di_extractor)
        assert len(li_comp) == 1
        di_compValue = li_comp[0]
        fl_comp = float(di_compValue[str_compKey])

        assert len(li_toolRawData) == 1
        fl_base = float(li_toolRawData[0][self.key])

        # print(str_compKey)
        # print(self.key)
        # print(fl_comp)
        # print(fl_base)
        assert fl_comp <= fl_base
        fl_configProp = (fl_comp / fl_base)
        # This is just component by total for one config, but we need to
        # compute the overall proportion normalized to one simulator tool

        assert len(li_toolNormData) == 1
        fl_multiplier = float(li_toolNormData[0][self.key])

        fl_overallProp = fl_configProp * fl_multiplier

        di_compProps[str_compKey] = fl_overallProp

    def __startXBar(self, fl_shade, fl_width, f, b_print, i,
                    str_compKey):
        # str_shade = str(round(fl_shade, self.PRECISION_DIGITS))
        # str_write = ("newcurve marktype xbar cfill " +
        #              str_shade + " " + str_shade + " " + str_shade)
        # if i % 2 == 0:
        #     str_write += " pattern solid "
        # else:
        #     str_write += " pattern estripe " + str(45 * i)
        if "Reboot" in str_compKey:
            str_shade = "0.9 0 0"
            str_pattern = "solid"
            str_label = "ARC/PF: program reboots"
        elif "RegionRestart" in str_compKey:
            str_shade = "0.8 0.7 0.1"
            str_pattern = "solid"
            str_label = "PF: region restarts"
        elif "Pause" in str_compKey:
            str_shade = "0.3 0.64 0.84"
            str_pattern = "estripe 45"
            str_label = "PF: pause"
        elif "NormalExec" in str_compKey:
            str_shade = "0.61 0.61 0.61"
            str_pattern = "estripe 135"
            str_label = "ARC/PF: normal execution"
        else:
            str_shade = "0.81 0.81 0.81"
            str_pattern = "solid"
            str_label = "MESI: entire execution"

        str_write = ("newcurve marktype xbar cfill " +
                     str_shade + " pattern " + str_pattern + " ")

        str_write += (" marksize " + str(round(fl_width,
                                               self.PRECISION_DIGITS)) +
                      " linethickness 0")
        if b_print:
            str_write += (" label : " + str_label)

        f.write(str_write + "\n")

    def __generatePoints(self, f, fl_barStart, fl_yStart, toolCount):
        if fl_yStart > self.yMax:
            self.__graphPoint(f, fl_barStart, self.yMax)
            str_text = str(round(fl_yStart,
                                 JGraph.PRECISION_DIGITS))
            fl_yPos = self.yMax + \
                (self.yMax - self.yMin) * 0.05 * (toolCount - 1)
            str_cmd = self._drawTextCommand(fl_barStart, fl_yPos,
                                            str_text)
            self.li_OverflownYValues.append(str_cmd)
        else:
            self.__graphPoint(f, fl_barStart, fl_yStart)

    def __graphPoint(self, f, fl_barStart, y):
        f.write("  pts " + str(round(fl_barStart, self.PRECISION_DIGITS)) +
                " " + str(round(y, self.PRECISION_DIGITS)) + "\n")

    def __plotSummary(self, file, li_keyData, li_di_normValues):
        """Plot summary results, based on tool as the key."""

        str_type = ""
        if JGraph.SUMMARY_MERGE_TYPE == MergeType.MERGE_GEOMEAN:
            str_type = "geomean"
        else:
            str_type = "average"

        fl_barStart = self.xMax - self.BENCH_PADDING + self.SUMMARY_PADDING
        self._writeHashLabel(file, str_type, fl_barStart)

        in_numConfigs = len(self.options.getSimulatorsTuple())
        fl_shadeStart = self.SHADE_START + (self.numComps * self.fl_shadeInc)

        fl_barWidth = ((self.BENCH_PADDING - (2 * self.BAR_PADDING)) /
                       in_numConfigs)
        fl_barStart = (fl_barStart - ((in_numConfigs - 1) * fl_barWidth / 2))

        b_firstTool = True
        fl_Value = 0.0
        toolCount = 1
        for tool in self.options.getSimulatorsTuple():
            di_tool = {}
            di_tool["tool"] = tool
            if li_di_normValues is None:
                li_toolRawData = ResultSet.limitResultSetWithDict(
                    li_keyData, di_tool)
            else:
                li_toolRawData = ResultSet.limitResultSetWithDict(
                    li_di_normValues, di_tool)
            assert((len(li_toolRawData) > 0) and
                   (len(li_toolRawData) == len(self.options.getBenchTuple())))

            li_toolNormData = copy.deepcopy(li_toolRawData)

            di_normMerged = Merge.merge(li_toolNormData, self.key,
                                        JGraph.SUMMARY_MERGE_TYPE)

            if li_di_normValues is None and self.normalize:
                if b_firstTool:
                    fl_Value = di_normMerged.get(self.key)
                    b_firstTool = False
                    if fl_Value > 0.0:
                        di_normMerged[self.key] = 1.0
                    else:
                        fl_Value = 0.0
                else:
                    if fl_Value > 0.0:
                        _factor = di_normMerged.get(self.key) / fl_Value
                    else:
                        # _factor = 0.0
                        _factor = float("inf")
                    di_normMerged[self.key] = _factor

            self.__startSummaryBar(file, fl_shadeStart, fl_barWidth, tool)
            self.__generateSummaryBar(file, fl_barStart, di_normMerged,
                                      toolCount)

            fl_shadeStart += self.fl_shadeInc
            fl_barStart += fl_barWidth
            toolCount += 1

        file.write("\n")

    def __computeSummaryProps(self, di_compProps, str_compKey,
                              di_toolRawData, di_toolNormData, str_tool):
        assert(len(di_toolRawData) == 1 and len(di_toolNormData) == 1)
        fl_base = float(di_toolRawData[self.key])

        li_di_compKey = self._merge(self.rs, str_compKey)
        # limit to current benchmark and configuration
        di_extractor = {}
        di_extractor["tool"] = str_tool
        li_compData = ResultSet.limitResultSetWithDict(
            li_di_compKey, di_extractor)
        assert(len(li_compData) == len(self.options.getBenchTuple()))

        di_rawCompMerged = Merge.merge(li_compData, str_compKey,
                                       JGraph.SUMMARY_MERGE_TYPE)
        assert(len(di_rawCompMerged) == 1)
        fl_comp = float(di_rawCompMerged[str_compKey])

        assert fl_comp <= fl_base
        fl_configProp = (fl_comp / fl_base)
        # This is just component by total for one config, but we need to
        # compute the overall proportion normalized to one simulator tool
        assert len(di_toolNormData) == 1
        fl_multiplier = float(di_toolNormData[self.key])
        fl_overallProp = fl_configProp * fl_multiplier
        di_compProps[str_compKey] = fl_overallProp

    def __startSummaryBar(self, file, fl_shade, fl_barWidth, str_tool):
        str_shade = str(round(fl_shade, self.PRECISION_DIGITS))
        str_write = ("newcurve marktype xbar cfill " + str_shade + " " +
                     str_shade + " " + str_shade + " pattern solid ")
        str_write += (" marksize " + str(round(fl_barWidth,
                                               self.PRECISION_DIGITS)) +
                      " linethickness 0")
        str_write += (" label : " + str_tool)
        file.write(str_write + "\n")

    def __generateSummaryBar(self, f, fl_barStart, di_normMerged, toolCount):
        fl_yStart = di_normMerged[self.key]
        if fl_yStart > self.yMax:
            self.__graphPoint(f, fl_barStart, self.yMax)
            str_text = str(round(fl_yStart,
                                 JGraph.PRECISION_DIGITS))
            fl_yPos = self.yMax + \
                (self.yMax - self.yMin) * 0.05 * (toolCount - 1)
            str_cmd = self._drawTextCommand(fl_barStart, fl_yPos,
                                            str_text)
            self.li_OverflownYValues.append(str_cmd)
        else:
            self.__graphPoint(f, fl_barStart, fl_yStart)
