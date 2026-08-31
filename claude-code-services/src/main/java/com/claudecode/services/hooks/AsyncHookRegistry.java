package com.claudecode.services.hooks;

import com.claudecode.core.engine.AsyncHookResponse;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns pending background Bash hooks for one {@link HookEngine} instance.
 */
final class AsyncHookRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncHookRegistry.class);

    private final ConcurrentHashMap<String, PendingAsyncHook> pendingHooks = new ConcurrentHashMap<>();
    private final AtomicLong processIdCounter = new AtomicLong();

    String register(String hookName, String command, String hookEvent, String toolName, String pluginId,
                    long timeout, Process process) {
        String processId = "async-hook-" + processIdCounter.incrementAndGet();
        PendingAsyncHook entry = new PendingAsyncHook(
            processId, hookName, hookEvent, toolName, pluginId, process);
        pendingHooks.put(processId, entry);
        LOG.debug("Registered async hook {} ({}), timeout {}ms", processId, command, timeout);
        return processId;
    }

    /** Publishes a background process's terminal output before its terminal status. */
    void complete(String processId, String stdout, String stderr, int exitCode, AsyncStatus status) {
        PendingAsyncHook pending = pendingHooks.get(processId);
        if (pending == null) {
            return;
        }
        pending.stdout = stdout;
        pending.stderr = stderr;
        pending.exitCode = exitCode;
        pending.status = status;
    }

    List<AsyncHookResponse> checkForAsyncHookResponses() {
        List<AsyncHookResponse> responses = new ArrayList<>();
        List<String> delivered = new ArrayList<>();
        for (PendingAsyncHook pending : pendingHooks.values()) {
            if (pending.status == AsyncStatus.RUNNING) {
                continue;
            }
            if (pending.status == AsyncStatus.COMPLETED && !pending.responseAttachmentSent) {
                String stdout = pending.stdout;
                if (stdout != null && !stdout.trim().isEmpty()) {
                    String responseJson = firstSyncJsonLine(stdout);
                    if (responseJson != null) {
                        responses.add(new AsyncHookResponse(
                            pending.processId, responseJson, pending.hookName, pending.hookEvent,
                            pending.toolName, pending.pluginId, stdout, pending.stderr, pending.exitCode));
                    }
                }
            }
            pending.responseAttachmentSent = true;
            delivered.add(pending.processId);
        }
        for (String processId : delivered) {
            pendingHooks.remove(processId);
        }
        return responses;
    }

    void removeDeliveredAsyncHooks(List<String> processIds) {
        for (String processId : processIds) {
            pendingHooks.remove(processId);
        }
    }

    void finalizePendingAsyncHooks() {
        for (PendingAsyncHook pending : pendingHooks.values()) {
            if (pending.status == AsyncStatus.COMPLETED) {
                LOG.debug("Finalizing completed async hook {}", pending.processId);
            } else {
                if (pending.process.isAlive()) {
                    pending.process.destroyForcibly();
                }
                pending.status = AsyncStatus.CANCELLED;
                LOG.debug("Killed in-flight async hook {} on shutdown", pending.processId);
            }
        }
        pendingHooks.clear();
    }

    private static String firstSyncJsonLine(String stdout) {
        for (String line : stdout.split("\\n", -1)) {
            String trimmed = line.trim();
            if (!Strings.CS.startsWith(trimmed, "{")) {
                continue;
            }
            try {
                JsonNode json = JsonUtils.getMapper().readTree(trimmed);
                if (!json.has("async")) {
                    return trimmed;
                }
            } catch (Exception _) {
                // Non-JSON output is ordinary command output, not a deferred hook response.
            }
        }
        return null;
    }

    enum AsyncStatus {
        RUNNING, COMPLETED, KILLED, CANCELLED
    }

    private static final class PendingAsyncHook {
        private final String processId;
        private final String hookName;
        private final String hookEvent;
        private final String toolName;
        private final String pluginId;
        private final Process process;
        private volatile AsyncStatus status = AsyncStatus.RUNNING;
        private volatile String stdout = "";
        private volatile String stderr = "";
        private volatile int exitCode = -1;
        private volatile boolean responseAttachmentSent;

        private PendingAsyncHook(String processId, String hookName, String hookEvent,
                                 String toolName, String pluginId, Process process) {
            this.processId = processId;
            this.hookName = hookName;
            this.hookEvent = hookEvent;
            this.toolName = toolName;
            this.pluginId = pluginId;
            this.process = process;
        }
    }
}
