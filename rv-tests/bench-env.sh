# bench-env.sh — shared, OS-agnostic environment for the RV experiment scripts.
# Source this (do not execute):  source "<repo>/bench-env.sh"
#
# Works on macOS (BSD userland) and Linux (GNU userland). It auto-detects the
# RV/native JDK, the stock JDK, and the DaCapo jar by GLOBBING the build tree
# instead of hardcoding a platform/arch string, and exposes portable helpers
# for `timeout` and peak-RSS measurement.
#
# Everything respects pre-set env vars, so any value can still be overridden:
#   PATCHED_JDK= RV_JDK= STOCK_JDK= DACAPO= RV_REPO_ROOT= RV_CONF=
#   (RV_CONF pins which build/<conf>/images/jdk to use when several exist)
#
# Exports / sets:
#   RV_REPO_ROOT   repo root (dir containing this file, unless preset)
#   PATCHED_JDK    RV/native (IndexingTree) JDK image   (== RV_JDK)
#   RV_JDK         alias of PATCHED_JDK
#   STOCK_JDK      stock weak-ref JDK image
#   DACAPO         DaCapo benchmark jar
#   RV_OS          "mac" | "linux"
#   RV_TIMEOUT     timeout binary ("timeout" / "gtimeout" / "")
# Functions:
#   rv_time CMD...        run CMD under /usr/bin/time, RSS goes to stderr
#   rv_parse_rss FILE     -> peak RSS in MB (parses BSD or GNU time output)
#   rv_run_timeout SECS CMD...   run CMD with a hard timeout if available

# --- umbrella root: walk up from this file until we find the dir that contains
# the JDK build trees (jdk21-rv-young-gc-fix/). Location-independent so a copy
# of this file can live inside either git repo (tracemop/, jdk21-rv-young-gc-fix/)
# and still locate the sibling JDK trees. Override with RV_REPO_ROOT=.
_benv_self="${BASH_SOURCE[0]:-$0}"
if [ -z "${RV_REPO_ROOT:-}" ]; then
  _benv_p=$(cd "$(dirname "$_benv_self")" && pwd)
  while [ "$_benv_p" != / ]; do
    [ -d "$_benv_p/jdk21-rv-young-gc-fix" ] && { RV_REPO_ROOT=$_benv_p; break; }
    _benv_p=$(dirname "$_benv_p")
  done
  RV_REPO_ROOT=${RV_REPO_ROOT:-$(cd "$(dirname "$_benv_self")" && pwd)}
fi

# --- OS flavor ----------------------------------------------------------------
case "$(uname -s)" in
  Darwin) RV_OS=mac ;;
  *)      RV_OS=linux ;;
esac

# --- JDK detection: glob build/*/images/jdk so arch/platform never hardcoded --
# When several configs exist (e.g. linux-x86_64-server-release, mtcleanup,
# weakobj), pick deterministically: an explicit RV_CONF wins, else a *release*
# config, else any config — but never the 'weakobj' ablation (it crashes).
_benv_pick_jdk() {  # $1 = tree dir; echo the chosen images/jdk
  local d
  if [ -n "${RV_CONF:-}" ] && [ -x "$1/build/$RV_CONF/images/jdk/bin/java" ]; then
    echo "$1/build/$RV_CONF/images/jdk"; return 0
  fi
  for d in "$1"/build/*release*/images/jdk; do
    [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
  done
  for d in "$1"/build/*/images/jdk; do
    case "$d" in *weakobj*) continue ;; esac
    [ -x "$d/bin/java" ] && { echo "$d"; return 0; }
  done
  return 1
}
PATCHED_JDK=${PATCHED_JDK:-$(_benv_pick_jdk "$RV_REPO_ROOT/jdk21-rv-young-gc-fix")}
RV_JDK=${RV_JDK:-$PATCHED_JDK}
STOCK_JDK=${STOCK_JDK:-$(_benv_pick_jdk "$RV_REPO_ROOT/jdk21u-stock")}
# Last-resort stock fallback for Linux CI boxes with a system JDK only.
if [ -z "${STOCK_JDK:-}" ] || [ ! -x "${STOCK_JDK}/bin/java" ]; then
  for d in /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21-openjdk-amd64; do
    [ -x "$d/bin/java" ] && { STOCK_JDK=$d; break; }
  done
fi

# --- DaCapo jars: env override, then known locations --------------------------
# Searched spots are user-relative ($HOME, repo) — never a hardcoded /Users path
# — and any pre-set DACAPO / DACAPO_912 / DACAPO_CHOPIN always wins.
_benv_first_existing() { local c; for c in "$@"; do [ -n "$c" ] && [ -f "$c" ] && { echo "$c"; return 0; }; done; return 1; }
# Remember whether the caller explicitly set DACAPO (so version-specific scripts
# can pick their preferred jar while still honoring an explicit override).
[ -n "${DACAPO:-}" ] && RV_DACAPO_USER_SET=1 || RV_DACAPO_USER_SET=0
DACAPO_912=${DACAPO_912:-$(_benv_first_existing \
  "$HOME/Downloads/dacapo-9.12-bach.jar" \
  "$RV_REPO_ROOT/jdk21-rv-young-gc-fix/bench/lib/dacapo.jar")}
DACAPO_CHOPIN=${DACAPO_CHOPIN:-$(_benv_first_existing \
  "$HOME/Downloads/dacapo-23.11/dacapo-23.11-chopin.jar")}
# Generic default: 9.12 (matches the classic spec set) unless told otherwise.
DACAPO=${DACAPO:-${DACAPO_912:-$DACAPO_CHOPIN}}

# --- async-profiler lib: .dylib on mac, .so on linux --------------------------
if [ "$RV_OS" = mac ]; then _benv_asp_ext=dylib; else _benv_asp_ext=so; fi
RV_ASP=${RV_ASP:-$(_benv_first_existing \
  "$HOME/async-profiler/lib/libasyncProfiler.$_benv_asp_ext" \
  "$HOME/async-profiler/build/lib/libasyncProfiler.$_benv_asp_ext" \
  "/usr/local/async-profiler/lib/libasyncProfiler.$_benv_asp_ext" \
  "/opt/async-profiler/lib/libasyncProfiler.$_benv_asp_ext")}

# --- portable timeout ---------------------------------------------------------
if command -v timeout >/dev/null 2>&1;    then RV_TIMEOUT=timeout
elif command -v gtimeout >/dev/null 2>&1; then RV_TIMEOUT=gtimeout
else                                           RV_TIMEOUT=""; fi

rv_run_timeout() {  # $1=secs, rest=cmd; runs with hard timeout if available
  local secs=$1; shift
  if [ -n "$RV_TIMEOUT" ]; then "$RV_TIMEOUT" -k 30 "$secs" "$@"; else "$@"; fi
}

# Echoes a timeout PREFIX ("binary -k 30 <secs>") or "" — for use between
# `/usr/bin/time` and the real command (time execs a binary, not a function).
# Intended to be used UNQUOTED so it splits into argv, e.g.:
#   rv_time $(rv_timeout_prefix "$TIMEOUT") java ...
rv_timeout_prefix() { [ -n "$RV_TIMEOUT" ] && echo "$RV_TIMEOUT -k 30 ${1:-0}"; }

# --- portable peak-RSS via /usr/bin/time --------------------------------------
# BSD:  /usr/bin/time -l  -> "<bytes>  maximum resident set size"  (lowercase m)
# GNU:  /usr/bin/time -v  -> "Maximum resident set size (kbytes): <kb>"
if [ "$RV_OS" = mac ]; then RV_TIME_FLAG=-l; else RV_TIME_FLAG=-v; fi
rv_time() { /usr/bin/time "$RV_TIME_FLAG" "$@"; }

rv_parse_rss() {  # $1 = file with time output -> peak RSS in MB (0 if absent)
  local b kb
  b=$(grep -E "maximum resident set size" "$1" 2>/dev/null | grep -oE "[0-9]+" | head -1)
  if [ -n "$b" ]; then echo $(( b / 1048576 )); return; fi
  kb=$(grep -E "Maximum resident set size" "$1" 2>/dev/null | grep -oE "[0-9]+" | tail -1)
  echo $(( ${kb:-0} / 1024 ))
}

# --- one-line banner so runs self-document which JDKs/jar were picked ----------
rv_env_banner() {
  echo "[bench-env] os=$RV_OS"
  echo "[bench-env] PATCHED_JDK=$PATCHED_JDK"
  echo "[bench-env] STOCK_JDK=$STOCK_JDK"
  echo "[bench-env] DACAPO=$DACAPO"
  echo "[bench-env] timeout=${RV_TIMEOUT:-<none>}"
}
