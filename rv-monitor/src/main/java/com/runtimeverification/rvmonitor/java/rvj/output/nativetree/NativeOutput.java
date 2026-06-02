package com.runtimeverification.rvmonitor.java.rvj.output.nativetree;

import com.runtimeverification.rvmonitor.java.rvj.Main;
import com.runtimeverification.rvmonitor.java.rvj.output.combinedoutputcode.RVMonitorStatManager;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.ImportDeclaration;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.RVMSpecFile;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.PackageDeclaration;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMonitorSpec;
import com.runtimeverification.rvmonitor.util.RVMException;

/**
 * Top-level orchestrator for native-mode codegen (-nativeindexingtree).
 *
 * <p>Emits a complete runtime monitor Java source for an {@link RVMSpecFile},
 * built around {@code java.lang.rv.IndexingTree} instead of the nested
 * {@code RVMMap*} hierarchy used by stock codegen.
 *
 * <p>Output layout (one file per RVMSpecFile, may contain multiple specs
 * if {@code -merge} was used):
 * <pre>
 *   package mop;
 *   imports (native set including java.lang.rv.*)
 *
 *   for each spec:
 *     final class &lt;Spec&gt;Monitor_Set { ... }      // if spec uses sets
 *     class &lt;Spec&gt;Monitor extends AbstractSynchronizedMonitor { ... }
 *     final class &lt;Spec&gt;MonitorFactory { ... }
 *     final class &lt;Spec&gt;MonitorSetFactory { ... }   // if spec uses sets
 *
 *   public final class &lt;Name&gt;RuntimeMonitor {
 *     // per-spec: lock, tree, cache fields, factory instances
 *     // per-event: static dispatch method
 *   }
 * </pre>
 *
 * <p>Stock codegen path is unchanged. The branch into this class lives at
 * {@code RVMProcessor.process()}.
 */
public final class NativeOutput {
    private final String name;
    private final RVMSpecFile specFile;
    /** Reused from stock codegen to keep the {@code <Name>_Statistics} class
     *  shape (and shutdown-hook output format) identical between modes when
     *  {@code -statistics} is on. Stays inert when the flag is off. */
    private final RVMonitorStatManager statManager;

    /** Imports needed by native-mode output. Superset of stock imports plus
     *  {@code java.lang.rv.*} for {@code IndexingTree} and {@code RuntimeMonitorFactory}. */
    private static final String[] NATIVE_IMPORTS = {
        "java.io.*",
        "java.util.*",
        "java.util.concurrent.*",
        "java.util.concurrent.locks.*",
        "java.util.Random",
        "java.lang.ref.*",
        "java.lang.rv.*",
        "com.runtimeverification.rvmonitor.java.rt.*",
        "com.runtimeverification.rvmonitor.java.rt.tablebase.TableAdopter.Tuple2",
        "com.runtimeverification.rvmonitor.java.rt.tablebase.TableAdopter.Tuple3",
        "com.runtimeverification.rvmonitor.java.rt.tablebase.IDisableHolder",
        "com.runtimeverification.rvmonitor.java.rt.tablebase.IMonitor",
        "com.runtimeverification.rvmonitor.java.rt.tablebase.DisableHolder",
    };

    public NativeOutput(String name, RVMSpecFile specFile) throws RVMException {
        this.name = name;
        this.specFile = specFile;
        this.statManager = new RVMonitorStatManager(name, specFile.getSpecs());
    }

    public String generate() {
        StringBuilder sb = new StringBuilder();
        emitPackageAndImports(sb);
        for (RVMonitorSpec spec : specFile.getSpecs()) {
            emitSpec(sb, spec);
        }
        // <Name>_Statistics class (shutdown hook printing total event/monitor
        // counts). Empty string when -statistics is off.
        sb.append(statManager.statClass(true));
        emitRuntimeMonitorClass(sb);
        return sb.toString();
    }

    /** Name of the static counter class. */
    String statsClassName() { return name + "_Statistics"; }

    private void emitPackageAndImports(StringBuilder sb) {
        PackageDeclaration pkg = specFile.getPakage();
        String pkgName = pkg != null ? pkg.getName().toString() : null;
        if (pkg != null) {
            sb.append("package ").append(pkg.getName()).append(";\n");
        }
        // Dedupe imports: user imports from spec file + native-mode set.
        // Drop same-package imports (e.g. `import mop.BaseAspect;` when
        // generating into `package mop`) — they're unresolvable as the named
        // type may not exist at codegen time, and aren't needed anyway.
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (ImportDeclaration imp : specFile.getImports()) {
            StringBuilder ib = new StringBuilder();
            if (imp.isStatic()) ib.append("static ");
            String name = imp.getName().toString().trim();
            ib.append(name);
            if (imp.isAsterisk()) ib.append(".*");
            String full = ib.toString();
            if (pkgName != null && !imp.isStatic() && !imp.isAsterisk()) {
                int lastDot = name.lastIndexOf('.');
                if (lastDot > 0 && name.substring(0, lastDot).equals(pkgName)) {
                    continue;
                }
            }
            seen.add(full);
        }
        for (String i : NATIVE_IMPORTS) seen.add(i);
        for (String i : seen) sb.append("import ").append(i).append(";\n");
        sb.append("\n");
    }

    private void emitSpec(StringBuilder sb, RVMonitorSpec spec) {
        NativeMonitorClass mc = new NativeMonitorClass(name, spec, statManager.getStat(spec));

        // Monitor-set class (if spec needs sets — i.e., has multi-param or general)
        if (specNeedsSets(spec)) {
            sb.append(mc.emitMonitorSetClass());
            sb.append("\n");
        }

        // For general specs: I<Spec>Monitor interface + <Spec>DisableHolder.
        // The interface unifies the leaf-monitor type so tree slots can hold
        // either a real Monitor or a DisableHolder placeholder; the
        // DisableHolder records that a slot was visited but no Monitor was
        // ever clone-defined into it.
        if (spec.isGeneral()) {
            sb.append(emitMonitorInterface(spec));
            sb.append("\n");
            sb.append(emitDisableHolder(spec));
            sb.append("\n");
        }

        // Monitor class itself
        sb.append(mc.emitMonitorClass());
        sb.append("\n");

        // Factory classes. General specs use only the set factory — leaf
        // monitors are created inline via `new <Spec>Monitor(tau++)`, not
        // via a factory, so no monitorFactory is emitted for them.
        if (!spec.isGeneral()) {
            sb.append(emitMonitorFactory(spec));
            sb.append("\n");
        }
        if (specNeedsSets(spec)) {
            sb.append(emitMonitorSetFactory(spec));
            sb.append("\n");
        }
    }

    private String emitMonitorInterface(RVMonitorSpec spec) {
        String monName = spec.getName() + "Monitor";
        return "interface I" + monName + " extends IMonitor, IDisableHolder {\n}\n";
    }

    private String emitDisableHolder(RVMonitorSpec spec) {
        String monName = spec.getName() + "Monitor";
        String holderName = spec.getName() + "DisableHolder";
        StringBuilder sb = new StringBuilder();
        sb.append("class ").append(holderName).append(" extends DisableHolder implements I")
          .append(monName).append(" {\n");
        sb.append("\t").append(holderName).append("(long tau) { super(tau); }\n");
        sb.append("\t@Override public final boolean isTerminated() { return false; }\n");
        sb.append("\t@Override public int getLastEvent() { return -1; }\n");
        sb.append("\t@Override public int getState() { return -1; }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private void emitRuntimeMonitorClass(StringBuilder sb) {
        sb.append("public final class ").append(name)
          .append("RuntimeMonitor implements com.runtimeverification.rvmonitor.java.rt.RVMObject {\n");

        // Statistics field + shutdown-hook registration (when -statistics).
        // Both strings are empty when the flag is off, so no gating here.
        String statField = statManager.fieldDecl2();
        if (!statField.isEmpty()) {
            sb.append("\t").append(statField);
            sb.append("\tstatic {\n\t\t");
            sb.append(statManager.constructor().replace("\n", "\n\t\t").trim()).append("\n");
            sb.append("\t}\n\n");
        }

        // Per-spec field declarations + dispatch methods
        for (RVMonitorSpec spec : specFile.getSpecs()) {
            emitSpecFields(sb, spec);
            NativeDispatch nd = new NativeDispatch(name, spec, statManager.getStat(spec));
            sb.append(nd.emitAllEvents());
        }

        sb.append("}\n");
    }

    /** Emit the static fields for one spec inside the RuntimeMonitor class. */
    private void emitSpecFields(StringBuilder sb, RVMonitorSpec spec) {
        String specName = spec.getName();
        String monName = specName + "Monitor";

        // Lock
        sb.append("\t// Declarations for the Lock\n");
        sb.append("\tstatic final ReentrantLock ").append(specName)
          .append("_RVMLock = new ReentrantLock();\n\n");

        // Monotonic timestamp counter — feeds tau/disable on every monitor
        // creation/visit. Drives the definability checks in defineTo.
        if (spec.isGeneral()) {
            sb.append("\t// Declarations for Timestamps\n");
            sb.append("\tprivate static long ").append(specName).append("_timestamp = 1;\n\n");
        }

        // Activated flag. Volatile so non-creation events can fast-path
        // (`if (!<spec>_activated) return;`) before acquiring the per-spec
        // lock — see the dispatch emission in NativeDispatch. The flag is
        // monotonic: false → true on the first creation event, never
        // reset, so a relaxed-acquire read is correct.
        sb.append("\tprivate static volatile boolean ").append(specName)
          .append("_activated = false;\n\n");

        // Cache + tree field declarations
        sb.append("\t// Declarations for Indexing Trees\n");
        if (spec.getParameters().size() == 0) {
            // 0-param: singleton monitor field, no tree, no cache. The ctor of
            // <Spec>Monitor bumps numTotalMonitors / <Spec>_Monitor_num when
            // -statistics is on, so no extra emission needed here.
            sb.append("\tprivate static final ").append(monName).append(" ")
              .append(specName).append("__Map = new ").append(monName).append("();\n\n");
        } else {
            String fullKey = paramKey(spec);
            String treeName = specName + "_" + fullKey + "_Map";
            // Cache fields per unique event-projection (full plus any partial-
            // binding projections used by non-creation events). Each projection
            // gets its own cache key fields + cache value.
            java.util.LinkedHashSet<String> projKeys = new java.util.LinkedHashSet<>();
            projKeys.add(fullKey);
            for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition e : spec.getEvents()) {
                com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters onSpec = e.getRVMParametersOnSpec();
                if (onSpec == null || onSpec.size() == 0) continue;
                projKeys.add(onSpec.parameterStringUnderscore());
            }
            for (String pk : projKeys) {
                String prefix = specName + "_" + pk + "_Map";
                for (String name : pk.split("_")) {
                    sb.append("\tprivate static Object ").append(prefix)
                      .append("_cachekey_").append(name).append(";\n");
                }
                sb.append("\tprivate static Object ").append(prefix).append("_cachevalue;\n");
            }
            // Single IndexingTree per spec (entries keyed by full spec params with null padding)
            sb.append("\tprivate static IndexingTree ").append(treeName)
              .append(" = new IndexingTree();\n\n");

            // Translation trees for multi-source defineTo. One per distinct
            // (commonK, P_C) pair: maps a partial key K to the set of
            // partial-creation monitors at P_C that share K. Populated by
            // creation-event dispatch; consumed by non-creation+partial-key
            // defineTo to clone source monitors into the destination key.
            java.util.LinkedHashSet<String> seenTrees = new java.util.LinkedHashSet<>();
            for (MultiSourceTriple t : multiSourceTriples(spec)) {
                String tname = translationTreeName(specName, t.commonK, t.creationP);
                if (!seenTrees.add(tname)) continue;
                for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameter p : t.commonK) {
                    sb.append("\tprivate static Object ").append(tname)
                      .append("_cachekey_").append(p.getName()).append(";\n");
                }
                sb.append("\tprivate static Object ").append(tname).append("_cachevalue;\n");
                sb.append("\tprivate static final IndexingTree ").append(tname)
                  .append(" = new IndexingTree();\n\n");
            }

            // Empty-binding singleton (`<Spec>__Map`) — the universal-set
            // container used by zero-param events on parametric specs.
            // `value1` is the set of every monitor ever created in this spec
            // (every monitor-creation path also adds to it); `value2` stays
            // null since the empty key never holds a real leaf. Matches
            // stock's "for <>" insert pattern. Only emitted when needed.
            if (specHasEmptyParamEvent(spec)) {
                sb.append("\tprivate static final Tuple2<").append(monName).append("_Set, ")
                  .append(monName).append("> ").append(specName).append("__Map = new Tuple2<")
                  .append(monName).append("_Set, ").append(monName).append(">(new ")
                  .append(monName).append("_Set(), null);\n\n");
            }

            // Factory instances. General specs build their leaf monitors
            // inline via `new <Spec>Monitor(tau++)`, so they only need the
            // set factory.
            if (!spec.isGeneral()) {
                sb.append("\tprivate static ").append(monName).append("Factory ")
                  .append(specName).append("_monitorFactory = new ").append(monName).append("Factory();\n");
            }
            if (specNeedsSets(spec)) {
                sb.append("\tprivate static ").append(monName).append("SetFactory ")
                  .append(specName).append("_monitorSetFactory = new ").append(monName).append("SetFactory();\n");
            }
            sb.append("\n");
        }
    }

    /**
     * Multi-source defineTo descriptor. Generated for each (non-creation event E,
     * creation event C) pair where E introduces parameters that C didn't bind
     * AND C bound parameters that E doesn't — i.e., neither is a subset of the
     * other and they share at least one common parameter.
     *
     * <p>In that case, the dispatch for E cannot reach the source monitors at
     * C's projection via the main tree (the projection sets at K aren't
     * populated with partial-creation monitors). A separate translation tree
     * indexed by {@code commonK} maps the K-keyed lookup back to the set of
     * (P_C)-projection source monitors, so we can clone them into the
     * destination at full key.
     */
    static final class MultiSourceTriple {
        final com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition event;
        final com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition creationEvent;
        final com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters commonK;
        final com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters creationP;
        MultiSourceTriple(com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition e,
                          com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition c,
                          com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters k,
                          com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters p) {
            this.event = e; this.creationEvent = c; this.commonK = k; this.creationP = p;
        }
    }

    /** All (event, creation_event, K, P_C) triples in the spec where multi-source
     *  defineTo emission is needed. Empty for specs whose creation events fully
     *  cover the non-creation events' params. */
    static java.util.List<MultiSourceTriple> multiSourceTriples(RVMonitorSpec spec) {
        java.util.List<MultiSourceTriple> out = new java.util.ArrayList<>();
        if (!spec.isGeneral()) return out;
        for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition e : spec.getEvents()) {
            if (e.isStartEvent()) continue;
            com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters pe = e.getRVMParametersOnSpec();
            if (pe == null || pe.size() == 0) continue;
            for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition c : spec.getEvents()) {
                if (!c.isStartEvent()) continue;
                com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters pc = c.getRVMParametersOnSpec();
                if (pc == null || pc.size() == 0) continue;
                // Case A (pe ⊆ pc) — broadcast handles it (monitor in pe-projection set).
                // Case B (pc ⊊ pe) — current `creationSources` simple defineTo handles it.
                if (pc.contains(pe) || pe.contains(pc)) continue;
                com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters common =
                    com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters.intersectionSet(pe, pc);
                if (common.size() == 0) continue;
                common = spec.getParameters().sortParam(common);
                out.add(new MultiSourceTriple(e, c, common, pc));
            }
        }
        return out;
    }

    /** True if {@code spec} needs any multi-source defineTo machinery
     *  (translation trees, RVM_&lt;p&gt; fields, parameter-passing constructor). */
    static boolean specNeedsMultiSourceDefineTo(RVMonitorSpec spec) {
        return !multiSourceTriples(spec).isEmpty();
    }

    /** Tree-field name: {@code <Spec>_<K>__To__<PC>_Map}. */
    static String translationTreeName(String specName,
            com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters k,
            com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters pc) {
        return specName + "_" + k.parameterStringUnderscore()
             + "__To__" + pc.parameterStringUnderscore() + "_Map";
    }

    /** True if the spec has any event with zero spec-params. Such events
     *  need broadcast dispatch via the universal `<Spec>__Map.getValue1()`
     *  set; the absence of a key means the tree cannot be looked up. */
    static boolean specHasEmptyParamEvent(RVMonitorSpec spec) {
        if (spec.getParameters().size() == 0) return false;
        for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition e : spec.getEvents()) {
            com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters onSpec = e.getRVMParametersOnSpec();
            if (onSpec == null || onSpec.size() == 0) return true;
        }
        return false;
    }

    /** Factory class for creating individual monitors. */
    private String emitMonitorFactory(RVMonitorSpec spec) {
        String monName = spec.getName() + "Monitor";
        StringBuilder sb = new StringBuilder();
        sb.append("final class ").append(monName).append("Factory extends java.lang.rv.RuntimeMonitorFactory {\n");
        sb.append("\tpublic boolean created = false;\n");
        sb.append("\tpublic Object createMonitor() {\n");
        sb.append("\t\tcreated = true;\n");
        sb.append("\t\treturn new ").append(monName).append("();\n");
        sb.append("\t}\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Factory class for creating monitor sets (for projection-set entries). */
    private String emitMonitorSetFactory(RVMonitorSpec spec) {
        String monName = spec.getName() + "Monitor";
        String setName = monName + "_Set";
        StringBuilder sb = new StringBuilder();
        sb.append("final class ").append(monName).append("SetFactory extends java.lang.rv.RuntimeMonitorFactory {\n");
        sb.append("\tpublic boolean created = false;\n");
        sb.append("\tpublic Object createMonitor() {\n");
        sb.append("\t\tcreated = true;\n");
        if (spec.isGeneral()) {
            String itfName = "I" + monName;
            sb.append("\t\tTuple2<").append(setName).append(", ").append(itfName).append("> ret = new Tuple2<")
              .append(setName).append(", ").append(itfName).append(">();\n");
            sb.append("\t\tret.setValue1(new ").append(setName).append("());\n");
            sb.append("\t\treturn ret;\n");
        } else {
            sb.append("\t\treturn new ").append(setName).append("();\n");
        }
        sb.append("\t}\n");
        sb.append("}\n");
        return sb.toString();
    }

    /** Spec needs a Monitor_Set class iff it has events that dispatch over sets
     *  (i.e., partial-binding events) OR it's general (defineTo machinery uses sets).
     *  General specs always need sets — even at arity 1 — because some events
     *  (e.g. partial-key creation, or 1-param events whose dispatch broadcasts
     *  through the enclosing set when the spec is classified general due to a
     *  zero-param sibling event). */
    private boolean specNeedsSets(RVMonitorSpec spec) {
        if (spec.isGeneral()) return true;
        // Zero-param events broadcast via the universal `__Map.getValue1()`
        // set, so we need the Set class regardless of spec arity.
        if (specHasEmptyParamEvent(spec)) return true;
        if (spec.getParameters().size() <= 1) return false;
        // multi-param suffix: check whether any event uses fewer params than spec.
        for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition e : spec.getEvents()) {
            if (e.getRVMParametersOnSpec() == null) continue;
            if (e.getRVMParametersOnSpec().size() < spec.getParameters().size()) return true;
        }
        return false;
    }

    /** Build the key for a tree field name, e.g. "c_i" for params (c, i). */
    private static String paramKey(RVMonitorSpec spec) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameter p : spec.getParameters()) {
            if (!first) sb.append("_");
            sb.append(p.getName());
            first = false;
        }
        return sb.toString();
    }
}
