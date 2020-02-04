#!/bin/sh

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 results_dir_name"
    exit
fi

CMD="viser-local --tools=pintool,viseroptregularplru32,pause32,restartunopt32,restartopt32 --tasks=clean,build,run,result --workload=simsmall --bench=canneal,streamcluster,httpd,mysqld --pinThreads=32 --output=$1 --trials=1 --assert=False --xassert=False --roiOnly=True --project=viser --cores=32 --siteTracking=True --verbose=0 --lockstep=True --pinTool=viserST"

: '
Simulated configurations and their corresponding names in Fig. 3a in the paper
    viseroptregularplru32: ARC 
    pause32: PNp
    restartunopt32: PNpr
    restartopt32: PN
'

echo $CMD
eval $CMD

echo "\n*********** Check out results by opening file://<exp-products-dir>/$1/index.html in a web browser." 
