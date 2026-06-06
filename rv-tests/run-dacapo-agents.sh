#!/bin/bash
# run-dacapo-agents.sh — unified DaCapo runner for the rv-tests LTW agents.
# Compares the stock (aspect / weak-ref) backend vs the native (IndexingTree)
# backend, both on the SAME patched JDK, on wall-clock time and memory.
#
# This one runner absorbs what used to be three scripts:
#   * plain timing  (was run-dacapo-agents.sh): stock vs native, time+heap+RSS
#   * overhead       (was overhead.sh):   add a no-agent baseline + overhead table
#   * heap sweep     (was heap-sweep.sh):  iterate over several heap sizes
#
# Per (heap,bench,agent,rep) we capture:
#   - every "PASSED in N msec" iteration -> steady-state (last) + best (min)
#   - peak managed heap from -Xlog:gc    -> peak_heap_mb
#   - process max RSS via /usr/bin/time  -> max_rss_mb   (portable, bench-env.sh)
#
# Env knobs (all overridable):
#   BENCHMARKS  benches to run            (default: 9-bench DaCapo set)
#   AGENTS      "stock native"            (add "baseline" for a no-agent control)
#   BASELINE=1  convenience: prepend "baseline" to AGENTS
#   HEAPS       bare sizes, e.g. "2g 16g" (default: "2g")  <- sweep dimension
#   THREADS     pass -t N to DaCapo       (default: unset; set 1 to isolate
#                                          per-event cost from contention)
#   ITERS REPS GC SIZE TIMEOUT OUT        (see defaults below)
#   OVERHEAD_TABLE=1  force the overhead summary (auto-on when baseline present)
#
# Examples:
#   bash run-dacapo-agents.sh                                   # stock vs native, 2g
#   BASELINE=1 THREADS=1 ITERS=8 bash run-dacapo-agents.sh      # overhead.sh mode
#   HEAPS="2g 16g" ITERS=10 REPS=3 bash run-dacapo-agents.sh    # heap-sweep mode
set -u

DIR=$( cd "$( dirname "$0" )" && pwd )
source "$DIR/bench-env.sh"
JDK=$PATCHED_JDK
AGENTS_DIR=$DIR/agents

SIZE=${SIZE:-default}
ITERS=${ITERS:-5}
REPS=${REPS:-2}
GC=${GC:--XX:+UseG1GC}
HEAPS=${HEAPS:-2g}
THREADS=${THREADS:-}
AGENTS=${AGENTS:-"stock native"}
[ "${BASELINE:-0}" = 1 ] && AGENTS="baseline $AGENTS"
BENCHMARKS=${BENCHMARKS:-"avrora batik h2 jython luindex lusearch pmd sunflow xalan"}
TIMEOUT=${TIMEOUT:-1200}

# Overhead table is meaningful only with a baseline control.
case " $AGENTS " in *" baseline "*) OVERHEAD_TABLE=${OVERHEAD_TABLE:-1};; esac
OVERHEAD_TABLE=${OVERHEAD_TABLE:-0}

OPENS="--add-opens java.base/java.lang=ALL-UNNAMED \
--add-opens java.base/java.util=ALL-UNNAMED \
--add-opens java.base/java.lang.rv=ALL-UNNAMED \
--add-opens java.base/java.io=ALL-UNNAMED \
--add-opens java.base/java.nio=ALL-UNNAMED \
--add-opens java.base/java.net=ALL-UNNAMED"

TFLAG=""; [ -n "$THREADS" ] && TFLAG="-t $THREADS"

OUT=${OUT:-$DIR/dacapo-results}
mkdir -p "$OUT"
CSV=$OUT/results.csv
echo "heap,gc,bench,agent,rep,steady_ms,best_ms,peak_heap_mb,max_rss_mb,status" > "$CSV"

parse_peak_heap() {  # arg: gc log file -> peak MB (max "NNNM->" or "NNNK->" pre-GC used)
  local peak=0 v
  for v in $(grep -oE "[0-9]+M->" "$1" 2>/dev/null | grep -oE "^[0-9]+"); do
    (( v > peak )) && peak=$v
  done
  for v in $(grep -oE "[0-9]+K->" "$1" 2>/dev/null | grep -oE "^[0-9]+"); do
    v=$(( v / 1024 )); (( v > peak )) && peak=$v
  done
  echo "$peak"
}

agent_flag() {  # $1 = agent name -> echoes the -javaagent flag ("" for baseline)
  [ "$1" = baseline ] && return 0
  echo "-javaagent:$AGENTS_DIR/${1}-no-track-no-stats-agent.jar"
}

echo "=== run-dacapo-agents.sh $(date) ==="
rv_env_banner
echo "size=$SIZE iters=$ITERS reps=$REPS gc=$GC heaps=$HEAPS threads=${THREADS:-default} agents=$AGENTS"
echo "benchmarks=$BENCHMARKS"
echo ""

for heap in $HEAPS; do
  for b in $BENCHMARKS; do
    for agent in $AGENTS; do
      if [ "$agent" != baseline ]; then
        JAR=$AGENTS_DIR/${agent}-no-track-no-stats-agent.jar
        [ -f "$JAR" ] || { echo "MISSING $JAR"; continue; }
      fi
      AFLAG=$(agent_flag "$agent")
      for rep in $(seq 1 "$REPS"); do
        RDIR="$OUT/$heap/$b/$agent/r$rep"; mkdir -p "$RDIR"
        GCLOG="$RDIR/gc.log"; RUNLOG="$RDIR/run.log"; TIMELOG="$RDIR/time.log"
        rm -f "$GCLOG" "$RUNLOG" "$TIMELOG"

        rv_time $(rv_timeout_prefix "$TIMEOUT") "$JDK/bin/java" \
          $OPENS $GC "-Xmx$heap" \
          -Xlog:gc:file="$GCLOG":time,uptime,level,tags \
          $AFLAG \
          -jar "$DACAPO" -s "$SIZE" --no-validation -n "$ITERS" $TFLAG "$b" \
          > "$RUNLOG" 2> "$TIMELOG"
        rc=$?

        # DaCapo prints to stderr, merged with /usr/bin/time output in TIMELOG.
        steady=$(grep "PASSED in" "$TIMELOG" | tail -1 | sed 's/.*in \([0-9]*\) msec.*/\1/')
        best=$(grep -oE "in [0-9]+ msec" "$TIMELOG" | grep -oE "[0-9]+" | sort -n | head -1)
        heapmb=$(parse_peak_heap "$GCLOG")
        rss=$(rv_parse_rss "$TIMELOG")

        if   [ $rc -eq 124 ] || [ $rc -eq 137 ]; then status=TIMEOUT
        elif [ -z "$steady" ];                   then status=ERR
        else                                          status=OK
        fi

        echo "$heap,$GC,$b,$agent,$rep,${steady:-0},${best:-0},${heapmb:-0},$rss,$status" >> "$CSV"
        printf "  %-4s %-9s %-8s r%d steady=%-7s best=%-7s heap=%-5s rss=%-5s %s\n" \
          "$heap" "$b" "$agent" "$rep" "${steady:-ERR}ms" "${best:-?}ms" "${heapmb}M" "${rss}M" "$status"
      done
    done
  done
done

# --- optional overhead summary (baseline vs stock vs native) ------------------
if [ "$OVERHEAD_TABLE" = 1 ]; then
  echo ""
  echo "=== overhead summary (best steady ms per heap×bench; ovh = agent - baseline) ==="
  printf "%-4s %-9s | %8s %8s %8s | %9s %9s %6s\n" \
         "heap" "bench" "base" "stock" "native" "ovh_st" "ovh_nat" "nat/st"
  echo "------------------------------------------------------------------------------"
  awk -F, 'NR>1 && $10=="OK" {
      k=$1 SUBSEP $3 SUBSEP $4
      if (!(k in m) || $6+0 < m[k]) m[k]=$6+0
      benches[$1 SUBSEP $3]=1
    }
    END {
      for (hb in benches) {
        split(hb, a, SUBSEP); h=a[1]; b=a[2]
        base=m[h SUBSEP b SUBSEP "baseline"]; st=m[h SUBSEP b SUBSEP "stock"]; nv=m[h SUBSEP b SUBSEP "native"]
        ovs=(st!=""&&base!="")?st-base:"n/a"; ovn=(nv!=""&&base!="")?nv-base:"n/a"
        ratio=(ovs!="n/a"&&ovs>0)?sprintf("%.1f",ovn/ovs):"n/a"
        printf "%-4s %-9s | %8s %8s %8s | %9s %9s %6s\n",
               h,b,(base==""?"-":base),(st==""?"-":st),(nv==""?"-":nv),ovs,ovn,ratio
      }
    }' "$CSV" | sort
fi

echo ""
echo "=== done $(date) ==="
echo "CSV: $CSV"
