#!/bin/bash
# Run each test program under each agent and compare outputs.
set -u

SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )
source "$SCRIPT_DIR/bench-env.sh"

if [ ! -x "$PATCHED_JDK/bin/java" ]; then
    echo "ERROR: Patched JDK not found at $PATCHED_JDK"
    echo "       Set PATCHED_JDK env var to override."
    exit 1
fi

JAVA=$PATCHED_JDK/bin/java
JAVAC=$PATCHED_JDK/bin/javac

CLASSES_DIR=$SCRIPT_DIR/classes
mkdir -p $CLASSES_DIR

echo "=== Compiling test programs ==="
$JAVAC -d $CLASSES_DIR $SCRIPT_DIR/src/*.java
ls $CLASSES_DIR/

TESTS="TestHasNextSafe TestHasNext TestUnsafeIterator TestSyncCollection TestConcurrent TestGC"
AGENTS="stock-no-track-no-stats-agent native-no-track-no-stats-agent"

for agent in $AGENTS; do
    JAR=$SCRIPT_DIR/agents/${agent}.jar
    if [ ! -f "$JAR" ]; then
        echo ""
        echo "SKIP: $agent.jar not found (run build-agents.sh first)"
        continue
    fi
    echo ""
    echo "===================================================================="
    echo "AGENT: $agent"
    echo "===================================================================="
    for test in $TESTS; do
        echo ""
        echo "--- $test ---"
        # Extra JVM opts for GC test
        EXTRA_OPTS=""
        if [ "$test" = "TestGC" ]; then EXTRA_OPTS="-Xmx256m"; fi
        # Portable timeout (timeout/gtimeout if available, else no limit)
        TIMEOUT=30
        if [ "$test" = "TestGC" ]; then TIMEOUT=120; fi
        $(rv_timeout_prefix "$TIMEOUT") $JAVA $EXTRA_OPTS \
            -javaagent:$JAR \
            -cp $CLASSES_DIR \
            $test 2>&1 | head -50
        echo "  exit=$?"
    done
done

echo ""
echo "=== Done ==="
