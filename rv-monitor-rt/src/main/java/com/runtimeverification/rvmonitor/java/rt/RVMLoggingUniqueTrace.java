package com.runtimeverification.rvmonitor.java.rt;

import com.runtimeverification.rvmonitor.java.rt.util.TraceUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class RVMLoggingUniqueTrace extends RVMLoggingUnique {

    RVMLoggingUniqueTrace(PrintStream ps, String level) {
        super(ps);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                // Copy .traces
                try {
                    // TODO: Hard-coded now, must change later
                    copyDirectory(File.separator + "tmp" + File.separator + ".traces", System.getProperty("user.dir") + File.separator + ".traces");
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

                File violationsDir = new File(System.getProperty("user.dir")
                        + File.separator + ".traces" + File.separator + "violations");
                if (!violationsDir.exists()) {
                    return;
                }
                // Compute mapping of files and checksums
                Map<File, String> fileToChecksum = Stream.of(violationsDir.listFiles())
                        .filter(file -> !file.isDirectory())
                        .collect(Collectors.toMap(file -> file, file -> getMD5Checksum(file.getPath())));
                Map<String, List<File>> checksumToFiles = fileToChecksum.entrySet()
                        .stream()
                        .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
                Map<String, File> checksumToUniqueFile = new HashMap<>();
                for (Map.Entry<String, List<File>> entry : checksumToFiles.entrySet()) {
                    checksumToUniqueFile.put(entry.getKey(), entry.getValue().get(0));
                }
                // Save unique trace files
                File savedDir = new File(System.getProperty("user.dir")
                        + File.separator + ".traces" + File.separator + "violations" + File.separator + "saved");
                if (!savedDir.exists()) {
                    savedDir.mkdirs();
                }
                try {
                    for (File file : checksumToUniqueFile.values()) {
                        Files.move(file.toPath(), savedDir.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
                // Clean duplicates
                for (File file : violationsDir.listFiles()) {
                    if (!file.isDirectory()) {
                        file.delete();
                    }
                }
            }
        });
    }

    private String getMD5Checksum(String path) {
        try {
            File file = new File(path);
            byte[] data = Files.readAllBytes(file.toPath());
            byte[] hash = MessageDigest.getInstance("MD5").digest(data);
            String checksum = new BigInteger(1, hash).toString(16);
            return checksum;
        } catch (IOException | NoSuchAlgorithmException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    // Referenced from https://www.baeldung.com/java-copy-directory section 2
    private void copyDirectory(String sourceDirectoryLocation, String destinationDirectoryLocation) throws IOException {
        Files.walk(Paths.get(sourceDirectoryLocation)).forEach(source -> {
            Path destination = Paths.get(destinationDirectoryLocation, source.toString()
                    .substring(sourceDirectoryLocation.length()));
            try {
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }
}
