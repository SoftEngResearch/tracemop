# rv-tests results

Stock (weak-ref backend) vs Native (VM IndexingTree backend), 160-spec agents,
both on the patched JDK (`jdk21-rv-young-gc-fix`), DaCapo 9.12 `default` size.

## #1 — Per-spec events & monitors (stock vs native)

What each spec fires (events) and how many monitors it creates, per benchmark,
for both backends. Confirms the two backends do **identical work** (parity).

- Readable tables: [results/perspec-events-monitors.txt](results/perspec-events-monitors.txt)
- Consolidated CSV (8 benchmarks x 160 specs = 1280 rows): [results/perspec-events-monitors.csv](results/perspec-events-monitors.csv)
  - columns: `bench,spec,events_stock,events_native,monitors_stock,monitors_native`
- Regenerate: `bash perspec-breakdown.sh`  (or `REUSE=1 ...` to reuse cached stats)

Per benchmark: 26-52 specs are active; the other 108-134 never fire an event.
Events/monitors match stock vs native within run-to-run noise.

## #2 — Heap-regime sweep (2g vs 16g), time + memory

Steady-state time (last of n=10) and peak managed heap, across heaps x
benchmarks x reps, stock vs native. Tests the crossover: native trails on small
heaps (per-event lookup cost) and leads on large heaps (stock's lazy weak-ref
cleanup balloons; native's eager tree cleanup stays compact).

- Live CSV: [heap-sweep-results/results.csv](heap-sweep-results/results.csv)
  - columns: `heap,gc,bench,agent,rep,steady_ms,peak_heap_mb,status`
- Config: heaps {2g, 16g}, n=10, 3 reps, G1, 8 benchmarks (avrora h2 jython
  luindex lusearch pmd sunflow xalan)
- Regenerate: `HEAPS="2g 16g" ITERS=10 REPS=3 bash run-dacapo-agents.sh`

NOTE: this machine has 16GB RAM, so at `-Xmx16g` stock's balloon is RAM-capped
(~3GB here vs a big server's ~8GB) -- the crossover direction holds but the 16g
magnitude is milder than a large-RAM server.

## #3 — Coenable (terminated monitors), stock
Monitors stock retires early via the enable-set optimization (native's is dead code).
- [results/stock-terminated.csv](results/stock-terminated.csv) — created vs terminated per bench.
  High exactly where native is slow (lusearch 90%, xalan 84%, sunflow 88%).
- Read live via reflection agent (/tmp/termread/termread.jar) on the stock STATS agent; no rebuild.

## #4 — Codegen comparison: old modified-hotspot vs current native codegen
- [results/codegen-compare/](results/codegen-compare/) — per spec: `<Spec>.OLD-reference.aj`,
  `<Spec>.NEW-codegen.java`, `<Spec>.diff`. UnsafeSyncMap diverges most (new much shorter;
  it's a "general spec" NativeDispatch flags as not fully handled).
- Regenerate/diff any spec: `bash compare-codegen.sh <SpecName>`

## #5 — async-profiler runs
- [results/profile-why.md](results/profile-why.md) — analysis: native-minus-stock CPU diff +
  monitoring-subtree breakdown (lusearch@2g = lock contention; pmd@16g = strong-ref GC + multi-key).
- [results/profiles/](results/profiles/) — raw outputs:
  - `lusearch-t1-{native,stock}.flat.txt` — flat top-frame CPU tables (t=1, no contention)
  - `{native,stock}.leaf.txt` — leaf-frame histograms (diff source)
  - `xalan-{native,stock}.collapsed.txt`, `profwhy-{native,stock}.collapsed.txt` — collapsed stacks (flamegraph source)
- Regenerate for any cell: `bash profile-why.sh <bench> <heap> [iters]` (now writes to results/profiles/<bench>-<heap>/)

## Scripts
- [run-dacapo-agents.sh](run-dacapo-agents.sh) - unified DaCapo time/heap/RSS runner, stock vs native.
  Absorbs the old heap-sweep (`HEAPS="2g 16g"`) and overhead (`BASELINE=1 THREADS=1`) scripts.
- [perspec-breakdown.sh](perspec-breakdown.sh) - per-spec events/monitors, stock vs native
- (archived: heap-sweep.sh, overhead.sh, stats-all.sh, diff-batch.sh — see [archive/](archive/))
