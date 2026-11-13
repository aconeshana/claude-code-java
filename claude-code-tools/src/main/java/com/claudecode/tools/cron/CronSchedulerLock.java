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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
        if (actual == null) return true;
        Instant expected = parseProcessStartToken(expectedStartToken);
        Instant started = parseProcessStartToken(actual);
        if (expected == null || started == null) return expectedStartToken.equals(actual);
        // Legacy ps tokens carry second precision; compare both at that
        // granularity so an old lock and the current API agree on the same
        // process.
        return expected.truncatedTo(ChronoUnit.SECONDS)
            .equals(started.truncatedTo(ChronoUnit.SECONDS));
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
        // Portable JDK API (GetProcessTimes on Windows, /proc on Linux): the
        // previous implementation shelled out to ps, which does not exist on
        // Windows, so every token came back null and PID-reuse protection was
        // vacuous there — a reused PID was treated as the original owner and
        // stale locks could never be taken over.
        try {
            return ProcessHandle.of(pid)
                .map(ProcessHandle::info)
                .flatMap(ProcessHandle.Info::startInstant)
                .map(Object::toString)
                .orElse(null);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static final DateTimeFormatter LEGACY_PS_START_FORMAT =
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    /**
     * Parses the token formats that may appear in lock files: ISO instants
     * written by {@link #processStartToken} and the legacy {@code ps -o lstart=}
     * strings written by earlier builds on POSIX hosts (LC_ALL=C, TZ=UTC).
     */
    private static Instant parseProcessStartToken(String token) {
        if (StringUtils.isBlank(token)) return null;
        String trimmed = token.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException _) {
            try {
                return Instant.from(LEGACY_PS_START_FORMAT.parse(trimmed));
            } catch (RuntimeException _) {
                return null;
            }
        }
    }

    private record LockInfo(String identity, long pid, String procStart) { }
    private record CachedProcessToken(long checkedAt, String token) { }
}
