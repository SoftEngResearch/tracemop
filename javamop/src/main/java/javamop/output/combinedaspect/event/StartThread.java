// Copyright (c) 2002-2014 JavaMOP Team. All Rights Reserved.
package javamop.output.combinedaspect.event;

import javamop.JavaMOPMain;
import javamop.util.MOPException;
import javamop.output.MOPVariable;
import javamop.output.combinedaspect.CombinedAspect;
import javamop.output.combinedaspect.GlobalLock;
import javamop.output.combinedaspect.event.advice.AdviceBody;
import javamop.parser.ast.mopspec.EventDefinition;
import javamop.parser.ast.mopspec.JavaMOPSpec;

/**
 * Hooks for startThread events.
 */
public class StartThread {
    private final JavaMOPSpec mopSpec;
    private final EventDefinition event;
    private final GlobalLock globalLock;
    
    private final AdviceBody eventBody;
    
    private final MOPVariable runnableMap;
    private final MOPVariable mainThread;
    
    private final MOPVariable commonPointcut = new MOPVariable("MOP_CommonPointCut");

    /**
     * Construct a StartThread hook for a particular event.
     * @param mopSpec The specification the event is part of.
     * @param event The startThread event.
     * @param combinedAspect The generated code that this will be part of.
     */
    public StartThread(final JavaMOPSpec mopSpec, final EventDefinition event, 
            final CombinedAspect combinedAspect) throws MOPException {
        if (!event.isStartThread())
            throw new MOPException("StartThread should be defined only " +
                "for an startThread pointcut.");
        
        this.mopSpec = mopSpec;
        this.event = event;
        this.globalLock = combinedAspect.lockManager.getLock();
        this.runnableMap = new MOPVariable(mopSpec.getName() + "_" + 
            event.getId() + "_ThreadToRunnable");
        this.mainThread = new MOPVariable(mopSpec.getName() + "_" + 
            event.getId() + "_MainThread");
        
        this.eventBody = new AdviceBody(mopSpec, event, combinedAspect);
    }
    
    /**
     * The datastructures needed to trigger the startThread events.
     * @return Java code with necessary datastructure declarations.
     */
    public String printDataStructures() {
        String ret = "";
        
        ret += "static HashMap<Thread, Runnable> " + runnableMap + 
            " = new HashMap<Thread, Runnable>();\n";
        ret += "static Thread " + mainThread + " = null;\n";
        return ret;
    }
    
    /**
     * The AspectJ hook to keep track of all Runnables initialized with threads.
     * @return AspectJ advice to register Runnables in a global table.
     */
    public String printAdviceForThreadWithRunnable() {
        String ret = "";
        
        ret += "after (Runnable r) returning (Thread t): ";
        ret += "(";
        ret += "(call(Thread+.new(Runnable+,..)) && args(r,..))";
        ret += "|| (initialization(Thread+.new(ThreadGroup+, Runnable+,..)) " +
            "&& args(ThreadGroup, r,..)))";
        ret += " && " + commonPointcut + "() {\n";
        ret += globalLock.getAcquireCode();
    	ret += runnableMap + ".put(t, r);\n";
        ret += globalLock.getReleaseCode();
    	ret += "}\n";
        
        return ret;
    }
    
    /**
     * The AspectJ hook to trigger the startThread event on new threads starting.
     * @return AspectJ advice that triggers the startThread event.
     */
    public String printAdviceForStartThread() {
        String ret = "";
        MOPVariable threadVar = new MOPVariable("t");
        
        ret += "before (Thread " + threadVar + "): ( execution(void Thread+.run()) && target(" + 
            threadVar + ") )";
        ret += " && " + commonPointcut + "() {\n";
    
        if (JavaMOPMain.options.valg) {     
        	if (mopSpec.getParameters().size() > 0) {
        	    ret += "int objHash = System.identityHashCode(" + threadVar + ");\n";
        	} else {
        	    ret += "int locHash = System.identityHashCode(thisJoinPointStaticPart.getSourceLocation());\n";
        	}
        }
        ret += globalLock.getAcquireCode();
        if (JavaMOPMain.options.valg) {
        	if (mopSpec.getParameters().size() > 0) {
        	    ret += "if (!objIds.contains(objHash)) {\n";
        	} else {
        	    ret += "if (!violationPoints.contains(locHash)) {\n";
        	}
        }

        ret += "if(Thread.currentThread() == " + threadVar + ") {\n";
        if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
            ret += "Thread " + event.getThreadVar() + " = Thread.currentThread();\n";
        }
        
        if (JavaMOPMain.options.valg) { 
        	ret += "boolean retValue = ";
        }
        ret += EventManager.EventMethodHelper.methodName(eventBody.getMOPSpec(), event,
            eventBody.fileName);
        ret += "(";
        if (JavaMOPMain.options.internalBehaviorObserving || JavaMOPMain.options.locationFromAjc) {
            ret += "thisJoinPointStaticPart";

            if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
                ret += ", " + event.getThreadVar();
            }
        } else {
            if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
                ret += event.getThreadVar();
            }
        }
        ret += ");\n";
     
        if (JavaMOPMain.options.valg) {  
            ret += "if (!retValue) {\n";
            if (mopSpec.getParameters().size() > 0) {
        	    ret += "objIds.add(objHash);\n";
            } else {
                ret += "violationPoints.add(locHash);\n";
        	}	    
        }
        ret += "}\n";
        
        if (JavaMOPMain.options.valg) {
        	ret += "}\n}\n";
        }
        ret += globalLock.getReleaseCode();
        ret += "}\n";
        
        return ret;
    }
    
    /**
     * The AspectJ hook to trigger the startThread event on Runnables running.
     * @return AspectJ advice that triggers the startThread event.
     */
    public String printAdviceForStartRunnable() {
        String ret = "";
        MOPVariable runnableVar = new MOPVariable("r");
        
        ret += "before (Runnable " + runnableVar + "): ( execution(void Runnable+.run()) " +
            "&& !execution(void Thread+.run()) && target(" + runnableVar + ") )";
        ret += " && " + commonPointcut + "() {\n";
      
        if (JavaMOPMain.options.valg) { 
        	if (mopSpec.getParameters().size() > 0) {
        	    ret += "int objHash = System.identityHashCode(" + runnableVar + ");\n";
        	} else {
        	    ret += "int locHash = System.identityHashCode(thisJoinPointStaticPart.getSourceLocation());\n";
        	}
        }
    	ret += globalLock.getAcquireCode();
        if (JavaMOPMain.options.valg) {
        	if (mopSpec.getParameters().size() > 0) {
        	    ret += "if (!objIds.contains(objHash)) {\n";
        	} else {
        	    ret += "if (!violationPoints.contains(locHash)) {\n";
        	}
        }

    	ret += "if(" + runnableMap + ".get(Thread.currentThread()) == " + runnableVar + ") {\n";
        if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
            ret += "Thread " + event.getThreadVar() + " = Thread.currentThread();\n";
        }
        if (JavaMOPMain.options.valg) {    
        	ret += "boolean retValue = ";
        }
        ret += EventManager.EventMethodHelper.methodName(eventBody.getMOPSpec(), event,
            eventBody.fileName);
        ret += "(";
        if (JavaMOPMain.options.internalBehaviorObserving || JavaMOPMain.options.locationFromAjc) {
            ret += "thisJoinPointStaticPart";

            if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
                ret += ", " + event.getThreadVar();
            }
        } else {
            if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
                ret += event.getThreadVar();
            }
        }
        ret += ");\n";
      
        if (JavaMOPMain.options.valg) {
           	ret += "if (!retValue) {\n";
        	if (mopSpec.getParameters().size() > 0) {
        	    ret += "objIds.add(objHash);\n";
            } else {
                ret += "violationPoints.add(locHash);\n";
            }	    
        }
        ret += "}\n";

        if (JavaMOPMain.options.valg) {
        	ret += "}\n}\n";
        }
    	ret += globalLock.getReleaseCode();        
        ret += "}\n";
        return ret;
    }
    
    /**
     * AspectJ hook to trigger the startThread event for the main thread.
     * @return AspectJ advice that triggers the startThread event.
     */
    public String printAdviceForMainStart() {
        String ret = "";
        
        ret += "before (): " + "(execution(void *.main(..)) )";
        ret += " && " + commonPointcut + "() {\n";
    	ret += globalLock.getAcquireCode();
        ret += "if(" + mainThread + " == null){\n";
        ret += mainThread + " = Thread.currentThread();\n";
        
        if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
            ret += "Thread " + event.getThreadVar() + " = Thread.currentThread();\n";
        }
        
        ret += EventManager.EventMethodHelper.methodName(eventBody.getMOPSpec(), event,
            eventBody.fileName);
        ret += "(";
        if (JavaMOPMain.options.internalBehaviorObserving || JavaMOPMain.options.locationFromAjc) {
            ret += "thisJoinPointStaticPart";

            if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
                ret += ", " + event.getThreadVar();
            }
        } else {
            if (event.getThreadVar() != null && event.getThreadVar().length() != 0) {
                ret += event.getThreadVar();
            }
        }
        ret += ");\n";
        
        ret += "}\n";
    	ret += globalLock.getReleaseCode();
        ret += "}\n";
        ret += "\n";
        
        return ret;
    }
    
    /**
     * Aggregate all AspectJ code together.
     * @return All AspectJ advice relevant to the startThread event.
     */
    public String printAdvices() {
        String ret = "";
        
        ret += printDataStructures();
        ret += "\n";
        ret += printAdviceForThreadWithRunnable();
        ret += "\n";
        ret += printAdviceForStartThread();
        ret += "\n";
        ret += printAdviceForStartRunnable();
        ret += "\n";
        ret += printAdviceForMainStart();
        
        return ret;
    }
    
}
