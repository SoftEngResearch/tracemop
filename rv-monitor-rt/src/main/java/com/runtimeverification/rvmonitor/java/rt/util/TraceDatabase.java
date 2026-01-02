package com.runtimeverification.rvmonitor.java.rt.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TraceDatabase implements TraceDB {

    private static TraceDatabase instance = null;
    private final TraceDB database;

    // com.runtimeverification.rvmonitor.java.rt.util.TraceDatabase.getInstance().randomFileName
    public String randomFileName = "";

    public static TraceDatabase getInstance()  {
        if (instance == null)
            instance = new TraceDatabase();

        return instance;
    }

    private TraceDatabase() {
        if (System.getenv("TRACEMOP_DB") == null || System.getenv("TRACEMOP_DB").equals("trie")) {
            database = new TraceDBTrie();
        } else if (System.getenv("TRACEMOP_DB").equals("compact")) {
            database = new TraceDBTrieCompact();
        } else {
            database = new TraceDBMemory();
        }

        if (System.getenv("TRACEDB_RANDOM") != null && System.getenv("TRACEDB_RANDOM").equals("1")) {
            randomFileName = "-" + UUID.randomUUID();
            System.err.println("[TraceDB] Random directory name is: " + randomFileName);
        }

        try {
            Files.createDirectories(Paths.get(System.getenv("TRACEDB_PATH") + randomFileName));
        } catch (IOException e) {
            System.err.println("Unable to create directory");
            e.printStackTrace();
        }
    }

    public void calledHandler(String monitorID) {
        System.out.println("monitorID: " + monitorID + ": called handler");
        System.out.println("Trace: " + database.get(monitorID));
    }

    @Override
    public void put(String monitorID, String trace, int length) {
        database.put(monitorID, trace, length);
    }

    @Override
    public void update(String monitorID, String trace, int length) {
        database.update(monitorID, trace, length);
    }

    @Override
    public void replace(String monitorID, List<String> trace) {
        database.replace(monitorID, trace);
    }

    @Override
    public void add(String monitorID, String event) {
        database.add(monitorID, event);
    }

    @Override
    public void addRaw(String monitorID, String event) {
        database.addRaw(monitorID, event);
    }

    @Override
    public void cloneMonitor(String oldMonitor, String newMonitor) {
        database.cloneMonitor(oldMonitor, newMonitor);
    }

    @Override
    public void cloneMonitorRaw(String oldMonitor, String newMonitor) {
        database.cloneMonitorRaw(oldMonitor, newMonitor);
    }

    @Override
    public void setCurrentTest(String test) {
        database.setCurrentTest(test);
    }

    @Override
    public List<String> computeTimeSeries(List<String> monitors) {
        return database.computeTimeSeries(monitors);
    }

    @Override
    public void createTable() {
        database.createTable();
    }

    @Override
    public int uniqueTraces() {
        return database.uniqueTraces();
    }

    @Override
    public int size() {
        return database.size();
    }

    @Override
    public List<Integer> getTraceLengths() {
        return database.getTraceLengths();
    }

    @Override
    public Map<String, Integer> getTraceFrequencies() {
        return database.getTraceFrequencies();
    }

    @Override
    public void dump() {
        database.dump();
    }
}
