#!/bin/bash
# heap-sweep.sh — DaCapo time+memory across heaps x benchmarks x reps, stock vs native.
# Per (heap,bench,agent,rep): steady-state time (last of n=10) + peak managed heap.
#
# Env: HEAPS, BENCHMARKS, ITERS, REPS, GC, OUT, TIMEOUT
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
source "$(cd "$(dirname "$0")/.." && pwd)/bench-env.sh"
JDK=$PATCHED_JDK   # DACAPO provided by bench-env.sh
HEAPS=${HEAPS:-"2g 16g"}
BENCHMARKS=${BENCHMARKS:-"avrora h2 jython luindex lusearch pmd sunflow xalan"}
ITERS=${ITERS:-10}
REPS=${REPS:-3}
GC=${GC:--XX:+UseG1GC}
OUT=${OUT:-/tmp/heap-sweep}; mkdir -p "$OUT"
OP="--add-opens java.base/java.lang=ALL-UNNAMED"

CSV="$OUT/results.csv"
[ -f "$CSV" ] || echo "heap,gc,bench,agent,rep,steady_ms,peak_heap_mb,status" > "$CSV"

parse_heap(){ local p=0 v; for v in $(grep -oE "[0-9]+M->" "$1" 2>/dev/null|grep -oE "^[0-9]+"); do ((v>p))&&p=$v; done; echo $p; }

echo "=== heap-sweep $(date): heaps=$HEAPS iters=$ITERS reps=$REPS gc=$GC ==="
for heap in $HEAPS; do
  for b in $BENCHMARKS; do
    for rep in $(seq 1 "$REPS"); do
      for a in stock native; do
        gl="$OUT/$heap-$b-$a-r$rep.gc"; rl="$OUT/$heap-$b-$a-r$rep.err"
        $JDK/bin/java $OP $GC "-Xmx$heap" -Xlog:gc:file="$gl":tags \
          -javaagent:"$DIR/agents/$a-no-track-no-stats-agent.jar" \
          -jar "$DACAPO" -s default --no-validation -n "$ITERS" "$b" \
          > /dev/null 2>"$rl"
        t=$(grep "PASSED in" "$rl" | tail -1 | grep -oE "[0-9]+ msec" | grep -oE "[0-9]+")
        h=$(parse_heap "$gl")
        st="OK"; [ -z "$t" ] && st="ERR"
        echo "$heap,$GC,$b,$a,$rep,${t:-0},${h:-0},$st" >> "$CSV"
        printf "  %-4s %-9s %-7s r%d  time=%-7s heap=%-6s %s\n" "$heap" "$b" "$a" "$rep" "${t:-ERR}ms" "${h}M" "$st"
      done
    done
  done
done
echo "=== done $(date) -> $CSV ==="
