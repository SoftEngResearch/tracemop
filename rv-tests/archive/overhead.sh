#!/bin/bash
# overhead.sh — monitoring overhead (over no-agent baseline), stock vs native.
# steady = last DaCapo iteration (warm). overhead = steady - baseline.
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
source "$(cd "$(dirname "$0")/.." && pwd)/bench-env.sh"
JDK=$PATCHED_JDK   # DACAPO provided by bench-env.sh
BENCHMARKS=${BENCHMARKS:-"avrora h2 jython luindex lusearch pmd sunflow xalan"}
ITERS=${ITERS:-8}
THREADS=${THREADS:-1}      # t=1 isolates per-event cost from contention
HEAP=${HEAP:--Xmx4g}
OUT=${OUT:-/tmp/overhead}; mkdir -p "$OUT"
OP="--add-opens java.base/java.lang=ALL-UNNAMED"

run() { # $1 bench $2 label $3 agentflag -> echoes steady ms
  local b=$1
  local lbl=$2
  local ag=$3
  local log="$OUT/$b-$lbl.log"
  $JDK/bin/java $OP -XX:+UseParallelGC $HEAP $ag \
    -jar "$DACAPO" -s default --no-validation -n "$ITERS" -t "$THREADS" "$b" \
    > /dev/null 2>"$log"
  grep "PASSED in" "$log" | tail -1 | grep -oE "[0-9]+ msec" | grep -oE "[0-9]+"
}

printf "%-9s | %8s | %8s | %8s | %10s | %10s | %6s\n" \
  "bench" "base" "stock" "native" "ovh_stock" "ovh_nat" "nat/st"
printf -- "------------------------------------------------------------------------------\n"
for b in $BENCHMARKS; do
  base=$(run "$b" baseline "")
  st=$(run "$b" stock  "-javaagent:$DIR/agents/stock-no-track-no-stats-agent.jar")
  nv=$(run "$b" native "-javaagent:$DIR/agents/native-no-track-no-stats-agent.jar")
  base=${base:-0}; st=${st:-0}; nv=${nv:-0}
  ovs=$(( st - base )); ovn=$(( nv - base ))
  ratio=$(awk -v a="$ovn" -v b="$ovs" 'BEGIN{ if(b>0) printf "%.1f", a/b; else printf "n/a" }')
  printf "%-9s | %8s | %8s | %8s | %10s | %10s | %6s\n" "$b" "$base" "$st" "$nv" "$ovs" "$ovn" "$ratio"
done