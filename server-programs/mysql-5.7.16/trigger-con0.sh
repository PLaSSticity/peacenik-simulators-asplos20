#!/bin/sh

CNT=1
CNT_THRESHOLD=$1

while [ $CNT -ne $CNT_THRESHOLD ]; do
        #echo "Console0: iteration $CNT"

        #/usr/local/mysql/bin/mysql -h 127.0.0.1 -u root -D test -e "insert into t1 values (1)"      
        $MYSQLD_ROOT/bin/mysql -u root -D test -e 'insert into t1 values (1)'
        sleep 30
        CNT=`expr $CNT + 1`
done
