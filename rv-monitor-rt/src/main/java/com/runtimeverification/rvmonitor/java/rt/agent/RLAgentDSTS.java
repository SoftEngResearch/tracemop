package com.runtimeverification.rvmonitor.java.rt.agent;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class RLAgentDSTS implements Agent {
    private double alphaC;
    private double alphaN;
    private double betaC = 1.0;
    private double betaN = 1.0;
    private double reward;
    
    private int numTotTraces = 0;
    private int numDupTraces = 0;

    private double GAMMA;

    private AbstractMonitor monitor = null;
    private HashSet<Long> uniqueTraces;

    private int timeStep = 0;

    private double THRESHOLD;
    public boolean converged = false;
    public boolean convStatus;

    private boolean traj;
    private List<Step> trajectory;

    private boolean lastAction = true;
    private int lastTimestep = -1;
    private double lastAlphaC;
    private double lastAlphaN;
    private double lastBetaC;
    private double lastBetaN;

    private final Random RNG = new Random();

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

    public RLAgentDSTS(HashSet<Long> uniqueTraces, 
        double GAMMA, double THRESHOLD, double initC, double initN) {
        this(uniqueTraces, GAMMA, THRESHOLD, initC, initN, false);
    }

    public RLAgentDSTS(HashSet<Long> uniqueTraces,
        double GAMMA, double THRESHOLD, double initC, double initN, boolean traj) {
        this.uniqueTraces = uniqueTraces;
        this.GAMMA = GAMMA;
        this.THRESHOLD = THRESHOLD;
        this.alphaC = Math.max(initC, 1.0);
        this.alphaN = Math.max(initN, 1.0);
        this.traj = traj;
        if (traj) {
            this.trajectory = new ArrayList<>();
        }
    }

    private void checkConverged() {
        double MuC = alphaC / (alphaC + betaC);
        double MuN = alphaN / (alphaN + betaN);
        if (!converged && Math.abs(1.0 - Math.abs(MuC - MuN)) < THRESHOLD) {
            converged = true;
            convStatus = (MuC > MuN);
        }
    }

    public boolean createAction() {
        if (timeStep++ == 0) {
            lastTimestep = 0;
            return true;
        }            

        int currentStep = timeStep - 1;
        lastAlphaC = alphaC;
        lastAlphaN = alphaN;
        lastBetaC = betaC;
        lastBetaN = betaN;

        if (monitor != null) {
            numTotTraces++;
    	    if (!uniqueTraces.contains(monitor.traceVal)) {
     		    uniqueTraces.add(monitor.traceVal);
                reward = 1.0;
            } else {
                numDupTraces++;
                reward = 0.0;
            }
            alphaC += reward;
            betaC  += (1.0 - reward);
        } else {
            reward = (double)numDupTraces / numTotTraces;
            alphaN += reward;
            betaN  += (1.0 - reward);
        }

        alphaC = Math.max(alphaC * (1.0 - GAMMA), 1e-6);
        alphaN = Math.max(alphaN * (1.0 - GAMMA), 1e-6);
        betaC  = Math.max(betaC  * (1.0 - GAMMA), 1e-6);
        betaN  = Math.max(betaN  * (1.0 - GAMMA), 1e-6);

        if (traj && lastTimestep >= 0) {
            double lastMuC = lastAlphaC / (lastAlphaC + lastBetaC);
            double lastMuN = lastAlphaN / (lastAlphaN + lastBetaN); 
            trajectory.add(new Step(lastAction, reward, lastTimestep, lastMuC, lastMuN));
        }
        lastTimestep = currentStep;
        return true;
    }

    @Override
    public boolean decideAction() {
        // Initial Action Selection 
        if (timeStep++ == 0) {
            boolean initAction = (alphaN <= alphaC);
            lastAction = initAction;
            lastTimestep = 0;
            return initAction;
        }            
        // Learning Converged
        if (converged) {
            return convStatus;
        }

        int currentStep = timeStep - 1;
        lastAlphaC = alphaC;
        lastAlphaN = alphaN;
        lastBetaC = betaC;
        lastBetaN = betaN;

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
            alphaC += reward;
            betaC  += (1.0 - reward);
        } else {
            reward = (double)numDupTraces / numTotTraces;
            alphaN += reward;
            betaN  += (1.0 - reward);
        }

        alphaC = Math.max(alphaC * (1.0 - GAMMA), 1e-6);
        alphaN = Math.max(alphaN * (1.0 - GAMMA), 1e-6);
        betaC  = Math.max(betaC  * (1.0 - GAMMA), 1e-6);
        betaN  = Math.max(betaN  * (1.0 - GAMMA), 1e-6);

        double thetaC = sampleBeta(alphaC, betaC);
        double thetaN = sampleBeta(alphaN, betaN);

        boolean action = (thetaC >= thetaN);

        if (traj && lastTimestep >= 0) {
            double lastMuC = lastAlphaC / (lastAlphaC + lastBetaC);
            double lastMuN = lastAlphaN / (lastAlphaN + lastBetaN); 
            trajectory.add(new Step(lastAction, reward, lastTimestep, lastMuC, lastMuN));
        }
        
        checkConverged();
        
        lastAction = action;
        lastTimestep = timeStep - 1;

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
            double stepMuC = alphaC / (alphaC + betaC);
            double stepMuN = alphaN / (alphaN + betaN);
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
        for (Step s : trajectory) {
            sb.append("<")
              .append(s.timestep)
              .append(": A=")
              .append(s.action ? "create" : "ncreate")
              .append(", R=")
              .append(String.format("%.2f", s.reward))
              .append(", MuC=")
              .append(String.format("%.2f", s.MuC))
              .append(", MuN=")
              .append(String.format("%.2f", s.MuN))
              .append("> ");
        }
        if (converged) sb.append("[converged]");
        return sb.toString().trim();
    }

    public List<Step> getTrajectory() {
        return traj ? trajectory : null;
    }

    private double sampleBeta(double alpha, double beta) {
        double y1 = sampleGamma(alpha);
        double y2 = sampleGamma(beta);
        return y1 / (y1 + y2);
    }

    private double sampleGamma(double shape) {
        if (shape < 1.0) {
            double u = RNG.nextDouble();
            return sampleGamma(1.0 + shape) * Math.pow(u, 1.0 / shape);
        }

        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);

        while (true) {
            double x = RNG.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0) continue;

            v = v * v * v;
            double u = RNG.nextDouble();

            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }    
}
