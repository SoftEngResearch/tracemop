package com.runtimeverification.rvmonitor.java.rt.agent;

import org.aspectj.lang.reflect.SourceLocation;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class RLAgentStore {

    private static class Entry {
        final String specName;
        final SourceLocation sourceLocation;
        final RLAgent agent;

        Entry(String specName, SourceLocation sourceLocation, RLAgent agent) {
            this.specName = specName;
            this.sourceLocation = sourceLocation;
            this.agent = agent;
        }

        String getLocationString() {
            if (sourceLocation != null) {
                return sourceLocation.getFileName() + ":" + sourceLocation.getLine();
            }
            return "UnknownLocation:0";
        }

        String getFileName() {
            return (sourceLocation != null) ? sourceLocation.getFileName() : "UnknownLocation";
        }

        int getLine() {
            return (sourceLocation != null) ? sourceLocation.getLine() : 0;
        }
    }

    private static final List<Entry> entries = new ArrayList<>();
    private static boolean shutdownHookRegistered = false;

    public static synchronized void add(String specName, SourceLocation sourceLocation, RLAgent agent) {
        entries.add(new Entry(specName, sourceLocation, agent));

        if (!shutdownHookRegistered) {
            Runtime.getRuntime().addShutdownHook(new Thread(RLAgentStore::writeAllTrajectories));
            shutdownHookRegistered = true;
        }
    }
    private static void writeAllTrajectories() {
        File file = new File(System.getProperty("user.dir") + File.separator + "trajectories");

        int lastWrittenIndex = 0;
        while (true) {
            List<Entry> snapshot;
            synchronized (entries) {
                if (lastWrittenIndex >= entries.size()) {
                    break;
                }
                snapshot = new ArrayList<>(entries.subList(lastWrittenIndex, entries.size()));
            }
            snapshot.sort(Comparator
                .comparing((Entry e) -> e.specName)
                .thenComparing(Entry::getFileName)
                .thenComparingInt(Entry::getLine));

            try (FileWriter fw = new FileWriter(file, true)) {
                for (Entry entry : snapshot) {
                    String location = entry.getLocationString();
                    String trajectory = entry.agent.getTrajectoryString();
                    if (!trajectory.isEmpty()) {
                        fw.write(entry.specName + " @ " + location + "\n => " + trajectory + "\n\n");
                    }
                }
                lastWrittenIndex += snapshot.size();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
