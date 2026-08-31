package com.claudecode.runtime.turn;

/**
 * Port for keeping the host awake while a model turn is active.
 */
public interface TurnAwakeGuard {

    void preventSleep();

    void allowSleep();

    static TurnAwakeGuard noop() {
        return NoopHolder.INSTANCE;
    }

    final class NoopHolder {
        private static final TurnAwakeGuard INSTANCE = new TurnAwakeGuard() {
            @Override public void preventSleep() {}
            @Override public void allowSleep() {}
        };

        private NoopHolder() {}
    }
}
