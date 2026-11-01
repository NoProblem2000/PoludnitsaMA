package com.poludnitsa.inspector.api.dto;

public enum ProfileSource {
    /** Flight Recorder in the target, driven through its FlightRecorderMXBean */
    JFR,
    /** async-profiler attached to the target ({@code asprof}), Linux and macOS */
    ASYNC_PROFILER
}
