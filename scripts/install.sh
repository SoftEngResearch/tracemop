#!/bin/bash

SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

TRACK=${1:-false}
STATS=${2:-false}
SERIES=${3:-false}

if [[ $# -ge 3 ]]; then
  shift 3
else
  shift $#
fi

valg=false
valg_value=""
traj=false
native=false
spec_configs=()

while [[ $# -gt 0 ]]; do
  key="$1"
  case $key in
    -valg)
      valg=true
      if [[ $# -ge 2 && "$2" != -* ]]; then
        if [[ "$2" =~ ^\{[0-9.+-]+(,[0-9.+-]+){4}\}$ ]]; then
          valg_value="$2"
          shift 2
        else
          echo "[install.sh] Error: -valg value must be in the format \"{<alpha>,<epsilon>,<threshold>,<initial values>}\""
          exit 1
        fi
      else
        shift 1
      fi
      ;;
    -traj)
      traj=true
      shift 1
      ;;
    -nativeindexingtree)
      native=true
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

if [[ "$traj" == true && "$valg" != true ]]; then
    echo "[install.sh] Error: -traj can only be used when -valg is enabled."
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
  # javamopagent's preflight requires aspectjrt/aspectjtools/aspectjweaver on
  # CLASSPATH (it greps for the jar names). Source them from the m2 cache;
  # caller can override with ASPECTJ_M2 if their layout differs.
  ASPECTJ_M2=${ASPECTJ_M2:-${HOME}/.m2/repository/org/aspectj}
  # Pick the newest version present in all three (rt/tools/weaver) artifacts;
  # aspectjweaver typically lags behind, so we constrain on it.
  if [[ -z "${ASPECTJ_VERSION}" ]]; then
      for v in $(ls ${ASPECTJ_M2}/aspectjweaver 2>/dev/null | sort -V -r); do
          if [[ -f ${ASPECTJ_M2}/aspectjrt/${v}/aspectjrt-${v}.jar && \
                -f ${ASPECTJ_M2}/aspectjtools/${v}/aspectjtools-${v}.jar ]]; then
              ASPECTJ_VERSION=$v; break
          fi
      done
  fi
  AJ_RT_JAR=${ASPECTJ_M2}/aspectjrt/${ASPECTJ_VERSION}/aspectjrt-${ASPECTJ_VERSION}.jar
  AJ_TOOLS_JAR=${ASPECTJ_M2}/aspectjtools/${ASPECTJ_VERSION}/aspectjtools-${ASPECTJ_VERSION}.jar
  AJ_WEAVER_JAR=${ASPECTJ_M2}/aspectjweaver/${ASPECTJ_VERSION}/aspectjweaver-${ASPECTJ_VERSION}.jar
  export CLASSPATH=${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/lib/rv-monitor-rt.jar:${SCRIPT_DIR}/../rv-monitor/target/release/rv-monitor/lib/rv-monitor.jar:${AJ_RT_JAR}:${AJ_TOOLS_JAR}:${AJ_WEAVER_JAR}:${CLASSPATH}
  export ASPECTJRT_JAR=${AJ_RT_JAR}
  local props="${PROPS:-props}"
  local agent="${TRACK}-${STATS}-agent"
  if [[ ${TRACK} == "track" || ${SERIES} == true ]]; then
    props="${PROPS:-props-track}"
  fi

  # Native indexing-tree codegen produces a separately-named agent jar so it
  # can sit alongside the stock jar without overwriting it.
  local native_flag=""
  if [[ "${native}" == true ]]; then
    native_flag="-nativeindexingtree"
    agent="native-${agent}"
  else
    agent="stock-${agent}"
  fi

  bash ${SCRIPT_DIR}/make-agent.sh ${SCRIPT_DIR}/${props} . quiet ${TRACK} . ${agent} . ${STATS} ${SERIES} true ${valg} ${valg_value} ${traj} ${native_flag} "${spec_configs[@]}"

  if [[ ${TRACK} == "track" || ${SERIES} == true ]]; then
    # Add aspect
    pushd resources &> /dev/null
    mkdir -p mop
    cp ../BaseAspect_new.aj mop/BaseAspect.aj
    ajc mop/BaseAspect.aj
    
    ajc TestNameAspect.aj -cp .:${CLASSPATH} -21
    mv TestNameAspect.class mop/TestNameAspect.class

    zip ../${agent}.jar mop/TestNameAspect.class
    zip ../${agent}.jar mop/BaseAspect.class
    zip ../${agent}.jar META-INF/aop-ajc.xml
    rm -rf mop
    popd &> /dev/null
  fi
}

install
