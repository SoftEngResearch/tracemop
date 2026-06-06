#!/bin/bash
# Per-spec events/monitors for every benchmark, both backends (stats agents).
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
source "$(cd "$(dirname "$0")/.." && pwd)/bench-env.sh"
JDK=$PATCHED_JDK   # DACAPO provided by bench-env.sh
OUT=${OUT:-/tmp/stats-all}; mkdir -p "$OUT"
BENCHMARKS=${BENCHMARKS:-"avrora h2 jython luindex lusearch pmd sunflow xalan"}
OPENS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang.rv=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED"

# parse one .err into  "spec<TAB>events<TAB>monitors"
parse() { awk '
  /^== / { spec=$2; ev[spec]=0; mon[spec]=0; seen[spec]=1; next }
  /^#monitors:/ { mon[spec]=$2; next }
  /^#event - / { ev[spec]+=$NF; next }
  END { for (s in seen) printf "%s\t%d\t%d\n", s, ev[s], mon[s] }' "$1" | sort ; }

SUM=$OUT/summary.csv
echo "bench,total_events_stock,total_monitors_stock,total_events_native,total_monitors_native,active_specs_stock,active_specs_native" > "$SUM"

for b in $BENCHMARKS; do
  echo "=== $b ==="
  for a in stock native; do
    $JDK/bin/java $OPENS -XX:+UseParallelGC -Xmx4g \
      -javaagent:"$DIR/agents/$a-no-track-stats-agent.jar" \
      -jar "$DACAPO" -s default --no-validation -n 1 "$b" > /dev/null 2>"$OUT/$b-$a.err"
    parse "$OUT/$b-$a.err" > "$OUT/$b-$a.tsv"
    g=$(grep "# of total" "$OUT/$b-$a.err")
    echo "  $a: $(echo "$g" | tr '\n' ' ')"
  done
  # joined per-spec table (only specs active in either)
  join -t$'\t' -a1 -a2 -e0 -o '0,1.2,1.3,2.2,2.3' "$OUT/$b-stock.tsv" "$OUT/$b-native.tsv" 2>/dev/null \
    | awk -F'\t' '$2+0>0||$3+0>0||$4+0>0||$5+0>0' \
    | sort -t$'\t' -k2,2nr > "$OUT/$b-perspec.tsv"
  es=$(grep "# of total events"   "$OUT/$b-stock.err"  | grep -oE "[0-9]+"); ms=$(grep "# of total monitors" "$OUT/$b-stock.err"  | grep -oE "[0-9]+")
  en=$(grep "# of total events"   "$OUT/$b-native.err" | grep -oE "[0-9]+"); mn=$(grep "# of total monitors" "$OUT/$b-native.err" | grep -oE "[0-9]+")
  as=$(awk -F'\t' '$3+0>0' "$OUT/$b-stock.tsv"  | wc -l | tr -d ' ')
  an=$(awk -F'\t' '$3+0>0' "$OUT/$b-native.tsv" | wc -l | tr -d ' ')
  echo "$b,${es:-0},${ms:-0},${en:-0},${mn:-0},$as,$an" >> "$SUM"
done
echo "=== DONE -> $OUT ; summary: $SUM ==="