#!/bin/bash

SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

TRACK=${1:-false}
STATS=${2:-false}

alpha=${3:-0.9}
epsilon=${4:-0.1}
threshold=${5:-0.0001}
initc=${6:-5.0}
initn=${7:-0.0}

function install() {
  if [[ ${TRACK} == true ]]; then
    TRACK="track"
  else
    TRACK="no-track"
  fi
  
  if [[ ${STATS} == true ]]; then
    STATS="stats"
  else
    STATS="no-stats"
  fi

  if [[ ! -f ${TRACK}-${STATS}-agent.jar ]]; then
    # Install TraceMOP's dependency
    echo "Install new JavaParser"
    bash ${SCRI/PT_DIR}/install-javaparser.sh
  fi
  
  # Install TraceMOP
  pushd ${SCRIPT_DIR}/../ &> /dev/null
  mvn clean install -DskipTests
  popd &> /dev/null
  
  # Build agent using TraceMOP
  export PATH=${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/bin:${SCRIPT_DIR}/../javamop/target/release/javamop/javamop/bin:${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/lib/rv-monitor-rt.jar:${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/lib/rv-monitor.jar:${PATH}
  export CLASSPATH=${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/lib/rv-monitor-rt.jar:${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/lib/rv-monitor.jar:${CLASSPATH}
  local props="props"
  if [[ ${TRACK} == "track" ]]; then
    props="props-track"
  fi

  bash ${SCRIPT_DIR}/make-agent.sh ${SCRIPT_DIR}/${props} . quiet ${TRACK} . ${TRACK}-${STATS}-agent . ${STATS} true ${alpha} ${epsilon} ${threshold} ${initc} ${initn}
  
  if [[ ${TRACK} == "track" ]]; then
    # Add aspect
    pushd resources &> /dev/null
    mkdir -p mop
    cp ../BaseAspect_new.aj mop/BaseAspect.aj
    ajc mop/BaseAspect.aj
    
    ajc TestNameAspect.aj -cp .:${CLASSPATH} -1.8
    mv TestNameAspect.class mop/TestNameAspect.class

    zip ../track-no-stats-agent.jar mop/TestNameAspect.class
    zip ../track-no-stats-agent.jar mop/BaseAspect.class
    zip ../track-no-stats-agent.jar META-INF/aop-ajc.xml
    rm -rf mop
    popd &> /dev/null
  fi
}

install
