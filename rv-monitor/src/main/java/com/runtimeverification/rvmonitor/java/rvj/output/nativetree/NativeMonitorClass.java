package com.runtimeverification.rvmonitor.java.rvj.output.nativetree;

import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.PropertyAndHandlers;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameter;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMonitorSpec;

/**
 * Emits the per-spec {@code <Spec>Monitor} and {@code <Spec>Monitor_Set} classes
 * for native-mode codegen.
 *
 * <p>Monitor class structure (matches what stock emits for the synchronized
 * variant, with strong-ref parameter storage instead of WeakReference):
 * <pre>
 *   class &lt;Spec&gt;Monitor extends AbstractSynchronizedMonitor
 *                       implements Cloneable, RVMObject {
 *     // boilerplate
 *     protected Object clone() { ... }
 *
 *     // DFA state + transition tables (from logic plugin output)
 *     int Prop_1_state;
 *     static final int Prop_1_transition_&lt;event&gt;[] = { ... };
 *
 *     // Category flags
 *     boolean Prop_1_Category_&lt;cat&gt; = false;
 *
 *     // Constructor (with remembered-param injection for general specs)
 *     &lt;Spec&gt;Monitor(...) { Prop_1_state = 0; ... }
 *
 *     // Event methods
 *     final boolean Prop_1_event_&lt;id&gt;(args) { ... }
 *
 *     // Handler methods
 *     final void Prop_1_handler_&lt;cat&gt;(args) { ... }
 *
 *     // reset()
 *
 *     // Remembered param fields (strong refs, native-mode-specific)
 *     final &lt;Type&gt; RVM_&lt;p&gt;;
 *
 *     // Coenable termination (dead in v1 native — no daemon calls it)
 *     boolean alive_parameters_&lt;g&gt; = true;
 *     protected final void terminateInternal(int idnum) { ... }
 *   }
 * </pre>
 *
 * <p>Status: partial. Currently emits a structural skeleton. Logic-plugin
 * output integration (transition tables, monitoring body, handler conditions)
 * is the major remaining work — these strings come from
 * {@code PropertyAndHandlers.getLogicProperty(...)} and need to be embedded
 * with the right substitutions.
 */
final class NativeMonitorClass {
    private final RVMonitorSpec spec;
    private final String specName;
    private final String monitorName;
    private final String setName;
    /** Per-spec statistics emitter (reused from stock). Returns empty strings
     *  when {@code -statistics} is off. */
    private final com.runtimeverification.rvmonitor.java.rvj.output.RVMonitorStatistics stat;
    /** Global statistics class name, e.g. {@code MOPAgent_Statistics}. */
    private final String statsClassName;

    NativeMonitorClass(String agentName, RVMonitorSpec spec,
                       com.runtimeverification.rvmonitor.java.rvj.output.RVMonitorStatistics stat) {
        this.spec = spec;
        this.specName = spec.getName();
        this.monitorName = specName + "Monitor";
        this.setName = monitorName + "_Set";
        this.stat = stat;
        this.statsClassName = agentName + "_Statistics";
    }

    /** Emit the {@code <Spec>Monitor} class definition. */
    String emitMonitorClass() {
        StringBuilder sb = new StringBuilder();
        sb.append("class ").append(monitorName)
          .append(" extends com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractSynchronizedMonitor")
          .append(" implements Cloneable, com.runtimeverification.rvmonitor.java.rt.RVMObject");
        if (spec.isGeneral()) {
            sb.append(", I").append(monitorName);
        }
        sb.append(" {\n");

        // clone() boilerplate. defineTo (general specs) creates monitors via
        // clone(), so — like stock's BaseMonitor.clone() — we bump the monitor
        // counter here too. Without this, clone-defined monitors are uncounted
        // and per-spec #monitors diverges from stock for general specs.
        sb.append("\tprotected Object clone() {\n");
        if (com.runtimeverification.rvmonitor.java.rvj.Main.options.statistics) {
            sb.append("\t\t").append(stat.incNumMonitor().trim().replace("\n", "\n\t\t")).append("\n");
        }
        sb.append("\t\ttry {\n");
        sb.append("\t\t\t").append(monitorName).append(" ret = (").append(monitorName).append(") super.clone();\n");
        sb.append("\t\t\treturn ret;\n");
        sb.append("\t\t}\n");
        sb.append("\t\tcatch (CloneNotSupportedException e) {\n");
        sb.append("\t\t\tthrow new InternalError(e.toString());\n");
        sb.append("\t\t}\n");
        sb.append("\t}\n\n");

        // Spec body declarations (e.g. `Object c;` declared at the top of the
        // .rvm spec body). Used by general specs to hold remembered params
        // that event actions assign via `this.<p> = <p>;`.
        String bodyDecls = spec.getDeclarationsStr();
        if (bodyDecls != null && !bodyDecls.trim().isEmpty()) {
            sb.append("\t").append(bodyDecls.trim().replace("\n", "\n\t")).append("\n\n");
        }

        // DFA state + transition tables (substituting $state$ → Prop_N_state etc.)
        for (PropertyAndHandlers prop : spec.getPropertiesAndHandlers()) {
            String stateDecl = prop.getLogicProperty("state declaration");
            if (stateDecl != null) {
                sb.append("\t").append(substitute(stateDecl, prop).replace("\n", "\n\t")).append("\n");
            }
            // Category boolean flags — one per @category (excluding deadlock)
            for (String cat : prop.getHandlers().keySet()) {
                if (cat.equals("deadlock")) continue;
                sb.append("\tboolean Prop_").append(prop.getPropertyId())
                  .append("_Category_").append(cat).append(" = false;\n");
            }
        }
        sb.append("\n");

        // Random field (matches stock structure)
        sb.append("\tRandom random = new Random(1);\n\n");

        // tau/disable fields for general specs (used by defineTo cloning
        // algorithm — see NativeDispatch general dispatch).
        if (spec.isGeneral()) {
            sb.append("\tprivate final long tau;\n");
            sb.append("\tprivate long disable = -1;\n\n");
        }

        // RVM_<p> remembered-parameter fields for general specs whose
        // multi-source defineTo needs to recover the creation-event params
        // from a source monitor. We emit one field per spec parameter; the
        // unused ones cost only a reference per monitor.
        boolean msdt = spec.isGeneral() && NativeOutput.specNeedsMultiSourceDefineTo(spec);
        if (msdt) {
            for (RVMParameter p : spec.getParameters()) {
                sb.append("\tfinal ").append(p.getType()).append(" RVM_").append(p.getName()).append(";\n");
            }
            sb.append("\n");
        }

        // Statistics field decls (per-spec long counters). Empty when off.
        String statFields = stat.fieldDecl();
        if (!statFields.isEmpty()) {
            sb.append("\t").append(statFields.replace("\n", "\n\t").trim()).append("\n\n");
        }

        // Constructor. General specs take `long tau`; multi-source specs
        // additionally take all spec params (passed by the dispatcher;
        // null for params not bound by the creating event).
        sb.append("\t").append(monitorName).append("(");
        if (spec.isGeneral()) {
            sb.append("long tau");
            if (msdt) {
                for (RVMParameter p : spec.getParameters()) {
                    sb.append(", ").append(p.getType()).append(" ").append(p.getName());
                }
            }
        }
        sb.append(") {\n");
        if (spec.isGeneral()) {
            sb.append("\t\tthis.tau = tau;\n");
            if (msdt) {
                for (RVMParameter p : spec.getParameters()) {
                    sb.append("\t\tthis.RVM_").append(p.getName()).append(" = ").append(p.getName()).append(";\n");
                }
            }
        }
        for (PropertyAndHandlers prop : spec.getPropertiesAndHandlers()) {
            String init = prop.getLogicProperty("initialization");
            if (init != null && !init.isEmpty()) {
                sb.append("\t\t").append(substitute(init, prop).replace("\n", "\n\t\t")).append("\n");
            }
        }
        // Statistics: per-spec + global monitor counter (both emitted by
        // stat.incNumMonitor() since the global-aggregator fix). Mirrors
        // stock (BaseMonitor/RawMonitor emit incNumMonitor() in the ctor).
        // Clone()-based monitor creation bypasses the ctor and is not counted
        // here — same semantics as stock.
        if (com.runtimeverification.rvmonitor.java.rvj.Main.options.statistics) {
            sb.append("\t\t").append(stat.incNumMonitor().trim().replace("\n", "\n\t\t")).append("\n");
        }
        sb.append("\t}\n\n");

        if (spec.isGeneral()) {
            sb.append("\t@Override public final long getTau() { return this.tau; }\n");
            sb.append("\t@Override public final long getDisable() { return this.disable; }\n");
            sb.append("\t@Override public final void setDisable(long value) { this.disable = value; }\n\n");
        }

        // getState override
        sb.append("\t@Override\n");
        sb.append("\tpublic final int getState() {\n");
        if (!spec.getPropertiesAndHandlers().isEmpty()) {
            // Use the first property's state field
            sb.append("\t\treturn Prop_").append(spec.getPropertiesAndHandlers().get(0).getPropertyId())
              .append("_state;\n");
        } else {
            sb.append("\t\treturn -1;\n");
        }
        sb.append("\t}\n\n");

        // Event methods (one per event per property)
        for (PropertyAndHandlers prop : spec.getPropertiesAndHandlers()) {
            for (EventDefinition event : spec.getEvents()) {
                sb.append(emitEventMethod(prop, event));
            }
            sb.append(emitHandlers(prop));
        }
        // For raw specs (no properties), event methods are named event_<id>
        if (spec.getPropertiesAndHandlers().isEmpty()) {
            for (EventDefinition event : spec.getEvents()) {
                sb.append(emitRawEventMethod(event));
            }
        }

        // reset()
        sb.append("\tfinal void reset() {\n");
        sb.append("\t\tRVM_lastevent = -1;\n");
        for (PropertyAndHandlers prop : spec.getPropertiesAndHandlers()) {
            String resetBody = prop.getLogicProperty("reset");
            if (resetBody != null) {
                sb.append("\t\t").append(substitute(resetBody, prop).replace("\n", "\n\t\t")).append("\n");
            }
        }
        sb.append("\t}\n\n");

        // Note: general specs remember params via the body-declared fields
        // (emitted at the top of the class), assigned in the event-action
        // body of the creation event (`this.<p> = <p>;`). No separate
        // `RVM_<p>` fields are emitted.

        // Coenable alive_parameters_* fields + terminateInternal stub.
        // For v1 strict stock-match, emit the same DNF body stock emits.
        // It's dead code in native (nothing calls terminateInternal), but matches.
        // TODO: integrate with OptimizedCoenableSet to emit the actual DNF.
        sb.append("\t@Override\n");
        sb.append("\tprotected final void terminateInternal(int idnum) {\n");
        sb.append("\t}\n");

        // Statistics getter methods (called by aspect-level advice that
        // javamop emits with -s). Empty when off.
        String statMethods = stat.methodDecl();
        if (!statMethods.isEmpty()) {
            sb.append("\t").append(statMethods.replace("\n", "\n\t").trim()).append("\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /** Emit the {@code <Spec>Monitor_Set} class. */
    String emitMonitorSetClass() {
        StringBuilder sb = new StringBuilder();
        sb.append("final class ").append(setName)
          .append(" extends com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitorSet<")
          .append(monitorName).append("> {\n");
        sb.append("\t").append(setName).append("() {\n");
        sb.append("\t\tthis.size = 0;\n");
        sb.append("\t\tthis.elements = new ").append(monitorName).append("[4];\n");
        sb.append("\t}\n");

        // Per-event iteration methods
        for (EventDefinition event : spec.getEvents()) {
            sb.append(emitSetEventMethod(event));
        }
        sb.append("}\n");
        return sb.toString();
    }

    // --- helpers ---

    private String emitEventMethod(PropertyAndHandlers prop, EventDefinition event) {
        int propId = prop.getPropertyId();
        StringBuilder sb = new StringBuilder();
        sb.append("\tfinal boolean Prop_").append(propId).append("_event_").append(event.getId())
          .append("(").append(eventParamSignature(event)).append(") {\n");
        // Advice body (from .rvm) — wrap in braces like stock does
        if (event.getAction() != null && !event.getAction().isEmpty()) {
            sb.append("\t\t").append(macroSub(event.getAction()).replace("\n", "\n\t\t")).append("\n");
        }
        sb.append("\t\tRVM_lastevent = ").append(event.getIdNum()).append(";\n\n");
        // Monitoring body: extract just this event's block from the property's
        // monitoring body string. The logic plugin emits it as a per-event map
        // separated by event-id markers; parse via PropertyAndHandlers helper.
        String body = prop.getEventMonitoringCode(event.getId());
        if (body != null && !body.isEmpty()) {
            sb.append("\t\t").append(substitute(body, prop).replace("\n", "\n\t\t")).append("\n");
        }
        // Category condition update lines: for each non-deadlock handler,
        //   Prop_<id>_Category_<cat> = <condition>;
        // The condition string comes from prop.getLogicProperty(<cat> + " condition").
        // Some plugins emit a per-event map (containing ":{"); in that case
        // use parseMonitoredEvent to extract just this event's expression.
        for (String cat : prop.getHandlers().keySet()) {
            if (cat.equals("deadlock")) continue;
            String condStr = prop.getLogicProperty(cat + " condition");
            if (condStr == null) continue;
            if (condStr.contains(":{")) {
                java.util.HashMap<String, String> conds = new java.util.HashMap<>();
                prop.parseMonitoredEvent(conds, condStr);
                condStr = conds.get(event.getId());
                if (condStr == null) continue;
            }
            String subbed = substitute(condStr.trim(), prop);
            sb.append("\t\tProp_").append(propId).append("_Category_").append(cat)
              .append(" = ").append(subbed).append(";\n");
        }
        sb.append("\t\treturn true;\n");
        sb.append("\t}\n\n");
        return sb.toString();
    }

    private String emitRawEventMethod(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        sb.append("\tfinal boolean event_").append(event.getId())
          .append("(").append(eventParamSignature(event)).append(") {\n");
        sb.append("\t\tRVM_lastevent = ").append(event.getIdNum()).append(";\n");
        if (event.getAction() != null && !event.getAction().isEmpty()) {
            sb.append("\t\t").append(macroSub(event.getAction()).replace("\n", "\n\t\t")).append("\n");
        }
        sb.append("\t\treturn true;\n");
        sb.append("\t}\n\n");
        return sb.toString();
    }

    private String emitHandlers(PropertyAndHandlers prop) {
        int propId = prop.getPropertyId();
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : prop.getHandlers().entrySet()) {
            String category = e.getKey();
            String body = e.getValue();
            if (category.equals("deadlock")) continue;
            sb.append("\tfinal void Prop_").append(propId).append("_handler_").append(category).append("(");
            // Handler takes joinpoint if -locationFromAjc on
            if (com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc) {
                sb.append("org.aspectj.lang.JoinPoint.StaticPart joinpoint");
            }
            sb.append(") {\n");
            // Statistics: bump per-category counter (mirrors stock's
            // HandlerMethod, which puts categoryInc at the top of each handler).
            if (com.runtimeverification.rvmonitor.java.rvj.Main.options.statistics) {
                sb.append("\t\t").append(stat.categoryInc(prop, category).trim()).append("\n");
            }
            if (body != null) {
                sb.append("\t\t").append(macroSub(body).replace("\n", "\n\t\t")).append("\n");
            }
            sb.append("\t}\n\n");
        }
        return sb.toString();
    }

    /** Substitute logic-plugin placeholders ($state$, $transition_foo$, etc.) with
     *  property-prefixed names (Prop_<id>_state, Prop_<id>_transition_foo, ...). */
    private String substitute(String s, PropertyAndHandlers prop) {
        if (s == null) return null;
        int propId = prop.getPropertyId();
        // The logic plugin emits placeholders enclosed in $...$. Replace each
        // with Prop_<id>_<placeholder>.
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf('$', i);
            if (start < 0) {
                out.append(s, i, s.length());
                break;
            }
            out.append(s, i, start);
            int end = s.indexOf('$', start + 1);
            if (end < 0) {
                out.append(s, start, s.length());
                break;
            }
            String inner = s.substring(start + 1, end);
            out.append("Prop_").append(propId).append("_").append(inner);
            i = end + 1;
        }
        return macroSub(out.toString());
    }

    /** Substitute MOP macros (__RESET, __DEFAULT_MESSAGE, etc.) like stock does.
     *  Also rewrites bare {@code return;} statements (which user actions write
     *  expecting void event semantics) to {@code return true;} for our
     *  boolean event methods — matches the {@code !generateVoidMethods}
     *  branch in {@code BaseMonitor.printEventMethod}. */
    private String macroSub(String s) {
        if (s == null) return null;
        // Match stock's macros from BaseMonitor/Util/Monitor:
        //   __DEFAULT_MESSAGE → "Specification <Name> has been violated on line " + <LOC> +
        //                      ". Documentation for this property can be found at <URL>"
        //   __LOC             → joinpoint.getSourceLocation().getWithinType().getName() + "@" +
        //                      joinpoint.getSourceLocation().toString()
        //                      (only valid when joinpoint is in scope, i.e. -locationFromAjc;
        //                       falls back to "<unknown>" otherwise.)
        boolean haveJp = com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc;
        String loc = haveJp
            ? "joinpoint.getSourceLocation().getWithinType().getName() + \"@\" + joinpoint.getSourceLocation().toString()"
            : "\"<unknown>\"";
        String docUrl = "https://github.com/SoftEngResearch/tracemop/tree/master/scripts/props/"
                      + specName + ".mop";
        String defaultMsg = "\"Specification " + specName + " has been violated on line \" + "
                          + loc
                          + " + \". Documentation for this property can be found at " + docUrl + "\"";
        // Important: substitute __DEFAULT_MESSAGE *before* __LOC, since the
        // default-message template contains __LOC unexpanded.
        return s
            .replaceAll("return;", "return true;")
            .replaceAll("__RESET", "this.reset()")
            .replaceAll("__DEFAULT_MESSAGE", java.util.regex.Matcher.quoteReplacement(defaultMsg))
            .replaceAll("__LOC", java.util.regex.Matcher.quoteReplacement(loc))
            .replaceAll("__SKIP", "/* skip */");
    }

    private String emitSetEventMethod(EventDefinition event) {
        StringBuilder sb = new StringBuilder();
        sb.append("\tfinal void event_").append(event.getId()).append("(")
          .append(eventParamSignature(event)).append(") {\n");
        sb.append("\t\tfor (int i_1 = 0; i_1 < this.size; i_1++) {\n");
        sb.append("\t\t\t").append(monitorName).append(" monitor = this.elements[i_1];\n");
        sb.append("\t\t\tfinal ").append(monitorName).append(" monitorfinalMonitor = monitor;\n");
        // Dispatch to monitor — call the right event method name based on raw/property
        if (spec.getPropertiesAndHandlers().isEmpty()) {
            sb.append("\t\t\tmonitor.event_").append(event.getId()).append("(")
              .append(eventCallArgs(event)).append(");\n");
        } else {
            int propId = spec.getPropertiesAndHandlers().get(0).getPropertyId();
            sb.append("\t\t\tmonitor.Prop_").append(propId).append("_event_").append(event.getId()).append("(")
              .append(eventCallArgs(event)).append(");\n");
            // Category handler dispatch — mirror the static dispatch path
            for (String cat : spec.getPropertiesAndHandlers().get(0).getHandlers().keySet()) {
                if (cat.equals("deadlock")) continue;
                sb.append("\t\t\tif (monitorfinalMonitor.Prop_").append(propId)
                  .append("_Category_").append(cat).append(") {\n");
                sb.append("\t\t\t\tmonitorfinalMonitor.Prop_").append(propId)
                  .append("_handler_").append(cat).append("(");
                if (com.runtimeverification.rvmonitor.java.rvj.Main.options.locationFromAjc) {
                    sb.append("joinpoint");
                }
                sb.append(");\n");
                sb.append("\t\t\t}\n");
            }
        }
        sb.append("\t\t}\n");
        sb.append("\t}\n");
        return sb.toString();
    }

    /** Signature: "Type1 name1, Type2 name2, ..." with optional joinpoint prefix. */
    String eventParamSignature(EventDefinition event) {
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

    /** Call args: "name1, name2, ..." with optional joinpoint prefix. */
    String eventCallArgs(EventDefinition event) {
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
}
