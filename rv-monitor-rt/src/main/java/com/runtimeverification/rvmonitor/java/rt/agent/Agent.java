package com.runtimeverification.rvmonitor.java.rt.agent;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;

public interface Agent {

    boolean decideAction();

    default void setMonitor(AbstractMonitor monitor) {}

    default void clearMonitor() {}
}

