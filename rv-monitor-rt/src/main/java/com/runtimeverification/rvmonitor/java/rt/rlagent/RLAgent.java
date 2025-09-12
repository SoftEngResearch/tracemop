package com.runtimeverification.rvmonitor.java.rt.rlagent;

import com.runtimeverification.rvmonitor.java.rt.tablebase.AbstractMonitor;
import java.lang.Math;
import java.util.Random;
import java.util.HashSet;

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

    public RLAgent(HashSet<Integer> uniqueTraces, 
	double alpha, double epsilon, double threshold, double initc, double initn) {
        this.uniqueTraces = uniqueTraces;

	this.ALPHA = alpha;
	this.EPSILON = epsilon;
	this.THRESHOLD = threshold;

	this.Qc = initc;
	this.Qn = initn;
    }

    private void checkConverged() {
	if (Math.abs(1.0 - Math.abs(Qc - Qn)) < THRESHOLD) {
	    converged = true;
	    convStatus = (Qn < Qc) ? true : false;
	} 
    }

    public boolean decideAction() { 
	// Initial Action Selection 
	if (timeStep++ == 0) {
	    return true;
	}
	// Learning Converged 
	if (converged) {
	    return convStatus;
	}
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
	    reward = (double)numDupTraces/numTotTraces;
	    Qn = Qn + ALPHA * (reward - Qn);
        }
	checkConverged();
	
	// Exploration Phase
        if (!converged && Math.random() < EPSILON) {
	    Random random = new Random();
	    return random.nextBoolean();
	} 	   
	// Exploitation Phase
	return (Qn <= Qc) ? true : false;
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
}
