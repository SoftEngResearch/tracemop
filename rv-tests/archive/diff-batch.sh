#!/bin/bash
# Batch differential: run several DaCapo benchmarks under stock + native agents
# and, for each, compare the two deterministic behaviour signals:
#   - per-spec violation counts (which specs fired, how often)
#   - true monitor population (sum of per-spec #monitors)
# Global event/monitor totals are racy (unsynchronised ++), so reported FYI only.
#
# Usage: bash diff-batch.sh [size] [bench1 bench2 ...]
set -u
TRACEMOP=$( cd "$( dirname "$0" )/.." && pwd )
source "$(cd "$(dirname "$0")/.." && pwd)/bench-env.sh"
JDK=$PATCHED_JDK
[ "$RV_DACAPO_USER_SET" = 1 ] || DACAPO=$DACAPO_CHOPIN   # diff tools default to DaCapo-23.11-chopin
SIZE=${1:-small}; shift || true
BENCHES=${*:-luindex lusearch avrora xalan h2 biojava sunflow zxing jython}
OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"
ROOT=/tmp/diff-batch; mkdir -p "$ROOT"

specvio() {  # extract per-spec violation counts robustly (ignores interleaved progress text)
  grep -hoE "Specification [A-Za-z0-9_]+ has been violated" "$@" 2>/dev/null \
    | sed -E 's/Specification ([A-Za-z0-9_]+) has been violated/\1/' \
    | sort | uniq -c | awk '{printf "%-40s %s\n", $2, $1}'
}
monsum() { grep -h "^== " -A1 "$1" 2>/dev/null; }  # placeholder; real sum below

printf "\n%-12s %-8s %-8s %-22s %-22s\n" BENCH stock native VIOLATIONS MONITOR_POP
printf "%s\n" "--------------------------------------------------------------------------------"
for b in $BENCHES; do
  d="$ROOT/$b"; mkdir -p "$d"
  for a in stock native; do
    "$JDK/bin/java" $OPTS -javaagent:"$TRACEMOP/${a}-no-track-stats-agent.jar" \
        -jar "$DACAPO" "$b" -s "$SIZE" >"$d/$a.out" 2>"$d/$a.err"
    echo $? > "$d/$a.exit"
    specvio "$d/$a.out" "$d/$a.err" > "$d/$a.specvio"
    grep -hE "^#monitors:" "$d/$a.err" | awk '{s+=$2} END{print s+0}' > "$d/$a.monsum"
  done
  se=$(cat "$d/stock.exit"); ne=$(cat "$d/native.exit")
  if diff -q "$d/stock.specvio" "$d/native.specvio" >/dev/null; then
    nv=$(awk '{s+=$2} END{print s+0}' "$d/stock.specvio"); ns=$(wc -l <"$d/stock.specvio" | tr -d ' ')
    vverdict="MATCH ($nv in $ns specs)"
  else vverdict="*** DIFFER ***"; fi
  sm=$(cat "$d/stock.monsum"); nm=$(cat "$d/native.monsum")
  if [ "$sm" = "$nm" ]; then mverdict="MATCH ($sm)"; else mverdict="*** $sm vs $nm ***"; fi
  printf "%-12s %-8s %-8s %-22s %-22s\n" "$b" "$se" "$ne" "$vverdict" "$mverdict"
done
echo
echo "Per-benchmark logs under $ROOT/<bench>/  (use: diff <bench>/stock.specvio <bench>/native.specvio)"
