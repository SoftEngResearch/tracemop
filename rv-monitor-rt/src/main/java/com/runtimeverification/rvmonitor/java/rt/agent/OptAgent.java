package com.runtimeverification.rvmonitor.java.rt.agent;

import java.util.*;
import java.nio.file.*;
import java.io.*;

public class OptAgent implements Agent {

    private static Map<String, Set<Integer>> timeSeriesMap = new HashMap<>();
    private int timestep;
    private Set<Integer> myIndices;

    static {
        String seriesPath = System.getenv("SERIES_PATH");

        if (seriesPath != null && !seriesPath.isEmpty()) {
            Path path = Paths.get(seriesPath).toAbsolutePath();

            if (Files.exists(path)) {
                try {
                    String content = new String(Files.readAllBytes(path));
                    timeSeriesMap = parseTimeSeries(content);
                } catch (IOException e) {
                    timeSeriesMap = new HashMap<>();
                }
            }
        }
    }

    public OptAgent(Set<Integer> indices) {
        this.timestep = 0;
        this.myIndices = indices;
    }

    @Override
    public boolean decideAction() {
        boolean action = false;
        if (myIndices != null) {
            action = myIndices.contains(timestep);
        }
        timestep++;
        return action;
    }

    public static Set<Integer> checkTimeSeries(String specName, org.aspectj.lang.JoinPoint.StaticPart joinPoint) {
        String key = specName + " @ " + joinPoint.getSourceLocation().toString();
        return timeSeriesMap.get(key);
    }

    public static Map<String, Set<Integer>> parseTimeSeries(String timeSeriesString) {
        Map<String, Set<Integer>> map = new HashMap<>();
        String[] lines = timeSeriesString.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;

            if (line.contains("@") && i + 1 < lines.length && lines[i + 1].trim().startsWith("=>")) {
                String location = line.trim();
                String seriesLine = lines[i + 1].substring(5, lines[i + 1].length() - 1).trim();

                String[] tokens = seriesLine.split(",");
                Set<Integer> indices = new HashSet<>();
                int currentIndex = 0;

                try {
                    for (String token : tokens) {
                        token = token.trim();
                        if (token.isEmpty()) continue;

                        if (token.contains("x")) {
                            String[] parts = token.split("x");
                            int value = Integer.parseInt(parts[0]);
                            int count = Integer.parseInt(parts[1]);
                            for (int j = 0; j < count; j++) {
                                if (value == 1) indices.add(currentIndex);
                                currentIndex++;
                            }
                        } else {
                            int val = Integer.parseInt(token);
                            if (val == 1) indices.add(currentIndex);
                            currentIndex++;
                        }
                    }
                } catch (Exception e) {}

                map.computeIfAbsent(location, k -> new HashSet<>()).addAll(indices);
                i++;
            }
        }
        return map;
    }
}
