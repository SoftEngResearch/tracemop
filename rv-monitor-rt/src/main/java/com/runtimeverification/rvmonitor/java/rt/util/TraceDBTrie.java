package com.runtimeverification.rvmonitor.java.rt.util;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class TraceDBTrie implements TraceDB {

    /* Non-raw specs */
    private final EventNode root = new EventNode("");
    private final HashMap<String, EventNode> monitorsMap = new HashMap<>();
    private final HashMap<String, List<RawEventNode>> rawMonitorsMap = new HashMap<>();
    private final HashMap<String, String> monitorFirstTestMap = new HashMap<>();

    ReentrantLock lock = new ReentrantLock();
    boolean allowUpdate = true;
    private String currentTest = "";


    public TraceDBTrie() {
        System.err.println("[TraceDBTrie] Set dbFilePath to: memory!");
    }

    @Override
    public void put(String monitorID, String trace, int length) {}

    @Override
    public void update(String monitorID, String trace, int length) {
    }

    @Override
    public void add(String monitorID, String event) {
        lock.lock();
        if (allowUpdate) {
            EventNode node;
            if (monitorsMap.containsKey(monitorID)) {
                node = monitorsMap.get(monitorID).getNextNodeAfterSeeingEvent(event);
            } else {
                // new monitor
                node = root.getNextNodeAfterSeeingEvent(event);
                monitorFirstTestMap.put(monitorID, currentTest);
            }
            monitorsMap.put(monitorID, node);
        }
        lock.unlock();
    }

    @Override
    public void addRaw(String monitorID, String event) {
        lock.lock();
//        if (allowUpdate) {
//            rawMonitorsMap.computeIfAbsent(monitorID, k -> new ArrayList<>()).add(event);
//        }
        if (allowUpdate) {
            if (rawMonitorsMap.containsKey(monitorID)) {
                List<RawEventNode> trace = rawMonitorsMap.get(monitorID);
                RawEventNode lastEvent = trace.get(trace.size() - 1);
                if (event.equals(lastEvent.event)) {
                    lastEvent.frequency += 1;
                } else {
                    rawMonitorsMap.get(monitorID).add(new RawEventNode(event));
                }
            } else {
                // new monitor
                List<RawEventNode> events = new ArrayList<>();
                events.add(new RawEventNode(event));
                rawMonitorsMap.put(monitorID, events);
                monitorFirstTestMap.put(monitorID, currentTest);
            }
        }
        lock.unlock();
    }

    @Override
    public void cloneMonitor(String oldMonitor, String newMonitor) {
        lock.lock();
        if (allowUpdate) {
            monitorsMap.put(newMonitor, monitorsMap.get(oldMonitor));
            monitorFirstTestMap.put(newMonitor, currentTest);
        }
        lock.unlock();
    }

    @Override
    public void cloneMonitorRaw(String oldMonitor, String newMonitor) {
        lock.lock();
        if (allowUpdate && rawMonitorsMap.containsKey(oldMonitor)) {
            // TODO: Can you even clone raw monitor?
            rawMonitorsMap.put(newMonitor, new ArrayList<>(rawMonitorsMap.get(oldMonitor)));
            monitorFirstTestMap.put(newMonitor, currentTest);
        }
        lock.unlock();
    }

    @Override
    public void setCurrentTest(String test) {
        currentTest = test;
    }

    @Override
    public void createTable() {}

    @Override
    public int uniqueTraces() {
        return 0;
    }

    @Override
    public int size() {
        return 0;
    }

    @Override
    public List<Integer> getTraceLengths() {
        return null;
    }

    @Override
    public Map<String, Integer> getTraceFrequencies() {
        lock.lock();
        allowUpdate = false;

        for (Map.Entry<String, EventNode> entry : monitorsMap.entrySet()) {
            // key is monitorID, value is node
            EventNode node = entry.getValue();
            if (node.monitors == null) {
                node.monitors = new HashSet<>();
            }
            node.monitors.add(entry.getKey());
        }

        Stack<Pair> stack = new Stack<>();
//        stack.add(new Pair(root, "");
        stack.add(new Pair(root, null));

        if (System.getenv("COLLECT_MONITORS") == null || !System.getenv("COLLECT_TRACES").equals("1")) {
            Map<String, Integer> frequencyMap = new HashMap<>();

            /* Non-raw spec */
            while (!stack.isEmpty()) {
                Pair obj = stack.pop();
                if (!obj.node.event.isEmpty()) {
//                    if (obj.events.isEmpty()) {
                    if (obj.events == null) {
//                        obj.events += obj.node.event;
//                        obj.events = new StringBuilder(obj.node.event);
                        obj.events = new ArrayList<>();
                        obj.events.add(obj.node.event);
                    } else {
//                        obj.events += "," + obj.node.event;
//                        obj.events.append(",").append(obj.node.event);
                        obj.events.add(obj.node.event);
                    }
                }

                if (obj.node.monitors != null) {
//                    frequencyMap.put(obj.events, frequencyMap.getOrDefault(obj.events, 0) + obj.node.monitors.size());

//                    String trace = obj.events.toString();
//                    frequencyMap.put(trace, frequencyMap.getOrDefault(trace, 0) + obj.node.monitors.size());

                    String trace = getTraces(obj.events);

                    frequencyMap.put(trace, frequencyMap.getOrDefault(trace, 0) + obj.node.monitors.size());
                }

                if (obj.events == null) {
                    for (EventNode child : obj.node.children.values()) {
                        stack.add(new Pair(child, null));
                    }
                } else {
                    if (obj.node.children.size() > 1) {
                        for (EventNode child : obj.node.children.values()) {
                            stack.add(new Pair(child, new ArrayList<>(obj.events)));
                        }
                    } else if (obj.node.children.size() == 1) {
                        for (EventNode child : obj.node.children.values()) {
                            // Don't need to duplicate events because no one is using it
                            stack.add(new Pair(child, obj.events));
                        }
                    }
                }
            }

            /* Raw spec */
            for (List<RawEventNode> events : rawMonitorsMap.values()) {
                String trace = getTracesRaw(events);
                frequencyMap.put(trace, frequencyMap.getOrDefault(trace, 0) + 1);
            }

            lock.unlock();
            return frequencyMap;
        } else {
            Map<String, Integer> traceToID = new HashMap<>(); // Map actual trace to trace ID
            Map<Integer, Map<String, Integer>> specFrequency = new HashMap<>(); // Map trace ID to <Spec, Freq>
            Map<Integer, Map<String, Integer>> traceTestsFrequency = new HashMap<>(); // Map trace ID to <Test, Freq>
            int nextTraceID = 0;

            /* Non-raw spec */
            while (!stack.isEmpty()) {
                Pair obj = stack.pop();
                if (!obj.node.event.isEmpty()) {
//                    if (obj.events.isEmpty()) {
                    if (obj.events == null) {
//                        obj.events += obj.node.event;
//                        obj.events = new StringBuilder(obj.node.event);
                        obj.events = new ArrayList<>();
                        obj.events.add(obj.node.event);
                    } else {
//                        obj.events += "," + obj.node.event;
//                        obj.events.append(",").append(obj.node.event);
                        obj.events.add(obj.node.event);
                    }
                }

                if (obj.node.monitors != null) {
//                    traceToID.put(obj.events, nextTraceID);
//                    traceToID.put(obj.events.toString(), nextTraceID);

                    String trace = getTraces(obj.events);
                    traceToID.put(trace, nextTraceID);

                    Map<String, Integer> f = specFrequency.computeIfAbsent(nextTraceID, k -> new HashMap<>());
                    Map<String, Integer> t = traceTestsFrequency.computeIfAbsent(nextTraceID, k -> new HashMap<>());
                    for (String monitor : obj.node.monitors) {
                        String specName = monitor.split("#")[0];
                        f.put(specName, f.getOrDefault(specName, 0) + 1);
                        String testName = monitorFirstTestMap.getOrDefault(monitor, "N/A");
                        t.put(testName, t.getOrDefault(testName, 0) + 1);
                    }
                    nextTraceID++;
                }

//                for (EventNode child : obj.node.children.values()) {
//                    stack.add(new Pair(child, obj.events));
//                }
//                if (obj.events == null) {
//                    for (EventNode child : obj.node.children.values()) {
//                        stack.add(new Pair(child, null));
//                    }
//                } else {
//                    for (EventNode child : obj.node.children.values()) {
//                        stack.add(new Pair(child, new StringBuilder(obj.events)));
//                    }
//                }
                if (obj.events == null) {
                    for (EventNode child : obj.node.children.values()) {
                        stack.add(new Pair(child, null));
                    }
                } else {
                    if (obj.node.children.size() > 1) {
                        for (EventNode child : obj.node.children.values()) {
                            stack.add(new Pair(child, new ArrayList<>(obj.events)));
                        }
                    } else if (obj.node.children.size() == 1) {
                        for (EventNode child : obj.node.children.values()) {
                            // Don't need to duplicate events because no one is using it
                            stack.add(new Pair(child, obj.events));
                        }
                    }
                }
            }

            /* Raw spec */
            int traceID;
            for (Map.Entry<String, List<RawEventNode>> entry : rawMonitorsMap.entrySet()) {
                String trace = getTracesRaw(entry.getValue());
                if (traceToID.containsKey(trace)) {
                    traceID = traceToID.get(trace);
                } else {
                    traceID = nextTraceID++;
                    traceToID.put(trace, traceID);
                }

                Map<String, Integer> f = specFrequency.computeIfAbsent(traceID, k -> new HashMap<>());
                String specName = entry.getKey().split("#")[0];
                f.put(specName, f.getOrDefault(specName, 0) + 1);

                Map<String, Integer> t = traceTestsFrequency.computeIfAbsent(nextTraceID, k -> new HashMap<>());
                String testName = monitorFirstTestMap.getOrDefault(entry.getKey(), "N/A");
                t.put(testName, t.getOrDefault(testName, 0) + 1);
            }

            lock.unlock();
            writeTraceAndSpecFrequencyToFile(specFrequency);
            writeSpecFirstTestToFile(traceTestsFrequency);
            return traceToID;
        }
    }

    private String getTraces(List<String> events) {
        StringBuilder trace = new StringBuilder();
        String lastEvent = "";
        int lastEventFreq = 0;
        for (String e : events) {
            if (e.equals(lastEvent)) {
                lastEventFreq += 1;
            } else {
                if (!lastEvent.isEmpty()) {
                    simplifyTrace(trace, lastEvent, lastEventFreq);
                }

                lastEvent = e;
                lastEventFreq = 1;
            }
        }
        simplifyTrace(trace, lastEvent, lastEventFreq);
        return "[" + trace + "]";
    }

    private String getTracesRaw(List<RawEventNode> events) {
        StringBuilder trace = new StringBuilder();
        for (RawEventNode event : events) {
            simplifyTrace(trace, event.event, event.frequency);
        }
        return "[" + trace + "]";
    }

    private void simplifyTrace(StringBuilder trace, String lastEvent, int lastEventFreq) {
        if (trace.length() == 0) {
            if (lastEventFreq > 1) {
                trace.append(lastEvent).append("x").append(lastEventFreq);
            } else {
                trace.append(lastEvent);
            }
        } else {
            if (lastEventFreq > 1) {
                trace.append(", ").append(lastEvent).append("x").append(lastEventFreq);
            } else {
                trace.append(", ").append(lastEvent);
            }
        }
    }

    private void writeTraceAndSpecFrequencyToFile(Map<Integer, Map<String, Integer>> specFrequency) {
        if (System.getenv("TRACEDB_PATH") == null) {
            return;
        }

        String csvDir = System.getenv("TRACEDB_PATH") + TraceDatabase.getInstance().randomFileName + File.separator + "specs-frequency.csv";
        try {
            FileWriter fileWriter = new FileWriter(csvDir);
            PrintWriter writer = new PrintWriter(fileWriter);
            int i = 0;
            for (Map.Entry<Integer, Map<String, Integer>> entry : specFrequency.entrySet()) {
                writer.println(entry.getKey() + " " + entry.getValue().toString());
                i++;
                if (i % 10 == 0) {
                    writer.flush();
                }
            }

            writer.println("OK");
            writer.flush();
            writer.close();
        } catch (Exception ex) {
            printError(Arrays.toString(ex.getStackTrace()));
        }
    }

    private void writeSpecFirstTestToFile(Map<Integer, Map<String, Integer>> testFrequency) {
        if (System.getenv("TRACEDB_PATH") == null) {
            return;
        }

        String csvDir = System.getenv("TRACEDB_PATH") + TraceDatabase.getInstance().randomFileName + File.separator + "specs-test.csv";
        try {
            FileWriter fileWriter = new FileWriter(csvDir);
            PrintWriter writer = new PrintWriter(fileWriter);
            int i = 0;
            for (Map.Entry<Integer, Map<String, Integer>> entry : testFrequency.entrySet()) {
                writer.println(entry.getKey() + " " + entry.getValue().toString());
                i++;
                if (i % 10 == 0) {
                    writer.flush();
                }
            }

            writer.println("OK");
            writer.flush();
            writer.close();
        } catch (Exception ex) {
            printError(Arrays.toString(ex.getStackTrace()));
        }
    }

    @Override
    public List<String> computeTimeSeries(List<String> orderedMonitorIds) {
        lock.lock();
        try {
            List<String> compressedSeries = new ArrayList<>();
            if (orderedMonitorIds.isEmpty()) {
                return compressedSeries;
            }
            EventNode firstNode = monitorsMap.get(orderedMonitorIds.get(0));
            int prev = !firstNode.visited ? 1 : 0;
            firstNode.visited = true;
            int count = 1;

            for (int i = 1; i < orderedMonitorIds.size(); i++) {
                String monitorId = orderedMonitorIds.get(i);
                EventNode node = monitorsMap.get(monitorId);
                int curr = !node.visited ? 1 : 0;
                node.visited = true;

                if (curr == prev) {
                    count++;
                } else {
                    if (count > 1) {
                        compressedSeries.add(prev + "x" + count);
                    } else {
                        compressedSeries.add(String.valueOf(prev));
                    }
                    prev = curr;
                    count = 1;
                }
            }
            if (count > 1) {
                compressedSeries.add(prev + "x" + count);
            } else {
                compressedSeries.add(String.valueOf(prev));
            }
            return compressedSeries;
        } finally {
            lock.unlock();
        }
    }

    private void printError(String message) {
        try {
            FileWriter fileWriter = new FileWriter(System.getenv("TRACEDB_PATH") + TraceDatabase.getInstance().randomFileName + File.separator +
                    "error-spec.txt");
            PrintWriter writer = new PrintWriter(fileWriter);
            writer.println(message);
            writer.flush();
            writer.close();
        } catch (Exception ignored) {}
    }

    @Override
    public void dump() {}
}
