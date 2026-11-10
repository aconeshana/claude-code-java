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
 */
record WebGatewayRecord(long pid, String url) {

    private static final Logger log = LoggerFactory.getLogger(WebGatewayRecord.class);

    /** The record's gateway if its owner is still alive; otherwise deletes the stale file. */
    static Optional<WebGatewayRecord> readLive(Path file) {
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            WebGatewayRecord record = JsonUtils.readJson(file, WebGatewayRecord.class);
            boolean usable = record.pid() > 0 && !StringUtils.isBlank(record.url());
            if (usable && ProcessHandle.of(record.pid()).map(ProcessHandle::isAlive).orElse(false)) {
                return Optional.of(record);
            }
        } catch (IOException | RuntimeException failure) {
            log.debug("Ignoring unreadable web gateway record {}", file, failure);
        }
        deleteQuietly(file);
        return Optional.empty();
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
