# Agents in this directory

Snapshot copied from the repo root on **2026-06-04**.

## `native-*` — FIXED (redundant dispatch lock removed)

The two `native-no-track-*-agent.jar` here are the **fixed** native
IndexingTree agents. The redundant per-spec dispatch lock (`<spec>_RVMLock`,
acquired via `while(!tryLock()) Thread.yield()`) has been **removed**. Native
now relies solely on the woven JavaMOP aspect's `<spec>_MOPLock` to serialize
per-spec dispatch — exactly like stock's aspect-based backend.

Why it was safe:
- `MOPLock` already wraps every event, and every IndexingTree access happens
  inside a dispatch method called under `MOPLock`, so `RVMLock` was a second
  coarse lock over the identical per-spec scope.
- GC-vs-mutator safety on the tree is handled inside the VM (no-safepoint
  `adjustCapacity0` rehash, snapshot reads), not by any Java lock.

Verified behavior-preserving:
- Violations MATCH stock on every benchmark tried (incl. multithreaded xalan).
- Attribution test: luindex monitor population is bit-identical with and
  without the lock (the small stock-vs-native gap is inherent weak-ref-vs-
  VM-tree reclamation timing, not the lock).
- Multithreaded monitor-pop deltas are run-to-run noise (direction flips).

Bonus: removing the lock also eliminates `codegen-bugs.txt` Bug 1
(lock-without-try/finally → hang on handler exception).

Source toggle: `NativeDispatch.EMIT_DISPATCH_LOCK` (default `false`). Flip to
`true` to rebuild the old self-locking variant (aspect-less use / ablation).

## `stock-*` — unchanged

The `stock-no-track-*-agent.jar` are the normal aspect-based weak-reference
agents. They were **not** "fixed" — stock never emitted the redundant lock.
Refreshed here only to keep the comparison set current.

## Rebuilding

`bash rv-tests/build-agents.sh` regenerates all four into the repo root and
copies them here. `run-tests.sh` uses the `*-no-track-no-stats-agent.jar`
pair from this directory.
