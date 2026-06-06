#!/bin/bash
# perspec-breakdown.sh — per-spec events/monitors breakdown, stock vs native.
#
# For each DaCapo benchmark, runs the rvm STATS agents (stock + native), then
# emits a per-spec table (events & monitors, side by side) sorted by event
# count, plus a consolidated CSV across all benchmarks.
#
# Usage:
#   bash perspec-breakdown.sh                         # all default benchmarks
#   BENCHMARKS="lusearch pmd" bash perspec-breakdown.sh
#   REUSE=1 bash perspec-breakdown.sh                 # reuse cached *.err in OUT (skip re-running)
#   SHOW_DORMANT=1 bash perspec-breakdown.sh          # also list 0-event specs
#
# Env: PATCHED_JDK, DACAPO, OUT, SIZE, BENCHMARKS, REUSE, SHOW_DORMANT
set -u

DIR=$( cd "$( dirname "$0" )" && pwd )
source "$(cd "$(dirname "$0")" && pwd)/bench-env.sh"
JDK=$PATCHED_JDK   # DACAPO provided by bench-env.sh
OUT=${OUT:-/tmp/stats-all}
SIZE=${SIZE:-default}
BENCHMARKS=${BENCHMARKS:-"avrora h2 jython luindex lusearch pmd sunflow xalan"}
REUSE=${REUSE:-0}
SHOW_DORMANT=${SHOW_DORMANT:-0}
mkdir -p "$OUT"

OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang.rv=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED"

gen() { # $1 bench $2 agent  -> writes $OUT/$1-$2.err
  local b=$1
  local a=$2
  local jar="$DIR/agents/$a-no-track-stats-agent.jar"
  [ -f "$jar" ] || { echo "MISSING agent: $jar" >&2; return 1; }
  if [ "$REUSE" = "1" ] && [ -s "$OUT/$b-$a.err" ]; then return 0; fi
  "$JDK/bin/java" $OPENS -XX:+UseParallelGC -Xmx4g \
    -javaagent:"$jar" -jar "$DACAPO" -s "$SIZE" --no-validation -n 1 "$b" \
    > /dev/null 2>"$OUT/$b-$a.err"
}

# merge stock+native .err -> spec ev_s ev_n mon_s mon_n viol_s viol_n
# violations = sum of #category counts per spec (the spec's violation-handler firings)
merge() { # $1 stock.err  $2 native.err
  awk '
    FNR==NR {
      if ($1=="=="){s=$2; spec[s]=1}
      else if ($1=="#monitors:"){smon[s]=$2}
      else if (/^#event - /){sev[s]+=$NF}
      else if (/^#category/){sviol[s]+=$NF}
      next
    }
    {
      if ($1=="=="){t=$2; spec[t]=1}
      else if ($1=="#monitors:"){nmon[t]=$2}
      else if (/^#event - /){nev[t]+=$NF}
      else if (/^#category/){nviol[t]+=$NF}
    }
    END { for (sp in spec) printf "%s\t%d\t%d\t%d\t%d\t%d\t%d\n",
            sp, sev[sp]+0, nev[sp]+0, smon[sp]+0, nmon[sp]+0, sviol[sp]+0, nviol[sp]+0 }
  ' "$1" "$2"
}

MASTER="$OUT/ALL-perspec.csv"
echo "bench,spec,events_stock,events_native,monitors_stock,monitors_native,violations_stock,violations_native" > "$MASTER"

for b in $BENCHMARKS; do
  gen "$b" stock  || continue
  gen "$b" native || continue
  [ -s "$OUT/$b-stock.err" ] && [ -s "$OUT/$b-native.err" ] || { echo "no stats for $b (crashed?)"; continue; }

  merge "$OUT/$b-stock.err" "$OUT/$b-native.err" | sort -t$'\t' -k2,2nr > "$OUT/$b-perspec.tsv"
  # append to master (active specs first; include all)
  awk -F'\t' -v b="$b" '{print b","$1","$2","$3","$4","$5","$6","$7}' "$OUT/$b-perspec.tsv" >> "$MASTER"

  # ---- readable report ----
  echo ""
  echo "==================== $b ($SIZE) ===================="
  printf "%-38s %11s %11s %9s %9s %7s %7s\n" "spec" "ev(stock)" "ev(native)" "mon(s)" "mon(n)" "viol(s)" "viol(n)"
  printf -- "------------------------------------------------------------------------------------------------\n"
  awk -F'\t' -v show="$SHOW_DORMANT" '
    ($2+0>0 || $3+0>0) || show==1 {
      printf "%-38s %11d %11d %9d %9d %7d %7d\n",$1,$2,$3,$4,$5,$6,$7
    }' "$OUT/$b-perspec.tsv"
  # totals (dormant = never fires an event)
  awk -F'\t' '
    {es+=$2; en+=$3; ms+=$4; mn+=$5; vs+=$6; vn+=$7; n++; if($2>0||$3>0)act++; else dorm++}
    END{
      printf "------------------------------------------------------------------------------------------------\n"
      printf "%-38s %11d %11d %9d %9d %7d %7d\n","TOTAL ("n" specs, "act" active, "dorm" 0-event)",es,en,ms,mn,vs,vn
    }' "$OUT/$b-perspec.tsv"
done

echo ""
echo "consolidated CSV: $MASTER  ($(($(wc -l < "$MASTER")-1)) rows)"