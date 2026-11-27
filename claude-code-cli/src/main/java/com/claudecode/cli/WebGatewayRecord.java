package com.claudecode.cli;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.process.ProcessUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionlink.OwnerOnlyPermissions;
import org.apache.commons.lang3.StringUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discovery record of the one global web gateway ({@code ~/.claude/web-gateway.json}).
 *
 * <p>The record carries the gateway's URL with its access token, so it is written
 * 0600. A record whose owning pid has exited is stale and is deleted on read, which
 * keeps a crashed gateway from blocking every later {@code /web}.
 *
 * <p>The version is what lets a later {@code claude web} say whose code a browser
 * is actually running: a second invocation reuses a live gateway rather than
 * starting its own, so without this field the two outcomes are indistinguishable.
 * {@code startedAtMillis} is the only source for an uptime, and both are for
 * display only — never for a decision.
 *
 * @param version        the product version that wrote this record; {@link #UNKNOWN_VERSION}
 *                       when the file predates the field
 * @param startedAtMillis when that gateway began serving, or {@code 0} when unknown
 */
record WebGatewayRecord(long pid, String url, String version, long startedAtMillis) {

    /** Reported for a record written before the field existed, and for a blank value. */
    static final String UNKNOWN_VERSION = "unknown";

    private static final long WATCH_FALLBACK_MILLIS = 100L;
    private static final Logger log = LoggerFactory.getLogger(WebGatewayRecord.class);

    /**
     * The record's gateway if its owner is still alive; otherwise deletes the stale file.
     *
     * <p>A record from an older build has neither of the display fields. They are
     * defaulted rather than required so that reading one still identifies its
     * gateway — which is precisely the case {@code stop} exists for.
     */
    static Optional<WebGatewayRecord> readLive(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            WebGatewayRecord parsed = JsonUtils.readJson(file, WebGatewayRecord.class);
            boolean usable = parsed.pid() > 0 && !StringUtils.isBlank(parsed.url());
            if (usable && ProcessUtils.isProcessRunning(parsed.pid())) {
                return Optional.of(parsed.withDisplayDefaults());
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("Ignoring unreadable web gateway record {}", file, failure);
        }
        deleteQuietly(file);
        return Optional.empty();
    }

    /**
     * Waits until {@code file} holds a live gateway record or {@code timeout} expires.
     *
     * <p>Registers a {@link WatchService} on the parent directory before the first read so
     * Linux/Windows wake immediately on atomic move, while bounded {@link WatchService#poll}
     * caps latency to {@value #WATCH_FALLBACK_MILLIS} ms on macOS where the default JDK
     * {@code WatchService} polls internally.
     */
    static Optional<WebGatewayRecord> awaitLive(Path file, Duration timeout)
            throws IOException, InterruptedException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long deadlineNanos = System.nanoTime() + Math.max(0L, timeout.toNanos());
        if (parent == null) {
            return readLive(file);
        }
        try (WatchService watch = parent.getFileSystem().newWatchService()) {
            parent.register(watch, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY);
            while (true) {
                Optional<WebGatewayRecord> live = readLive(file);
                if (live.isPresent()) {
                    return live;
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0) {
                    return Optional.empty();
                }
                WatchKey key = watch.poll(
                    Math.min(WATCH_FALLBACK_MILLIS, remainingMs), TimeUnit.MILLISECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                }
            }
        }
    }

    private WebGatewayRecord withDisplayDefaults() {
        return new WebGatewayRecord(pid, url,
            StringUtils.isBlank(version) ? UNKNOWN_VERSION : version,
            Math.max(startedAtMillis, 0));
    }

    static void write(Path file, WebGatewayRecord record) throws IOException {
        FileUtils.atomicReplace(file, temporary -> {
            JsonUtils.writeJson(temporary, record, true);
            restrict(temporary);
        });
        restrict(file);
    }

    /** Deletes the record only when this process still owns it. */
    static void removeIfOwnedBy(Path file, long pid) {
        if (!Files.isRegularFile(file)) return;
        try {
            if (JsonUtils.readJson(file, WebGatewayRecord.class).pid() == pid) {
                deleteQuietly(file);
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("Could not inspect web gateway record {}", file, failure);
        }
    }

    private static void restrict(Path path) {
        OwnerOnlyPermissions.restrictFile(path);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.debug("Could not delete web gateway record {}", file, failure);
        }
    }
}
