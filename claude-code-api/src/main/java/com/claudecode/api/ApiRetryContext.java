package com.claudecode.api;

import java.util.function.Supplier;

/** Per-call retry seed used when a streaming 529 is followed by synchronous recovery. */
public final class ApiRetryContext {
    private static final ScopedValue<Integer> INITIAL_529 = ScopedValue.newInstance();

    private ApiRetryContext() {
    }

    public static <T> T withInitial529Errors(int count, Supplier<T> action) {
        return ScopedValue.where(INITIAL_529, Math.max(0, count)).call(action::get);
    }

    static int initial529Errors() {
        return INITIAL_529.isBound() ? INITIAL_529.get() : 0;
    }
}
