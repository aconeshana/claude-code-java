package com.claudecode.cli;

import com.claudecode.core.error.ErrorUtils;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs independent interactive-startup work on named virtual threads.
 */
final class CliStartupTasks {

    private static final Logger log = LoggerFactory.getLogger(CliStartupTasks.class);

    private CliStartupTasks() {}

    static CompletableFuture<Void> run(String threadName, Runnable task) {
        return supply(threadName, () -> {
            task.run();
            return null;
        });
    }

    static <T> CompletableFuture<T> supply(String threadName, Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                log.warn("[STARTUP] Startup task failed [task={}, failureType={}]",
                    threadName, failure.getClass().getName(),
                    ErrorUtils.redactedForLogging(failure));
                result.completeExceptionally(failure);
            }
        });
        return result;
    }
}
