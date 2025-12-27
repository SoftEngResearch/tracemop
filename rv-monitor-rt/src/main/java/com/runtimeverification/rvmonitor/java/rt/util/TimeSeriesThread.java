package com.runtimeverification.rvmonitor.java.rt.util;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import org.aspectj.lang.JoinPoint;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public class TimeSeriesThread {

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

                String[] la = pa[1].split(" ");
                String[] lb = pb[1].split(" ");

                String[] fileA = la[0].split(":");
                String[] fileB = lb[0].split(":");

                c = fileA[0].compareTo(fileB[0]);
                if (c != 0) return c;

                c = Integer.compare(Integer.parseInt(fileA[1]), Integer.parseInt(fileB[1]));
                if (c != 0) return c;

                int threadA = 0;
                int threadB = 0;

                if (la.length > 1 && la[1].startsWith("[T")) {
                    threadA = Integer.parseInt(la[1].substring(2, la[1].length() - 1));
                }
                if (lb.length > 1 && lb[1].startsWith("[T")) {
                    threadB = Integer.parseInt(lb[1].substring(2, lb[1].length() - 1));
                }
                return Integer.compare(threadA, threadB);
            });

            File file = new File(System.getProperty("user.dir") + File.separator + "time-series");

            try (FileWriter fw = new FileWriter(file)) {
                Map<String, Map<Long, Long>> earliestPerBaseLocation = new HashMap<>();

                for (Map.Entry<String, Integer> locEntry : locations) {
                    String location = locEntry.getKey();
                    Integer id = locEntry.getValue();

                    String baseLocation = location.replaceAll("\\s*\\[T\\d+\\]$", "");
                    Map<Long, Long> earliest =
                            earliestPerBaseLocation.computeIfAbsent(baseLocation, k -> new HashMap<>());

                    List<AbstractMonitor> list = monitorMap.get(id);
                    if (list == null) continue;

                    for (AbstractMonitor monitor : list) {
                        long traceVal = monitor.traceVal;
                        long t = monitor.creation_time;
                        earliest.merge(traceVal, t, Math::min);
                    }
                }

                for (Map.Entry<String, Integer> locEntry : locations) {
                    String location = locEntry.getKey();
                    Integer id = locEntry.getValue();
                    fw.write(location + System.lineSeparator());
                    fw.write(" => ");

                    String baseLocation = location.replaceAll("\\s*\\[T\\d+\\]$", "");
                    Map<Long, Long> earliest = earliestPerBaseLocation.get(baseLocation);

                    List<AbstractMonitor> list = monitorMap.get(id);
                    if (list != null) {
                        List<Integer> uniqueness = new ArrayList<>(list.size());
                        for (AbstractMonitor monitor : list) {
                            long traceVal = monitor.traceVal;
                            long t = monitor.creation_time;
                            uniqueness.add(earliest.get(traceVal) == t ? 1 : 0);
                        }
                        fw.write(uniqueness.toString());
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

    public static void addMonitor(String spec, int threadId, JoinPoint.StaticPart joinpoint, AbstractMonitor monitor) {
        lock.lock();
        try {
            if (monitor.location == null) {
                monitor.location = spec + " @ " + joinpoint.getSourceLocation().toString() + " [T" + threadId + "]";
            }
            monitor.creation_time = System.nanoTime();
            String location = monitor.location;
            Integer id = locationToId.computeIfAbsent(location, k -> nextId++);

            List<AbstractMonitor> list = monitorMap.computeIfAbsent(id, k -> new ArrayList<>());
            list.add(monitor);
        } finally {
            lock.unlock();
        }
    }
}
