package com.claudecode.cli;

import com.claudecode.core.error.ErrorUtils;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns CLI lifecycle resources and releases them once in reverse registration order.
 */
final class CliResourceScope implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CliResourceScope.class);

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private boolean closed;

    synchronized <T extends AutoCloseable> T own(T resource) {
        Objects.requireNonNull(resource, "resource");
        if (closed) throw new IllegalStateException("resource scope is closed");
        resources.addLast(resource);
        return resource;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        while (!resources.isEmpty()) {
            AutoCloseable resource = resources.removeLast();
            try {
                resource.close();
            } catch (Exception failure) {
                log.warn("[STARTUP] CLI lifecycle resource close failed "
                        + "[resourceType={}, failureType={}]",
                    resource.getClass().getName(), failure.getClass().getName(),
                    ErrorUtils.redactedForLogging(failure));
            }
        }
    }
}
