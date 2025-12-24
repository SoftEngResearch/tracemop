package com.runtimeverification.rvmonitor.java.rvj;
import com.beust.jcommander.IStringConverter;

public class DefaultConfigConverter implements IStringConverter<SpecConfig> {

    @Override
    public SpecConfig convert(String value) {
        SpecConfig config = new SpecConfig();

        value = value.trim();
        if (!value.startsWith("{") || !value.endsWith("}")) {
            throw new IllegalArgumentException("Invalid format for -default. Expected {alpha,epsilon,threshold,initc,initn}");
        }

        String[] parts = value.substring(1, value.length() - 1).split(",");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Expected 5 hyperparameter values for -default");
        }

        try {
            config.alpha = Double.parseDouble(parts[0].trim());
            config.epsilon = Double.parseDouble(parts[1].trim());
            config.threshold = Double.parseDouble(parts[2].trim());
            config.initc = Double.parseDouble(parts[3].trim());
            config.initn = Double.parseDouble(parts[4].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Failed to parse default hyperparameter values");
        }

        return config;
    }
}
