#!/bin/bash
# preflight.sh — verify this machine is ready to run the rv-tests experiments.
# Run this FIRST on a new (remote) box. Exits non-zero if anything required is
# missing, and tells you the exact command to fix it.
#
#   bash preflight.sh
set -u
DIR=$( cd "$( dirname "$0" )" && pwd )
source "$DIR/bench-env.sh"

PASS=0; FAIL=0; WARN=0
ok()   { printf "  \033[32mOK\033[0m   %s\n" "$1"; PASS=$((PASS+1)); }
bad()  { printf "  \033[31mFAIL\033[0m %s\n     -> %s\n" "$1" "$2"; FAIL=$((FAIL+1)); }
warn() { printf "  \033[33mWARN\033[0m %s\n     -> %s\n" "$1" "$2"; WARN=$((WARN+1)); }

echo "=== rv-tests preflight ($(uname -sm)) ==="
echo "repo root: $RV_REPO_ROOT"
echo "jdk tree:  ${RV_JDK_TREE:-<not found>}"
echo ""

# 1. RV/native patched JDK (the one experiments run on)
echo "[1] patched JDK (native IndexingTree backend)"
if [ -n "${PATCHED_JDK:-}" ] && [ -x "$PATCHED_JDK/bin/java" ]; then
  ver=$("$PATCHED_JDK/bin/java" -version 2>&1 | head -1)
  ok "$PATCHED_JDK  ($ver)"
  # sanity: java.lang.rv package present (proves it's the patched build, not stock)
  if "$PATCHED_JDK/bin/java" --add-opens java.base/java.lang.rv=ALL-UNNAMED -version >/dev/null 2>&1; then
    ok "java.lang.rv module opens (patched build confirmed)"
  else
    warn "could not --add-opens java.base/java.lang.rv" "is this really the RV-patched JDK?"
  fi
elif [ -n "${RV_JDK_TREE:-}" ]; then
  bad "JDK tree found but not built: $RV_JDK_TREE/build/*/images/jdk missing" \
      "build it:  cd $RV_JDK_TREE && make CONF=linux-x86_64-server-release images"
else
  bad "no configured JDK tree found near $(dirname "$DIR")" \
      "point at it:  RV_JDK_TREE=/path/to/jdk-repo bash preflight.sh   (and configure+build it)"
fi
echo ""

# 2. Agents
echo "[2] LTW agents (built by build-agents.sh)"
miss=0
for a in stock native; do
  for k in no-track-no-stats no-track-stats; do
    j="$DIR/agents/${a}-${k}-agent.jar"
    if [ -f "$j" ]; then ok "$(basename "$j")  ($(du -h "$j"|cut -f1))"; else bad "missing $(basename "$j")" "build:  bash build-agents.sh"; miss=1; fi
  done
done
[ $miss = 0 ] || true
echo ""

# 3. DaCapo
echo "[3] DaCapo benchmark jar"
if [ -n "${DACAPO:-}" ] && [ -f "$DACAPO" ]; then
  ok "$DACAPO"
else
  bad "DaCapo jar not found" "set DACAPO=/path/to/dacapo-9.12-bach.jar, or drop it in \$HOME/Downloads/ (auto-detected)"
fi
echo ""

# 4. Run-time tooling
echo "[4] tooling"
if [ -n "$RV_TIMEOUT" ]; then ok "timeout = $RV_TIMEOUT"; else
  warn "no timeout/gtimeout" "hung benchmarks won't be killed. Linux: usually preinstalled (coreutils). mac: brew install coreutils"
fi
if [ -x /usr/bin/time ]; then ok "/usr/bin/time present (RSS via $RV_TIME_FLAG)"; else
  warn "/usr/bin/time missing" "max_rss_mb column will be 0. Linux: apt-get install time"
fi
if [ -n "${RV_ASP:-}" ] && [ -f "$RV_ASP" ]; then ok "async-profiler = $RV_ASP"; else
  warn "async-profiler not found" "only needed for profile-jfr.sh / profile-why.sh. Put libasyncProfiler.so under \$HOME/async-profiler/lib/"
fi
echo ""

# 5. Memory (heap sizing sanity)
echo "[5] system memory"
if [ "$RV_OS" = linux ]; then
  memkb=$(grep MemTotal /proc/meminfo | grep -oE '[0-9]+'); memgb=$(( memkb / 1024 / 1024 ))
else
  memgb=$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1024 / 1024 / 1024 ))
fi
if [ "$memgb" -ge 32 ]; then ok "${memgb}GB RAM"; elif [ "$memgb" -ge 8 ]; then
  warn "${memgb}GB RAM" "large-heap configs (-Xmx16g+) may be RAM-capped; keep HEAPS modest"
else bad "${memgb}GB RAM" "too little for the default heaps; lower HEAPS"; fi
echo ""

echo "=== summary: $PASS ok, $WARN warn, $FAIL fail ==="
[ $FAIL -eq 0 ] && { echo "READY. e.g.  bash run-dacapo-agents.sh"; exit 0; }
echo "NOT READY — fix the FAIL items above."; exit 1
