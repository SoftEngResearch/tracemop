package edu.cornell;

import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.blocktest.BTest.*;
import static org.inlinetest.ITest.*;

public class TracesProcessor {
    private Log log;
    private String output;

    public TracesProcessor(Log log, String output) {
        this.log = log;
        this.output = output;
    }

    public void process() {
        log.info("Post processing traces in directory: " + output);

        String traceDB = output + File.separator + "all-traces";
        File outputFile = new File(output);
        int numDB = 0;
        String lastDB = "";

        try {
            File defaultUniqueTracesFile = new File(traceDB + File.separator + "unique-traces.txt");
            if (defaultUniqueTracesFile.exists()) {
                processTracesDir(traceDB);
            }

            for (File tracesDir : outputFile.listFiles()) {
                traceDB = tracesDir.getAbsolutePath();
                if (!tracesDir.isDirectory()) {
                    continue;
                }

                if (!Files.exists(Paths.get(traceDB + File.separator + "unique-traces.txt")) ||
                    !Files.exists(Paths.get(traceDB + File.separator + "locations.txt")) ||
                    !Files.exists(Paths.get(traceDB + File.separator + "traces.txt")) ||
                    !Files.exists(Paths.get(traceDB + File.separator + "specs-frequency.csv"))) {
                    continue;
                }

                processTracesDir(traceDB);

                numDB += 1;
                lastDB = traceDB;
            }

            if (numDB == 1 && !lastDB.isEmpty()) {
                Files.move(Paths.get(lastDB), Paths.get(output + File.separator + "all-traces"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error(e);
        }
    }

    private void processTracesDir(String traceDB) throws IOException {
        log.info("Processing traces directory " + traceDB);

        File uniqueTracesFile = new File(traceDB + File.separator + "unique-traces.txt");
        File tracesFile= new File(traceDB + File.separator + "traces.txt");
        File tracesIDFile = new File(traceDB + File.separator + "traces-id.txt");

        Files.move(uniqueTracesFile.toPath(), tracesIDFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        countTracesFrequency(traceDB);
        tracesIDFile.delete();
        tracesFile.delete();
    }

    private void countTracesFrequency(String traceDB) throws IOException {
        log.info("Counting traces frequency " + traceDB);

        Path tracesIDFile = Paths.get(traceDB + File.separator + "traces-id.txt");
        Path specsFreqFile = Paths.get(traceDB + File.separator + "specs-frequency.csv");

        Map<String, String> IDToTraces = new HashMap<>();
        List<String> output = new ArrayList<>();

        output.add("=== UNIQUE TRACES WITH ID ===");

        boolean header = false;
        for (String line : Files.readAllLines(tracesIDFile)) {
            if (!header) {
                header = true;
                continue;
            }
            blocktest().given(line, "4 1 [e42~22, e43~22]").given(IDToTraces, new HashMap<String, String>())
                    .checkEq(IDToTraces.get("4"), "1 [e42~22, e43~22]");
            String[] parts = line.split(" ", 2);
            if (parts.length != 2)
                continue;
            String id = parts[0];
            String trace = parts[1];

            IDToTraces.put(id, trace);
        }

        for (String line : Files.readAllLines(specsFreqFile)) {
            if (line.equals("OK"))
                continue;

            String[] parts = line.split(" ", 2);
            if (parts.length != 2)
                continue;
            String id = parts[0];
            String specToFreq = parts[1];

            if (specToFreq.length() < 2) {
                log.error("Error processing spec ID " + id);
                continue;
            }

            int totalFreq = 0;
            specToFreq = specToFreq.substring(1, specToFreq.length() - 1); // remove {}
            itest().given(specToFreq, "{Throwable_InitCauseOnceMonitor=190}")
                    .checkEq(specToFreq, "Throwable_InitCauseOnceMonitor=190");
            for (String specString : specToFreq.split(", ")) {
                blocktest().given(specString, "Throwable_InitCauseOnceMonitor=190").given(totalFreq, 42)
                        .checkEq(totalFreq, 232);
                parts = specString.split("=", 2);
                if (parts.length != 2)
                    continue;
                String spec = parts[0];
                int freq = Integer.parseInt(parts[1]);
                totalFreq += freq;
            }
            output.add(id + " " + totalFreq + " " + IDToTraces.get(id));
        }

        PrintWriter writer = new PrintWriter(traceDB + File.separator + "unique-traces.txt", "UTF-8");
        for (String line : output) {
            writer.println(line);
        }
        writer.close();
    }
}
