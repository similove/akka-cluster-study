#!/bin/bash

APP_NAME=akka-cluster
APP_VERSION=1.0-SNAPSHOT

if [ -z "${AKKA_CLUSTER_HOME}" ]; then
  export AKKA_CLUSTER_HOME="$(cd "`dirname "$0"`"/.; pwd)"
fi

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
  cd $AKKA_CLUSTER_HOME
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