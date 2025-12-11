#!/bin/bash

SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

TRACK=${1:-false}
STATS=${2:-false}
SERIES=${3:-false}

shift 3

valg=false
traj=false
spec_configs=()

while [[ $# -gt 0 ]]; do
  key="$1"
  case $key in
    -valg)
      valg=true
      shift 1
      ;;
    -traj)
      traj=true
      shift 1
      ;;
    -spec)
      if [[ $# -lt 3 ]]; then
        echo "[install.sh] Missing argument for -spec"
        exit 1
      fi
      spec_name="$2"
      spec_value="$3"
      spec_configs+=("-spec" "$spec_name" "$spec_value")
      shift 3
      ;;
    *)
      echo "[install.sh] Unknown option $1"
      shift
      ;;
  esac
done

if [[ ${#spec_configs[@]} -gt 0 && "$valg" != true ]]; then
    echo "[install.sh] Error: -spec can only be used when -valg is enabled."
    exit 1
fi

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
    bash ${SCRIPT_DIR}/install-javaparser.sh
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

  bash ${SCRIPT_DIR}/make-agent.sh ${SCRIPT_DIR}/${props} . quiet ${TRACK} . ${TRACK}-${STATS}-agent . ${STATS} ${SERIES} true ${valg} ${traj} "${spec_configs[@]}"

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
