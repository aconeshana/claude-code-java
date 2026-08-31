package com.claudecode.lsp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;


final class GitignoreFilter {

    private static final int BATCH_SIZE = 50;
    private static final int PROCESS_TIMEOUT_SECONDS = 10;

    private GitignoreFilter() {}

    /**
     * Returns the subset of {@code filePaths} that git considers ignored, relative to {@code repoRoot}.
     */
    static Set<String> ignoredPaths(Path repoRoot, List<String> filePaths) {
        Set<String> ignored = new HashSet<>();
        if (repoRoot == null || filePaths.isEmpty()) {
            return ignored;
        }
        List<String> paths = new ArrayList<>(filePaths);
        try {
            for (int i = 0; i < paths.size(); i += BATCH_SIZE) {
                List<String> batch = paths.subList(i, Math.min(i + BATCH_SIZE, paths.size()));
                List<String> command = new ArrayList<>();
                command.add("git");
                command.add("-C");
                command.add(repoRoot.toString());
                command.add("check-ignore");
                command.add("--");
                command.addAll(batch);

                Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
                // Read stdout on a separate thread so a hung git process cannot
                // block us on readLine: the waitFor(timeout) below then forcibly
                // kills it, which closes the stream and unblocks the reader.
                List<String> batchIgnored = new ArrayList<>();
                Thread reader = Thread.ofVirtual().name("gitignore-read").start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            String trimmed = line.strip();
                            if (!trimmed.isEmpty()) {
                                batchIgnored.add(trimmed);
                            }
                        }
                    } catch (IOException _) {
                        // process was destroyed; the stream closed underneath us
                    }
                });
                boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                }
                reader.join();
                if (!finished) {
                    return Set.of(); // timed out — degrade to no filtering
                }
                ignored.addAll(batchIgnored);
            }
        } catch (IOException | InterruptedException _) {
            // git missing, not a repo, or interrupted — degrade to no filtering.
            Thread.currentThread().interrupt();
            return Set.of();
        }
        return ignored;
    }
}
