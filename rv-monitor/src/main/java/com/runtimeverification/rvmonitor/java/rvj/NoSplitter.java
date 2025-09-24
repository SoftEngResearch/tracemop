package com.runtimeverification.rvmonitor.java.rvj;

import com.beust.jcommander.converters.IParameterSplitter;
import java.util.Collections;
import java.util.List;

public class NoSplitter implements IParameterSplitter {
    @Override
    public List<String> split(String value) {
        return Collections.singletonList(value);
    }
}
