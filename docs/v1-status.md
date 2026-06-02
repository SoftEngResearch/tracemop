# Native-mode codegen v1 — status as of session end

## TL;DR

**End-to-end working against the patched JDK 21.** All four dispatch
shapes — 0-param singleton, 1-param suffix (Iterator_HasNext),
multi-param suffix (Collection_UnsafeIterator), and general
(Collections_SynchronizedCollection) — fire the correct violations
under `rv-tests/` and reach parity with the stock agent. **All 159
specs in `scripts/props-stock/` generate and compile.** TestGC shows
the native IndexingTree's GC-reclaim benefit: ~12 MB peak vs ~111 MB
peak on stock, ~2× faster.

All work lives in `output/nativetree/` (three files); existing
rv-monitor source unchanged except two trivial touches plus
`scripts/install.sh` and `scripts/make-agent.sh` accepting
`-nativeindexingtree`.

A `java.lang.rv` shim built from `indexing-jdk.patch` lives at
`/tmp/v1-shim/classes` and is consumed by `/tmp/v1-work/regen.sh` so each
regen verifies the output compiles. To rebuild the shim, see
"How to continue" → step 2.

The end-to-end test harness lives in `rv-tests/` — `build-agents.sh`
builds stock + native jars, `run-tests.sh` runs both under the patched
JDK at `../jdk21-rv-young-gc-fix/build/.../images/jdk`. Set `PROPS=…`
to limit which specs go into the agent (we used `PROPS=props-mini`
with just the three reference specs for fast iteration).

## What's done

### Existing files touched (2 changes, both trivial)
- [`RVMOptions.java`](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/RVMOptions.java) — added `-nativeindexingtree` flag.
- [`RVMProcessor.java`](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/RVMProcessor.java) — one-line branch to `NativeOutput.generate()` when flag set.

### New `output/nativetree/` package (3 files, ~600 lines total)
- **`NativeOutput.java`** — orchestrator. Emits package + imports + per-spec
  factories/monitor/set classes + the top-level `<Name>RuntimeMonitor`.
  Handles cache/tree/lock field declarations and dedupes imports.
- **`NativeMonitorClass.java`** — emits the `<Spec>Monitor` and
  `<Spec>Monitor_Set` classes. Embeds logic-plugin output strings with
  `$state$` → `Prop_<id>_state` substitution and MOP macro substitution
  (`__RESET`, `__DEFAULT_MESSAGE`, etc.).
- **`NativeDispatch.java`** — emits per-event static dispatch methods.
  Working variants for **0-param singleton**, **1-param suffix**,
  **multi-param suffix** (UnsafeIterator), and **general specs**
  (UnsafeSyncCollection / UnsafeMapIterator / UnsafeSyncMap).

## Verified working

### HasNext (1-param suffix) — `/tmp/v1-out/HasNextRuntimeMonitor.java`
- Structurally matches the reference (modulo `-atomicmonitor`).
- javac passes against the `java.lang.rv` shim at `/tmp/v1-shim/classes`.
- All key pieces present: imports, monitor class with DFA + state field
  + transition tables + category flag + event methods (now including the
  `Prop_N_Category_X = …` update line — was missing in earlier draft) +
  handler + reset, factory class, runtime monitor with lock + cache + tree
  + factory instance + per-event dispatch.
- Macro substitution working: `__RESET` → `this.reset()`,
  `$state$` → `Prop_1_state`, `$transition_hasnext$` → `Prop_1_transition_hasnext`.
- Dispatch method naming correct: `hasnextEvent` (no spec prefix in
  single-spec mode; would be `HasNext_hasnextEvent` with `-merge`).

### UnsafeIterator (multi-param suffix) — `/tmp/v1-out/UnsafeIteratorRuntimeMonitor.java`
- Structurally matches the reference (modulo `-atomicmonitor`).
- javac passes.
- Per-projection cache fields (`_c_i_Map_*`, `_c_Map_*`, `_i_Map_*`).
  Single physical `IndexingTree` keyed by full params with null padding.
- `createEvent`: full-key `getOrCreate2(monitorFactory, c, i)`; on
  `monitorFactory.created`, insert the new monitor into projection sets
  via `getOrCreate2(monitorSetFactory, c, null)` and `(null, i)`.
- `updatesourceEvent` / `nextEvent`: guard on `<spec>_activated`,
  per-projection cache check, fall through to `get2(c, null)` / `get2(null, i)`,
  dispatch via `matchedSet.event_<id>(...)`.
- Set's `event_<id>` does per-monitor category-flag check + handler call
  (was missing in earlier draft).

### General specs — `UnsafeSyncCollection`, `UnsafeMapIterator`, `UnsafeSyncMap`
- All three structurally match references and javac-clean.
- Emitted machinery: `I<Spec>Monitor` interface, `<Spec>DisableHolder`
  class, `tau`/`disable` fields + getters/setters on the monitor class,
  per-spec `<Spec>_timestamp` counter, spec-body field declarations
  (e.g. `Object c;`), constructor changed to `(long tau)`. The
  `monitorFactory` is omitted for general specs (leaves are instantiated
  inline with the next timestamp); only `monitorSetFactory` is kept,
  and it wraps the set in a `Tuple2<Set, I<Spec>Monitor>`.
- Three dispatch shapes:
  - **Creation, partial-key** (e.g. `syncEvent(c)`): get/create projection
    `Tuple2<Set, Monitor>`; create leaf inline with `new <Spec>Monitor(ts++)`;
    add to enclosing set; broadcast via the set.
  - **Non-creation, full-key** (e.g. `syncCreateIter(c, iter)`): cache +
    `get<n>(c, iter)`; on miss run defineTo for each creation-event
    projection ⊊ event params (clone source leaf into the event's slot
    after definability checks against every non-source subset); on full
    failure drop a `DisableHolder`; dispatch only if `matchedEntry`
    `instanceof` real Monitor.
  - **Non-creation, partial-key** (e.g. `accessIter(iter)`): get/create
    projection `Tuple2<Set, IMonitor>`; drop `DisableHolder` at leaf if
    absent; broadcast via the set.

### Suite-wide validation
- 159/159 specs in `scripts/props-stock/` pass codegen + javac (via the
  same `--patch-module java.base=<shim>` recipe used by `regen.sh`).
- Required two fixes during validation:
  1. `macroSub` now rewrites bare `return;` to `return true;` (matches
     `BaseMonitor.printEventMethod` when `!generateVoidMethods`); fixed 9
     specs whose user-action code wrote void-style `return`.
  2. `specNeedsSets` now returns true for any general spec regardless of
     arity (Console_FillZeroPassword-style degenerate cases — general
     because of a zero-param sibling event, but other events are 1-param
     — still need the Set type so the dispatch can be emitted uniformly).

### Quick dev loop
- `/tmp/v1-work/regen.sh <SpecName>` — rebuilds rv-monitor, regenerates
  output, compile-tests against the `java.lang.rv` shim, diffs against
  reference. Use this for iteration.

## Real-workload validation: DaCapo pmd

Running `dacapo-23.11-chopin pmd -s small` under both agents (full 160-spec
agent) surfaced two real codegen bugs that the small `rv-tests/` programs
didn't exercise:

1. **1-param suffix non-creation events were over-creating monitors.**
   `emitOneParamSuffixDispatch` always called `getOrCreate1` regardless
   of whether the event was a creation event. For a non-creation event
   like `ListIterator_Set.set(i)`, this fabricated a fresh monitor at
   state 0 every time `set` was called on an unknown iterator, then the
   set-from-state-0 transition tripped @fail. **Symptom:** native fired
   598 false-positive `ListIterator_Set` violations on pmd vs stock's 0.
   **Fix:** non-creation events use `get1(...)`; skip dispatch if null
   ([NativeDispatch.java:144-211](rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/output/nativetree/NativeDispatch.java#L144-L211)).

2. **Multi-param suffix conflated "full key" with "creation event".**
   `emitMultiParamSuffixDispatch` set `isCreation = (eventParams.size() == specParams.size())`,
   so non-creation full-key events (e.g. `Map_UnsynchronizedAddAll.leave(t, s)`)
   ran the creation path and fabricated monitors, which then propagated
   into projection sets for `modify(s)` and tripped @fail there.
   **Symptom:** native fired 764 false-positive `Map_UnsynchronizedAddAll`
   violations on pmd vs stock's 0. **Fix:** use
   `event.isStartEvent()` for isCreation; add a separate non-creation
   full-key branch that uses `get<n>` and skips dispatch if null
   ([NativeDispatch.java:259-353](rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/output/nativetree/NativeDispatch.java#L259-L353)).

After both fixes, pmd produces **identical 556-violation 6-spec output
under both agents**. Per-spec breakdown:

| Spec | Stock | Native |
|------|------:|-------:|
| Collections_SortBeforeBinarySearch | 389 | 389 |
| Iterator_HasNext | 144 | 144 |
| Closeable_MeaninglessClose | 9 | 9 |
| Collection_UnsafeIterator | 6 | 6 |
| Reader_ManipulateAfterClose | 4 | 4 |
| Closeable_MultipleClose | 4 | 4 |

(fop crashes for both agents — `Helvetica.<clinit>` exceeds 64KB after
aspect weaving; unrelated to our codegen.)

## End-to-end results (`rv-tests/`)

`rv-tests/` invoked under the patched JDK at
`../jdk21-rv-young-gc-fix/build/macosx-aarch64-server-release/images/jdk`,
weaving with stock and native agents built from `PROPS=props-mini`
(Iterator_HasNext, Collection_UnsafeIterator, Collections_SynchronizedCollection):

| Test | Shape exercised | Stock | Native |
|------|-----------------|-------|--------|
| TestHasNextSafe | 1-param suffix, no violation | exit 0 | exit 0 ✅ |
| TestHasNext | 1-param suffix, violation | `Iterator_HasNext` violated | `Iterator_HasNext` violated ✅ |
| TestUnsafeIterator | multi-param suffix, violation | `Collection_UnsafeIterator` violated | `Collection_UnsafeIterator` violated ✅ |
| TestSyncCollection | general spec, violation | `Collections_SynchronizedCollection` violated | `Collections_SynchronizedCollection` violated ✅ |
| TestConcurrent | 100 threads × 100 iterators | exit 0 (no spurious) | exit 0 (no spurious) ✅ |
| TestGC | 1M short-lived iterators, -Xmx256m | peak ~111 MB, 2.3 s | **peak ~12 MB, 1.2 s** ✅✅ |

The general-spec test confirms the defineTo cloning, DisableHolder
fallback, broadcast through the enclosing Monitor_Set, and `tau`/`disable`
timestamp bookkeeping are all runtime-correct end-to-end.

## What's incomplete

### Cosmetic: violation-message location info missing
Stock prints `"… has been violated on line TestHasNext@TestHasNext.java:9.
Documentation for this property can be found at … "`; native prints just
`"… has been violated."`. The `__LOC` macro in `NativeMonitorClass.macroSub`
is hardcoded to `"<unknown>"`, and the documentation-URL suffix isn't
emitted at all. Both come from option-conditioned branches in stock
`BaseMonitor.printEventMethod`; we'd need to mirror them.

### `alive_parameters_*` and `terminateInternal` DNF body
Currently emit an empty `terminateInternal`. For strict stock-match should
emit the same `alive_parameters_<g>` declarations + DNF check that stock
emits (even though they're dead code in native mode — see earlier discussion).
Source data is `OptimizedCoenableSet` via `spec.getCoenableSet()` or similar;
need to thread that through to `NativeMonitorClass`.

### Zero-param event on parametric spec — DONE
Three specs use this pattern (`Console_FillZeroPassword`,
`ObjectStreamClass_Initialize`, `PasswordAuthentication_FillZeroPassword`).
Now handled by emitting a `<Spec>__Map` singleton (a Tuple2 whose
`value1` is the universal set of every monitor in the spec) and adding
`<Spec>__Map.getValue1().add(created)` to every monitor-creation path.
Zero-param events broadcast via `<Spec>__Map.getValue1().event_<id>(...)`.
Mirrors stock's `__Map` pattern. Routed in
`NativeDispatch.emitAllEvents` → `emitZeroParamBroadcast`.

### Multi-source defineTo (theoretical)
Only single-creation-event general specs have been verified against the
reference. Multi-source emission (when several creation events have
different proper-subset param signatures) iterates sources in declaration
order without inter-source interaction handling. No spec in the reference
set exercises this; behavior is best-effort.

## How to continue

### Next session, in this order

1. **Add the `__LOC`/documentation-URL substitution** to match stock's
   violation-message format. The joinpoint is already in scope inside the
   monitor event method since we generate with `-locationFromAjc`; just
   need to thread `joinpoint` into the macro substitution and append the
   doc-URL suffix.

2. **Scale up the rv-tests harness** to weave in all 159 specs (run
   `bash rv-tests/build-agents.sh` without `PROPS=`) and add a smoke
   test that exercises a workload touching dozens of specs at once —
   confirms no cross-spec interference and that the universal-set
   broadcast paths don't blow memory.

3. **Rebuild the shim if `indexing-jdk.patch` changes.** The shim at
   `/tmp/v1-shim/classes` was extracted from the patch with:
   ```sh
   mkdir -p /tmp/v1-shim/java/lang/rv
   for f in IndexingTree.java IndexingTreeEntry.java RuntimeMonitorFactory.java; do
     awk -v target="openjdk-7-indexing/jdk/src/share/classes/java/lang/rv/$f" \
       '/^diff -uNr / { in_block = ($NF == target); next }
        in_block && /^[-+]{3}|^@@/ { next }
        in_block && /^\+/ { print substr($0, 2) }' \
       indexing-jdk.patch > /tmp/v1-shim/java/lang/rv/$f
   done
   javac --patch-module java.base=/tmp/v1-shim -d /tmp/v1-shim/classes \
     /tmp/v1-shim/java/lang/rv/*.java
   ```

3. **Add `alive_parameters_*` DNF emission** for full stock semantic match.
   Won't change runtime behavior (DNF is dead in native), but cleans up
   the diff.

4. **Audit multi-source defineTo** if any real spec ever exercises it.
   None in the reference set does today.

### Key resources

- **Reference outputs** for all 5 spec types:
  `/Users/jy2249/Desktop/jdk7-4-rv/tracemop/aspect-specs-modified-hotspot/*.aj`
- **Stock codegen for reference** (DON'T modify, but read for patterns):
  - `output/monitor/BaseMonitor.java` — how stock emits monitor class +
    event methods.
  - `output/monitor/MonitorTermination.java` — coenable + alive_parameters.
  - `output/combinedoutputcode/event/itf/EventMethodBody.java` — stock
    dispatch using nested maps.
  - The OLD branch `jdk21-native-indexingtree` had a previous attempt at
    this — `git show jdk21-native-indexingtree:rv-monitor/src/main/java/...`
    to see how it was previously structured. Don't copy wholesale (it had
    bugs we discussed), but useful for cross-referencing.

### Files in current state

```
rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/
  RVMOptions.java                                  [+3 lines]
  RVMProcessor.java                                [+2 lines]
  output/nativetree/                               [new package]
    NativeOutput.java                              [307 lines]
    NativeMonitorClass.java                        [397 lines]
    NativeDispatch.java                            [717 lines]
```

## Notes / design recap (for context-rebuilding)

- **Tree shape**: ONE physical `IndexingTree` per spec. Projection entries
  share it via null-padding in the keys array. Set up per-spec field
  `<Spec>_<paramKey>_Map`.
- **Value shape**: heterogeneous in same tree. Suffix specs hold raw
  `Monitor` at full keys and `MonitorSet` at projection keys. General
  specs hold `Tuple2<Set, IMonitor>` uniformly.
- **Strong refs**: monitor's remembered params are plain strong refs
  (`final Collection RVM_c`). No `WeakReference` wrappers — the tree is
  weakly-scanned via `WeakObjArrayKlass` so monitors don't artificially
  pin keys.
- **Cleanup story v1**: GC handles it via the JDK's `clean_all_trees`.
  No daemon, no inline tombstones, no state-terminal flag. Coenable
  termination is dead code in native. v2/v3 layer on top.
- **Lock**: per-spec `ReentrantLock`, spin-yield acquisition to match
  stock pattern (stock has the same bug-prone no-try/finally pattern).
- **Method naming**: in single-spec mode, dispatch methods are just
  `<event>Event(...)`. With `-merge`, they become `<Spec>_<event>Event(...)`.
  Reference set is all single-spec mode.
- **`-atomicmonitor` not supported in v1**. Reference set uses it
  (`AbstractAtomicMonitor`, `AtomicInteger pairValue`). v1 emits
  `AbstractSynchronizedMonitor` with plain `int Prop_<id>_state`.
  Semantically equivalent under the per-spec lock. Diffs against
  reference will include the atomic-vs-sync delta — filter mentally.
