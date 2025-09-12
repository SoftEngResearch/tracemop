package com.runtimeverification.rvmonitor.java.rvj.output.combinedoutputcode;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

import com.runtimeverification.rvmonitor.java.rvj.output.RVMVariable;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.rvmspec.RVMonitorSpec;
import com.runtimeverification.rvmonitor.util.RVMException;

public class LockManager {

    // HashMap<RVMonitorSpec, GlobalLock> locks = new HashMap<RVMonitorSpec,
    // GlobalLock>();

    private final HashMap<String, GlobalLock> lockMap = new HashMap<String, GlobalLock>();

    public LockManager(String name, List<RVMonitorSpec> specs)
            throws RVMException {
        // for (RVMonitorSpec spec : specs) {
        // if (spec.isSync())
        // locks.put(spec, new GlobalLock(new RVMVariable(spec.getName() +
        // "_RVMLock")));
        // }

    	for (RVMonitorSpec spec: specs) {
	    lockMap.put(spec.getName(), new GlobalLock(new RVMVariable(name + "_RVMLock" + "_" + spec.getName())));
	}
    }

    /*
     * public GlobalLock getLock(RVMonitorSpec spec){ return locks.get(spec); }
     */
    public GlobalLock getLock(String name) {
	return lockMap.get(name);
    }

    public String decl() {
        String ret = "";

        /*
         * if (locks.size() <= 0) return ret;
         */
        /*
         * ret += "// Declarations for Locks \n"; for (GlobalLock lock :
         * locks.values()) { ret += lock; } ret += "\n";
         */
        ret += "// Declarations for the Lock \n";
	for(Map.Entry<String, GlobalLock> entry : lockMap.entrySet()) {
	    GlobalLock lock = entry.getValue();
            ret += lock;
	}
        ret += "\n";

        return ret;
    }

}
