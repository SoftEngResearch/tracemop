package com.runtimeverification.rvmonitor.java.rt.rlagent;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import java.lang.Math;
import java.util.Random;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;

public class RLAgent {
    private double Qn;
    private double Qc;
    private double reward;

    private int numTotTraces = 0;
    private int numDupTraces = 0;

    private double EPSILON;
    private double ALPHA;

    private AbstractMonitor monitor = null;
    private HashSet<Integer> uniqueTraces;

    private int timeStep = 0;

    private double THRESHOLD;
    public boolean converged = false;
    public boolean convStatus;

    private boolean traj = false;
    private List<Step> trajectory;

    private boolean lastAction = true;
    private int lastTimestep = -1;
    private double lastQc;
    private double lastQn;

    public static class Step {
        public final boolean action;
        public final float reward;
        public final int timestep;
        public final double Qc;
        public final double Qn;

        public Step(boolean action, double reward, int timestep, double Qc, double Qn) {
            this.action = action;
            this.reward = (float) reward;
            this.timestep = timestep;
            this.Qc = Qc;
            this.Qn = Qn;
        }
    }

    public RLAgent(HashSet<Integer> uniqueTraces,
        double alpha, double epsilon, double threshold, double initc, double initn) {
        this(uniqueTraces, alpha, epsilon, threshold, initc, initn, false);
    }

    public RLAgent(HashSet<Integer> uniqueTraces,
        double alpha, double epsilon, double threshold, double initc, double initn, boolean traj) {
        this.uniqueTraces = uniqueTraces;
        this.ALPHA = alpha;
        this.EPSILON = epsilon;
        this.THRESHOLD = threshold;
        this.Qc = initc;
        this.Qn = initn;
        this.traj = traj;
        if (traj) {
            this.trajectory = new ArrayList<>();
        }
    }

    private void checkConverged() {
        if (!converged && Math.abs(1.0 - Math.abs(Qc - Qn)) < THRESHOLD) {
            converged = true;
            convStatus = (Qn < Qc);
        }
    }

    public boolean decideAction() { 
    	// Initial Action Selection 
    	if (timeStep++ == 0) {
            boolean initAction = (Qn <= Qc);
            lastAction = initAction;
            lastTimestep = 0;
    	    return initAction;
    	}
    	// Learning Converged 
    	if (converged) {
    	    return convStatus;
    	}

        int currentStep = timeStep - 1;
        lastQc = Qc;
        lastQn = Qn;

        // Reward Update 
    	if (monitor != null) {
    	    numTotTraces++;
    	    if (!uniqueTraces.contains(monitor.traceVal)) {
     		    uniqueTraces.add(monitor.traceVal);
    	        reward = 1.0;
    	    } else {
        		numDupTraces++;
    	        reward = 0.0;
    	    }
    	    Qc = Qc + ALPHA * (reward - Qc);
    	} else {
    	    reward = (double)numDupTraces / numTotTraces;
    	    Qn = Qn + ALPHA * (reward - Qn);
        }

        // Exploration Phase
        boolean action;
        if (!converged && Math.random() < EPSILON) {
    	    Random random = new Random();
    	    action = random.nextBoolean();
    	} else {
    	    // Exploitation Phase
    	    action = (Qn <= Qc);
    	}

        if (traj && !converged && lastTimestep >= 0) {
            trajectory.add(new Step(lastAction, reward, lastTimestep, lastQc, lastQn));
        }

        checkConverged();

        lastAction = action;
        lastTimestep = currentStep;

        return action;
    }

    public void setMonitor(AbstractMonitor monitor) {
        this.monitor = monitor;
    	if (converged) {
    	    monitor.recordEvents = false;
    	}
    }

    public void clearMonitor() {
    	this.monitor = null;
    }

    public String getTrajectoryString() {
        if (!traj || trajectory == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Step step : trajectory) {
            String actionStr = step.action ? "create" : "ncreate";
            sb.append("<")
              .append(step.timestep)
              .append(": ")
              .append(actionStr)
              .append(", reward=")
              .append(String.format("%.4f", step.reward))
              .append(", Qc=")
              .append(String.format("%.4f", step.Qc))
              .append(", Qn=")
              .append(String.format("%.4f", step.Qn))
              .append("> ");
        }

        if (converged) {
            sb.append("[converged]");
        }

        return sb.toString().trim();
    }

    public List<Step> getTrajectory() {
        return traj ? trajectory : null;
    }
}
