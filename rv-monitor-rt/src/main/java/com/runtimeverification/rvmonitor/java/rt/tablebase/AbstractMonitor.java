package com.runtimeverification.rvmonitor.java.rt.tablebase;

import com.runtimeverification.rvmonitor.java.rt.RVMObject;
import com.runtimeverification.rvmonitor.java.rt.util.TimeSeries;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractMonitor implements IMonitor, RVMObject {
	public int monitorid = -123;

	public String location = null;
	private boolean activated = true;

	public int traceVal = 0;
    public TimeSeries.Node node = null; 
    public boolean recordEvents = true;

	/**
	 * Terminates this monitor instance. The actual code depends on the specification and,
	 * therefore, is to be implemented in the generated code.
	 * @param treeid
	 */
	protected abstract void terminateInternal(int treeid);

	public void activate() {
	    this.activated = true;
	}

	public void deactivate() {
	    this.activated = false;
	}

	public boolean isActivated() {
	    return activated;
	}

	@Override
	public String toString() {
		String r = this.getClass().getSimpleName();
		r += "#";
		r += String.format("%03x", this.hashCode() & 0xFFF);
		return r;
	}
}
