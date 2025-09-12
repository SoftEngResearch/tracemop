package com.runtimeverification.rvmonitor.java.rt.util;

import com.runtimeverification.rvmonitor.java.rt.ViolationRecorder;
import org.aspectj.lang.JoinPoint;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TraceUtil {

    private static Map<String, Integer> locationMap = new HashMap<>();

    private static int freshID = 1;

    public static File artifactsDir = null;

    public static File dbConf = null;

    /**
     * This method reduces the size of stored traces.
     *
     * @param joinpoint I.e., thisJoinPointStaticPart from AspectJ,
     * @return A short location ID, e.g., loc2.
     */
    public static Integer getShortLocation(JoinPoint.StaticPart joinpoint) {
        String loc = joinpoint.getSourceLocation().getWithinType().getName() + "(" + joinpoint.getSourceLocation().toString() + ")";
	Integer locId = locationMap.get(loc);
	if (locId == null) {
	    locId = freshID++;
	    locationMap.put(loc, locId);
	}
	return locId;
    }

    /**
     * Updates locationMap and freshID based on a potentially existing locationMap recorded in locationMapFile.
     * The method will return immediately if locationMapFile is empty or does not exist.
     * @param locationMapFile locationMapFile to read from
     */
    public static void updateLocationMapFromFile(File locationMapFile) {
        if (!locationMapFile.exists() || locationMapFile.length() == 0) return;
        String line;
        int largestId = freshID;
        try (BufferedReader reader = new BufferedReader(new FileReader(locationMapFile))) {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("===")) continue; // skip the first line, it doesn't contain map info
                String[] splits = line.split("\\s+");
                String shortLocation = splits[1];
                int id = Integer.valueOf(splits[0]);
                locationMap.put(shortLocation, id);
                if (id > largestId) {
                    largestId = id;
                }
            }
        } catch (FileNotFoundException ex) { // ignore if we can't read
            ex.printStackTrace();
        } catch (IOException ex) {
            ex.printStackTrace();
        }
        freshID = largestId + 1;
    }

    public static Map<String, Integer> getLocationMap() {
        return locationMap;
    }

    public static String getAbsolutePath(String fileName) {
        return new File(artifactsDir + File.separator + fileName).getAbsolutePath();
    }
}
