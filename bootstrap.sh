#!/bin/bash

APP_NAME=akka-cluster
APP_VERSION=1.0-SNAPSHOT

if [ -z "${AKKA_CLUSTER_HOME}" ]; then
  export AKKA_CLUSTER_HOME="$(cd "`dirname "$0"`"/.; pwd)"
fi

JAVA_OPTS="-Dfile.encoding=UTF-8 -Dlog4j.configurationFile=file://${AKKA_CLUSTER_HOME}/conf/log4j2.xml "

JARS=$(echo ${AKKA_CLUSTER_HOME}/libs/*.jar | tr ' ' ':'):$(echo ${AKKA_CLUSTER_HOME}/jars/*.jar | tr ' ' ':'):$(echo ${AKKA_CLUSTER_HOME}/*/target/scala-2.11/*.jar | tr ' ' ':')

function setEnv() {
    export BIN_DIR=${AKKA_CLUSTER_HOME}
    export MODE=$1
    if [ "$1" == "dev" ]; then
        dev
    elif [ "$1" == "pro" ]; then
        pro
    else
        echo "unknow env."
        exit -1
    fi
}

function pro() {
    export PID_FILE=/var/run/release/${APP_NAME}-${MODULE}.pid
    export LOG_FILE=/var/log/release/${APP_NAME}-${MODULE}.${DATE_VERSION}.log
    JAVA_OPTS=${JAVA_OPTS}"-Xms2g -Xmx2g \
                           -XX:ParallelGCThreads=8 \
                           -XX:SurvivorRatio=1 \
                           -XX:LargePageSizeInBytes=128M \
                           -XX:MaxNewSize=1g  \
                           -XX:CMSInitiatingOccupancyFraction=80 \
                           -XX:+UseCMSCompactAtFullCollection \
                           -XX:CMSFullGCsBeforeCompaction=0 \
                           -XX:-UseGCOverheadLimit \
                           -XX:MaxTenuringThreshold=5  \
                           -XX:GCTimeRatio=19  \
                           -XX:+UseConcMarkSweepGC \
                           -XX:+UseParNewGC \
                           -XX:+PrintGCDetails \
                           -XX:+PrintGCTimeStamps \
                           -XX:+HeapDumpOnOutOfMemoryError \
                           -XX:HeapDumpPath=/var/log/release/${APP_NAME}-${MODULE}.dump \
                           -Xloggc:/var/log/release/${APP_NAME}-${MODULE}-gc.$DATE_VERSION.log"
}

function dev() {
    export PID_FILE=/tmp/${APP_NAME}-${MODULE}.${USER}.pid
    export LOG_FILE=/tmp/${APP_NAME}-${MODULE}.${USER}.log
    JAVA_OPTS=${JAVA_OPTS}"-XX:+PrintGCDetails \
                         -XX:+PrintGCTimeStamps \
                         -XX:+HeapDumpOnOutOfMemoryError \
                         -XX:HeapDumpPath=/tmp/${APP_NAME}-${MODULE}.dump \
                         -Xloggc:/tmp/${APP_NAME}-${MODULE}-gc.log"
}

function start_module() {
    MODULE=$1
    setEnv $2

    case "$1" in
    recall)
        MAIN_CLASS=com.zjw.actor.RecallServer
        ;;
    rerank)
        MAIN_CLASS=com.zjw.actor.RerankServer
        ;;
    engine)
        MAIN_CLASS=com.zjw.actor.EngineServer
        ;;
    *)
        echo "can not get main class."
        exit -1
    esac

    java $JAVA_OPTS -cp $JARS $MAIN_CLASS
}

function stop() {

    MODULE=$1

    setEnv $2

    echo "Stop App $APP_NAME-${MODULE}"
    if [ -s ${PID_FILE} ]; then
        echo "stopping ${APP_NAME}-${MODULE}: $(cat ${PID_FILE})"
        kill -9 $(cat ${PID_FILE})
        rm -f ${PID_FILE}
    else
        echo "pid file not found"
        exit 1
    fi
}

function status() {
    echo "$APP_NAME Status"
    if [ -s ${PID_FILE} ]; then
        ps h -fp $(cat ${PID_FILE})
    fi
}

function common_run() {
   java $JAVA_OPTS -cp $JARS "$@"
}

function usage() {
cat << EOF
  Usage: ./bootstrap.sh package
         ./bootstrap.sh start [engine|recall|rerank]
         ./bootstrap.sh stop [engine|recall|rerank]
         ./bootstrap.sh status
         ./bootstrap.sh help
EOF
}

function package() {
  cd ${AKKA_CLUSTER_HOME}
  echo "Package App $APP_NAME-$APP_VERSION"
  mvn clean package -Dmaven.test.skip=true
}


function echo_build_properties() {
  echo version=${APP_VERSION}
  echo user=${USER}
  echo revision=$(git rev-parse HEAD)
  echo branch=$(git rev-parse --abbrev-ref HEAD)
  echo date=$(date +"%Y/%m/%d %H:%M:%S")
  echo url=$(git config --get remote.origin.url)
}

function build_info() {
    echo_build_properties $2 > ${AKKA_CLUSTER_HOME}/INFO
}

case "$1:$2:$3" in
    package:*)
        package "${@:2}"
        ;;
    start:recall:dev)
        start_module recall dev
        ;;
    start:rerank:dev)
        start_module rerank dev
        ;;
    start:engine:dev)
        start_module engine dev
        ;;
    start:engine:*)
        start_module engine pro
        ;;
    start:recall:*)
        start_module recall pro
        ;;
    start:rerank:*)
        start_module rerank pro
        ;;
    stop:recall:dev)
        stop recall dev
        ;;
    stop:rerank:dev)
        stop rerank dev
        ;;
    stop:engine:dev)
        stop engine dev
        ;;
    stop:recall:*)
        stop recall pro
        ;;
    stop:rerank:*)
        stop rerank pro
        ;;
    stop:engine:*)
        stop engine pro
        ;;
    status:*:*)
        status
        ;;
    run:*:*)
        common_run "${@:2}"
        ;;
    build_info:*:*|bi:*:*)
        build_info
        ;;
    h|help)
        usage
        ;;
    *)
        usage
        exit 0
esac