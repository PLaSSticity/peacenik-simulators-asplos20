#!/bin/sh

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 results_dir_name"
    exit
fi

CMD="viser-local --tools=pintool,viseroptregularplru32,viseroptregularplru32cp1k,viseroptregularplru32cp100,viseroptregularplru32cp10,viseroptregularplru32cp1,restartopt32 --tasks=clean,build,run,result --workload=simsmall --bench=canneal,streamcluster --pinThreads=32 --output=$1 --trials=1 --assert=False --xassert=False --roiOnly=True --project=viser --cores=32 --siteTracking=True --verbose=0 --lockstep=True --pinTool=viserST"

: '
Simulated configurations and their corresponding names in Fig. 5 in the paper
    viseroptregularplru32: ARC 
    viseroptregularplru32cpXX: ARC-cpXX
    restartopt32: PN
'

echo $CMD
eval $CMD

CMD="viser-local --tools=pintool,viseroptregularplru32,restartopt32 --tasks=clean,build,run,result --workload=simsmall --bench=httpd,mysqld --pinThreads=32 --output=$1 --trials=1 --assert=False --xassert=False --roiOnly=True --project=viser --cores=32 --siteTracking=True --verbose=0 --lockstep=True --pinTool=viserST"

: '
Simulated configurations and their corresponding names in Fig. 5 in the paper
    viseroptregularplru32: ARC 
    restartopt32: PN

Note that no extra configurations needed for the ARC-noreboot bars in Fig. 5 for the server programs. One just needs to hide/ignore the red components of the viseroptregularplru32/ARC bars to get ARC-noreboot bars.
'

echo $CMD
eval $CMD

echo "\n*********** Check out results by opening file://<exp-products-dir>/$1/index.html in a web browser." 
