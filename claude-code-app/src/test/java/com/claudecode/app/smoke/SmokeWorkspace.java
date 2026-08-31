package com.claudecode.app.smoke;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.Strings;

/**
 * One target's isolated home, working directory, and pre-recorded session.
 * <p>Session-origin flags cannot be smoked against an empty machine — {@code -c} on a fresh home
 * continues nothing and would pass without ever deserializing a recorded message, which is exactly
 * the code the harness exists to exercise. So one launch seeds a real transcript first, and every
 * case of that target then shares this home and cwd. Sharing is load-bearing rather than
 * convenient: the project directory a transcript lands in is keyed by cwd, so a case that ran
 * somewhere else would find nothing to continue.
 */
final class SmokeWorkspace {

    private final Path home;
    private final Path workingDirectory;
    private final Path scratchRoot;
    private final String seededSessionId;

    private SmokeWorkspace(Path home, Path workingDirectory, Path scratchRoot, String sessionId) {
        this.home = home;
        this.workingDirectory = workingDirectory;
        this.scratchRoot = scratchRoot;
        this.seededSessionId = sessionId;
    }

    /**
     * @param root  this target's directory; the three directories owned here are recreated from
     *              scratch so a previous run cannot supply the session a case is supposed to have
     *              recorded itself. Only those three, never {@code root} itself — the launcher
     *              keeps its redirected stdin alongside them and would lose it to a wider wipe.
     * @param seeds the launch that records the first transcript, or a no-op when the plan needs no
     *              session — its outcome is returned so an unseedable target fails as itself
     *              rather than as every session-origin case
     */
    static SmokeWorkspace create(Path root, SeedLaunch seeds) {
        Path home = root.resolve("home");
        Path workingDirectory = root.resolve("workspace");
        Path scratchRoot = root.resolve("scratch");
        try {
            for (Path owned : List.of(home, workingDirectory, scratchRoot)) {
                deleteRecursively(owned);
            }
            Files.createDirectories(home.resolve(".claude"));
            Files.createDirectories(workingDirectory);
            Files.createDirectories(scratchRoot);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot prepare the smoke workspace at " + root, cause);
        }
        SmokeOutcome outcome = seeds.run(workingDirectory, home);
        if (outcome.exitCode() != 0) {
            throw new IllegalStateException(
                "the smoke workspace could not record a session to resume from."
                    + outcome.transcript());
        }
        return new SmokeWorkspace(
            home, workingDirectory, scratchRoot, newestSessionId(home.resolve(".claude")));
    }

    /** The launch that records the workspace's first transcript. */
    interface SeedLaunch {

        SmokeOutcome run(Path workingDirectory, Path home);
    }

    Path home() {
        return home;
    }

    Path workingDirectory() {
        return workingDirectory;
    }

    String seededSessionId() {
        return seededSessionId;
    }

    Set<Path> transcriptFiles() {
        Path projects = home.resolve(".claude").resolve("projects");
        if (!Files.isDirectory(projects)) return Set.of();
        try (Stream<Path> paths = Files.walk(projects)) {
            return paths
                .filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".jsonl"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot inspect smoke transcripts under " + projects, cause);
        }
    }

    /** A directory of this case's own, so two cases cannot disagree about one fixture file. */
    Path scratchFor(String caseId) {
        Path scratch = scratchRoot.resolve(caseId);
        try {
            Files.createDirectories(scratch);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot prepare scratch space at " + scratch, cause);
        }
        return scratch;
    }

    private static String newestSessionId(Path configDirectory) {
        Path projects = configDirectory.resolve("projects");
        if (!Files.isDirectory(projects)) {
            throw new IllegalStateException(
                "the seed launch exited 0 but wrote no project directory under " + projects);
        }
        try (Stream<Path> transcripts = Files.walk(projects)) {
            return transcripts
                .filter(path -> Strings.CS.endsWith(path.getFileName().toString(), ".jsonl"))
                .max(Comparator.comparingLong(path -> path.toFile().lastModified()))
                .map(path -> {
                    String name = path.getFileName().toString();
                    return name.substring(0, name.length() - ".jsonl".length());
                })
                .orElseThrow(() -> new IllegalStateException(
                    "the seed launch exited 0 but recorded no transcript under " + projects));
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot inspect " + projects, cause);
        }
    }

    /** Writes the scratch files and directories one case's arguments point at. */
    static void materialize(Path scratch, SmokePlan.Template template) {
        try {
            for (String directory : template.dirs()) {
                Files.createDirectories(scratch.resolve(directory));
            }
            for (var file : template.files().entrySet()) {
                Path path = scratch.resolve(file.getKey());
                Files.createDirectories(path.getParent());
                Files.writeString(path, file.getValue());
            }
        } catch (IOException cause) {
            throw new UncheckedIOException(
                "cannot materialize fixtures for " + template.entryId(), cause);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
