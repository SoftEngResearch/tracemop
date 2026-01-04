package com.runtimeverification.rvmonitor.java.rt.util;

import java.util.List;
import java.util.Map;
import java.util.Collections;

public interface TraceDB {

    void put(String monitorID, String trace, int length);

    void update(String monitorID, String trace, int length);

    default void replace(String monitorID, List<String> trace) {}

    default void add(String monitorID, String event) {}
    default void addRaw(String monitorID, String event) {}

    default void cloneMonitor(String oldMonitor, String newMonitor) {}
    default void cloneMonitorRaw(String oldMonitor, String newMonitor) {}

    default void setCurrentTest(String test) {}

    default List<String> get(String monitorID) { return null; }

    default List<String> computeTimeSeries(String specName, List<Integer> monitors) { return Collections.emptyList(); }

    void createTable();

    int uniqueTraces();

    int size();

    List<Integer> getTraceLengths();

    Map<String, Integer> getTraceFrequencies();

    void dump();

}
