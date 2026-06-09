#!/bin/bash
# Build both stock and native agents (using props-stock) and copy to ./agents/
set -e

SCRIPT_DIR=$( cd $( dirname $0 ) && pwd )
TRACEMOP_DIR=$( cd $SCRIPT_DIR/.. && pwd )

cd $TRACEMOP_DIR

# Native codegen emits references to `java.lang.rv.*` (added by the patched
# JDK) and `org.aspectj.lang.JoinPoint`. Surface those to make-agent.sh's
# javac invocation.
source "$SCRIPT_DIR/bench-env.sh"
export PATCHED_JDK

echo "=== Building stock agent ==="
bash scripts/install.sh false false false
echo ""
echo "=== Building native agent (-nativeindexingtree) ==="
bash scripts/install.sh false false false -nativeindexingtree

echo ""
echo "=== Copying agents to rv-tests/agents/ ==="
mkdir -p $SCRIPT_DIR/agents
for jar in stock-no-track-no-stats-agent.jar \
           stock-no-track-stats-agent.jar \
           native-no-track-no-stats-agent.jar \
           native-no-track-stats-agent.jar; do
    if [ -f "$TRACEMOP_DIR/$jar" ]; then
        cp "$TRACEMOP_DIR/$jar" $SCRIPT_DIR/agents/
        echo "  $jar"
    else
        echo "  MISSING: $jar"
    fi
done

echo ""
echo "=== Done ==="
ls -la $SCRIPT_DIR/agents/
