package com.runtimeverification.rvmonitor.java.rt.observable;

import java.io.PrintWriter;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

import com.runtimeverification.rvmonitor.java.rt.util.TraceDBTrie;
import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;

public class TimeSeriesCollector extends MonitorTraceCollector {

    private static final Map<Integer, List<Integer>> locationToMonitorIds = new HashMap<>();
    private static final Map<String, Integer> locationToId = new HashMap<>();
    private static int nextId = 0;
    private static final ReentrantLock lock = new ReentrantLock();

    public static String randomFileName = "-" + UUID.randomUUID();
    
    public TimeSeriesCollector(PrintWriter writer, String dbPath, String dbConfPath) {
        super(writer, dbPath, dbConfPath);
    }

    public static void addMonitor(String location, int monitorID) {
        lock.lock();
        try {
            Integer id = locationToId.computeIfAbsent(location, k -> nextId++);
            List<Integer> list = locationToMonitorIds.computeIfAbsent(id, k -> new ArrayList<>());
            list.add(monitorID);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onMonitorTransitioned(AbstractMonitor monitor) {}

    @Override
    public <TMonitor extends com.runtimeverification.rvmonitor.java.rt.tablebase.IMonitor>
    void onMonitorTransitioned(
            com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitorSet<TMonitor> set) {}

    @Override
    public <TMonitor extends com.runtimeverification.rvmonitor.java.rt.tablebase.IMonitor>
    void onMonitorTransitioned(
            com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractPartitionedMonitorSet<TMonitor> set) {}

    @Override
    public void onMonitorCloned(AbstractMonitor existing, AbstractMonitor created) {}

    @Override
    public void onCompleted() {
        try {
            List<Map.Entry<String, Integer>> locations =
                    new ArrayList<>(locationToId.entrySet());

            locations.sort((a, b) -> {
                String[] pa = a.getKey().split(" @ ");
                String[] pb = b.getKey().split(" @ ");

                int c = pa[0].compareTo(pb[0]);
                if (c != 0) return c;

                String[] la = pa[1].split(":");
                String[] lb = pb[1].split(":");

                c = la[0].compareTo(lb[0]);
                if (c != 0) return c;

                return Integer.compare(
                        Integer.parseInt(la[1]),
                        Integer.parseInt(lb[1]));
            });

            for (Map.Entry<String, Integer> loc : locations) {
                String location = loc.getKey();
                Integer id = loc.getValue();

                writer.write(location);
                writer.write(System.lineSeparator());
                writer.write(" => ");
    
                String specName = location.split(" @ ", 2)[0];
                List<Integer> monitors = locationToMonitorIds.get(id);
                if (monitors != null) {
                    List<String> compressedSeries = traceDB.computeTimeSeries(specName, monitors);
                    writer.write(compressedSeries.toString());
                }
                writer.write(System.lineSeparator());
                writer.write(System.lineSeparator());
                writer.flush();
            }
            writer.close();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
