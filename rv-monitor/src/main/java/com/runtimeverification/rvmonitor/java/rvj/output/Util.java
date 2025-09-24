package com.runtimeverification.rvmonitor.java.rvj.output;

import com.runtimeverification.rvmonitor.java.rvj.Main;
import com.runtimeverification.rvmonitor.java.rvj.parser.ast.PackageDeclaration;

public class Util {

    public static String getDefaultLocation() {
        return "joinpoint.getSourceLocation().getWithinType().getName() + \"@\" + joinpoint.getSourceLocation().toString()";
    }

    public static String packageAndNameToUrl(
            PackageDeclaration packageDeclaration, String name) {
        return "https://github.com/SoftEngResearch/tracemop/tree/master/scripts/props/"
                + name
                + ".mop";
    }
}
