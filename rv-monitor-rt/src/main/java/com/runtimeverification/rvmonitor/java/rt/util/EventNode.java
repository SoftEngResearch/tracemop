package com.runtimeverification.rvmonitor.java.rt.util;

import java.util.HashMap;
import java.util.Set;

public class EventNode {
    public String event;
    public Set<String> monitors = null; // only used during traces collection process
    public HashMap<String, EventNode> children = new HashMap<>();
    public boolean visited = false;

    public EventNode(String event) {
        this.event = event;
    }

    public EventNode getNextNodeAfterSeeingEvent(String event) {
        if (children.containsKey(event)) {
            return children.get(event);
        }

        EventNode nextEvent = new EventNode(event);
        children.put(event, nextEvent);
        return nextEvent;
    }
}
