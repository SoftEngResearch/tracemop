# Running rv-tests on a remote machine

Scripts are OS-agnostic (see `bench-env.sh`): the JDK, DaCapo jar, `timeout`,
`/usr/bin/time` RSS parsing, and async-profiler lib are all auto-detected on
both macOS and Linux. Nothing here hardcodes a platform or a `/Users/...` path.

## One-time bring-up (Linux x86_64, repo already pulled, JDK not built)

```bash
# 1. Build the RV-patched JDK (the native IndexingTree backend). Takes a while.
cd <repo>/jdk21-rv-young-gc-fix
bash configure        # add --with-boot-jdk=... if configure can't find one
make images           # produces build/linux-x86_64-server-release/images/jdk

# 2. Build the four LTW agents (stock + native, with/without stats).
cd <repo>/tracemop/rv-tests
bash build-agents.sh

# 3. Get the DaCapo jar (any ONE of):
#    - copy dacapo-9.12-bach.jar into ~/Downloads/   (auto-detected), or
#    - export DACAPO=/abs/path/to/dacapo-9.12-bach.jar
#    download: https://github.com/dacapobench/dacapobench/releases/tag/v9.12-bach

# 4. Verify everything is in place.
bash preflight.sh
```

`preflight.sh` checks the patched JDK, all four agents, the DaCapo jar, the
`timeout`/`/usr/bin/time`/async-profiler tooling, and RAM — and prints the exact
fix command for anything missing. Get it to `0 fail` before running.

Note: only the **patched** JDK is needed for the agent runs (stock and native
backends both run on it). The separate stock JDK is only used by the Family B
`jdk21-rv-young-gc-fix/bench/` woven-jar lane.

## Running

```bash
cd <repo>/tracemop/rv-tests

# time + heap + RSS, stock vs native (the headline A/B)
bash run-dacapo-agents.sh

# overhead vs a no-agent baseline (single-threaded isolation)
BASELINE=1 THREADS=1 ITERS=8 bash run-dacapo-agents.sh

# heap sweep
HEAPS="2g 8g 16g" ITERS=10 REPS=3 bash run-dacapo-agents.sh

# per-spec event/monitor counts ; behavior diff ; profiling
bash perspec-breakdown.sh
bash diff-bench.sh --batch small luindex lusearch avrora xalan
EVENTS=wall bash profile-jfr.sh        # wall-clock profiling (linux-friendly)
```

All knobs (`BENCHMARKS ITERS REPS GC HEAPS THREADS AGENTS SIZE TIMEOUT OUT`) are
env vars. Results go to `dacapo-results/results.csv` by default.

## Long runs — don't lose them on disconnect

```bash
# tmux (preferred): survives SSH drop, lets you re-attach
tmux new -s rv 'HEAPS="2g 16g" REPS=3 bash run-dacapo-agents.sh 2>&1 | tee run.log'
#   detach: Ctrl-b d   reattach: tmux attach -t rv

# or nohup
nohup bash run-dacapo-agents.sh > run.log 2>&1 &
tail -f run.log
```

## async-profiler on Linux

`EVENTS=wall` (off-CPU + on-CPU wall time) works without privileges. `EVENTS=cpu`
needs perf access — as root once per boot:
```bash
sysctl kernel.perf_event_paranoid=1
sysctl kernel.kptr_restrict=0
```
Put `libasyncProfiler.so` under `~/async-profiler/lib/` (auto-detected).
