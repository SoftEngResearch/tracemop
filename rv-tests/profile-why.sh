#!/bin/bash
# profile-why.sh — async-profiler CPU diff for a benchmark, stock vs native.
# Shows where NATIVE spends time that stock doesn't (the per-event overhead).
#
# Usage: bash profile-why.sh <bench> [heap] [iters]
#   e.g. bash profile-why.sh xalan 2g 8
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
source "$(cd "$(dirname "$0")" && pwd)/bench-env.sh"
JDK=$PATCHED_JDK   # DACAPO provided by bench-env.sh
ASP=${ASP:-$RV_ASP}
GC=${GC:--XX:+UseG1GC}
B=${1:?usage: profile-why.sh <bench> [heap] [iters]}
HEAP=${2:-2g}
ITERS=${3:-8}
OP="--add-opens java.base/java.lang=ALL-UNNAMED"
OUT=${OUT:-$DIR/results/profiles/$B-$HEAP}; mkdir -p "$OUT"

prof() { # $1 agent -> writes $OUT/$1.coll
  local a=$1
  # retry once: async-profiler agentpath occasionally no-ops on first attach
  for try in 1 2; do
    $JDK/bin/java $OP $GC "-Xmx$HEAP" \
      -agentpath:"$ASP=start,event=cpu,collapsed,file=$OUT/$a.coll" \
      -javaagent:"$DIR/agents/$a-no-track-no-stats-agent.jar" \
      -jar "$DACAPO" -s default --no-validation -n "$ITERS" "$B" > /dev/null 2>/dev/null
    [ -s "$OUT/$a.coll" ] && break
  done
  echo "$a: $(wc -l < "$OUT/$a.coll" 2>/dev/null) stacks"
}

echo "=== profiling $B  heap=$HEAP  iters=$ITERS  gc=$GC ==="
prof stock
prof native

echo ""
echo "=== leaf-frame diff: NATIVE minus STOCK (top 20 hotter-in-native) ==="
leaf(){ awk '{c=$NF; sub(/ [0-9]+$/,""); n=split($0,f,";"); print c"\t"f[n]}' "$1" \
  | awk -F'\t' '{a[$2]+=$1} END{for(k in a) printf "%d\t%s\n",a[k],k}'; }
leaf "$OUT/native.coll" | sort -t$'\t' -k1 -rn > "$OUT/native.leaf"
leaf "$OUT/stock.coll"  | sort -t$'\t' -k1 -rn > "$OUT/stock.leaf"
awk -F'\t' 'FNR==NR{s[$2]=$1;next}{d=$1-(s[$2]+0); print d"\t"$1"\t"(s[$2]+0)"\t"$2}' \
  "$OUT/stock.leaf" "$OUT/native.leaf" | sort -t$'\t' -k1 -rn | head -20 \
  | awk -F'\t' '{printf "  d%-6d nat=%-5d stock=%-5d  %s\n",$1,$2,$3,$4}'

echo ""
echo "=== monitoring-subtree leaves (stacks through mop/ or java/lang/rv), native ==="
awk '/mop\/|java\/lang\/rv|MonitorAspect|RuntimeMonitor/{c=$NF; sub(/ [0-9]+$/,""); n=split($0,f,";"); print c"\t"f[n]}' "$OUT/native.coll" \
  | awk -F'\t' '{a[$2]+=$1;t+=$1} END{for(k in a) printf "%d\t%s\n",a[k],k; printf "%d\tTOTAL_MON\n",t}' | sort -rn | head -14
