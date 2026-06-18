#!/bin/bash
#
# Run TraceMOP
# Usage: run_tracemop.sh <repo> <sha> <output-dir> [collect-traces: false] [timed: false] [stats: false] [per-test: false]
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

REPO=$1
SHA=$2
OUTPUT_DIR=$3
COLLECT_TRACES=${4:-false}
TIMED=${5:-false}
STATS=${6:-false}
PER_TEST=${7:-false}
PROJECT_NAME=$(echo ${REPO} | tr / -)

if [[ -z ${OUTPUT_DIR} ]]; then
  echo "Usage: run_tracemop.sh <repo> <sha> <output-dir> [collect-traces: false] [timed: false] [stats: false] [per-test: false]"
  exit 1
fi

function move_violations() {
  local directory=$1
  local filename=${2:-"violation-counts"}
  local copy=${3:-"false"}
  
  for violation in $(find -name "violation-counts"); do
    if [[ -n ${violation} ]]; then
      local name=$(echo "${violation}" | rev | cut -d '/' -f 2 | rev)
      if [[ ${name} != "." ]]; then
        # Is MMMP, add module name to file name
        if [[ ${copy} == "true" ]]; then
          cp ${violation} ${directory}/${filename}_${name}
        else
          mv ${violation} ${directory}/${filename}_${name}
        fi
      else
        if [[ ${copy} == "true" ]]; then
          cp ${violation} ${directory}/${filename}
        else
          mv ${violation} ${directory}/${filename}
        fi
      fi
    fi
  done
}

mkdir -p ${OUTPUT_DIR}/logs

CMD_PID=0
if [[ ${TIMED} == "true" ]]; then
  bash ${SCRIPT_DIR}/monitor_memory.sh ${OUTPUT_DIR}/logs/memory.log &
  CMD_PID=$!
fi

if [[ ${COLLECT_TRACES} == "true" ]]; then
  bash ${SCRIPT_DIR}/collect_traces.sh ${REPO} ${SHA} ${OUTPUT_DIR} ${TIMED} ${PER_TEST} "" ${STATS}
  
  mkdir -p ${OUTPUT_DIR}/logs/violations/traces
  cd ${OUTPUT_DIR}/project
  move_violations ${OUTPUT_DIR}/logs/violations/traces
else
  bash ${SCRIPT_DIR}/not_collect_traces.sh ${REPO} ${SHA} ${OUTPUT_DIR} ${TIMED} ${STATS}
  
  if [[ ${STATS} == "javamop" ]]; then
    mkdir -p ${OUTPUT_DIR}/logs/violations/javamop
    cd ${OUTPUT_DIR}/project
    move_violations ${OUTPUT_DIR}/logs/violations/javamop
  else
    mkdir -p ${OUTPUT_DIR}/logs/violations/mop
    cd ${OUTPUT_DIR}/project
    move_violations ${OUTPUT_DIR}/logs/violations/mop
  fi
fi

if [[ ${CMD_PID} -ne 0 ]]; then
  if [[ -f ${OUTPUT_DIR}/logs/memory.log ]]; then
    echo "Killing memory monitor PID: ${CMD_PID}. Max memory usage is $(cat ${OUTPUT_DIR}/logs/memory.log)"
  else
    echo "Killing memory monitor PID: ${CMD_PID}"
  fi

  kill -9 ${CMD_PID}
fi
