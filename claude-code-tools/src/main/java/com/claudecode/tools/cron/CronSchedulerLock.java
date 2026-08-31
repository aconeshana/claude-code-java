package com.claudecode.tools.cron;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

/**
 * Atomic lease for one project scheduler.
 *
 * <ul>
 *   <li>O_EXCL acquisition, PID liveness
 *       plus process-start-token checks for PID reuse, stale-lock takeover,
 *       idempotent re-acquisition, and release.</li>
 * </ul>
 */
final class CronSchedulerLock {
    private static final long PROCESS_TOKEN_CACHE_MS = 60_000L;
    private static final Map<Long, CachedProcessToken> PROCESS_TOKEN_CACHE =
        new ConcurrentHashMap<>();
    private static volatile String currentProcessStartToken;

    private final Path path;
    private final String identity;

    CronSchedulerLock(Path projectRoot, String identity) {
        this.path = projectRoot.resolve(".claude").resolve("scheduled_tasks.lock");
        this.identity = StringUtils.isBlank(identity)
            ? UUID.randomUUID().toString() : identity;
    }

    synchronized boolean tryAcquire() {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, body(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return true;
        } catch (IOException _) {
            LockInfo existing = read();
            if (existing != null && identity.equals(existing.identity())) {
                if (existing.pid() != ProcessHandle.current().pid()) {
                    try {
                        Files.writeString(path, body(), StandardCharsets.UTF_8,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                    } catch (IOException _) { }
                }
                return true;
            }
            if (existing != null
                    && sameProcess(existing.pid(), existing.procStart())) return false;
            try {
                Files.deleteIfExists(path);
                Files.writeString(path, body(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return true;
            } catch (IOException _) {
                return false;
            }
        }
    }

    synchronized void release() {
        LockInfo existing = read();
        if (existing != null && identity.equals(existing.identity())) {
            try { Files.deleteIfExists(path); } catch (IOException _) { }
        }
    }

    Path path() { return path; }

    private String body() {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("sessionId", identity);
        node.put("pid", ProcessHandle.current().pid());
        String procStart = currentProcessStartToken();
        if (procStart != null) node.put("procStart", procStart);
        node.put("acquiredAt", System.currentTimeMillis());
        return JsonUtils.toJson(node);
    }

    private LockInfo read() {
        try {
            JsonNode node = JsonUtils.getMapper().readTree(path.toFile());
            if (!node.isObject() || !node.path("sessionId").isTextual()
                    || !node.path("pid").canConvertToLong()) return null;
            return new LockInfo(node.path("sessionId").asText(), node.path("pid").asLong(),
                node.path("procStart").isTextual() ? node.path("procStart").asText() : null);
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    static String currentProcessStartToken() {
        String cached = currentProcessStartToken;
        if (cached != null) return cached;
        cached = processStartToken(ProcessHandle.current().pid());
        currentProcessStartToken = cached;
        return cached;
    }


    static boolean sameProcess(long pid, String expectedStartToken) {
        if (pid <= 0 || !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            return false;
        }
        if (expectedStartToken == null) return true;
        String actual = cachedProcessStartToken(pid);
        return actual == null || actual.equals(expectedStartToken);
    }

    private static String cachedProcessStartToken(long pid) {
        long now = System.currentTimeMillis();
        CachedProcessToken cached = PROCESS_TOKEN_CACHE.get(pid);
        if (cached != null && now - cached.checkedAt() < PROCESS_TOKEN_CACHE_MS) {
            return cached.token();
        }
        String token = processStartToken(pid);
        PROCESS_TOKEN_CACHE.put(pid, new CachedProcessToken(now, token));
        return token;
    }

    private static String processStartToken(long pid) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                "ps", "-o", "lstart=", "-p", Long.toString(pid));
            builder.environment().put("LC_ALL", "C");
            builder.environment().put("TZ", "UTC");
            process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(1).toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0) return null;
            String value = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException _) {
            return null;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private record LockInfo(String identity, long pid, String procStart) { }
    private record CachedProcessToken(long checkedAt, String token) { }
}
