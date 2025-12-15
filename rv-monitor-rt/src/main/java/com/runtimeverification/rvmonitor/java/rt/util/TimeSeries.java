package com.runtimeverification.rvmonitor.java.rt.util;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import org.aspectj.lang.JoinPoint;
import java.util.concurrent.locks.ReentrantLock;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class TimeSeries {

    private static final Map<String, List<AbstractMonitor>> map = new HashMap<>();
    private static final Map<String, Node> locationTrieMap = new HashMap<>();
    private static boolean shutdownHookRegistered = false;
	private static final ReentrantLock lock = new ReentrantLock();

    public static class Node {
        boolean marked = false;
	    public int count = 0;
        Map<Integer, Node> children = new HashMap<>();
    }

    public static synchronized void registerShutdownHook() {
        if (shutdownHookRegistered) return;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<String> keys = new ArrayList<>(map.keySet());
            keys.sort((a, b) -> {
                String[] pa = a.split(" @ ");
                String[] pb = b.split(" @ ");

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
                for (String key : keys) {
                    fw.write(key + System.lineSeparator());
                    fw.write(" => ");

                    List<AbstractMonitor> list = map.get(key);
					Set<Long> seenTraces = new HashSet<>();
	
                    int index = 0;
                    for (AbstractMonitor monitor : list) {
						long traceVal = monitor.traceVal;
						boolean isUnique = seenTraces.add(traceVal);
                        if (isUnique) {
                            fw.write("<" + index + ": unique> ");
                        } else {
                            fw.write("<" + index + ": redundant> ");
                        }
                        index++;
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
        if (monitor.location == null) {
            monitor.location = spec + " @ " + joinpoint.getSourceLocation().toString();
        }
        String key = monitor.location;
        List<AbstractMonitor> list = map.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(monitor);
		lock.unlock();
    }
}
