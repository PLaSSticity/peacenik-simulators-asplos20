#!/bin/sh

CNT=1
CNT_THRESHOLD=$1

while [ $CNT -ne $CNT_THRESHOLD ]; do
        #echo "Console1: iteration $CNT"

        #wget -q -O con0.out http://127.0.1.1:8088/pippo.php?variable=0000      
        wget -q -O con1.out http://127.0.1.1:8080/image1.png
        CNT=`expr $CNT + 1`
done
