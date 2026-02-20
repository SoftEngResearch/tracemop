#!/bin/bash

SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )
TRACEMOP_DIR="${SCRIPT_DIR}/tracemop"

function clone_repository() {
  echo "Cloning tracemop repository"
  pushd ${SCRIPT_DIR}
  git clone -b java11-migration https://github.com/SoftEngResearch/tracemop tracemop
  popd
}

function build_extension() {
  echo "Building tracemop extension"
  pushd ${TRACEMOP_DIR}/scripts/javamop-extension
  mvn package
  mkdir -p ${TRACEMOP_DIR}/extensions/
  cp target/javamop-extension-1.0.jar ${TRACEMOP_DIR}/extensions/
  popd

  pushd ${TRACEMOP_DIR}/plugin
  mvn install
  popd
}

function build_agents() {
  echo "Building tracemop agents"
  pushd ${TRACEMOP_DIR}/scripts
  echo "Installing track, no stats agent"
  bash install.sh true false # track, no stats
  echo "Installing no track, stats agent"
  bash install.sh false true # no track, stats
  echo "Installing no track, no stats agent"
  bash install.sh false false # no track, no stats
  popd
}

function setup() {
  clone_repository
  build_extension
  build_agents
}

setup
