#!/bin/sh

LIB_PATH=./lib
ARGS=$*

for i in `ls -1 $LIB_PATH`
do
       WSC_CLASSPATH=$WSC_CLASSPATH$LIB_PATH"/"$i":"
done

if [ $# -lt 4 ]; then
	if [ "$1" = "" ]; then
		ARGS="./config/as2-partnership.xml"
	fi
	if [ "$2" = "" ]; then
		ARGS="$ARGS ./config/as2-send/as2-request.xml"	
	fi
	if [ "$3" = "" ]; then
		ARGS="$ARGS ./logs/as2-send.log"
	fi
	if [ "$4" = "" ]; then
		ARGS="$ARGS ./config/as2-send/testpayload"
	fi
fi

echo $ARGS

EXEC="java -cp $WSC_CLASSPATH hk.hku.cecid.corvus.ws.AS2MessageSender $ARGS"
echo $EXEC
exec $EXEC
