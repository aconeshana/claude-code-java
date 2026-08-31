package com.claudecode.core.engine;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ApiRetryEvents {
    public record Event(int status, int retryAttempt, int maxRetries, long retryInMs) { }

    private static final ScopedValue<Consumer<Event>> OBSERVER = ScopedValue.newInstance();

    private ApiRetryEvents() { }

    public static <T> T observe(Consumer<Event> observer, Supplier<T> action) {
        return ScopedValue.where(OBSERVER, observer).call(action::get);
    }

    public static void emit(Event event) {
        Consumer<Event> observer = OBSERVER.isBound() ? OBSERVER.get() : null;
        if (observer != null && event != null) observer.accept(event);
    }
}
