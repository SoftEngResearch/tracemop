package com.runtimeverification.rvmonitor.java.rvj;
import com.beust.jcommander.IStringConverter;

public class SpecConfigConverter implements IStringConverter<SpecConfig> {

    @Override
    public SpecConfig convert(String value) {
        SpecConfig config = new SpecConfig();
        value = value.trim();

        int firstSpace = value.indexOf(' ');
        if (firstSpace == -1) {
            throw new IllegalArgumentException("Invalid value for spec parameter");
        }

        String namePart = value.substring(0, firstSpace).trim();
        String configPart = value.substring(firstSpace + 1).trim();

        config.name = namePart;

        if (configPart.equalsIgnoreCase("off")) {
            config.disabled = true;
        } else if (configPart.startsWith("{") && configPart.endsWith("}")) {
            String[] parts = configPart.substring(1, configPart.length() - 1).split(",");
            if (parts.length != 5) {
                throw new IllegalArgumentException("Expected 5 hyperparameter values");
            }

            try {
                config.alpha = Double.parseDouble(parts[0].trim());
                config.epsilon = Double.parseDouble(parts[1].trim());
                config.threshold = Double.parseDouble(parts[2].trim());
                config.initc = Double.parseDouble(parts[3].trim());
                config.initn = Double.parseDouble(parts[4].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Failed to parse hyperparameter values");
            }
        } else {
            System.out.println(configPart);
            throw new IllegalArgumentException("Invalid config value");
        }

        return config;
    }
}
