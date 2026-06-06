#!/bin/bash
# Differential benchmark runner.
#
# Runs a DaCapo benchmark under the stock (weak-ref) and native (IndexingTree)
# stats agents, then diffs the three behaviour-preservation signals:
#   1. violations  — {spec, location} multiset (sort|uniq -c on violation lines)
#   2. global      — # of total events / # of total monitors
#   3. per-spec    — #monitors / #event / #category, keyed by spec (order-free)
#
# Usage:
#   bash diff-bench.sh [benchmark] [size]          # detailed single-bench diff
#   bash diff-bench.sh --batch [size] [b1 b2 ...]  # summary table over many benches
#     e.g. bash diff-bench.sh pmd small
#          bash diff-bench.sh --batch small luindex lusearch avrora xalan
set -u

TRACEMOP=$( cd "$( dirname "$0" )/.." && pwd )
source "$(cd "$(dirname "$0")" && pwd)/bench-env.sh"
JDK=$PATCHED_JDK
[ "$RV_DACAPO_USER_SET" = 1 ] || DACAPO=$DACAPO_CHOPIN   # diff tools default to DaCapo-23.11-chopin

# --- batch mode: one-line MATCH/DIFFER summary per benchmark ------------------
if [ "${1:-}" = --batch ]; then
  shift
  SIZE=${1:-small}; shift || true
  BENCHES=${*:-luindex lusearch avrora xalan h2 biojava sunflow zxing jython}
  OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"
  ROOT=/tmp/diff-batch; mkdir -p "$ROOT"
  specvio() {  # per-spec violation counts (ignores interleaved progress text)
    grep -hoE "Specification [A-Za-z0-9_]+ has been violated" "$@" 2>/dev/null \
      | sed -E 's/Specification ([A-Za-z0-9_]+) has been violated/\1/' \
      | sort | uniq -c | awk '{printf "%-40s %s\n", $2, $1}'
  }
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
  echo "Per-benchmark logs under $ROOT/<bench>/  (diff <bench>/stock.specvio <bench>/native.specvio)"
  exit 0
fi

BENCH=${1:-pmd}
SIZE=${2:-small}
OUT=/tmp/diff-$BENCH-$SIZE
mkdir -p "$OUT"

# JDK 9+ weaving of java.base types needs these opens (see dockerfile note).
JVM_OPTS="--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED"

run() {
  local agent=$1
  local jar=$TRACEMOP/${agent}-no-track-stats-agent.jar
  if [ ! -f "$jar" ]; then echo "MISSING: $jar"; exit 1; fi
  echo "=== $BENCH ($SIZE) under $agent ==="
  "$JDK/bin/java" $JVM_OPTS -javaagent:"$jar" -jar "$DACAPO" "$BENCH" -s "$SIZE" \
      >"$OUT/$agent.out" 2>"$OUT/$agent.err"
  echo "  exit=$?  out=$(wc -l <"$OUT/$agent.out") err=$(wc -l <"$OUT/$agent.err")"

  # 1. Violations: full line multiset (matches commons-fileupload-violation-counts format)
  grep -h "has been violated" "$OUT/$agent.out" "$OUT/$agent.err" 2>/dev/null \
      | sed -E 's/ on line .*//' \
      | sort | uniq -c | sed -E 's/^ *//' > "$OUT/$agent.viol.byspec"
  grep -h "has been violated" "$OUT/$agent.out" "$OUT/$agent.err" 2>/dev/null \
      | sort | uniq -c | sed -E 's/^ *//' > "$OUT/$agent.viol.byloc"

  # 2+3. Stats normalised to "<spec>\t<metric>\t<value>" so order doesn't matter
  awk '
    /^== /            { spec=$2; next }
    /^#monitors:/     { print spec"\t#monitors\t"$2; next }
    /^#event - /      { print spec"\t"$0; next }
    /^#category/      { print spec"\t"$0; next }
    /^# of total/     { print "GLOBAL\t"$0; next }
  ' "$OUT/$agent.err" | sort > "$OUT/$agent.stats"
}

run stock
run native

section() { echo; echo "######## $1 ########"; }

section "GLOBAL TOTALS"
grep '^GLOBAL' "$OUT/stock.stats"  | sed 's/^GLOBAL\t/  stock : /'
grep '^GLOBAL' "$OUT/native.stats" | sed 's/^GLOBAL\t/  native: /'

section "VIOLATIONS by spec+location (stock vs native)"
if diff "$OUT/stock.viol.byloc" "$OUT/native.viol.byloc" >/dev/null; then
  echo "IDENTICAL ✅  ($(wc -l <"$OUT/stock.viol.byloc") distinct spec+location violation rows)"
  cat "$OUT/stock.viol.byspec"
else
  echo "DIFFERENCES ✗"
  diff "$OUT/stock.viol.byloc" "$OUT/native.viol.byloc"
fi

section "PER-SPEC #monitors / #events (stock vs native)"
if diff "$OUT/stock.stats" "$OUT/native.stats" >/dev/null; then
  echo "IDENTICAL ✅  (all per-spec + global counters match)"
else
  echo "DIFFERENCES ✗ (showing only specs that differ):"
  diff "$OUT/stock.stats" "$OUT/native.stats"
fi

echo; echo "Raw logs under $OUT/"
