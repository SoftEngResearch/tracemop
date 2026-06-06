# Archived scripts (retired 2026-06-06)

These were folded into other runners during a consolidation. Kept here for
reference / recovery — nothing depends on them. Each was behavior-preserved in
its replacement.

| Archived | Replaced by | How |
|----------|-------------|-----|
| `overhead.sh`    | `../run-dacapo-agents.sh` | `BASELINE=1 THREADS=1 ITERS=8 bash run-dacapo-agents.sh` — adds a no-agent baseline column + the overhead summary table. |
| `heap-sweep.sh`  | `../run-dacapo-agents.sh` | `HEAPS="2g 16g" ITERS=10 REPS=3 bash run-dacapo-agents.sh` — the `HEAPS` sweep dimension and per-(heap,bench,agent,rep) CSV. |
| `stats-all.sh`   | `../perspec-breakdown.sh` | perspec-breakdown is a strict superset (same per-spec event/monitor CSV, plus violations, `REUSE=1` cache, and formatted tables). |
| `diff-batch.sh`  | `../diff-bench.sh --batch` | `bash diff-bench.sh --batch <size> <bench...>` — same one-line MATCH/DIFFER summary table per benchmark. |
