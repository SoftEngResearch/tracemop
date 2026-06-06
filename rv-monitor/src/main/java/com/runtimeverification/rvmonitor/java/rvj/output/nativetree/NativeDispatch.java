package com.runtimeverification.rvmonitor.java.rvj.output.nativetree;

import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameter;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMonitorSpec;

/**
 * Emits per-event static dispatch methods for the {@code <Name>RuntimeMonitor} class.
 *
 * <p>One method per event, named {@code <Spec>_<event>Event(joinpoint, args...)}.
 * The body follows the canonical shape (matches stock-native architecture):
 * <pre>
 *   acquire spec lock (spin-yield, matches stock)
 *   cache check (identity compare on cached keys)
 *   tree lookup or getOrCreate via factory
 *   defineTo cloning (general specs only)
 *   dispatch event to monitor / monitor-set
 *   call category handlers
 *   update cache on miss
 *   release lock
 * </pre>
 *
 * <p>Status: partial. Currently handles:
 * <ul>
 *   <li>0-param singleton — direct singleton field dispatch</li>
 *   <li>1-param suffix — single getOrCreate1 / get1 dispatch</li>
 * </ul>
 *
 * <p>Not yet handled:
 * <ul>
 *   <li>Multi-param suffix — projection sets via null-padded keys (UnsafeIterator)</li>
 *   <li>General specs — Tuple2 entries, defineTo cloning, tau/disable
 *       (UnsafeMapIterator, UnsafeSyncCollection, UnsafeSyncMap)</li>
 * </ul>
 */
final class NativeDispatch {
    private final RVMonitorSpec spec;
    private final String specName;
    private final String monitorName;
    private final String setName;
    private final String lockName;
    private final String activatedName;
    private final String treeName;
    private final String factoryName;
    private final String setFactoryName;
    /** Aggregator class name, e.g. {@code MOPAgent_Statistics}. */
    private final String statsClassName;
    /** Per-spec statistics emitter (reused from stock). Returns empty strings
     *  when {@code -statistics} is off. Provided by {@link NativeOutput}. */
    private final com.runtimeverification.rvmonitor.java.rvj.output.RVMonitorStatistics stat;

    /**
     * Whether native dispatch emits its own per-spec lock (RVMLock).
     *
     * The woven JavaMOP aspect already wraps every event in a blocking
     * per-spec {@code <spec>_MOPLock.lock()} (combinedaspect/LockManager),
     * which serializes all access to this spec's IndexingTree — the only Java
     * code that touches the tree runs inside these dispatch methods. So an
     * RVMLock here is a redundant second coarse lock over the identical scope.
     * Stock's aspect-based backend emits no such lock and relies solely on the
     * aspect; native now does the same. GC-vs-mutator safety on the tree is
     * handled inside the VM (no-safepoint rehash, snapshot reads), not by any
     * Java lock, so dropping RVMLock loses no protection.
     *
     * Flip to {@code true} to restore self-locking dispatch (aspect-less
     * deployment, or lock-overhead ablation).
     */
    static final boolean EMIT_DISPATCH_LOCK = false;

    /** Lock acquire line for a dispatch body, or empty when {@link #EMIT_DISPATCH_LOCK} is off. */
    private String acquireLock(String pad) {
        return EMIT_DISPATCH_LOCK
            ? pad + "while (!" + lockName + ".tryLock()) Thread.yield();\n"
            : "";
    }

    /** Lock release line for a dispatch body, or empty when {@link #EMIT_DISPATCH_LOCK} is off. */
    private String releaseLock(String pad) {
        return EMIT_DISPATCH_LOCK
            ? "\n" + pad + lockName + ".unlock();\n"
            : "";
    }

    NativeDispatch(String agentName, RVMonitorSpec spec,
                   com.runtimeverification.rvmonitor.java.rvj.output.RVMonitorStatistics stat) {
        this.spec = spec;
        this.specName = spec.getName();
        this.monitorName = specName + "Monitor";
        this.setName = monitorName + "_Set";
        this.lockName = specName + "_RVMLock";
        this.activatedName = specName + "_activated";
        this.treeName = specName + "_" + paramKey(spec) + "_Map";
        this.factoryName = specName + "_monitorFactory";
        this.setFactoryName = specName + "_monitorSetFactory";
        this.statsClassName = agentName + "_Statistics";
        this.stat = stat;
    }

    /** Global event counter — bumped on every advice firing, emitted BEFORE the
     *  per-spec {@code activated} fast-path. Mirrors stock's Advice.java, where
     *  {@code numTotalEvents++} precedes the activator guard, so it counts the
     *  full workload regardless of which specs are active. Empty when
     *  {@code -statistics} off. */
    private String incEventGlobal(String pad, EventDefinition event) {
        if (!com.runtimeverification.rvmonitor.java.rvj.Main.options.statistics) return "";
        return pad + statsClassName + ".numTotalEvents++;\n";
    }

    /** Per-spec event counter — bumped only once the spec is active, so it is
     *  emitted AFTER the {@code activated} guard. Mirrors stock, which puts
     *  {@code stat.eventInc} inside {@code if (<spec>_activated)}. A dormant
     *  spec creates no monitors and does no dispatch, so counting its observed
     *  events would be both meaningless and wasted work on the fast path.
     *  Empty when {@code -statistics} off. */
    private String incEventPerSpec(String pad, EventDefinition event) {
        if (!com.runtimeverification.rvmonitor.java.rvj.Main.options.statistics) return "";
        return pad + stat.eventInc(event.getId()).trim() + "\n";
    }

/** Emit dispatch methods for all events of the spec. */
    String emitAllEvents() {
        StringBuilder sb = new StringBuilder();
        int specArity = spec.getParameters().size();
        for (EventDefinition event : spec.getEvents()) {
            RVMParameters eventParams = event.getRVMParametersOnSpec();
            // Zero-param event on a parametric spec — broadcast through
            // `<Spec>__Map.getValue1()` regardless of the spec's normal
            // dispatch shape. Mirrors stock's pattern.
            if (specArity > 0 && (eventParams == null || eventParams.size() == 0)) {
                sb.append(emitZeroParamBroadcast(event));
                continue;
            }
            if (specArity == 0) {
                sb.append(emitSingletonDispatch(event));
            } else if (specArity == 1 && !spec.isGeneral()) {
                sb.append(emitOneParamSuffixDispatch(event));
            } else if (spec.isGeneral()) {
                sb.append(emitGeneralDispatch(event));
            } else {
                // multi-param suffix
                sb.append(emitMultiParamSuffixDispatch(event));
            }
        }
        return sb.toString();
    }

    /** Broadcast dispatch for zero-param events on parametric specs.
     *  Sends the event through {@code <Spec>__Map.getValue1()}, which is
     *  populated by every monitor-creation path elsewhere in the spec. */
    private String emitZeroParamBroadcast(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        boolean isCreation = event.isStartEvent();
        sb.append("\tpublic static final void ").append(methodPrefix()).append(event.getId())
          .append("Event(").append(staticDispatchSignature(event)).append(") {\n");
        // Statistics: count every advice firing (matches stock semantics).
        sb.append(incEventGlobal("\t\t", event));
        if (isCreation) {
            sb.append("\t\t").append(activatedName).append(" = true;\n");
        } else {
            // Fast path: skip lock for dormant specs (see emitOneParamSuffixDispatch).
            sb.append("\t\tif (!").append(activatedName).append(") return;\n");
        }
        // Per-spec event counter: spec is guaranteed active here (creation
        // events set `activated` above; non-creation events returned early if
        // dormant), so this never runs on the dormant fast path.
        sb.append(incEventPerSpec("\t\t", event));
        sb.append(acquireLock("\t\t"));
        String pad = "\t\t";
        sb.append(pad).append("// D(X) main:8--9 — broadcast through universal set\n");
        sb.append(pad).append(setName).append(" stateTransitionedSet = ").append(specName)
          .append("__Map.getValue1();\n");
        sb.append(pad).append("stateTransitionedSet.event_").append(event.getId()).append("(")
          .append(eventCallArgs(event)).append(");\n");
        sb.append(releaseLock("\t\t"));
        sb.append("\t}\n\n");
        return sb.toString();
    }

    /** Emit `<Spec>__Map.getValue1().add(<ref>);` if the spec has any
     *  zero-param event (so the universal set was emitted); otherwise empty. */
    private String emitUniversalAdd(String pad, String monRef) {
        if (!NativeOutput.specHasEmptyParamEvent(spec)) return "";
        return pad + specName + "__Map.getValue1().add(" + monRef + ");\n";
    }

    /** 0-param singleton: no tree, just dispatch on the static singleton. */
    private String emitSingletonDispatch(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        sb.append("\tpublic static final void ").append(methodPrefix()).append(event.getId())
          .append("Event(").append(staticDispatchSignature(event)).append(") {\n");
        // Statistics: count every advice firing (matches stock semantics).
        sb.append(incEventGlobal("\t\t", event));
        sb.append("\t\t").append(activatedName).append(" = true;\n");
        // Per-spec event counter: spec is guaranteed active here (creation
        // events set `activated` above; non-creation events returned early if
        // dormant), so this never runs on the dormant fast path.
        sb.append(incEventPerSpec("\t\t", event));
        sb.append(acquireLock("\t\t"));
        sb.append("\t\t").append(monitorName).append(" matchedEntry = ")
          .append(specName).append("__Map;\n");
        sb.append("\t\t// D(X) main:8--9\n");
        sb.append(emitDispatchCall(event, "matchedEntry"));
        sb.append(releaseLock("\t\t"));
        sb.append("\t}\n\n");
        return sb.toString();
    }

    /** 1-param suffix: creation events use getOrCreate1 (and set
     *  `<spec>_activated`); non-creation events use get1 and skip dispatch
     *  if the monitor doesn't exist yet. Conflating the two — always
     *  getOrCreate — over-fires violations because non-creation events
     *  would create a fresh monitor in state 0, then transition through
     *  the dead/fail state on the very first event (see e.g.
     *  ListIterator_Set, where calling set() before the create event
     *  used to fabricate a monitor and immediately trip @fail). */
    private String emitOneParamSuffixDispatch(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        RVMParameter p = spec.getParameters().get(0);
        boolean isCreation = event.isStartEvent();

        sb.append("\tpublic static final void ").append(methodPrefix()).append(event.getId())
          .append("Event(").append(staticDispatchSignature(event)).append(") {\n");
        // Statistics: count every advice firing (matches stock semantics).
        sb.append(incEventGlobal("\t\t", event));
        if (isCreation) {
            sb.append("\t\t").append(activatedName).append(" = true;\n");
        } else {
            // Fast path: skip the lock entirely if the spec has never been
            // activated. `activated` is monotonic (false → true exactly once,
            // on the first creation event), so a relaxed read is correct.
            // Without this, every non-creation event pays a full tryLock /
            // unlock cycle on every JDK iterator call even when the spec is
            // dormant — what DaCapo surfaced as the lock-everything regression.
            sb.append("\t\tif (!").append(activatedName).append(") return;\n");
        }
        // Per-spec event counter: spec is guaranteed active here (creation
        // events set `activated` above; non-creation events returned early if
        // dormant), so this never runs on the dormant fast path.
        sb.append(incEventPerSpec("\t\t", event));
        sb.append(acquireLock("\t\t"));
        String pad = "\t\t";

        sb.append(pad).append(monitorName).append(" matchedEntry = null;\n");
        sb.append(pad).append("boolean cachehit = false;\n");
        sb.append(pad).append("if (").append(p.getName()).append(" == ").append(treeName)
          .append("_cachekey_").append(p.getName()).append(") {\n");
        sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
          .append(treeName).append("_cachevalue;\n");
        sb.append(pad).append("\tcachehit = true;\n");
        sb.append(pad).append("} else {\n");
        if (isCreation) {
            sb.append(pad).append("\t// FindOrCreateEntry\n");
            sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
              .append(treeName).append(".getOrCreate1(").append(factoryName)
              .append(", ").append(p.getName()).append(");\n");
        } else {
            sb.append(pad).append("\t// FindEntry (don't create — non-creation event)\n");
            sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
              .append(treeName).append(".get1(").append(p.getName()).append(");\n");
        }
        sb.append(pad).append("}\n");

        sb.append(pad).append("// D(X) main:8--9\n");
        if (!isCreation) {
            sb.append(pad).append("if (matchedEntry != null) {\n");
            sb.append(emitDispatchCall(event, "matchedEntry").replaceAll("(?m)^\t\t", pad + "\t"));
            sb.append("\n").append(pad).append("\tif (!cachehit) {\n");
            sb.append(pad).append("\t\t").append(treeName).append("_cachekey_").append(p.getName())
              .append(" = ").append(p.getName()).append(";\n");
            sb.append(pad).append("\t\t").append(treeName).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("}\n");
        } else {
            sb.append(emitDispatchCall(event, "matchedEntry"));
            sb.append("\n").append(pad).append("if (!cachehit) {\n");
            sb.append(pad).append("\t").append(treeName).append("_cachekey_").append(p.getName())
              .append(" = ").append(p.getName()).append(";\n");
            sb.append(pad).append("\t").append(treeName).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("}\n");
        }

        sb.append(releaseLock("\t\t"));
        sb.append("\t}\n\n");
        return sb.toString();
    }

    /** Emit the actual monitor.event_* + handler dispatch call. */
    private String emitDispatchCall(EventDefinition event, String monRef) {
        StringBuilder sb = new StringBuilder();
        boolean isRaw = spec.getPropertiesAndHandlers().isEmpty();
        String eventMethod;
        if (isRaw) {
            eventMethod = "event_" + event.getId();
        } else {
            int propId = spec.getPropertiesAndHandlers().get(0).getPropertyId();
            eventMethod = "Prop_" + propId + "_event_" + event.getId();
        }

        sb.append("\t\tfinal ").append(monitorName).append(" finalMon = ").append(monRef).append(";\n");
        sb.append("\t\t").append(monRef).append(".").append(eventMethod).append("(")
          .append(eventCallArgs(event)).append(");\n");

        // Category handler dispatch — only for property specs
        if (!isRaw) {
            int propId = spec.getPropertiesAndHandlers().get(0).getPropertyId();
            for (String cat : spec.getPropertiesAndHandlers().get(0).getHandlers().keySet()) {
                if (cat.equals("deadlock")) continue;
                String categoryFlag = "Prop_" + propId + "_Category_" + cat;
                sb.append("\t\tif (finalMon.").append(categoryFlag).append(") {\n");
                sb.append("\t\t\tfinalMon.Prop_").append(propId).append("_handler_").append(cat).append("(");
                if (com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc) {
                    sb.append("joinpoint");
                }
                sb.append(");\n");
                sb.append("\t\t}\n");
            }
        }
        return sb.toString();
    }

    /**
     * Multi-param suffix dispatch. Two flavors:
     * <ul>
     *   <li><b>Creation events</b> (event params == spec params): cache check on
     *       full key; on miss call {@code getOrCreate<arity>(monitorFactory, ...)};
     *       if the factory created a new monitor, insert it into the projection
     *       sets used by non-creation events; dispatch event to the monitor;
     *       update full cache on miss.</li>
     *   <li><b>Partial-binding events</b> (event params ⊊ spec params): guard
     *       on {@code <spec>_activated}; cache check on this event's projection;
     *       on miss call {@code get<arity>(...)} with nulls padding the absent
     *       slots; dispatch to the matched set's {@code event_<id>} method;
     *       update projection cache on miss.</li>
     * </ul>
     */
    private String emitMultiParamSuffixDispatch(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        RVMParameters specParams = spec.getParameters();
        RVMParameters eventParams = event.getRVMParametersOnSpec();
        // CRITICAL: isCreation and isFullKey are independent. An event can
        // have full spec params (full key) without being a creation event
        // (e.g. Map_UnsynchronizedAddAll.leave(t, s) — full-key but
        // non-creation). Conflating them causes non-creation full-key
        // events to fabricate fresh monitors via getOrCreate, which then
        // trip false-positive @fail / @match transitions from state 0.
        boolean isCreation = event.isStartEvent();
        boolean isFullKey = eventParams != null && eventParams.size() == specParams.size();
        int arity = specParams.size();
        String fullKey = paramKey(spec);
        String fullCachePrefix = specName + "_" + fullKey + "_Map";

        sb.append("\tpublic static final void ").append(methodPrefix()).append(event.getId())
          .append("Event(").append(staticDispatchSignature(event)).append(") {\n");
        // Statistics: count every advice firing (matches stock semantics).
        sb.append(incEventGlobal("\t\t", event));

        if (isCreation) {
            sb.append("\t\t").append(activatedName).append(" = true;\n");
        } else {
            // Fast path: skip lock for dormant specs (see emitOneParamSuffixDispatch).
            sb.append("\t\tif (!").append(activatedName).append(") return;\n");
        }
        // Per-spec event counter: spec is guaranteed active here (creation
        // events set `activated` above; non-creation events returned early if
        // dormant), so this never runs on the dormant fast path.
        sb.append(incEventPerSpec("\t\t", event));
        sb.append(acquireLock("\t\t"));
        String pad = "\t\t";

        if (isCreation && isFullKey) {
            // Creation + full key: getOrCreate at full key, insert into
            // projection sets only on the first creation.
            sb.append(pad).append(monitorName).append(" matchedEntry = null;\n");
            sb.append(pad).append("boolean cachehit = false;\n");
            sb.append(pad).append("if (").append(cacheKeyCompare(fullCachePrefix, specParams)).append(") {\n");
            sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
              .append(fullCachePrefix).append("_cachevalue;\n");
            sb.append(pad).append("\tcachehit = true;\n");
            sb.append(pad).append("} else {\n");
            sb.append(pad).append("\t// FindOrCreateEntry\n");
            sb.append(pad).append("\t").append(factoryName).append(".created = false;\n");
            sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
              .append(treeName).append(".getOrCreate").append(arity).append("(")
              .append(factoryName).append(", ").append(paramList(specParams, specParams)).append(");\n");
            sb.append(pad).append("\tif (").append(factoryName).append(".created) {\n");
            // For each non-full projection used by some non-creation event, insert.
            for (RVMParameters proj : nonCreationProjections(spec)) {
                sb.append(pad).append("\t\t{\n");
                sb.append(pad).append("\t\t\t// InsertMonitor for <").append(proj.parameterStringUnderscore()).append(">\n");
                sb.append(pad).append("\t\t\t").append(setName).append(" targetSet = (").append(setName)
                  .append(") ").append(treeName).append(".getOrCreate").append(arity).append("(")
                  .append(setFactoryName).append(", ").append(paramList(specParams, proj)).append(");\n");
                sb.append(pad).append("\t\t\ttargetSet.add(matchedEntry);\n");
                sb.append(pad).append("\t\t}\n");
            }
            sb.append(emitUniversalAdd(pad + "\t\t", "matchedEntry"));
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:8--9\n");
            sb.append(emitDispatchCall(event, "matchedEntry"));
            sb.append("\n").append(pad).append("if (!cachehit) {\n");
            for (RVMParameter p : specParams) {
                sb.append(pad).append("\t").append(fullCachePrefix).append("_cachekey_")
                  .append(p.getName()).append(" = ").append(p.getName()).append(";\n");
            }
            sb.append(pad).append("\t").append(fullCachePrefix).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("}\n");
        } else if (!isCreation && isFullKey) {
            // Non-creation, full key: get the monitor (don't create);
            // skip dispatch if absent.
            sb.append(pad).append(monitorName).append(" matchedEntry = null;\n");
            sb.append(pad).append("boolean cachehit = false;\n");
            sb.append(pad).append("if (").append(cacheKeyCompare(fullCachePrefix, specParams)).append(") {\n");
            sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
              .append(fullCachePrefix).append("_cachevalue;\n");
            sb.append(pad).append("\tcachehit = true;\n");
            sb.append(pad).append("} else {\n");
            sb.append(pad).append("\t// FindEntry (don't create)\n");
            sb.append(pad).append("\tmatchedEntry = (").append(monitorName).append(") ")
              .append(treeName).append(".get").append(arity).append("(")
              .append(paramList(specParams, specParams)).append(");\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:8--9\n");
            sb.append(pad).append("if (matchedEntry != null) {\n");
            sb.append(emitDispatchCall(event, "matchedEntry").replaceAll("(?m)^\t\t", pad + "\t"));
            sb.append("\n").append(pad).append("\tif (!cachehit) {\n");
            for (RVMParameter p : specParams) {
                sb.append(pad).append("\t\t").append(fullCachePrefix).append("_cachekey_")
                  .append(p.getName()).append(" = ").append(p.getName()).append(";\n");
            }
            sb.append(pad).append("\t\t").append(fullCachePrefix).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("}\n");
        } else {
            // Partial-binding path — dispatch through Set
            String projKey = eventParams.parameterStringUnderscore();
            String cachePrefix = specName + "_" + projKey + "_Map";
            sb.append(pad).append(setName).append(" matchedSet = null;\n");
            sb.append(pad).append("boolean cachehit = false;\n");
            sb.append(pad).append("if (").append(cacheKeyCompare(cachePrefix, eventParams)).append(") {\n");
            sb.append(pad).append("\tmatchedSet = (").append(setName).append(") ")
              .append(cachePrefix).append("_cachevalue;\n");
            sb.append(pad).append("\tcachehit = true;\n");
            sb.append(pad).append("} else {\n");
            sb.append(pad).append("\t// FindEntry\n");
            sb.append(pad).append("\tmatchedSet = (").append(setName).append(") ")
              .append(treeName).append(".get").append(arity).append("(")
              .append(paramList(specParams, eventParams)).append(");\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:8--9\n");
            sb.append(pad).append("if (matchedSet != null) {\n");
            sb.append(pad).append("\tmatchedSet.event_").append(event.getId()).append("(")
              .append(eventCallArgs(event)).append(");\n");
            sb.append(pad).append("\tif (!cachehit) {\n");
            for (RVMParameter p : eventParams) {
                sb.append(pad).append("\t\t").append(cachePrefix).append("_cachekey_")
                  .append(p.getName()).append(" = ").append(p.getName()).append(";\n");
            }
            sb.append(pad).append("\t\t").append(cachePrefix).append("_cachevalue = matchedSet;\n");
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("}\n");
        }

        sb.append(releaseLock("\t\t"));
        sb.append("\t}\n\n");
        return sb.toString();
    }

    /** Build the cache-key identity check, e.g. "(c == ..._cachekey_c) && (i == ..._cachekey_i)". */
    private String cacheKeyCompare(String prefix, RVMParameters params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (RVMParameter p : params) {
            if (!first) sb.append(" && ");
            sb.append(p.getName()).append(" == ").append(prefix).append("_cachekey_").append(p.getName());
            first = false;
        }
        return sb.toString();
    }

    /** Build the argument list for getOrCreate<N>/get<N>: spec-param-order, with
     *  "null" in slots not in {@code present}. */
    private String paramList(RVMParameters all, RVMParameters present) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (RVMParameter p : all) {
            if (!first) sb.append(", ");
            sb.append(present.contains(p) ? p.getName() : "null");
            first = false;
        }
        return sb.toString();
    }

    /** Unique projections (one per param subset) used by non-creation events. */
    private static java.util.List<RVMParameters> nonCreationProjections(RVMonitorSpec spec) {
        java.util.LinkedHashMap<String, RVMParameters> uniq = new java.util.LinkedHashMap<>();
        int specSize = spec.getParameters().size();
        for (EventDefinition e : spec.getEvents()) {
            RVMParameters onSpec = e.getRVMParametersOnSpec();
            if (onSpec == null || onSpec.size() == 0 || onSpec.size() == specSize) continue;
            uniq.put(onSpec.parameterStringUnderscore(), onSpec);
        }
        return new java.util.ArrayList<>(uniq.values());
    }

    /**
     * General-spec dispatch — the connected/full-binding pattern with the
     * {@code defineTo} cloning algorithm.
     *
     * <p>Tree entries are heterogeneous:
     * <ul>
     *   <li>Full-key slots hold a bare {@code I<Spec>Monitor} (a real
     *       {@code <Spec>Monitor} or a {@code <Spec>DisableHolder} placeholder).</li>
     *   <li>Projection slots hold a {@code Tuple2<<Spec>Monitor_Set, I<Spec>Monitor>}:
     *       {@code value1} is the set of all monitors whose projection passes
     *       through this slot; {@code value2} is the leaf monitor or holder
     *       for the projection itself.</li>
     * </ul>
     *
     * <p>Three dispatch shapes:
     * <ul>
     *   <li><b>Creation, partial-key</b>: get/create projection entry,
     *       inline-create the leaf via {@code new <Spec>Monitor(timestamp++)},
     *       broadcast the event through {@code matchedEntry.getValue1()}.</li>
     *   <li><b>Non-creation, full-key</b>: cache + {@code get<n>}; on miss
     *       run defineTo against any creation-event projection whose params
     *       are a proper subset of this event's; on definability failure
     *       drop a {@code DisableHolder}; dispatch only if a real Monitor
     *       was bound at this slot.</li>
     *   <li><b>Non-creation, partial-key</b>: get/create projection entry,
     *       drop a {@code DisableHolder} at the leaf if absent, broadcast.</li>
     * </ul>
     *
     * <p>Known limitations of this v1 emission:
     * <ul>
     *   <li>Multiple creation-event sources are handled by trying each in
     *       declaration order. Cross-source interactions (e.g. one source
     *       defines a leaf at a smaller subset than another) match the
     *       reference for single-source specs but have not been audited
     *       against multi-source specs.</li>
     *   <li>The full-binding creation event case (creation event whose
     *       params equal spec params) does not appear in any reference spec
     *       and is not handled here — would fall through to the partial-key
     *       creation path with degenerate behavior.</li>
     * </ul>
     */
    private String emitGeneralDispatch(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        RVMParameters specParams = spec.getParameters();
        RVMParameters eventParams = event.getRVMParametersOnSpec();
        if (eventParams == null) eventParams = new RVMParameters();
        boolean isCreation = event.isStartEvent();
        boolean isFullKey = eventParams.size() == specParams.size();
        int arity = specParams.size();
        String tsName = specName + "_timestamp";
        String holderName = specName + "DisableHolder";
        String itfName = "I" + monitorName;
        String tuple2Set = "Tuple2<" + setName + ", " + itfName + ">";

        sb.append("\tpublic static final void ").append(methodPrefix()).append(event.getId())
          .append("Event(").append(staticDispatchSignature(event)).append(") {\n");
        // Statistics: count every advice firing (matches stock semantics).
        sb.append(incEventGlobal("\t\t", event));

        // Note: zero-param events on parametric specs are routed to
        // `emitZeroParamBroadcast` in `emitAllEvents` before reaching here.

        if (isCreation) {
            sb.append("\t\t").append(activatedName).append(" = true;\n");
        } else {
            // Fast path: skip lock for dormant specs (see emitOneParamSuffixDispatch).
            sb.append("\t\tif (!").append(activatedName).append(") return;\n");
        }
        // Per-spec event counter: spec is guaranteed active here (creation
        // events set `activated` above; non-creation events returned early if
        // dormant), so this never runs on the dormant fast path.
        sb.append(incEventPerSpec("\t\t", event));
        sb.append(acquireLock("\t\t"));
        String pad = "\t\t";
        String projKey = eventParams.parameterStringUnderscore();
        String cachePrefix = specName + "_" + projKey + "_Map";

        if (isCreation) {
            // ---- Creation event with proper-subset params ----
            // Cache value is Tuple2<Set, <Spec>Monitor> (the leaf-here type
            // is the concrete monitor since creation guarantees a Monitor,
            // never a DisableHolder).
            String tuple2Monitor = "Tuple2<" + setName + ", " + monitorName + ">";
            sb.append(pad).append(tuple2Monitor).append(" matchedEntry = null;\n");
            sb.append(pad).append("boolean cachehit = false;\n");
            sb.append(pad).append("if (").append(cacheKeyCompare(cachePrefix, eventParams)).append(") {\n");
            sb.append(pad).append("\tmatchedEntry = (").append(tuple2Monitor).append(") ")
              .append(cachePrefix).append("_cachevalue;\n");
            sb.append(pad).append("\tcachehit = true;\n");
            sb.append(pad).append("} else {\n");
            sb.append(pad).append("\t// FindOrCreateEntry\n");
            sb.append(pad).append("\tmatchedEntry = (").append(tuple2Monitor).append(") ")
              .append(treeName).append(".getOrCreate").append(arity).append("(")
              .append(setFactoryName).append(", ").append(paramList(specParams, eventParams)).append(");\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:1 — check leaf\n");
            sb.append(pad).append(monitorName).append(" matchedLeaf = matchedEntry.getValue2();\n");
            sb.append(pad).append("if (matchedLeaf == null) {\n");
            sb.append(pad).append("\t// D(X) main:4 — define new monitor at this projection\n");
            // Constructor signature includes all spec params for specs needing
            // multi-source defineTo. For params not bound by this creation event,
            // pass null; the dispatch for the eventual defineTo recovers them
            // from sourceMonitor.RVM_<p>.
            boolean msdt = NativeOutput.specNeedsMultiSourceDefineTo(spec);
            sb.append(pad).append("\t").append(monitorName).append(" created = new ")
              .append(monitorName).append("(").append(tsName).append("++");
            if (msdt) {
                for (RVMParameter sp : specParams) {
                    sb.append(", ");
                    sb.append(eventParams.contains(sp) ? sp.getName() : "null");
                }
            }
            sb.append(");\n");
            sb.append(pad).append("\tmatchedEntry.setValue2(created);\n");
            sb.append(pad).append("\t").append(setName).append(" enclosingSet = matchedEntry.getValue1();\n");
            sb.append(pad).append("\tenclosingSet.add(created);\n");
            // `defineNew:5 for <X>` — for each proper subset X of the creation
            // key P_C that's used by some non-creation event, insert the new
            // monitor into the main tree's X-projection set. Skip subsets
            // that are themselves a translation-tree key K (those get the
            // translation-tree insert below instead — modifyMap-style events
            // dispatch through the main tree's X-projection, not through
            // translation trees).
            java.util.Set<String> translationKKeys = new java.util.HashSet<>();
            for (NativeOutput.MultiSourceTriple t : NativeOutput.multiSourceTriples(spec)) {
                if (!t.creationP.equals(eventParams)) continue;
                translationKKeys.add(t.commonK.parameterStringUnderscore());
            }
            for (RVMParameters proj : nonCreationProjections(spec)) {
                if (!eventParams.contains(proj)) continue;  // only subsets of P_C
                if (proj.size() == eventParams.size()) continue;  // skip full P_C (= enclosingSet)
                if (translationKKeys.contains(proj.parameterStringUnderscore())) continue;
                sb.append(pad).append("\t{\n");
                sb.append(pad).append("\t\t// defineNew:5 for <").append(proj.parameterStringUnderscore()).append(">\n");
                String tuple2I = "Tuple2<" + setName + ", I" + monitorName + ">";
                sb.append(pad).append("\t\t").append(tuple2I).append(" node = (").append(tuple2I)
                  .append(") ").append(treeName).append(".getOrCreate").append(arity).append("(")
                  .append(setFactoryName).append(", ").append(paramList(specParams, proj)).append(");\n");
                sb.append(pad).append("\t\tnode.getValue1().add(created);\n");
                sb.append(pad).append("\t}\n");
            }
            // Insert into translation trees for any (commonK, P_C) where this
            // creation event matches P_C — these feed downstream multi-source
            // defineTo lookups from non-creation events.
            java.util.LinkedHashSet<String> insertedTrees = new java.util.LinkedHashSet<>();
            for (NativeOutput.MultiSourceTriple t : NativeOutput.multiSourceTriples(spec)) {
                if (!t.creationP.equals(eventParams)) continue;
                String tname = NativeOutput.translationTreeName(specName, t.commonK, t.creationP);
                if (!insertedTrees.add(tname)) continue;
                String tuple2M = "Tuple2<" + setName + ", " + monitorName + ">";
                sb.append(pad).append("\t{\n");
                sb.append(pad).append("\t\t// InsertMonitor into translation tree <")
                  .append(t.commonK.parameterStringUnderscore()).append("__To__")
                  .append(t.creationP.parameterStringUnderscore()).append(">\n");
                sb.append(pad).append("\t\t").append(tuple2M).append(" node_k = (").append(tuple2M)
                  .append(") ").append(tname).append(".getOrCreate").append(t.commonK.size()).append("(")
                  .append(setFactoryName).append(", ");
                boolean first = true;
                for (RVMParameter p : t.commonK) {
                    if (!first) sb.append(", ");
                    sb.append(p.getName());
                    first = false;
                }
                sb.append(");\n");
                sb.append(pad).append("\t\tnode_k.getValue1().add(created);\n");
                sb.append(pad).append("\t}\n");
            }
            sb.append(emitUniversalAdd(pad + "\t", "created"));
            sb.append(pad).append("\t// D(X) main:6 — bump disable on freshly created leaf\n");
            sb.append(pad).append("\tmatchedEntry.getValue2().setDisable(").append(tsName).append("++);\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:8--9 — broadcast through enclosing set\n");
            sb.append(pad).append(setName).append(" stateTransitionedSet = matchedEntry.getValue1();\n");
            sb.append(pad).append("stateTransitionedSet.event_").append(event.getId()).append("(")
              .append(eventCallArgs(event)).append(");\n");
            sb.append("\n").append(pad).append("if (!cachehit) {\n");
            for (RVMParameter p : eventParams) {
                sb.append(pad).append("\t").append(cachePrefix).append("_cachekey_")
                  .append(p.getName()).append(" = ").append(p.getName()).append(";\n");
            }
            sb.append(pad).append("\t").append(cachePrefix).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("}\n");
        } else if (isFullKey) {
            // ---- Non-creation event, full-key ----
            sb.append(pad).append(itfName).append(" matchedEntry = null;\n");
            sb.append(pad).append("boolean cachehit = false;\n");
            sb.append(pad).append("if (").append(cacheKeyCompare(cachePrefix, eventParams)).append(") {\n");
            sb.append(pad).append("\tmatchedEntry = (").append(itfName).append(") ")
              .append(cachePrefix).append("_cachevalue;\n");
            sb.append(pad).append("\tcachehit = true;\n");
            sb.append(pad).append("} else {\n");
            sb.append(pad).append("\t// FindEntry\n");
            sb.append(pad).append("\tmatchedEntry = (").append(itfName).append(") ")
              .append(treeName).append(".get").append(arity).append("(")
              .append(paramList(specParams, eventParams)).append(");\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:1\n");
            sb.append(pad).append("if (matchedEntry == null) {\n");
            // defineTo for each creation-event source whose params ⊊ eventParams
            for (RVMParameters source : creationSources(spec, eventParams)) {
                emitDefineToBlock(sb, pad + "\t", source, eventParams, tsName, holderName);
            }
            sb.append(pad).append("\t// D(X) main:6 — DisableHolder fallback\n");
            sb.append(pad).append("\tif (matchedEntry == null) {\n");
            sb.append(pad).append("\t\t").append(holderName).append(" holder = new ")
              .append(holderName).append("(-1);\n");
            sb.append(pad).append("\t\t").append(treeName).append(".put(holder, ")
              .append(paramList(specParams, eventParams)).append(");\n");
            sb.append(pad).append("\t\tmatchedEntry = holder;\n");
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("\tmatchedEntry.setDisable(").append(tsName).append("++);\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:8--9\n");
            sb.append(pad).append("if (matchedEntry instanceof ").append(monitorName).append(") {\n");
            sb.append(pad).append("\t").append(monitorName).append(" monitor = (")
              .append(monitorName).append(") matchedEntry;\n");
            sb.append(pad).append("\tfinal ").append(monitorName).append(" finalMon = monitor;\n");
            sb.append(pad).append("\tmonitor.").append(propEventMethod(event.getId())).append("(")
              .append(eventCallArgs(event)).append(");\n");
            sb.append(handlerDispatchLines(pad + "\t", "finalMon"));
            sb.append("\n").append(pad).append("\tif (!cachehit) {\n");
            for (RVMParameter p : eventParams) {
                sb.append(pad).append("\t\t").append(cachePrefix).append("_cachekey_")
                  .append(p.getName()).append(" = ").append(p.getName()).append(";\n");
            }
            sb.append(pad).append("\t\t").append(cachePrefix).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("}\n");
        } else {
            // ---- Non-creation event, partial-key ----
            sb.append(pad).append(tuple2Set).append(" matchedEntry = null;\n");
            sb.append(pad).append("boolean cachehit = false;\n");
            sb.append(pad).append("if (").append(cacheKeyCompare(cachePrefix, eventParams)).append(") {\n");
            sb.append(pad).append("\tmatchedEntry = (").append(tuple2Set).append(") ")
              .append(cachePrefix).append("_cachevalue;\n");
            sb.append(pad).append("\tcachehit = true;\n");
            sb.append(pad).append("} else {\n");
            sb.append(pad).append("\t// FindOrCreateEntry\n");
            sb.append(pad).append("\tmatchedEntry = (").append(tuple2Set).append(") ")
              .append(treeName).append(".getOrCreate").append(arity).append("(")
              .append(setFactoryName).append(", ").append(paramList(specParams, eventParams)).append(");\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:1\n");
            sb.append(pad).append(itfName).append(" matchedLeaf = matchedEntry.getValue2();\n");
            sb.append(pad).append("if (matchedLeaf == null) {\n");
            // Multi-source defineTo: if this event introduces a new parameter
            // that no creation event's P_C contains, clone from source monitors
            // found via translation tree. See NativeOutput.MultiSourceTriple.
            for (NativeOutput.MultiSourceTriple t : NativeOutput.multiSourceTriples(spec)) {
                if (!t.event.getId().equals(event.getId())) continue;
                emitMultiSourceDefineToBlock(sb, pad + "\t", t, eventParams, tsName);
            }
            sb.append(pad).append("\t// D(X) main:6 — drop DisableHolder at the leaf slot\n");
            sb.append(pad).append("\tif (matchedEntry.getValue2() == null) {\n");
            sb.append(pad).append("\t\t").append(holderName).append(" holder = new ")
              .append(holderName).append("(-1);\n");
            sb.append(pad).append("\t\tmatchedEntry.setValue2(holder);\n");
            sb.append(pad).append("\t\tholder.setDisable(").append(tsName).append("++);\n");
            sb.append(pad).append("\t}\n");
            sb.append(pad).append("}\n");
            sb.append(pad).append("// D(X) main:8--9 — broadcast through enclosing set\n");
            sb.append(pad).append(setName).append(" stateTransitionedSet = matchedEntry.getValue1();\n");
            sb.append(pad).append("stateTransitionedSet.event_").append(event.getId()).append("(")
              .append(eventCallArgs(event)).append(");\n");
            sb.append("\n").append(pad).append("if (!cachehit) {\n");
            for (RVMParameter p : eventParams) {
                sb.append(pad).append("\t").append(cachePrefix).append("_cachekey_")
                  .append(p.getName()).append(" = ").append(p.getName()).append(";\n");
            }
            sb.append(pad).append("\t").append(cachePrefix).append("_cachevalue = matchedEntry;\n");
            sb.append(pad).append("}\n");
        }

        sb.append(releaseLock("\t\t"));
        sb.append("\t}\n\n");
        return sb.toString();
    }

    /** Emit a defineTo block for one source projection. Tries to clone the
     *  source's leaf into the event's full-key slot, after definability
     *  checks against every non-source subset of eventParams. */
    private void emitDefineToBlock(StringBuilder sb, String pad, RVMParameters source,
                                   RVMParameters eventParams, String tsName, String holderName) {
        RVMParameters specParams = spec.getParameters();
        String tuple2Monitor = "Tuple2<" + setName + ", " + monitorName + ">";
        String tuple2Set = "Tuple2<" + setName + ", I" + monitorName + ">";
        String itfName = "I" + monitorName;

        sb.append(pad).append("// D(X) createNewMonitorStates:4 when Dom(theta'') = <")
          .append(source.parameterStringUnderscore()).append(">\n");
        sb.append(pad).append(monitorName).append(" sourceLeaf = null;\n");
        sb.append(pad).append("{\n");
        sb.append(pad).append("\t// FindCode — load source projection\n");
        sb.append(pad).append("\t").append(tuple2Monitor).append(" node_src = (").append(tuple2Monitor)
          .append(") ").append(treeName).append(".get").append(specParams.size()).append("(")
          .append(paramList(specParams, source)).append(");\n");
        sb.append(pad).append("\tif (node_src != null) sourceLeaf = node_src.getValue2();\n");
        sb.append(pad).append("}\n");
        sb.append(pad).append("if (sourceLeaf != null) {\n");
        sb.append(pad).append("\tboolean definable = true;\n");
        // Definability checks: for every non-empty subset Y ⊆ eventParams, Y ≠ source
        for (RVMParameters check : nonEmptySubsetsExcept(eventParams, source)) {
            boolean isFull = check.size() == specParams.size();
            sb.append(pad).append("\t// D(X) defineTo:1--5 for <").append(check.parameterStringUnderscore()).append(">\n");
            sb.append(pad).append("\tif (definable) {\n");
            if (isFull) {
                sb.append(pad).append("\t\t").append(itfName).append(" node_check = (").append(itfName)
                  .append(") ").append(treeName).append(".get").append(specParams.size()).append("(")
                  .append(paramList(specParams, check)).append(");\n");
                sb.append(pad).append("\t\tif (node_check != null && ((node_check.getDisable() > sourceLeaf.getTau()) || (node_check.getTau() > 0 && node_check.getTau() < sourceLeaf.getTau()))) {\n");
                sb.append(pad).append("\t\t\tdefinable = false;\n");
                sb.append(pad).append("\t\t}\n");
            } else {
                sb.append(pad).append("\t\t").append(tuple2Set).append(" node_check = (").append(tuple2Set)
                  .append(") ").append(treeName).append(".get").append(specParams.size()).append("(")
                  .append(paramList(specParams, check)).append(");\n");
                sb.append(pad).append("\t\tif (node_check != null) {\n");
                sb.append(pad).append("\t\t\t").append(itfName).append(" itmdLeaf = node_check.getValue2();\n");
                sb.append(pad).append("\t\t\tif (itmdLeaf != null && ((itmdLeaf.getDisable() > sourceLeaf.getTau()) || (itmdLeaf.getTau() > 0 && itmdLeaf.getTau() < sourceLeaf.getTau()))) {\n");
                sb.append(pad).append("\t\t\t\tdefinable = false;\n");
                sb.append(pad).append("\t\t\t}\n");
                sb.append(pad).append("\t\t}\n");
            }
            sb.append(pad).append("\t}\n");
        }
        sb.append(pad).append("\tif (definable) {\n");
        sb.append(pad).append("\t\t// D(X) defineTo:6 — clone source into event's slot\n");
        sb.append(pad).append("\t\t").append(monitorName).append(" created = (").append(monitorName)
          .append(") sourceLeaf.clone();\n");
        sb.append(pad).append("\t\tmatchedEntry = created;\n");
        sb.append(pad).append("\t\t").append(treeName).append(".put(created, ")
          .append(paramList(specParams, eventParams)).append(");\n");
        sb.append(emitUniversalAdd(pad + "\t\t", "created"));
        // Insert into projection sets for every proper-subset projection of eventParams
        for (RVMParameters proj : properSubsetProjections(eventParams)) {
            sb.append(pad).append("\t\t// D(X) defineTo:7 for <").append(proj.parameterStringUnderscore()).append(">\n");
            sb.append(pad).append("\t\t{\n");
            sb.append(pad).append("\t\t\t").append(tuple2Set).append(" node = (").append(tuple2Set)
              .append(") ").append(treeName).append(".getOrCreate").append(specParams.size()).append("(")
              .append(setFactoryName).append(", ").append(paramList(specParams, proj)).append(");\n");
            sb.append(pad).append("\t\t\tnode.getValue1().add(created);\n");
            sb.append(pad).append("\t\t}\n");
        }
        sb.append(pad).append("\t}\n");
        sb.append(pad).append("}\n");
    }

    /**
     * Emit a multi-source defineTo block for a non-creation event whose params
     * neither contain nor are contained by some creation event's params, but
     * share a common key K.
     *
     * <p>Algorithm (mirroring the modified-hotspot reference):
     * <pre>
     *   sourceSet = translation_tree_K_To_PC.get&lt;|K|&gt;(K_args).getValue1();
     *   if (sourceSet != null) {
     *       for each sourceMonitor in sourceSet:
     *           recover P_C-only params via sourceMonitor.RVM_&lt;p&gt;;
     *           destination_args = P_C ∪ event_params (= spec params under the
     *               common single-creation assumption);
     *           if destination slot is null or DisableHolder:
     *               check definability against every non-source subset;
     *               if definable:
     *                   created = sourceMonitor.clone();
     *                   tree.put(created, destination_args);
     *                   insert into all proper-subset projection sets of destination.
     *   }
     * </pre>
     */
    private void emitMultiSourceDefineToBlock(StringBuilder sb, String pad,
            NativeOutput.MultiSourceTriple t, RVMParameters eventParams, String tsName) {
        RVMParameters specParams = spec.getParameters();
        String tuple2Monitor = "Tuple2<" + setName + ", " + monitorName + ">";
        String tuple2Set = "Tuple2<" + setName + ", I" + monitorName + ">";
        String itfName = "I" + monitorName;
        String tname = NativeOutput.translationTreeName(specName, t.commonK, t.creationP);

        // Destination = union of creation params and event params, sorted in spec order.
        // For specs whose union equals spec.params, destination = full key.
        RVMParameters destination = new RVMParameters();
        for (RVMParameter sp : specParams) {
            if (t.creationP.contains(sp) || eventParams.contains(sp)) destination.add(sp);
        }

        sb.append(pad).append("// D(X) createNewMonitorStates:4 when Dom(theta'') = <")
          .append(t.commonK.parameterStringUnderscore()).append("> via translation tree to <")
          .append(t.creationP.parameterStringUnderscore()).append(">\n");
        // Scope-block so multiple multi-source triples for the same event
        // don't clash on the `sourceSet` / inner names.
        sb.append(pad).append("{\n");
        pad = pad + "\t";
        sb.append(pad).append(setName).append(" sourceSet = null;\n");
        sb.append(pad).append("{\n");
        sb.append(pad).append("\t").append(tuple2Monitor).append(" node_k = (").append(tuple2Monitor)
          .append(") ").append(tname).append(".get").append(t.commonK.size()).append("(");
        boolean first = true;
        for (RVMParameter p : t.commonK) {
            if (!first) sb.append(", ");
            sb.append(p.getName());
            first = false;
        }
        sb.append(");\n");
        sb.append(pad).append("\tif (node_k != null) sourceSet = node_k.getValue1();\n");
        sb.append(pad).append("}\n");
        sb.append(pad).append("if (sourceSet != null) {\n");
        sb.append(pad).append("\tint setlen = sourceSet.getSize();\n");
        sb.append(pad).append("\tfor (int ielem = 0; ielem < setlen; ++ielem) {\n");
        sb.append(pad).append("\t\t").append(monitorName).append(" sourceMonitor = sourceSet.get(ielem);\n");
        // Recover source-only params (in P_C but not in eventParams).
        for (RVMParameter p : t.creationP) {
            if (eventParams.contains(p)) continue;
            sb.append(pad).append("\t\t").append(p.getType()).append(" ").append(p.getName())
              .append(" = sourceMonitor.RVM_").append(p.getName()).append(";\n");
        }
        sb.append(pad).append("\t\tif (");
        first = true;
        for (RVMParameter p : t.creationP) {
            if (eventParams.contains(p)) continue;
            if (!first) sb.append(" && ");
            sb.append(p.getName()).append(" != null");
            first = false;
        }
        if (first) sb.append("true");
        sb.append(") {\n");

        // Check destination slot for existing entry.
        sb.append(pad).append("\t\t\t").append(itfName).append(" destLeaf = (").append(itfName)
          .append(") ").append(treeName).append(".get").append(specParams.size()).append("(")
          .append(paramList(specParams, destination)).append(");\n");
        String holderName = specName + "DisableHolder";
        sb.append(pad).append("\t\t\tif (destLeaf == null || destLeaf instanceof ").append(holderName).append(") {\n");
        sb.append(pad).append("\t\t\t\tboolean definable = true;\n");

        // Definability checks: subsets ⊆ destination that don't contain any
        // source-only param (P_C \ P_E), plus the full destination.
        // Subsets containing a source-only param would find the source
        // monitor itself in their projection (since the source monitor is
        // at projection key P_C ⊇ source-only), which would trip
        // (disable > tau) on the source itself. The reference (modified-
        // hotspot UnsafeMapIterator) excludes these.
        RVMParameters sourceOnly = new RVMParameters();
        for (RVMParameter p : t.creationP) {
            if (!eventParams.contains(p)) sourceOnly.add(p);
        }
        for (RVMParameters check : nonEmptySubsetsExcept(destination, t.commonK)) {
            boolean hasSourceOnly = false;
            for (RVMParameter p : sourceOnly) {
                if (check.contains(p) && !check.equals(destination)) { hasSourceOnly = true; break; }
            }
            if (hasSourceOnly) continue;
            boolean isFull = check.size() == specParams.size();
            sb.append(pad).append("\t\t\t\t// D(X) defineTo:1--5 for <").append(check.parameterStringUnderscore()).append(">\n");
            sb.append(pad).append("\t\t\t\tif (definable) {\n");
            if (isFull) {
                sb.append(pad).append("\t\t\t\t\t").append(itfName).append(" node_check = (").append(itfName)
                  .append(") ").append(treeName).append(".get").append(specParams.size()).append("(")
                  .append(paramList(specParams, check)).append(");\n");
                sb.append(pad).append("\t\t\t\t\tif (node_check != null && ((node_check.getDisable() > sourceMonitor.getTau()) || (node_check.getTau() > 0 && node_check.getTau() < sourceMonitor.getTau()))) {\n");
                sb.append(pad).append("\t\t\t\t\t\tdefinable = false;\n");
                sb.append(pad).append("\t\t\t\t\t}\n");
            } else {
                sb.append(pad).append("\t\t\t\t\t").append(tuple2Set).append(" node_check = (").append(tuple2Set)
                  .append(") ").append(treeName).append(".get").append(specParams.size()).append("(")
                  .append(paramList(specParams, check)).append(");\n");
                sb.append(pad).append("\t\t\t\t\tif (node_check != null) {\n");
                sb.append(pad).append("\t\t\t\t\t\t").append(itfName).append(" itmdLeaf = node_check.getValue2();\n");
                sb.append(pad).append("\t\t\t\t\t\tif (itmdLeaf != null && ((itmdLeaf.getDisable() > sourceMonitor.getTau()) || (itmdLeaf.getTau() > 0 && itmdLeaf.getTau() < sourceMonitor.getTau()))) {\n");
                sb.append(pad).append("\t\t\t\t\t\t\tdefinable = false;\n");
                sb.append(pad).append("\t\t\t\t\t\t}\n");
                sb.append(pad).append("\t\t\t\t\t}\n");
            }
            sb.append(pad).append("\t\t\t\t}\n");
        }
        sb.append(pad).append("\t\t\t\tif (definable) {\n");
        sb.append(pad).append("\t\t\t\t\t// D(X) defineTo:6 — clone source into destination slot\n");
        sb.append(pad).append("\t\t\t\t\t").append(monitorName).append(" created = (").append(monitorName)
          .append(") sourceMonitor.clone();\n");
        sb.append(pad).append("\t\t\t\t\t").append(treeName).append(".put(created, ")
          .append(paramList(specParams, destination)).append(");\n");
        sb.append(emitUniversalAdd(pad + "\t\t\t\t\t", "created"));
        // Insert into proper-subset projection sets of destination.
        for (RVMParameters proj : properSubsetProjections(destination)) {
            sb.append(pad).append("\t\t\t\t\t// D(X) defineTo:7 for <").append(proj.parameterStringUnderscore()).append(">\n");
            sb.append(pad).append("\t\t\t\t\t{\n");
            sb.append(pad).append("\t\t\t\t\t\t").append(tuple2Set).append(" node = (").append(tuple2Set)
              .append(") ").append(treeName).append(".getOrCreate").append(specParams.size()).append("(")
              .append(setFactoryName).append(", ").append(paramList(specParams, proj)).append(");\n");
            sb.append(pad).append("\t\t\t\t\t\tnode.getValue1().add(created);\n");
            sb.append(pad).append("\t\t\t\t\t}\n");
        }
        sb.append(pad).append("\t\t\t\t}\n");
        sb.append(pad).append("\t\t\t}\n");
        sb.append(pad).append("\t\t}\n");
        sb.append(pad).append("\t}\n");
        sb.append(pad).append("}\n");
        // close the outer scope-block opened at the top of this method.
        pad = pad.substring(0, pad.length() - 1);
        sb.append(pad).append("}\n");
    }

    /** Creation-event projections that are proper subsets of {@code eventParams}. */
    private static java.util.List<RVMParameters> creationSources(RVMonitorSpec spec, RVMParameters eventParams) {
        java.util.LinkedHashMap<String, RVMParameters> seen = new java.util.LinkedHashMap<>();
        for (EventDefinition e : spec.getEvents()) {
            if (!e.isStartEvent()) continue;
            RVMParameters cp = e.getRVMParametersOnSpec();
            if (cp == null || cp.size() == 0) continue;
            if (cp.size() >= eventParams.size()) continue;
            if (!eventParams.contains(cp)) continue;
            seen.put(cp.parameterStringUnderscore(), cp);
        }
        return new java.util.ArrayList<>(seen.values());
    }

    /** All non-empty subsets of {@code params} except {@code exclude}, in
     *  spec-param order. */
    private static java.util.List<RVMParameters> nonEmptySubsetsExcept(RVMParameters params,
                                                                       RVMParameters exclude) {
        java.util.List<RVMParameters> out = new java.util.ArrayList<>();
        int n = params.size();
        for (int mask = 1; mask < (1 << n); mask++) {
            RVMParameters sub = new RVMParameters();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) sub.add(params.get(i));
            }
            if (sub.parameterStringUnderscore().equals(exclude.parameterStringUnderscore())) continue;
            out.add(sub);
        }
        return out;
    }

    /** All non-empty proper subsets of {@code params}, in spec-param order. */
    private static java.util.List<RVMParameters> properSubsetProjections(RVMParameters params) {
        java.util.List<RVMParameters> out = new java.util.ArrayList<>();
        int n = params.size();
        for (int mask = 1; mask < (1 << n) - 1; mask++) {
            RVMParameters sub = new RVMParameters();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) sub.add(params.get(i));
            }
            out.add(sub);
        }
        return out;
    }

    /** Property-prefixed event-method name (single-property assumption). */
    private String propEventMethod(String eventId) {
        if (spec.getPropertiesAndHandlers().isEmpty()) {
            return "event_" + eventId;
        }
        int propId = spec.getPropertiesAndHandlers().get(0).getPropertyId();
        return "Prop_" + propId + "_event_" + eventId;
    }

    /** Emit `if (mon.Prop_N_Category_X) mon.Prop_N_handler_X(joinpoint?);` for
     *  each non-deadlock category on the single property. */
    private String handlerDispatchLines(String pad, String monRef) {
        StringBuilder sb = new StringBuilder();
        if (spec.getPropertiesAndHandlers().isEmpty()) return "";
        int propId = spec.getPropertiesAndHandlers().get(0).getPropertyId();
        for (String cat : spec.getPropertiesAndHandlers().get(0).getHandlers().keySet()) {
            if (cat.equals("deadlock")) continue;
            sb.append(pad).append("if (").append(monRef).append(".Prop_").append(propId)
              .append("_Category_").append(cat).append(") {\n");
            sb.append(pad).append("\t").append(monRef).append(".Prop_").append(propId)
              .append("_handler_").append(cat).append("(");
            if (com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc) {
                sb.append("joinpoint");
            }
            sb.append(");\n");
            sb.append(pad).append("}\n");
        }
        return sb.toString();
    }

    // --- helpers ---

    private String staticDispatchSignature(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        if (com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc) {
            sb.append("org.aspectj.lang.JoinPoint.StaticPart joinpoint");
        }
        for (RVMParameter p : event.getRVMParameters()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getType()).append(" ").append(p.getName());
        }
        return sb.toString();
    }

    private String eventCallArgs(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        if (com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc) {
            sb.append("joinpoint");
        }
        for (RVMParameter p : event.getRVMParameters()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p.getName());
        }
        return sb.toString();
    }

    /** Method-name prefix: empty in single-spec mode, "<Spec>_" with -merge. */
    private String methodPrefix() {
        return com.runtimeverification.rvmonitor.java.rvj.Main.options.merge
            ? specName + "_"
            : "";
    }

    private static String paramKey(RVMonitorSpec spec) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (RVMParameter p : spec.getParameters()) {
            if (!first) sb.append("_");
            sb.append(p.getName());
            first = false;
        }
        return sb.toString();
    }
}
