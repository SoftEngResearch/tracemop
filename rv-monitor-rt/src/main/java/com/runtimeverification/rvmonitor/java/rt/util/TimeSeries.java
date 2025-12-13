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

                    int index = 0;
                    for (AbstractMonitor monitor : list) {
                        Node node = monitor.node;
                        boolean isUnique = !node.marked;
                        if (isUnique) {
                            fw.write("<" + index + ": unique> ");
                            node.marked = true;
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
        if (monitor.location == null) {
            monitor.location = spec + " @ " + joinpoint.getSourceLocation().toString();
        }
        String key = monitor.location;

        synchronized (map) {
            List<AbstractMonitor> list = map.computeIfAbsent(key, k -> new ArrayList<>());
            list.add(monitor);

            if (monitor.node != null) return;

            Node root = locationTrieMap.computeIfAbsent(key, k -> new Node());
            monitor.node = root;
            monitor.node.count++;
        }
    }

    public static void updateMonitor(AbstractMonitor monitor, JoinPoint.StaticPart joinpoint) {
        Node current = monitor.node;
    	current.count--;
        int eventHash = System.identityHashCode(joinpoint);

        Node nextNode = current.children.get(eventHash);
        if (nextNode == null) {
            nextNode = new Node();
            current.children.put(eventHash, nextNode);
        }
        monitor.node = nextNode;
    	monitor.node.count++;
    }
}
