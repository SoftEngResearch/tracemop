#!/bin/bash
#
# Collect traces
# Usage: bash collect_traces.sh <repo> <sha> <output-dir> [timed: false] [per-test: false] [path-to-javamop-extension]
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

REPO=$1
SHA=$2
OUTPUT_DIR=$3
TIMED=${4:-false}
PER_TEST=${5:-false}
PATH_TO_EXTENSION=$6
PROJECT_NAME=$(echo ${REPO} | tr / -)

ALREADY_CHECKED=false
TMP_DIR=/tmp/tracemop-${PROJECT_NAME}

if [[ -z ${OUTPUT_DIR} ]]; then
  echo "Usage: bash collect_traces.sh <repo> <sha> <output-dir> [timed: false] [per-test: false] [path-to-javamop-extension]"
  exit 1
else
  if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
    OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
  fi

  if [[ -n ${PATH_TO_EXTENSION} ]]; then
    if [[ ! ${PATH_TO_EXTENSION} =~ ^/.* ]]; then
      PATH_TO_EXTENSION=${SCRIPT_DIR}/${PATH_TO_EXTENSION}
    fi
  else
    PATH_TO_EXTENSION=${SCRIPT_DIR}/../extensions/javamop-extension-1.0.jar
  fi
  
  if [[ ${PER_TEST} == "true" ]]; then
    TIMED="true"
  fi

  mkdir -p ${OUTPUT_DIR}/logs
fi

export TRACEDB_CONFIG_PATH="${SCRIPT_DIR}/.trace-db.config"
echo -e "db=memory\ndumpDB=false" > ${TRACEDB_CONFIG_PATH}

function clone() {
  if [[ -d ${OUTPUT_DIR}/project ]]; then
    ALREADY_CHECKED=true
    pushd ${OUTPUT_DIR}/project &> /dev/null
    return 0
  fi

  echo "[TRACEMOP] Cloning project ${REPO}"
  pushd ${OUTPUT_DIR} &> /dev/null
  git clone https://github.com/${REPO} project &> ${OUTPUT_DIR}/logs/clone.log
  if [[ $? -ne 0 ]]; then
    echo "[TRACEMOP] ERROR: Unable to clone project ${REPO}"
    exit 1
  fi
  pushd project &> /dev/null
  git checkout ${SHA} &>> ${OUTPUT_DIR}/logs/clone.log
  
  if [[ -f ${SCRIPT_DIR}/treat_special.sh ]]; then
    bash ${SCRIPT_DIR}/treat_special.sh ${OUTPUT_DIR}/project ${PROJECT_NAME}
  fi
}

function install() {
  (time mvn -Dmaven.repo.local=${OUTPUT_DIR}/repo install:install-file -Dfile=${SCRIPT_DIR}/track-no-stats-agent.jar -DgroupId="javamop-agent" -DartifactId="javamop-agent" -Dversion="1.0" -Dpackaging="jar") &>> ${OUTPUT_DIR}/logs/install.log
  if [[ $? -ne 0 ]]; then
    echo "[TRACEMOP] ERROR: Unable to install agent"
    exit 1
  fi
}

function initial_test() {
  if [[ ${ALREADY_CHECKED} == "true" ]]; then
    return 0
  fi
  
  mkdir -p ${TMP_DIR} && chmod -R +w ${TMP_DIR} && rm -rf ${TMP_DIR} && mkdir -p ${TMP_DIR}

  (time mvn test-compile -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400 -Dmaven.ext.class.path=${PATH_TO_EXTENSION}) &> ${OUTPUT_DIR}/logs/compile.log
  if [[ ${TIMED} == "true" ]]; then
    echo "[TRACEMOP] Running surefire:test once to download dependency"
    export ADD_AGENT=0
    (time mvn surefire:test -Djava.io.tmpdir=${TMP_DIR} -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400 -Dmaven.ext.class.path=${PATH_TO_EXTENSION}) &> ${OUTPUT_DIR}/logs/initial-test.log
    local status=$?
#    (time mvn surefire:test -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400 -DreuseForks=false -DthreadCountMethods=1 -Dparallel=methods -DforkCount=1 -Dmaven.ext.class.path=${PATH_TO_EXTENSION}) &> ${OUTPUT_DIR}/logs/initial-test.log
    if [[ ${status} -ne 0 ]]; then
      echo "[TRACEMOP] ERROR: Unable to run test (initial)"
      exit 1
    fi
    unset ADD_AGENT
  fi
}

function collect() {
  export COLLECT_TRACES=1 # extension will add -Xmx500g -XX:-UseGCOverheadLimit
  export COLLECT_MONITORS=1 # TraceMOP will collect monitor
  export TRACEDB_PATH=${OUTPUT_DIR}/all-traces # Store traces in this directory
  export TRACEDB_RANDOM=1 # Directory name should end with random string, to prevent duplicated DB
  export MAVEN_OPTS="-Xmx500g -XX:-UseGCOverheadLimit"
  export RVMLOGGINGLEVEL=UNIQUE
  
  mkdir -p ${TRACEDB_PATH}
  
  echo "[TRACEMOP] Collecting traces"
  mkdir -p ${TMP_DIR} && chmod -R +w ${TMP_DIR} && rm -rf ${TMP_DIR} && mkdir -p ${TMP_DIR}
  local start=$(date +%s%3N)
  if [[ ${PER_TEST} == "true" ]]; then
    collect_per_test
  else
    (time mvn surefire:test -Djava.io.tmpdir=${TMP_DIR} -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400 -Dmaven.ext.class.path=${PATH_TO_EXTENSION}) &> ${OUTPUT_DIR}/logs/traces.log
    local status=$?
    
    if [[ ${status} -ne 0 ]]; then
      echo "[TRACEMOP] ERROR: Unable to run collect traces"
      exit 1
    fi
  fi
  local end=$(date +%s%3N)
  local duration=$((end - start))
  echo "Duration: ${duration} ms"
  
  popd &> /dev/null # back to ${OUTPUT_DIR}
  popd &> /dev/null # back to CWD
}

function collect_per_test() {
  for test_class in $(ls target/surefire-reports/*.xml); do
    for test_case in $(python3 ${SCRIPT_DIR}/get_junit_testcases.py ${test_class}); do
      echo "[TRACEMOP] collecting ${test_case}"
      mkdir -p ${TMP_DIR} && chmod -R +w ${TMP_DIR} && rm -rf ${TMP_DIR} && mkdir -p ${TMP_DIR}
      local start=$(date +%s%3N)
      
      (time mvn surefire:test -Djava.io.tmpdir=${TMP_DIR} -Dtest=${test_case} -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400 -Dmaven.ext.class.path=${PATH_TO_EXTENSION}) &> ${OUTPUT_DIR}/logs/test-${test_case}.log
      local status=$?
      if [[ ${status} -ne 0 ]]; then
        echo "[TRACEMOP] ERROR: Unable to collect traces for test ${test_case}"
      fi
  
      local end=$(date +%s%3N)
      local duration=$((end - start))
      echo "Finished collecting traces for ${test_case}: ${duration} ms"
    done
  done
}

function process() {
  if [[ -f ${TRACEDB_PATH}/unique-traces.txt ]]; then
    mv ${TRACEDB_PATH}/unique-traces.txt ${TRACEDB_PATH}/traces-id.txt
    (time python3 ${SCRIPT_DIR}/count-traces-frequency.py ${TRACEDB_PATH}) &>> ${OUTPUT_DIR}/logs/process.log
    rm ${TRACEDB_PATH}/traces-id.txt ${TRACEDB_PATH}/traces.txt
  fi
  
  local num_db=0
  local last_db=""
  for db in $(ls ${TRACEDB_PATH}/../ | grep "all-traces-"); do
    # search directory starts with all-traces-*
    if [[ ! -f ${OUTPUT_DIR}/${db}/unique-traces.txt || ! -f ${OUTPUT_DIR}/${db}/specs-frequency.csv || ! -f ${OUTPUT_DIR}/${db}/locations.txt || ! -f ${OUTPUT_DIR}/${db}/traces.txt ]]; then
      continue
    fi

    mv ${OUTPUT_DIR}/${db}/unique-traces.txt ${OUTPUT_DIR}/${db}/traces-id.txt
    (time python3 ${SCRIPT_DIR}/count-traces-frequency.py ${OUTPUT_DIR}/${db}/) &>> ${OUTPUT_DIR}/logs/process.log
    rm ${OUTPUT_DIR}/${db}/traces-id.txt ${OUTPUT_DIR}/${db}/traces.txt
    num_db=$((num_db + 1))
    last_db=${db}
  done

  if [[ ! -d ${TRACEDB_PATH} || -z $(ls -A ${TRACEDB_PATH}) ]]; then
    # if all-traces is empty, delete it
    rm -rf ${TRACEDB_PATH}
  fi
  
  if [[ ${num_db} -eq 1 ]]; then
    # if we only have one all-traces-*, then rename it to all-traces
    mv ${OUTPUT_DIR}/${db} ${TRACEDB_PATH}
  elif [[ ${num_db} -eq 0 ]]; then
    empty_db=$(ls -d ${OUTPUT_DIR}/all-traces-* 2>/dev/null)

    if [[ -n "${empty_db}" && -d "${empty_db}" && -z "$(ls -A "${empty_db}")" ]]; then
      rm -rf "${empty_db}"
    fi
  fi
}

clone
install
initial_test
collect
process
echo "OK!"
