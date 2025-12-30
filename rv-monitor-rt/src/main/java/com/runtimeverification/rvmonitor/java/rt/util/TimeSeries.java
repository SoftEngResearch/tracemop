package com.runtimeverification.rvmonitor.java.rt.util;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import org.aspectj.lang.JoinPoint;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class TimeSeries {

    private static final Map<Integer, List<AbstractMonitor>> monitorMap = new HashMap<>();
    private static final Map<String, Integer> locationToId = new HashMap<>();
    private static int nextId = 0;
    private static boolean shutdownHookRegistered = false;
    private static final ReentrantLock lock = new ReentrantLock();

    public static synchronized void registerShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<Map.Entry<String, Integer>> locations = new ArrayList<>(locationToId.entrySet());

            locations.sort((a, b) -> {
                String[] pa = a.getKey().split(" @ ");
                String[] pb = b.getKey().split(" @ ");

                int c = pa[0].compareTo(pb[0]);
                if (c != 0) return c;

                String[] la = pa[1].split(":");
                String[] lb = pb[1].split(":");

                c = la[0].compareTo(lb[0]);
                if (c != 0) return c;

                return Integer.compare(Integer.parseInt(la[1]), Integer.parseInt(lb[1]));
            });

            File file = new File(System.getProperty("user.dir") + File.separator + "time-series");

            try (FileWriter fw = new FileWriter(file)) {
                for (Map.Entry<String, Integer> locEntry : locations) {
                    String location = locEntry.getKey();
                    Integer id = locEntry.getValue();

                    fw.write(location + System.lineSeparator());
                    fw.write(" => ");

                    List<AbstractMonitor> list = monitorMap.get(id);
                    if (list != null) {
                        Set<Long> seenTraces = new HashSet<>();
                        List<Integer> uniqueness = new ArrayList<>();

                        for (AbstractMonitor monitor : list) {
                            long traceVal = monitor.traceVal;
                            boolean isUnique = seenTraces.add(traceVal);
                            uniqueness.add(isUnique ? 1 : 0);
                        }
                        StringBuilder compressed = new StringBuilder();
                        compressed.append("[");

                        if (!uniqueness.isEmpty()) {
                            int prev = uniqueness.get(0);
                            int count = 1;

                            for (int i = 1; i < uniqueness.size(); i++) {
                                int curr = uniqueness.get(i);
                                if (curr == prev) {
                                    count++;
                                } else {
                                    if (count > 1) {
                                        compressed.append(prev).append("x").append(count);
                                    } else {
                                        compressed.append(prev);
                                    }
                                    compressed.append(", ");
                                    prev = curr;
                                    count = 1;
                                }
                            }
                            if (count > 1) {
                                compressed.append(prev).append("x").append(count);
                            } else {
                                compressed.append(prev);
                            }
                        }
                        compressed.append("]");
                        fw.write(compressed.toString());
                    }
                    fw.write(System.lineSeparator());
                    fw.write(System.lineSeparator());
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }));
        shutdownHookRegistered = true;
    }

    public static void addMonitor(String spec, JoinPoint.StaticPart joinpoint, AbstractMonitor monitor) {
        lock.lock();
        try {
            if (monitor.location == null) {
                monitor.location = spec + " @ " + joinpoint.getSourceLocation().toString();
            }
            String location = monitor.location;
            Integer id = locationToId.computeIfAbsent(location, k -> nextId++);
            List<AbstractMonitor> list = monitorMap.computeIfAbsent(id, k -> new ArrayList<>());
            list.add(monitor);
        } finally {
            lock.unlock();
        }
    }
}
