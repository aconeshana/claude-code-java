package com.claudecode.runtime.query;

/**
 * Turn-scoped latency profiler used by non-interactive sessions.
 */
public interface HeadlessTurnProfiler {

    HeadlessTurnProfiler NOOP = new HeadlessTurnProfiler() {
        @Override public void startTurn() {}
        @Override public void checkpoint(String name) {}
        @Override public void finishTurn() {}
    };

    void startTurn();

    void checkpoint(String name);

    void finishTurn();
}
