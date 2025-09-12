package com.runtimeverification.rvmonitor.java.rvj;
import com.beust.jcommander.IStringConverter;

public class SpecConfigConverter implements IStringConverter<SpecConfig> {

    @Override
    public SpecConfig convert(String value) {
        SpecConfig config = new SpecConfig();

        value = value.trim();
        if (value.contains("off")) {
            config.name = value.split("\\s+")[0];
            config.disabled = true;
        } else if (value.contains("{") && value.contains("}")) {
            int braceStart = value.indexOf("{");
            config.name = value.substring(0, braceStart).trim();

            String[] parts = value.substring(braceStart + 1, value.indexOf("}")).split(",");
            if (parts.length != 5) {
                throw new IllegalArgumentException("Expected 5 hyperparameter values");
            }

            config.alpha = Double.parseDouble(parts[0].trim());
            config.epsilon = Double.parseDouble(parts[1].trim());
            config.threshold = Double.parseDouble(parts[2].trim());
            config.initc = Double.parseDouble(parts[3].trim());
            config.initn = Double.parseDouble(parts[4].trim());
        } else {
            throw new IllegalArgumentException("Invalid spec format: " + value);
        }

        return config;
    }
}
