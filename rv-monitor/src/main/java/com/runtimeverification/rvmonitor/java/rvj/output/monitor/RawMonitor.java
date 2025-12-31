package com.runtimeverification.rvmonitor.java.rvj.output.monitor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

import com.runtimeverification.rvmonitor.java.rvj.Main;
import com.runtimeverification.rvmonitor.java.rvj.SpecConfig;
import com.runtimeverification.rvmonitor.java.rvj.output.OptimizedCoenableSet;
import com.runtimeverification.rvmonitor.java.rvj.output.RVMJavaCode;
import com.runtimeverification.rvmonitor.java.rvj.output.RVMVariable;
import com.runtimeverification.rvmonitor.java.rvj.output.UserJavaCode;
import com.runtimeverification.rvmonitor.java.rvj.output.Util;
import com.runtimeverification.rvmonitor.java.rvj.output.combinedoutputcode.GlobalLock;
import com.runtimeverification.rvmonitor.java.rvj.output.combinedoutputcode.indexingtree.reftree.RefTree;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.EventDefinition;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMParameters;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMonitorSpec;
import com.runtimeverification.rvmonitor.util.RVMException;

public class RawMonitor extends Monitor {

    private final RVMVariable activity = new RVMVariable("RVM_activity");
    private final RVMVariable lastevent = new RVMVariable("RVM_lastevent");
    private final List<EventDefinition> events;

    private final UserJavaCode monitorDeclaration;

    public RawMonitor(String outputName, RVMonitorSpec rvmSpec,
            OptimizedCoenableSet coenableSet, boolean isOutermost)
                    throws RVMException {
        super(outputName, rvmSpec, coenableSet, isOutermost);

        isDefined = true;

        this.monitorName = new RVMVariable(rvmSpec.getName() + "Monitor");

        if (isOutermost) {
            varInOutermostMonitor = new VarInOutermostMonitor(outputName,
                    rvmSpec, rvmSpec.getEvents());
            monitorTermination = new MonitorTermination(outputName, rvmSpec,
                    rvmSpec.getEvents(), coenableSet);
        }

        monitorDeclaration = new UserJavaCode(rvmSpec.getDeclarationsStr());

        events = rvmSpec.getEvents();

        if (isDefined && rvmSpec.isGeneral()) {
            if (rvmSpec.isFullBinding() || rvmSpec.isConnected())
                monitorInfo = new MonitorInfo(rvmSpec);
        }
    }

    @Override
    public void setRefTrees(TreeMap<String, RefTree> refTrees) {
        this.refTrees = refTrees;

        if (monitorTermination != null)
            monitorTermination.setRefTrees(refTrees);
    }

    @Override
    public RVMVariable getOutermostName() {
        return monitorName;
    }

    @Override
    public Set<String> getNames() {
        Set<String> ret = new HashSet<String>();

        ret.add(monitorName.toString());
        return ret;
    }

    @Override
    public Set<RVMVariable> getCategoryVars() {
        HashSet<RVMVariable> ret = new HashSet<RVMVariable>();
        return ret;
    }

    public String doEvent(EventDefinition event) {
        String ret = "";

        int idnum = event.getIdNum();
        RVMJavaCode condition = new RVMJavaCode(event.getCondition(),
                monitorName);
        RVMJavaCode eventAction = null;

        if (event.getAction() != null) {
            String eventActionStr = event.getAction();

            if (!Main.options.generateVoidMethods) {
                eventActionStr = eventActionStr.replaceAll("return;",
                        "return true;");
            }
            eventActionStr = eventActionStr.replaceAll("__RESET",
                    "this.reset()");
            eventActionStr = eventActionStr.replaceAll("__DEFAULT_MESSAGE",
                    defaultMessage);
            // __DEFAULT_MESSAGE may contain __LOC, make sure to sub in
            // __DEFAULT_MESSAGE first
            // -P
            eventActionStr = eventActionStr.replaceAll("__LOC",
                    Util.getDefaultLocation());
            eventActionStr = eventActionStr.replaceAll("__ACTIVITY", "this."
                    + activity);
            eventActionStr = eventActionStr.replaceAll("__SKIP",
                    BaseMonitor.skipEvent + " = true");

            if (Main.options.valg || Main.options.traj) { 
                SpecConfig config = Main.options.specConfigMap.get(this.getOutputName());
                if (config != null && !config.disabled) {
            	    int idx = eventActionStr.indexOf("has been violated on line");
            	    while (idx > 0) {
                		idx = eventActionStr.indexOf("}", idx);
                		eventActionStr = eventActionStr.substring(0, idx) +
                		"violated = true;\n" +
                		eventActionStr.substring(idx);
                		idx = eventActionStr.indexOf("has been violated on line", idx);
            	    }
                }
            }
            eventAction = new RVMJavaCode(eventActionStr);
        }

        boolean retbool = !Main.options.generateVoidMethods;
        String synch = Main.options.finegrainedlock ? " synchronized " : " ";
        ret += "final" + synch + (retbool ? "boolean" : "void") + " event_"
                + event.getId() + "(";
        {
            if (Main.options.internalBehaviorObserving || Main.options.locationFromAjc) {
                ret += "org.aspectj.lang.JoinPoint.StaticPart joinpoint, ";
            }
            RVMParameters params;
            if (Main.options.stripUnusedParameterInMonitor)
                params = event.getReferredParameters(event.getRVMParameters());
            else
                params = event.getRVMParameters();
            ret += params.parameterDeclString();
            if (ret.endsWith(", ")) {
                ret = ret.substring(0, ret.length() - 2);
            }
        }
        ret += ") {\n";
        if (has__SKIP)
            ret += "boolean " + BaseMonitor.skipEvent + " = false;\n";

        if (Main.options.internalBehaviorObserving) {
            // Receive new event, notify traceDB
            ret += "com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().addRaw(specName + \"#\" + this.monitorid, \"";
            if (Main.options.trackEventLocations) {
                ret += event.getId() + "\" + \"~\" + TraceUtil.getShortLocation(joinpoint)";
            } else {
                ret += event.getId() + "\"";
            }
            ret += ");\n";
        }
        if (!condition.isEmpty()) {
            ret += "if (!(" + condition + ")) {\n";
            if (Main.options.generateVoidMethods)
                ret += "return;\n";
            else
                ret += "return false;\n";
            ret += "}\n";
        }

        if (isOutermost) {
            ret += lastevent + " = " + idnum + ";\n";
        }

        if (eventAction != null)
            ret += eventAction;

        if (!Main.options.generateVoidMethods)
            ret += "return true;\n";
        ret += "}\n";

        return ret;
    }

    @Override
    public String Monitoring(RVMVariable monitorVar, EventDefinition event,
            RVMVariable loc, GlobalLock l, String outputName,
            boolean inMonitorSet) {
        String ret = "";

        // if (has__LOC) {
        // if(loc != null)
        // ret += monitorVar + "." + this.loc + " = " + loc + ";\n";
        // else
        // ret += monitorVar + "." + this.loc + " = " +
        // "Thread.currentThread().getStackTrace()[2].toString()"
        // + ";\n";
        // i
		if (!inMonitorSet) {
		    ret += monitorVar + ".violated = false;\n";
		}
        ret += monitorVar + ".event_" + event.getId() + "(";
        {
            if (Main.options.internalBehaviorObserving || Main.options.locationFromAjc) {
                ret += "joinpoint, ";
            }

            RVMParameters params;
            if (Main.options.stripUnusedParameterInMonitor)
                params = event.getReferredParameters(event.getRVMParameters());
            else
                params = event.getRVMParameters();
            ret += params.parameterString();

            if (ret.endsWith(", ")) {
                ret = ret.substring(0, ret.length() - 2);
            }
        }
        ret += ");\n";
		if (!inMonitorSet) {
		    ret += "\nif (" + monitorVar + ".violated) {\n";
		    ret += "return false;\n";
		    ret += "}\n";
		}
        return ret;
    }

    @Override
    public MonitorInfo getMonitorInfo() {
        return monitorInfo;
    }

    @Override
    public String toString() {
        String synch = Main.options.finegrainedlock ? " synchronized " : " ";
        String ret = "";

        ret += "class " + monitorName;
        if (isOutermost)
            ret += " extends com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractSynchronizedMonitor";
        ret += " implements Cloneable, com.runtimeverification.rvmonitor.java.rt.RVMObject";
        if (Main.options.internalBehaviorObserving)
            ret += ", IObservableObject";

        ret +=  " {\n";

        if (varInOutermostMonitor != null)
            ret += varInOutermostMonitor;

        // clone()
        ret += "protected Object clone() {\n";
        if (Main.options.statistics) {
            ret += stat.incNumMonitor();
        }
        ret += "try {\n";
        ret += monitorName + " ret = (" + monitorName + ") super.clone();\n";
        if (monitorInfo != null)
            ret += monitorInfo.copy("ret", "this");
        if (Main.options.internalBehaviorObserving) {
            ret += "ret.monitorid = nextid.incrementAndGet();\n";
            // Clone monitor, notify traceDB
            ret += "com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().cloneMonitorRaw(";
            ret += "specName + \"#\" + this.monitorid, specName + \"#\" + ret.monitorid";
            ret += ");\n";

//            ret += "ret.trace = new ArrayList<String>();\n";
//            ret += "ret.trace.addAll(this.trace);\n";
        }
        ret += "return ret;\n";
        ret += "}\n";
        ret += "catch (CloneNotSupportedException e) {\n";
        ret += "throw new InternalError(e.toString());\n";
        ret += "}\n";
        ret += "}\n";

        if (Main.options.internalBehaviorObserving) {
//            ret += "private List<String> trace;\n";
//            ret += "public List<String> getTrace(){ return this.trace; };\n";
//            ret += "private int monitorid;\n";
//            ret += "public int getMonitorID(){ return this.monitorid; };\n";
            ret += "private static java.util.concurrent.atomic.AtomicInteger nextid = new java.util.concurrent.atomic.AtomicInteger(0);\n";
            ret += "public static String specName = \"" + this.monitorName + "\";\n";

            ret += "\n";
            ret += "@Override\n";
            ret += "public final String getObservableObjectDescription() {\n";
            ret += "StringBuilder s = new StringBuilder();\n";
            ret += "s.append('#');\n";
            ret += "s.append(this.monitorid);\n";
//            if (feature.isTimeTrackingNeeded()) {
//                ret += "s.append(\"{t:\");\n";
//                ret += "s.append(this.tau);\n";
//                ret += "s.append(\",dis:\");\n";
//                ret += "s.append(this.disable);\n";
//                ret += "s.append('}');\n";
//            }
//            ret += "s.append('[');\n";
//            ret += "for (int i = 0; i < this.trace.size(); ++i) {\n";
//            ret += "if (i > 0)\n";
//            ret += "s.append(',');\n";
//            ret += "s.append(this.trace.get(i));\n";
//            ret += "}\n";
//            ret += "s.append(']');\n";
            ret += "return s.toString();\n";
            ret += "}\n";
        }


        ret += monitorDeclaration + "\n";
        if (this.has__ACTIVITY)
            ret += activityCode();

        if (Main.options.statistics) {
            ret += stat.fieldDecl() + "\n";
        }
        if (Main.options.valg || Main.options.traj) { 
            SpecConfig config = Main.options.specConfigMap.get(this.getOutputName());
            if (config != null && !config.disabled) {
            	ret += "public boolean violated;\n";
            }
        }
        //constructor
        ret += monitorName + "(){\n";
        if (Main.options.statistics) {
            ret += stat.incNumMonitor();
        }
        if (Main.options.internalBehaviorObserving) {
//            ret += "this.trace = new ArrayList<String>();\n";
            ret += "this.monitorid = nextid.incrementAndGet();\n";
        }
        ret += "}\n";

        if (Main.options.statistics) {
            ret += stat.methodDecl() + "\n";
        }
        // if (this.has__LOC)
        // ret += "String " + loc + ";\n";
        // implements getState(), which returns -1
        {
            ret += "@Override\n";
            ret += "public final int getState() {\n";
            ret += "return -1;\n";
            ret += "}\n\n";
        }

        // events
        for (EventDefinition event : this.events) {
            ret += this.doEvent(event) + "\n";
        }

        // reset
        ret += "final" + synch + "void reset() {\n";
        if (isOutermost) {
            ret += lastevent + " = -1;\n";
        }
        ret += "}\n";
        ret += "\n";

        // endObject and some declarations
        if (isOutermost) {
            ret += monitorTermination.getCode(this.getFeatures(), null, null);
            ret += "\n";
        }

        if (monitorInfo != null)
            ret += monitorInfo.monitorDecl();

        ret += "}\n";

        return ret;
    }
}
