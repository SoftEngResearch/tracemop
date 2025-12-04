package com.runtimeverification.rvmonitor.java.rvj;

public class SpecConfig {
    public String name;
    public boolean disabled = false;
    public double alpha = 0.9;
    public double epsilon = 0.1;
    public double threshold = 0.0001;
    public double initc = 5.0;
    public double initn = 0.0;

    @Override
    public String toString() {
        if (disabled) {
            return name + " [OFF]";
        } else {
            return String.format("%s {%.3f, %.3f, %.5f, %.1f, %.1f}", name, alpha, epsilon, threshold, initc, initn);
        }
    }
}
