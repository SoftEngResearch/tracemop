# Native-Mode Codegen — How the Existing Codebase Works, and What We Added

Two questions in order:

1. **What does the existing stock codegen do?** — so we have a baseline to
   talk about.
2. **What did we add for `-nativeindexingtree`, and why?** — the actual
   change, with the rationale.

This doc is intentionally short and concrete; the generated output snippets
are the source of truth.

---

## 1. The existing codebase

### 1.1 What it produces

A `.mop` / `.rvm` spec compiles to a single `.aj` (AspectJ) source file
containing four kinds of class:

- `<Spec>Monitor` — per-tuple FSM. State, transition tables, event methods,
  category flags, handlers, reset.
- `<Spec>Monitor_Set` — broadcast container. Iterates `elements[]` and
  dispatches `event_<id>` to each monitor.
- `<Name>RuntimeMonitor` — the static-dispatch class. Per-spec lock + tree
  field + cache fields, plus one `static <event>Event(args)` method that the
  woven AspectJ advice calls.
- `<Name>MonitorAspect` — pointcuts and advice. Calls into the
  RuntimeMonitor.

The events themselves come from the `.mop` (one event per `before/after`
pointcut). The FSM transition tables, monitoring body, and category
conditions come from the **logic plugin**, which compiles the property
formula. Our codegen treats those as opaque strings.

### 1.2 Stock pipeline

```
.mop → Parser → RVMSpecFile
                      │
        property analysis (EnableSet, CoEnableSet, OptimizedCoenableSet)
                      │
RVMProcessor.process()
  └── RVMOutputCode.toString()       ◄── stock orchestrator
        ├── MonitorSet (rv-monitor/...output/monitorset/MonitorSet.java)
        ├── SuffixMonitor / BaseMonitor (.../output/monitor/)
        └── CombinedOutput (.../output/combinedoutputcode/)
              ├── LockManager, TimestampManager, ActivatorManager
              ├── IndexingTreeManager + IndexingTree (.../indexingtree/)
              ├── EventManager + AdviceBody (.../event/)
              └── RuntimeServiceManager (daemons)
```

`RVMProcessor.process()` ([line 74](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/RVMProcessor.java#L74))
calls `new RVMOutputCode(name, rvmSpecFile).toString()`. That builder
constructs a `CombinedOutput`, which in turn owns *eight* sub-managers — one
per concern. The output is assembled by `CombinedOutput.toString()`
([CombinedOutput.java](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/output/combinedoutputcode/CombinedOutput.java)).

The `output/combinedoutputcode/indexingtree/` package has three flavors
(centralized, decentralized, reftree) and inside centralized/decentralized
further specializes by parameter shape (`FullParam`, `PartialParam`,
`NoParam`, `OneFullParam`, ...). `IndexingTreeManager` orchestrates them.
There's also a newer parallel package `newindexingtree/` using the codedom
AST (`IndexingDeclNew`, `IndexingTreeImplementation`, `IndexingCacheNew`).

### 1.3 The runtime data structure stock uses

For `Iterator_HasNext` (1-param spec), `<Name>RuntimeMonitor` declares:

```java
private static final MapOfMonitor<HasNextMonitor> HasNext_i_Map =
    new MapOfMonitor<HasNextMonitor>(0);
private static Object HasNext_i_Map_cachekey_i;
private static HasNextMonitor HasNext_i_Map_cachevalue;
```

`MapOfMonitor` is one of ~30 hand-rolled hashmap-style classes in
[`rv-monitor-rt/.../rt/map/`](../rv-monitor-rt/src/main/java/com/runtimeverification/rvmonitor/java/rt/map/)
(`RVMMap`, `RVMMapOfMonitor`, `RVMRefMap`, `RVMTagRefMap`, ...). Keys are
wrapped in `CachedWeakReference` so the JVM can collect a monitor's
parameters; entries with collected keys are reaped by a Java daemon
(`RVMMapManager`, started in a `static` block) periodically calling
`cleanUpUnnecessaryMappings`.

Per-monitor, stock also stores `Ref_<p>` weak-reference fields so
`terminateInternal` can run the coenable DNF over `alive_parameters_*`
booleans and flip `RVM_terminated` when no satisfying clause remains.
`<Spec>Monitor_Set.event_<id>` then `numAlive`-compacts terminated monitors
out on every dispatch.

### 1.4 Stock dispatch — `HasNextRuntimeMonitor.hasnextEvent`

Lifted from [`aspect-specs/HasNextMonitorAspect.aj:238`](../aspect-specs/HasNextMonitorAspect.aj#L238):

```java
public static final void hasnextEvent(Iterator i) {
    HasNext_activated = true;
    while (!HasNext_RVMLock.tryLock()) Thread.yield();

    CachedWeakReference wr_i = null;
    MapOfMonitor<HasNextMonitor> matchedLastMap = null;
    HasNextMonitor matchedEntry = null;
    boolean cachehit = false;
    if (i == HasNext_i_Map_cachekey_i) {
        matchedEntry = HasNext_i_Map_cachevalue;
        cachehit = true;
    } else {
        wr_i = new CachedWeakReference(i);
        matchedLastMap = HasNext_i_Map;
        HasNextMonitor node_i = HasNext_i_Map.getNodeEquivalent(wr_i);
        matchedEntry = node_i;
    }
    if (matchedEntry == null) {
        if (wr_i == null) wr_i = new CachedWeakReference(i);
        HasNextMonitor created = new HasNextMonitor();
        matchedEntry = created;
        matchedLastMap.putNode(wr_i, created);    // ◄── new wrapper alloc per insert
    }
    matchedEntry.Prop_1_event_hasnext(i);
    if (matchedEntry.Prop_1_Category_match) matchedEntry.Prop_1_handler_match();

    if (!cachehit) { HasNext_i_Map_cachekey_i = i; HasNext_i_Map_cachevalue = matchedEntry; }
    HasNext_RVMLock.unlock();
}
```

Each cache miss allocates a `CachedWeakReference` wrapper around `i`. The
daemon must scan the map to reap dead entries. Every `Monitor_Set.event_<id>`
walk has to compact terminated entries (see
[`HasNextMonitorAspect.aj:27-46`](../aspect-specs/HasNextMonitorAspect.aj#L27)).

---

## 2. What we added — and why

### 2.1 The wedge

A single flag and a single branch:

- [`RVMOptions.java`](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/RVMOptions.java) — `-nativeindexingtree`.
- [`RVMProcessor.java:74`](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/RVMProcessor.java#L74) — when set, route to a new emitter instead of `RVMOutputCode`.

Everything stock is untouched. The new emitter lives in one package:

```
rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/output/nativetree/
  NativeOutput.java        — orchestrator (file structure, per-spec fields)
  NativeMonitorClass.java  — <Spec>Monitor + <Spec>Monitor_Set
  NativeDispatch.java      — per-event static dispatch (4 shapes + zero-param broadcast)
```

~1500 lines total. No new files in `rv-monitor-rt/`.

### 2.2 Why: replace the hand-rolled map hierarchy with a JVM-managed tree

The patched JDK 21 adds `java.lang.rv.IndexingTree`
(see [`indexing-jdk.patch`](../indexing-jdk.patch)):

```java
public final class IndexingTree {
    public Object getOrCreate1(RuntimeMonitorFactory f, Object k1);
    public Object getOrCreate2(RuntimeMonitorFactory f, Object k1, Object k2);
    public Object get1(Object k1); public Object get2(Object k1, Object k2);
    public void   put(Object value, Object... keys);
    // keys[] scanned weakly via WeakObjArrayKlass; dead entries reaped during GC
}
```

The whole **stock map hierarchy + WeakReference wrappers + daemon thread**
collapses into one field per spec backed by a JVM-aware structure. The
liveness sweep is no longer Java code competing with the workload — it
happens inside STW GC (`clean_all_trees`).

| | Stock | Native |
|---|---|---|
| Tree backing | Nested `RVMMap*` hierarchy (~30 classes in `rt/map/`) | One `java.lang.rv.IndexingTree` per spec |
| Key storage | `CachedWeakReference` wrapper allocated per insert | Raw object slot; key array scanned weakly by GC |
| Param storage on monitor | `Ref_<p>` weak-ref fields | Plain strong refs (the tree pins nothing) |
| Cleanup | `RVMMapManager` daemon → `cleanUpUnnecessaryMappings` | JVM `clean_all_trees` during STW GC |
| Set compaction | `numAlive` shifting in every `event_<id>` | None — nothing flips `RVM_terminated` |
| Termination | `terminateInternal` runs DNF over `alive_parameters_*` | Empty body |

On `TestGC` (1M short-lived iterators, `-Xmx256m`): ~111 MB peak / 2.3 s
stock → ~12 MB peak / 1.2 s native.

### 2.3 What our codegen emits — same `HasNext`, native

```java
private static IndexingTree HasNext_i_Map = new IndexingTree();
private static Object HasNext_i_Map_cachekey_i;
private static Object HasNext_i_Map_cachevalue;
private static HasNextMonitorFactory HasNext_monitorFactory = new HasNextMonitorFactory();

public static final void hasnextEvent(Iterator i) {
    HasNext_activated = true;
    while (!HasNext_RVMLock.tryLock()) Thread.yield();

    HasNextMonitor matchedEntry = null;
    boolean cachehit = false;
    if (i == HasNext_i_Map_cachekey_i) {
        matchedEntry = (HasNextMonitor) HasNext_i_Map_cachevalue;
        cachehit = true;
    } else {
        matchedEntry = (HasNextMonitor)
            HasNext_i_Map.getOrCreate1(HasNext_monitorFactory, i);
    }
    matchedEntry.Prop_1_event_hasnext(i);
    if (matchedEntry.Prop_1_Category_match) matchedEntry.Prop_1_handler_match();

    if (!cachehit) { HasNext_i_Map_cachekey_i = i; HasNext_i_Map_cachevalue = matchedEntry; }
    HasNext_RVMLock.unlock();
}
```

Differences vs. stock:

- No `CachedWeakReference` allocation. `i` goes straight into the tree.
- No `matchedLastMap`, no `getNodeEquivalent` / `putNode` split. The tree
  itself does find-or-create via the factory.
- `HasNextMonitor_Set.event_<id>` no longer compacts terminated entries
  (none ever terminate).
- The monitor class has no `Ref_i` field and an empty
  `terminateInternal` body.

### 2.4 Internal organization of our emitter

`NativeOutput.generate()` walks the spec file once and emits, in order:
package + imports → per-spec classes → the top-level `<Name>RuntimeMonitor`
class.

`NativeDispatch.emitAllEvents` is the only non-trivial switch. Per event:

| Spec shape | Dispatch |
|---|---|
| 0-param singleton | Static field, direct call |
| 1-param suffix | `getOrCreate1` (creation) / `get1` (non-creation) on the tree |
| Multi-param suffix | `getOrCreate<N>` on creation + insert into each projection set; partial-key events `get<N>` with `null` padding and broadcast via `Monitor_Set` |
| General | `Tuple2<Set, IMonitor>` cells; defineTo cloning; `DisableHolder` placeholders; `tau`/`disable` timestamps |
| Zero-param event on parametric spec | Broadcast via the universal `<Spec>__Map.getValue1()` set |

The complete output structure for one spec:

```
final class <Spec>Monitor_Set extends AbstractMonitorSet<<Spec>Monitor> { ... }
interface I<Spec>Monitor                                    // general specs only
class <Spec>DisableHolder implements I<Spec>Monitor { ... } // general specs only
class <Spec>Monitor extends AbstractSynchronizedMonitor { ... }
final class <Spec>MonitorFactory extends java.lang.rv.RuntimeMonitorFactory { ... }
final class <Spec>MonitorSetFactory extends java.lang.rv.RuntimeMonitorFactory { ... }
```

The `<Spec>Monitor` body is built from the same logic-plugin strings stock
uses — we just embed them with `$state$` → `Prop_<id>_state` substitution
and the standard MOP macros (`__RESET`, `__DEFAULT_MESSAGE`, `__LOC`).

### 2.5 Two bugs DaCapo pmd surfaced

These were both in `NativeDispatch` and worth keeping in mind:

1. **1-param suffix non-creation events** were always calling `getOrCreate1`.
   Result: a `ListIterator.set(i)` on an unknown iterator fabricated a
   state-0 monitor that immediately tripped `@fail`. **Fix:** non-creation
   events use `get1` and null-guard the dispatch.
   ([NativeDispatch.java:147-208](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/output/nativetree/NativeDispatch.java#L147-L208))

2. **Multi-param suffix** was deriving `isCreation` from key arity. A
   `Map_UnsynchronizedAddAll.leave(t, s)` event has the full key but
   isn't a creation event; conflating the two ran the creation path and
   propagated false positives into the partial-key projection sets.
   **Fix:** independent `isCreation = event.isStartEvent()` and
   `isFullKey = (eventParams.size() == specParams.size())`.
   ([NativeDispatch.java:259-353](../rv-monitor/src/main/java/com/runtimeverification/rvmonitor/java/rvj/output/nativetree/NativeDispatch.java#L259-L353))

After both fixes: byte-exact 556-violation / 6-spec parity with stock on
DaCapo pmd `-s small`.

---

## 3. Where to look next

- **Coenable in GC** (the planned next step). The `terminateInternal` body
  is empty in v1. Land per-monitor `DEATH_MASK[]` + `REQUIRED_MASK_BY_EVENT[]`
  arrays + `RVMonitorBase.registerClass(...)` static init in
  `NativeMonitorClass.emitMonitorClass`; pass `paramMapping[]` to the
  `IndexingTree` ctor in `NativeOutput.emitSpecFields` so the GC can map
  dead keys to the right mask bits. Design sketch:
  [`MonitorSynthesisNativeIdxTree.md`](MonitorSynthesisNativeIdxTree.md).
- **Location info** in violation messages — `__LOC` is hardcoded to
  `"<unknown>"`. Joinpoint is already in scope when `-locationFromAjc` is
  on.
- **Status** — current test/parity results, dev loop commands:
  [`v1-status.md`](v1-status.md).
