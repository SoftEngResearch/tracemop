package com.runtimeverification.rvmonitor.java.rt.agent;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import java.util.Random;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;

public class RLAgentDUCB implements Agent {
    private double sumC;
    private double sumN;
    private double countC = 1.0;
    private double countN = 1.0;
    private double reward;

    private int numTotTraces = 0;
    private int numDupTraces = 0;
    
    private double GAMMA;
    private double C;

    private AbstractMonitor monitor = null;
    private HashSet<Long> uniqueTraces;

    private int timeStep = 0;

    private double THRESHOLD;
    public boolean converged = false;
    public boolean convStatus;

    private boolean traj = false;
    private List<Step> trajectory;

    private boolean lastAction = true;
    private int lastTimestep = -1;
    private double lastSumC;
    private double lastSumN;
    private double lastCountC;
    private double lastCountN;

    public static class Step {
        public final boolean action;
        public final double reward;
        public final int timestep;
        public final double MuC;
        public final double MuN;

        public Step(boolean action, double reward, int timestep, double MuC, double MuN) {
            this.action = action;
            this.reward = reward;
            this.timestep = timestep;
            this.MuC = MuC;
            this.MuN = MuN;
        }
    }

    public RLAgentDUCB(HashSet<Long> uniqueTraces, 
        double GAMMA, double C, double THRESHOLD, double initC, double initN) {
		this(uniqueTraces, GAMMA, C, THRESHOLD, initC, initN, false);
	}

    public RLAgentDUCB(HashSet<Long> uniqueTraces,
        double GAMMA, double C, double THRESHOLD, double initC, double initN, boolean traj) {
        this.uniqueTraces = uniqueTraces;
        this.GAMMA = GAMMA;
        this.C = C;
        this.THRESHOLD = THRESHOLD;
        this.sumC = initC;
        this.sumN = initN;
        this.traj = traj;
        if (traj) {
            this.trajectory = new ArrayList<>();
        }
    }

    private void checkConverged() {
        double MuC = sumC / countC;
        double MuN = sumN / countN;
        if (!converged && Math.abs(1.0 - Math.abs(MuC - MuN)) < THRESHOLD) {
            converged = true;
            convStatus = MuC > MuN;
        }
    }

    public boolean createAction() {
    	if (timeStep++ == 0) {
            lastTimestep = 0;
    	    return true;
    	}

        int currentStep = timeStep - 1;
        lastSumC = sumC;
        lastSumN = sumN;
        lastCountC = countC;
        lastCountN = countN;

        if (monitor != null) {
            numTotTraces++;
    	    if (!uniqueTraces.contains(monitor.traceVal)) {
     		    uniqueTraces.add(monitor.traceVal);
                reward = 1.0;
            } else {
                numDupTraces++;
                reward = 0.0;
            }
            sumC = GAMMA * sumC + reward;
            countC = GAMMA * countC + 1.0;            
        } else {
    	    reward = (double)numDupTraces / numTotTraces;
            sumN = GAMMA * sumN + reward;
            countN = GAMMA * countN + 1.0;
        }

        if (traj && lastTimestep >= 0) {
            double lastMuC = lastSumC / lastCountC;
            double lastMuN = lastSumN / lastCountN;
            trajectory.add(new Step(true, reward, lastTimestep, lastMuC, lastMuN));
        }
        lastTimestep = currentStep;
        return true;
    }

    @Override
    public boolean decideAction() {
    	// Initial Action Selection 
    	if (timeStep++ == 0) {
            boolean initAction = (sumN <= sumC);
            lastAction = initAction;
            lastTimestep = 0;
    	    return initAction;
    	}
    	// Learning Converged 
    	if (converged) {
    	    return convStatus;
    	}

        int currentStep = timeStep - 1;
        lastSumC = sumC;
        lastSumN = sumN;
        lastCountC = countC;
        lastCountN = countN;

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
            sumC = GAMMA * sumC + reward;
            countC = GAMMA * countC + 1.0;
        } else {
    	    reward = (double)numDupTraces / numTotTraces;
            sumN = GAMMA * sumN + reward;
            countN = GAMMA * countN + 1.0;
        }
        
        double DUCBc = sumC / countC + C * Math.sqrt(Math.log(timeStep) / countC);
        double DUCBn = sumN / countN + C * Math.sqrt(Math.log(timeStep) / countN);

        boolean action = (DUCBc >= DUCBn);

        if (traj && lastTimestep >= 0) {
            double lastMuC = lastSumC / lastCountC;
            double lastMuN = lastSumN / lastCountN;
            trajectory.add(new Step(true, reward, lastTimestep, lastMuC, lastMuN));
        }

        checkConverged();

        lastAction = action;
        lastTimestep = currentStep;

        return action;
    }

    @Override
    public void setMonitor(AbstractMonitor monitor) {
        this.monitor = monitor;
    }

    @Override
    public void clearMonitor() {
        this.monitor = null;
    }

    public String getTrajectoryString() {
        if (!traj || trajectory == null) return "";

        if (!converged) {
            double stepMuC = sumC / countC;
            double stepMuN = sumN / countN;
            double stepReward;

            if (monitor != null) {
                if (!uniqueTraces.contains(monitor.traceVal)) {
                    stepReward = 1.0;
                } else {
                    stepReward = 0.0;
                }
            } else {
                stepReward = (double)numDupTraces / numTotTraces;
            }
            trajectory.add(new Step(lastAction, stepReward, lastTimestep, stepMuC, stepMuN));
        }
        StringBuilder sb = new StringBuilder();
        for (Step step : trajectory) {
            sb.append("<")
              .append(step.timestep)
              .append(": A=")
              .append(step.action ? "create" : "ncreate")
              .append(", R=")
              .append(String.format("%.2f", step.reward))
              .append(", MuC=")
              .append(String.format("%.2f", step.MuC))
              .append(", MuN=")
              .append(String.format("%.2f", step.MuN))
              .append("> ");
        }
        if (converged) sb.append("[converged]");
        return sb.toString().trim();
    }

    public List<Step> getTrajectory() {
        return traj ? trajectory : null;
    }
}
