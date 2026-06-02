# Native IndexingTree Codegen — Implementation Notes

## Pipeline

A `.mop` file passes through three stages before becoming a `.aj` source file:

```
.mop
  │
  ▼
PARSE          → RVMonitorSpec
  │              (parameters, events, raw property formula, modifiers)
  │
  ▼
ANALYZE        → FSM transition tables (per property)
  │              EnableSet  (per event) — drives tree shape
  │              CoEnableSet (per event) — drives termination logic
  │
  ▼
CODEGEN        → Java source string
                 Reads RVMonitorSpec + analysis output.
                 Branches on Main.options.useNativeIndexingTree.

.aj
```

We only modify the codegen stage. Parse and analyze are unchanged — the
new path consumes the same upstream data.

## Codegen files

"Emit method" refers to string-concat (`ret += "..."`) or codedom AST (typed AST nodes composed, then formatted by an `ICodeFormatter`). 
"Native change" = what we change for `-nativeindexingtree`.

| File | Emits | Emit Method | Change req for Native Indexing Tree |
|---|---|---|---|
| `RVMOptions.java` | `-nativeindexingtree` flag definition | n/a | add the flag |
| `AdviceBody.java` | event-method-body dispatcher | n/a | route to new class when flag on |
| `Imports.java` | top-of-file import block | string-concat | switch to `requiredNative` import set |
| `RVMOutputCode.java` | top-level orchestrator (no direct emission) | n/a | skip `cleanUp()` invocation |
| `CombinedOutput.java` | static inits, factory classes, lock decls | string-concat | suppress daemon blocks; emit factory classes |
| `BaseMonitor.java` | `<Spec>Monitor` class shell, transitions, CAS dispatch | mixed (mostly string) | minor — base class choice unchanged |
| `MonitorTermination.java` | `Ref_<p>` fields, `alive_parameters_*`, `terminateInternal` | string-concat | drop `Ref_<p>` for non-remembered; empty `terminateInternal` body |
| `MonitorSet.java` | `<Spec>Monitor_Set` class with `event_X` methods | mixed (leans codedom) | drop `numAlive` compaction OR replace with Phase B hook |
| `IndexingDeclNew.java` | tree field declarations + cache fields | mostly string-concat | emit `new IndexingTree()` instead of `new MapOfMonitor<>(0)` |
| `IndexingCacheNew.java` | per-tree cache fields/helpers | codedom | unchanged — cache machinery is tree-agnostic |
| `IndexingTreeManager.java` (codegen) | `cleanUp()` method body | string-concat | suppress entirely |
| `IndexingTreeImplementation.java` | tree access expressions (get/put) | codedom | likely augment for `get<arity>` / `put` shape (verify) |
| `IndexingTreeQueryResult.java` | matched-entry locals + composition helpers | codedom | likely minor augmentation (verify) |
| `EventMethodBody.java` | the body of one event handler | codedom | leave |
| (new) `IndexingTreeEventMethodBody.java` | the body of one event handler, native path | codedom  | **the big new file** |




## Coenable on the native path


Coenable analysis computes, per event, a DNF over alive-parameter
clauses: at least one clause must remain satisfiable for any future
violation to be reachable. If no clause holds, the monitor cannot fire
again and can be dropped even when some of its keys are still alive.


### Where coenable runs


1. An event fires that inserts a fresh monitor into a
   tree. `tree.add()` walks the bucket head, reaping entries whose key
   has been GC'd since the last insert:

   ```java
   for (Entry e : head) {
       if (e.key.get() == null) {        // dead key
           e.value.terminate(treeid);    // → terminateInternal
           remove e;
       }
   }
   ```

2. `terminate(treeid)` lands in the generated
   `terminateInternal(idnum)` on the monitor.

   ```java
   void terminateInternal(int idnum) {
       // flip alive_parameters_<j> for clauses containing param[idnum]
       if (no required clause holds for lastEvent)
           RVM_terminated = true;
   }
   ```

3. On the next event dispatch through a MonitorSet, the
   generated `event_X` skips terminated monitors and compacts them out:

   ```java
   for (Monitor m : elements) {
       if (!m.isTerminated())
           m.event_X(...);
   }
   // survivors shifted to front, size updated
   ```


### coenable doesn't for the native indexing tree

The native path removes Step 1. No weak references, no
`BucketNode.cleanUpUnnecessaryMappings`, no `key.get() == null`. With
nothing calling `val.terminate(treeid)`:

1. The generated `terminateInternal` body becomes dead code → codegen
   drops it.
2. `RVM_terminated` is never set → Step 3's `numAlive` compaction is a
   no-op → codegen drops that too.

This matches the paper's reference output
(`aspect-specs-modified-hotspot/`): empty `terminateInternal`, no
`alive_parameters_*`, no `numAlive` compaction, no `Ref_<p>` strong
fields.

To bring coenable back, we need a new Step 1 a death-detection
trigger that doesn't use weak refs. So we need to update our current GC cleanup to do this.

### Sketch of coenables in gc

Both phases run inside the existing STW cleanup pass in
`indexingTreeManager.cpp`, immediately after the entry-key liveness
sweep that already exists.

```
Existing STW cleanup
  │
  ├─ Phase A: per-entry sweep
  │   for each surviving entry:
  │     for each dead key in entry.keys[]:
  │       value = entry.value      // a Monitor or a MonitorSet
  │       propagate_death(value, paramMapping[k])
  │         // OR death-mask bits into monitor.aliveMask
  │         // if (aliveMask & requiredByLastEvent[lastEvent]) == 0:
  │         //   monitor.terminated = true
  │
  └─ Phase B: per-MonitorSet compaction
      for each surviving entry whose value is RVMonitorSetBase:
        compact elements[], dropping entries with terminated == true
```

The monitor itself doesn't store its own keys — keys live in the entry's
`keys[]` array, which the cleanup is already walking. Phase A propagates
the dead-key observation into `aliveMask` as a side effect of the
existing sweep.

### Codegen-side delta — `MonitorTermination.java`

For coenable to land, the generated monitor needs three new pieces of
data, all derived from `OptimizedCoenableSet` (already computed during
analyze):

```java
class <Spec>Monitor extends RVMonitorBase {
    // OR-mask of clauses to clear when parameter i dies.
    private static final int[] DEATH_MASK = { ... };

    // Required-clause mask, indexed by lastEvent.
    private static final int[] REQUIRED_MASK_BY_EVENT = { ... };

    // Registers the per-class metadata with the JVM at class load.
    static {
        RVMonitorBase.registerClass(<Spec>Monitor.class,
                                    DEATH_MASK,
                                    REQUIRED_MASK_BY_EVENT);
    }

    // Body remains empty — JVM does the bit-flipping in Phase A.
    @Override protected final void terminateInternal(int idnum) {}
}
```

Sources for the masks:

- `DEATH_MASK[i]` = OR of clause-bits for every coenable clause
  containing parameter `i`. Pulled from
  `OptimizedCoenableSet.getParameterGroups()`.
- `REQUIRED_MASK_BY_EVENT[e]` = bitmask of clauses required to be
  satisfiable when `lastEvent == e`. Pulled from
  `coenableSet.getEnable(eventId)` per event.

Existing emission in `MonitorTermination.java` already iterates
`OptimizedCoenableSet` to produce `alive_parameters_*` booleans and the
DNF switch. The change is to fold that loop down into two integer
arrays plus the `registerClass` call.

### Java/JVM-side delta

- New `java.lang.rv.RVMonitorBase` (Java) — fields `int aliveMask`,
  `boolean terminated`, `int lastEvent`; static native `registerClass`.
- New `java.lang.rv.RVMonitorSetBase` (Java) — `Object[] elements`,
  `int size` at fixed offsets so C++ can compact in place.
- `IndexingTree` constructor takes `int[] paramMapping` — translates the
  tree's local key indices to canonical spec-param indices.
- C++ side (`indexingTreeManager.cpp`):
  - `MonitorClassMeta` registry (`Klass*` → mask tables) populated from
    JNI.
  - `propagate_death(value, spec_param)` and `compact_monitor_set(value)`
    helpers.
  - Wired into `clean_bucket_range` at the existing dead-key observation
    point.

### Decision flag

Gate the new behavior behind `-XX:+RVCoenableInGc` (HotSpot flag) so the
native codegen output runs unchanged when the flag is off. With the flag
off, the registered metadata is harmless dead data; with it on, the JVM
performs Phase A + Phase B during cleanup.

This lets us A/B compare:

- Native + coenable-off (paper-faithful)
- Native + coenable-on (paper-extended)
- Baseline (`EventMethodBody.java` path, original WeakRef behavior)

without recompiling the codegen between configurations.
