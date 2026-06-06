#!/bin/bash
# Contention scaling benchmark: BenchSyncCollection under no-agent / stock / native.
# Reports throughput (ops/s) per thread count; the question is whether native's
# wider general-spec critical section scales worse than stock under contention.
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
JDK=${PATCHED_JDK:-/Users/jy2249/Desktop/jdk7-4-rv/jdk21-rv-young-gc-fix/build/macosx-aarch64-server-release/images/jdk}
OPTS=(--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED -Xmx2g -XX:+UseParallelGC)
ITERS=${ITERS:-150000}
THREADS=(1 2 4 8)
REPS=${REPS:-2}

"$JDK/bin/javac" -d "$DIR" "$DIR/BenchSyncCollection.java" || exit 1

run() {  # $1=label $2=agentflag
  local label=$1 ag=$2
  for t in "${THREADS[@]}"; do
    local best=0
    for r in $(seq 1 $REPS); do
      local out
      if [ -n "$ag" ]; then
        out=$("$JDK/bin/java" "${OPTS[@]}" "$ag" -cp "$DIR" BenchSyncCollection "$t" "$ITERS" 2>/dev/null)
      else
        out=$("$JDK/bin/java" "${OPTS[@]}" -cp "$DIR" BenchSyncCollection "$t" "$ITERS" 2>/dev/null)
      fi
      local tp=$(echo "$out" | grep -oE "throughput=[0-9,]+" | tr -d 'throughput=,' )
      [ -n "$tp" ] && [ "$tp" -gt "$best" ] && best=$tp
    done
    printf "%-8s threads=%-2d  throughput=%'d ops/s\n" "$label" "$t" "$best"
  done
  echo
}

echo "=== ITERS/thread=$ITERS  REPS=$REPS (best-of)  cores=$(sysctl -n hw.ncpu) ==="
run none   ""
run stock  "-javaagent:$DIR/stock-sync.jar"
run native "-javaagent:$DIR/native-sync.jar"
