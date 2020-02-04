#!/bin/sh

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 results_dir_name core_count(8|16|32)"
    exit
fi

if [ "$2" -ne 8 ] && [ "$2" -ne 16 ] && [ "$2" -ne 32 ]; then
    echo "Usage: $0 results_dir_name core_count(8|16|32)"
    exit
fi

cc="$2"

if [ "$2" -eq 8 ]; then
    cc=""
fi


CMD="viser-local --tools=pintool,ce$cc,cerestartopt$cc,mesiregularplru$cc --tasks=clean,build,run,result --workload=simsmall --bench=bodytrack,blackscholes,dedup,ferret,swaptions,canneal,vips,fluidanimate,streamcluster,x264,httpd,mysqld --pinThreads=$2 --output=$1 --trials=1 --assert=False --xassert=False --roiOnly=True --project=viser --cores=$2 --siteTracking=True --verbose=0 --lockstep=True --pinTool=viserST"

: '
Simulated configurations and their corresponding names in Fig. 4b in the paper
    ceCC: CE-CC 
    cerestartoptCC: PN-CC
    mesiregularplruCC: WMM-CC
'

echo $CMD
eval $CMD

echo "\n*********** Check out results by opening file://<exp-products-dir>/$1/index.html in a web browser." 
