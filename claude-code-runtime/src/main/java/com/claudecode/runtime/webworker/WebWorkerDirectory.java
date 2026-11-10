package com.claudecode.runtime.webworker;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.serialization.JsonUtils;
import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code ~/.claude/web-workers/} directory: one registration file per live
 * interactive process, written by the process itself and read by the standalone
 * {@code claude web} supervisor.
 *
 * <p>Registration is deliberately advisory. A process that dies without running
 * {@link Registration#close()} leaves its file behind, so {@link #scan()} treats
 * a registration whose pid is no longer alive as stale, deletes it, and never
 * reports it. The directory itself is created 0700 and each file 0600, because
 * every registration carries a live authentication token.
 *
 * <p>Writes go through a temporary sibling and an atomic move so a supervisor
 * polling this directory never observes a half-written JSON document.
 */
@Explanation("Cross-process discovery of Session Link endpoints for the claude web supervisor")
public final class WebWorkerDirectory {

    private static final Logger log = LoggerFactory.getLogger(WebWorkerDirectory.class);

    private final Path directory;

    public WebWorkerDirectory(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    /** The directory this instance manages. */
    public Path directory() {
        return directory;
    }

    /**
     * Publishes a registration for this process and returns a handle that
     * deletes the file on close. A failure to publish is reported to the caller
     * as an exception: the supervisor would otherwise silently never see this
     * process, and the user would be told {@code claude web} shows nothing.
     */
    public Registration register(WebWorkerRegistration registration) throws IOException {
        Files.createDirectories(directory);
        restrictDirectory(directory);
        Path target = fileFor(registration.pid());
        writeAtomically(target, registration);
        return new Registration(target);
    }

    /**
     * Every live registration, most recently modified first.
     *
     * <p>Stale entries are removed as a side effect: a pid that no longer exists
     * cannot own a socket that accepts connections, and leaving those files
     * behind would make every supervisor start do a growing amount of dead work.
     */
    public List<WebWorkerRegistration> scan() {
        if (!Files.isDirectory(directory)) return List.of();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            stream.forEach(files::add);
        } catch (IOException failure) {
            log.debug("Could not list web worker registrations in {}", directory, failure);
            return List.of();
        }
        List<WebWorkerRegistration> live = new ArrayList<>();
        for (Path file : files) {
            Optional<WebWorkerRegistration> parsed = read(file);
            if (parsed.isEmpty()) {
                deleteQuietly(file);
                continue;
            }
            WebWorkerRegistration registration = parsed.get();
            if (Boolean.FALSE.equals(ProcessHandle.of(registration.pid()).map(ProcessHandle::isAlive).orElse(false))) {
                deleteQuietly(file);
                continue;
            }
            live.add(registration);
        }
        live.sort(Comparator.comparingLong(WebWorkerRegistration::pid));
        return List.copyOf(live);
    }

    /** Removes this process's registration; safe to call more than once. */
    public void unregister(long pid) {
        deleteQuietly(fileFor(pid));
    }

    private Path fileFor(long pid) {
        return directory.resolve(pid + ".json");
    }

    private static Optional<WebWorkerRegistration> read(Path file) {
        try {
            WebWorkerRegistration registration =
                JsonUtils.readJson(file, WebWorkerRegistration.class);
            boolean usable = registration.pid() > 0
                && registration.socket() != null && !StringUtils.isBlank(registration.socket())
                && registration.token() != null && !StringUtils.isBlank(registration.token());
            return usable ? Optional.of(registration) : Optional.empty();
        } catch (IOException | RuntimeException failure) {
            log.debug("Ignoring unreadable web worker registration {}", file, failure);
            return Optional.empty();
        }
    }

    private static void writeAtomically(Path target, WebWorkerRegistration registration)
            throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        JsonUtils.writeJson(temporary, registration, true);
        restrictFile(temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException _) {
            // A filesystem without atomic rename still needs to be usable; the
            // move stays in the same directory so the partial-write window is
            // as small as the platform allows.
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrictDirectory(Path path) {
        restrict(path, Set.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
    }

    private static void restrictFile(Path path) {
        restrict(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }

    /**
     * Applies POSIX permissions where the filesystem supports them. A non-POSIX
     * filesystem (Windows) must not make registration fail — the directory still
     * lives under the user's home, which is the platform's own ACL boundary.
     */
    private static void restrict(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException _) {
            // Best effort; see the method contract.
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException failure) {
            log.debug("Could not delete web worker registration {}", file, failure);
        }
    }

    /** An owned registration file, deleted on close. */
    public static final class Registration implements AutoCloseable {
        private final Path file;
        private boolean closed;

        private Registration(Path file) {
            this.file = file;
        }

        public Path file() {
            return file;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            deleteQuietly(file);
        }
    }
}
