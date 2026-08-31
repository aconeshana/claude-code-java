package com.claudecode.core.engine;

import java.util.List;
import java.util.function.Supplier;

/**
 * Scoped process-history context for a {@code /btw} fork.
 */
public final class SideQuestionContext {
    public record Exchange(String question, String response) { }

    private record Scope(List<Exchange> history, AbortController abortController) { }

    private static final ScopedValue<Scope> SCOPE = ScopedValue.newInstance();

    private SideQuestionContext() { }

    public static <T> T withHistory(List<Exchange> history,
                                    AbortController abortController,
                                    Supplier<T> action) {
        Scope scope = new Scope(history == null ? List.of() : List.copyOf(history), abortController);
        return ScopedValue.where(SCOPE, scope).call(action::get);
    }

    public static List<Exchange> history() {
        Scope scope = SCOPE.isBound() ? SCOPE.get() : null;
        return scope == null ? List.of() : scope.history();
    }

    public static AbortController abortController() {
        Scope scope = SCOPE.isBound() ? SCOPE.get() : null;
        return scope == null ? null : scope.abortController();
    }
}
