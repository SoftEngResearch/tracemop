package mop;
import org.aspectj.lang.*;
import java.util.*;

public aspect TestNameAspect {
    public static Stack<String> tests = new Stack<>();
    public static String testName = "";

    pointcut testExec() : execution(@(*..Test || *..Before || *..After) * *(..)) && !adviceexecution() && BaseAspect.notwithin();
    before() : testExec() {
        String name = thisJoinPointStaticPart.getSourceLocation().getWithinType().getName() + "." +
                        thisJoinPointStaticPart.getSignature().getName() + "(" + 
                        thisJoinPointStaticPart.getSourceLocation().toString() + ")";
        
        System.out.println("[TraceMOP] Running test " + name);
        testName = name;
        tests.push(name);
        com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().setCurrentTest(testName);
    }

    after() : testExec() {
        String name = thisJoinPointStaticPart.getSourceLocation().getWithinType().getName() + "." +
                        thisJoinPointStaticPart.getSignature().getName() + "(" + 
                        thisJoinPointStaticPart.getSourceLocation().toString() + ")";

        System.out.println("[TraceMOP] Finishing test " + name);
        if (!testName.isEmpty()) {
            if (!tests.isEmpty()) {
                try {
                    tests.pop();
                    if (!tests.isEmpty()) {
                        testName = tests.peek();
                    } else {
                        testName = "";
                    }
                } catch (EmptyStackException e) {
                    System.err.println("EmptyStackException");
                    testName = "";
                }
            } else {
                testName = "";
            }
            com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().setCurrentTest(testName);
        }
    }

    pointcut testExec2() : execution(* test*(..)) && within(junit.framework.TestCase+) && !adviceexecution() && BaseAspect.notwithin();
    before() : testExec2() {
        String name = thisJoinPointStaticPart.getSourceLocation().getWithinType().getName() + "." +
                        thisJoinPointStaticPart.getSignature().getName() + "(" + 
                        thisJoinPointStaticPart.getSourceLocation().toString() + ")";
                        
        System.out.println("[TraceMOP] Running test " + name);
        testName = name;
        tests.push(name);
        com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().setCurrentTest(testName);
    }

    after() : testExec2() {
        String name = thisJoinPointStaticPart.getSourceLocation().getWithinType().getName() + "." +
                        thisJoinPointStaticPart.getSignature().getName() + "(" + 
                        thisJoinPointStaticPart.getSourceLocation().toString() + ")";
                        
        System.out.println("[TraceMOP] Finishing test " + name);
        if (!testName.isEmpty()) {
            if (!tests.isEmpty()) {
                try {
                    tests.pop();
                    if (!tests.isEmpty()) {
                        testName = tests.peek();
                    } else {
                        testName = "";
                    }
                } catch (EmptyStackException e) {
                    System.err.println("EmptyStackException");
                    testName = "";
                }
            } else {
                testName = "";
            }
            com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().setCurrentTest(testName);
        }
    }
}
