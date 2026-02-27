#!/bin/bash
#
# Run TraceMOP without trace-collection
# Usage: bash not_collect_traces.sh <repo> <sha> <output-dir> [timed: false] [stats: false] [save-to-file: true]
#
SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )

REPO=$1
SHA=$2
OUTPUT_DIR=$3
TIMED=${4:-false}
STATS=${5:-false}
SAVE_TO_FILE=${6:-true}
PROJECT_NAME=$(echo ${REPO} | tr / -)

ALREADY_CHECKED=false

if [[ -z ${OUTPUT_DIR} ]]; then
  echo "Usage: bash not_collect_traces.sh <repo> <sha> <output-dir> [timed: false] [stats: false] [save-to-file: true]"
  exit 1
else
  if [[ ! ${OUTPUT_DIR} =~ ^/.* ]]; then
    OUTPUT_DIR=${SCRIPT_DIR}/${OUTPUT_DIR}
  fi

  mkdir -p ${OUTPUT_DIR}/logs
fi

# Direct agent attachment (bypasses extension which has Sisu issues on Java 21)
SUREFIRE_GOAL="org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test"
AGENT_JAR="${OUTPUT_DIR}/repo/javamop-agent/javamop-agent/1.0/javamop-agent-1.0.jar"

# Java 21+ module system: open JDK internals so AspectJ can weave all classes
JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
ADD_OPENS=""
if [[ ${JAVA_VERSION} -ge 9 ]]; then
  ADD_OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED"
fi

# Create JVM wrapper to inject agent into surefire's forked JVM.
# Using -Djvm=<wrapper> instead of -DargLine because projects that override
# <argLine> in their POM ignore the CLI property, causing the agent to never load.
JAVA_WRAPPER=${OUTPUT_DIR}/java-wrapper.sh
JAVA_BIN=${JAVA_HOME:+${JAVA_HOME}/bin/java}
JAVA_BIN=${JAVA_BIN:-java}
cat > ${JAVA_WRAPPER} << WRAPPER_EOF
#!/bin/bash
exec ${JAVA_BIN} -javaagent:${AGENT_JAR} ${ADD_OPENS} "\$@"
WRAPPER_EOF
chmod +x ${JAVA_WRAPPER}

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
}

function install() {
  if [[ ${STATS} == "true" ]]; then
    (time mvn -Dmaven.repo.local=${OUTPUT_DIR}/repo install:install-file -Dfile=${SCRIPT_DIR}/no-track-stats-agent.jar -DgroupId="javamop-agent" -DartifactId="javamop-agent" -Dversion="1.0" -Dpackaging="jar") &>> ${OUTPUT_DIR}/logs/install.log
  else
    (time mvn -Dmaven.repo.local=${OUTPUT_DIR}/repo install:install-file -Dfile=${SCRIPT_DIR}/no-track-no-stats-agent.jar -DgroupId="javamop-agent" -DartifactId="javamop-agent" -Dversion="1.0" -Dpackaging="jar") &>> ${OUTPUT_DIR}/logs/install.log
  fi
  if [[ $? -ne 0 ]]; then
    echo "[TRACEMOP] ERROR: Unable to install agent"
    exit 1
  fi
}

function initial_test() {
  if [[ ${ALREADY_CHECKED} == "false" ]]; then
    (time mvn test-compile -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400) &> ${OUTPUT_DIR}/logs/compile.log
    local status=$?
    if [[ ${status} -ne 0 ]]; then
      echo "[TRACEMOP] ERROR: Unable to compile (initial)"
      exit 1
    fi
  fi

  if [[ ${TIMED} == "true" ]]; then
    echo "[TRACEMOP] Running surefire:test once to download dependency"
    local start=$(date +%s%3N)
    (time mvn ${SUREFIRE_GOAL} -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400) &> ${OUTPUT_DIR}/logs/initial-test.log
    local status=$?
    if [[ ${status} -ne 0 ]]; then
      echo "[TRACEMOP] ERROR: Unable to run test (initial)"
      exit 1
    fi
    local end=$(date +%s%3N)
    local duration=$((end - start))
    echo "Duration: ${duration} ms"
  fi
}

function mop() {
  export MAVEN_OPTS="-Xmx500g -XX:-UseGCOverheadLimit"
  local filename="mop"
  if [[ ${STATS} == "javamop" ]]; then
    filename="javamop"
  fi
  if [[ ${SAVE_TO_FILE} == "true" ]]; then
    export RVMLOGGINGLEVEL=UNIQUE
  fi

  echo "[TRACEMOP] Running MOP"
  local start=$(date +%s%3N)
  (time mvn ${SUREFIRE_GOAL} -Dmaven.repo.local=${OUTPUT_DIR}/repo -Dsurefire.exitTimeout=86400 -Djvm=${JAVA_WRAPPER}) &> ${OUTPUT_DIR}/logs/${filename}.log
  local status=$?
  
  if [[ ${status} -ne 0 ]]; then
    echo "[TRACEMOP] ERROR: Unable to run MOP"
    exit 1
  fi
  local end=$(date +%s%3N)
  local duration=$((end - start))
  echo "Duration: ${duration} ms"
  
  popd &> /dev/null # back to ${OUTPUT_DIR}
  popd &> /dev/null # back to CWD
}

clone
install
initial_test
mop
echo "OK!"
