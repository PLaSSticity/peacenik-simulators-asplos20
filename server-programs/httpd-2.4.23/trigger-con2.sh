#!/bin/sh

CNT=1
CNT_THRESHOLD=$1

while [ $CNT -ne $CNT_THRESHOLD ]; do
        #echo "Console0: iteration $CNT"

        #wget -q -O con0.out http://127.0.1.1:8088/pippo.php?variable=0000      
        wget -q -O con0.out http://127.0.1.1:8080/
        # sleep 2
        CNT=`expr $CNT + 1`
done
