#!/bin/sh

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 results_dir_name"
    exit
fi

CMD="viser-local --tools=pintool,viseroptregularplru,pause,restartunopt,restartopt,mesiregularplru --tasks=clean,build,run,result --workload=test --bench=streamcluster --pinThreads=4 --output=$1 --trials=1 --assert=False --xassert=False --roiOnly=True --project=viser --cores=4 --siteTracking=True --verbose=0 --lockstep=True --pinTool=viserST"
#CMD="viser-local --tools=pintool,viseroptregularplru --tasks=clean,build,run,result --workload=test --bench=canneal --pinThreads=4 --output=$1 --trials=1 --assert=False --xassert=False --roiOnly=True --project=viser --cores=4 --siteTracking=True --verbose=0 --lockstep=True --pinTool=viserST"

echo $CMD
eval $CMD

echo "\n*********** Check out results by opening file://<exp-products-dir>/$1/index.html in a web browser." 
