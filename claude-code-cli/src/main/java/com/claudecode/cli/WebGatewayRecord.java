package com.claudecode.cli;

import com.claudecode.core.serialization.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;
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
            if (usable && ProcessHandle.of(parsed.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                return Optional.of(parsed.withDisplayDefaults());
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("Ignoring unreadable web gateway record {}", file, failure);
        }
        deleteQuietly(file);
        return Optional.empty();
    }

    private WebGatewayRecord withDisplayDefaults() {
        return new WebGatewayRecord(pid, url,
            StringUtils.isBlank(version) ? UNKNOWN_VERSION : version,
            Math.max(startedAtMillis, 0));
    }

    static void write(Path file, WebGatewayRecord record) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        JsonUtils.writeJson(temporary, record, true);
        restrict(temporary);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException _) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
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
        try {
            Files.setPosixFilePermissions(path,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException _) {
            // Non-POSIX filesystems rely on the home directory's own ACL.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.debug("Could not delete web gateway record {}", file, failure);
        }
    }
}
