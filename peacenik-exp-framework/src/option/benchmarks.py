
class Benchmark:
    """Wrapper class for allowed benchmarks."""

    PARSEC = ["blackscholes", "bodytrack", "canneal", "dedup", "facesim",
              "ferret", "fluidanimate", "raytrace", "streamcluster",
              "swaptions", "vips", "x264"]
    SPLASH2X = ["barnes", "cholesky", "fft", "fmm", "lu_cb", "lu_ncb",
                "ocean_cp", "ocean_ncp", "radiosity", "radix", "raytrace",
                "volrend", "water_nsquared", "water_spatial"]
    HTTPD = ["httpd", "mysqld"]

    @staticmethod
    def isParsecBenchmark(bench):
        if bench in Benchmark.PARSEC:
            return True
        return False

    @staticmethod
    def isSplash2xBenchmark(bench):
        if bench in Benchmark.SPLASH2X:
            return True
        return False

    @staticmethod
    def isHTTPDBenchmark(bench):
        if bench in Benchmark.HTTPD:
            return True
        return False
