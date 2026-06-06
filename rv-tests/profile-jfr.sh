#!/bin/bash
# profile-jfr.sh — async-profiler JFR capture, stock vs native IndexingTree, DaCapo.
#
# Produces one .jfr per (bench, agent, event). Two events are captured so we can
# separate the two overhead mechanisms:
#   event=cpu   -> where extra ON-CPU work goes (per-event dispatch, rehash, GC work)
#   event=wall  -> where threads BLOCK/wait (lock contention, the lusearch convoy)
#
# Both backends run on the SAME patched JDK so the only variable is the monitoring
# backend (stock = aspect/weak-ref, native = VM IndexingTree). Agents are the
# fixed no-track-no-stats jars (no stats overhead to perturb the profile).
#
# Usage: bash profile-jfr.sh
#   override via env: BENCHES, EVENTS, AGENTS, ITERS, HEAP, GC, OUT
#   smoke test:  BENCHES=avrora AGENTS=native EVENTS=cpu ITERS=2 bash profile-jfr.sh
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
source "$(cd "$(dirname "$0")" && pwd)/bench-env.sh"
JDK=$PATCHED_JDK   # DACAPO provided by bench-env.sh (9.12 by default)
ASP=${ASP:-$RV_ASP}
GC=${GC:--XX:+UseG1GC}
HEAP=${HEAP:-2g}
ITERS=${ITERS:-10}
THREADS=${THREADS:-}          # empty = DaCapo default; set e.g. 1 to force -t 1
BENCHES=${BENCHES:-"lusearch xalan pmd luindex avrora"}
EVENTS=${EVENTS:-"cpu wall"}
AGENTS=${AGENTS:-"stock native"}   # use "none" for a no-agent baseline
OUT=${OUT:-$DIR/results/jfr}
mkdir -p "$OUT"

OPENS="--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED \
--add-opens java.base/java.lang.rv=ALL-UNNAMED \
--add-opens java.base/java.io=ALL-UNNAMED \
--add-opens java.base/java.nio=ALL-UNNAMED \
--add-opens java.base/java.net=ALL-UNNAMED"

LOG="$OUT/run.log"
: > "$LOG"
say(){ echo "$@" | tee -a "$LOG"; }

say "=== profile-jfr $(date) ==="
say "jdk=$JDK"
say "dacapo=$DACAPO  asp=$ASP"
say "heap=$HEAP iters=$ITERS gc=$GC"
say "benches=[$BENCHES] events=[$EVENTS] agents=[$AGENTS]"
say "out=$OUT"
say ""

run() { # bench agent event
  local b=$1 a=$2 ev=$3
  local jfr="$OUT/${b}-${a}-${ev}.jfr"
  local rl="$OUT/${b}-${a}-${ev}.runlog"
  local agentopt=""
  if [ "$a" != "none" ]; then
    local jar="$DIR/agents/${a}-no-track-no-stats-agent.jar"
    [ -f "$jar" ] || { say "  MISSING agent $jar"; return; }
    agentopt="-javaagent:$jar"
  fi
  local tflag=""; [ -n "$THREADS" ] && tflag="-t $THREADS"
  rm -f "$jfr"
  local t0=$SECONDS
  # retry once: async-profiler agentpath occasionally no-ops on first attach
  for try in 1 2; do
    "$JDK/bin/java" $OPENS $GC "-Xmx$HEAP" \
      -agentpath:"$ASP=start,event=$ev,file=$jfr" \
      $agentopt \
      -jar "$DACAPO" -s default --no-validation -n "$ITERS" $tflag "$b" > "$rl" 2>&1
    [ -s "$jfr" ] && break
  done
  local dt=$(( SECONDS - t0 ))
  local sz; sz=$(du -h "$jfr" 2>/dev/null | cut -f1)
  local steady; steady=$(grep -oE "in [0-9]+ msec" "$rl" | grep -oE "[0-9]+" | tail -1)
  if [ -s "$jfr" ]; then
    printf "  OK  %-9s %-7s %-5s  jfr=%-6s steady=%-7s  %ds\n" "$b" "$a" "$ev" "$sz" "${steady:-?}ms" "$dt" | tee -a "$LOG"
  else
    printf "  FAIL %-9s %-7s %-5s  (see %s)\n" "$b" "$a" "$ev" "${rl##*/}" | tee -a "$LOG"
  fi
}

for b in $BENCHES; do
  for a in $AGENTS; do
    for ev in $EVENTS; do
      run "$b" "$a" "$ev"
    done
  done
done

say ""
say "=== done $(date) ==="
ls -la "$OUT"/*.jfr 2>/dev/null | tee -a "$LOG"
