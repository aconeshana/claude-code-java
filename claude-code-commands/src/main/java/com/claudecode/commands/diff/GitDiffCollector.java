package com.claudecode.commands.diff;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects the working-tree-vs-HEAD git diff for the {@code /diff} command by shelling out to
 * {@code git} (5s timeout per subprocess) and parsing the output into a {@link DiffData}.
 */
public final class GitDiffCollector {

    static final int GIT_TIMEOUT_MS = 5000;
    static final int MAX_FILES = 50;
    static final int MAX_DIFF_SIZE_BYTES = 1_000_000;
    static final int MAX_LINES_PER_FILE = 400;
    static final int MAX_FILES_FOR_DETAILS = 500;

    private static final Pattern SHORTSTAT = Pattern.compile(
        "(\\d+)\\s+files?\\s+changed(?:,\\s+(\\d+)\\s+insertions?\\(\\+\\))?(?:,\\s+(\\d+)\\s+deletions?\\(-\\))?");
    private static final Pattern FILE_DIFF_SPLIT = Pattern.compile("^diff --git ", Pattern.MULTILINE);
    private static final Pattern FILE_HEADER = Pattern.compile("^a/(.+?) b/(.+)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");
    private static final List<String> TRANSIENT_FILES =
        List.of("MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD");

    private final String workingDirectory;

    public GitDiffCollector(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    /**
     * Fetches diff stats, per-file rows, and hunks comparing working tree to HEAD.
     */
    public DiffData collect() {
        GitResult isGit = runGit("rev-parse", "--is-inside-work-tree");
        if (isGit == null || isGit.code() != 0) return null;

        if (isInTransientGitState()) return null;

        // Quick probe: totals without loading content; bail out of per-file

        GitResult shortstat = runGit("--no-optional-locks", "diff", "HEAD", "--shortstat");
        if (shortstat != null && shortstat.code() == 0) {
            DiffData.Stats quick = parseShortstat(shortstat.stdout());
            if (quick != null && quick.filesCount() > MAX_FILES_FOR_DETAILS) {
                return new DiffData(quick, List.of(), Map.of());
            }
        }

        GitResult numstat = runGit("--no-optional-locks", "diff", "HEAD", "--numstat");
        if (numstat == null || numstat.code() != 0) return null;
        NumstatResult parsed = parseGitNumstat(numstat.stdout());

        LinkedHashMap<String, PerFileStat> perFileStats = new LinkedHashMap<>(parsed.perFileStats());
        DiffData.Stats stats = parsed.stats();

        int remainingSlots = MAX_FILES - perFileStats.size();
        if (remainingSlots > 0) {
            Map<String, PerFileStat> untracked = fetchUntrackedFiles(remainingSlots);
            if (untracked != null && !untracked.isEmpty()) {
                stats = new DiffData.Stats(
                    stats.filesCount() + untracked.size(), stats.linesAdded(), stats.linesRemoved());
                perFileStats.putAll(untracked);
            }
        }


// fetchGitDiffHunks preamble reduces to the diff call itself.
        Map<String, List<StructuredPatchHunk>> hunks = fetchHunks();

        List<DiffData.DiffFile> files = new ArrayList<>(perFileStats.size());
        for (Map.Entry<String, PerFileStat> entry : perFileStats.entrySet()) {
            PerFileStat fs = entry.getValue();
            boolean hasHunks = hunks.containsKey(entry.getKey());
            boolean isLargeFile = !fs.isBinary() && !fs.isUntracked() && !hasHunks;
            int totalLines = fs.added() + fs.removed();
            boolean isTruncated = !isLargeFile && !fs.isBinary() && totalLines > MAX_LINES_PER_FILE;
            files.add(new DiffData.DiffFile(
                entry.getKey(), fs.added(), fs.removed(), fs.isBinary(),
                isLargeFile, isTruncated, false, fs.isUntracked()));
        }
        files.sort(Comparator.comparing(DiffData.DiffFile::path));

        return new DiffData(stats, files, hunks);
    }

    // ── parsers (pure, package-private for tests) ────────────────────────────

    /**
     * Parses {@code git diff --shortstat} output; {@code null} when the line
     * doesn't match (e.g. empty diff).
     */
    static DiffData.Stats parseShortstat(String stdout) {
        Matcher m = SHORTSTAT.matcher(stdout);
        if (!m.find()) return null;
        return new DiffData.Stats(
            parseIntOrZero(m.group(1)),
            parseIntOrZero(m.group(2)),
            parseIntOrZero(m.group(3)));
    }

    /**
     * Parses {@code git diff --numstat} output ({@code added\tremoved\tpath};
     * binary counts are {@code '-'}; the path may itself contain tabs). Totals
     * cover every valid line; only the first {@link #MAX_FILES} entries are
     * retained per-file.
     */
    static NumstatResult parseGitNumstat(String stdout) {
        int added = 0;
        int removed = 0;
        int validFileCount = 0;
        LinkedHashMap<String, PerFileStat> perFileStats = new LinkedHashMap<>();

        for (String line : stdout.trim().split("\n")) {
            if (line.isEmpty()) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length < 3) continue;

            validFileCount++;
            String addStr = parts[0];
            String remStr = parts[1];
            String filePath = String.join("\t", Arrays.copyOfRange(parts, 2, parts.length));
            boolean isBinary = Strings.CS.equals("-", addStr) || Strings.CS.equals("-", remStr);
            int fileAdded = isBinary ? 0 : parseIntOrZero(addStr);
            int fileRemoved = isBinary ? 0 : parseIntOrZero(remStr);

            added += fileAdded;
            removed += fileRemoved;

            if (perFileStats.size() < MAX_FILES) {
                perFileStats.put(filePath, new PerFileStat(fileAdded, fileRemoved, isBinary, false));
            }
        }

        return new NumstatResult(new DiffData.Stats(validFileCount, added, removed), perFileStats);
    }

    /**
     * Parses full {@code git diff HEAD} output into per-file hunks.
     */
    static Map<String, List<StructuredPatchHunk>> parseGitDiff(String stdout) {
        LinkedHashMap<String, List<StructuredPatchHunk>> result = new LinkedHashMap<>();
        if (stdout == null || stdout.trim().isEmpty()) return result;

        for (String fileDiff : FILE_DIFF_SPLIT.split(stdout)) {
            if (fileDiff.isEmpty()) continue;
            if (result.size() >= MAX_FILES) break;
            if (fileDiff.length() > MAX_DIFF_SIZE_BYTES) continue;

            String[] lines = fileDiff.split("\n", -1);
            Matcher header = FILE_HEADER.matcher(lines[0]);
            if (!header.find()) continue;
            String filePath = header.group(2);

            List<StructuredPatchHunk> fileHunks = new ArrayList<>();
            HunkBuilder currentHunk = null;
            int lineCount = 0;

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];

                Matcher hunkMatch = HUNK_HEADER.matcher(line);
                if (hunkMatch.find()) {
                    if (currentHunk != null) fileHunks.add(currentHunk.build());
                    currentHunk = new HunkBuilder(
                        parseIntOrZero(hunkMatch.group(1)),
                        hunkMatch.group(2) != null ? parseIntOrZero(hunkMatch.group(2)) : 1,
                        parseIntOrZero(hunkMatch.group(3)),
                        hunkMatch.group(4) != null ? parseIntOrZero(hunkMatch.group(4)) : 1);
                    continue;
                }

                if (Strings.CS.startsWith(line, "index ")
                    || Strings.CS.startsWith(line, "---")
                    || Strings.CS.startsWith(line, "+++")
                    || Strings.CS.startsWith(line, "new file")
                    || Strings.CS.startsWith(line, "deleted file")
                    || Strings.CS.startsWith(line, "old mode")
                    || Strings.CS.startsWith(line, "new mode")
                    || Strings.CS.startsWith(line, "Binary files")) {
                    continue;
                }

                if (currentHunk != null
                    && (Strings.CS.startsWith(line, "+") || Strings.CS.startsWith(line, "-") || Strings.CS.startsWith(line, " ") || line.isEmpty())) {
                    if (lineCount >= MAX_LINES_PER_FILE) continue;
                    currentHunk.lines.add(line);
                    lineCount++;
                }
            }

            if (currentHunk != null) fileHunks.add(currentHunk.build());
            if (!fileHunks.isEmpty()) result.put(filePath, List.copyOf(fileHunks));
        }

        return result;
    }

    // ── git plumbing ─────────────────────────────────────────────────────────

    /**
     * True while a merge / rebase / cherry-pick / revert is in flight — the
     * working tree then contains incoming changes that were not intentionally
     * made by the user, so diff collection is skipped (returns {@code null}).
     */
    private boolean isInTransientGitState() {
        GitResult gitDirResult = runGit("rev-parse", "--git-dir");
        if (gitDirResult == null || gitDirResult.code() != 0) return false;
        String gitDir = gitDirResult.stdout().trim();
        if (gitDir.isEmpty()) return false;

        Path dir = Path.of(gitDir);
        if (!dir.isAbsolute()) {
            dir = Path.of(workingDirectory).resolve(dir);
        }
        for (String file : TRANSIENT_FILES) {
            if (Files.exists(dir.resolve(file))) return true;
        }
        return false;
    }

    /** Untracked (non-ignored) file names, capped at {@code maxFiles}; no content reads. */
    private Map<String, PerFileStat> fetchUntrackedFiles(int maxFiles) {
        GitResult result = runGit("--no-optional-locks", "ls-files", "--others", "--exclude-standard");
        if (result == null || result.code() != 0 || result.stdout().trim().isEmpty()) return null;

        LinkedHashMap<String, PerFileStat> perFileStats = new LinkedHashMap<>();
        for (String path : result.stdout().trim().split("\n")) {
            if (path.isEmpty()) continue;
            if (perFileStats.size() >= maxFiles) break;
            perFileStats.put(path, new PerFileStat(0, 0, false, true));
        }
        return perFileStats.isEmpty() ? null : perFileStats;
    }


    private Map<String, List<StructuredPatchHunk>> fetchHunks() {
        GitResult diff = runGit("--no-optional-locks", "diff", "HEAD");
        if (diff == null || diff.code() != 0) return Map.of();
        return parseGitDiff(diff.stdout());
    }

    /**
     * Runs git with the given args in {@link #workingDirectory}.
     */
    private GitResult runGit(String... args) {
        List<String> command = new ArrayList<>(args.length + 1);
        command.add("git");
        command.addAll(Arrays.asList(args));
        ProcessResult result = ProcessRunner.run(
            command, Path.of(workingDirectory), Duration.ofMillis(GIT_TIMEOUT_MS));
        if (result.timedOut() || result.exitCode() < 0) return null;
        return new GitResult(result.exitCode(), result.stdout());
    }

    private static int parseIntOrZero(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    // ── internal shapes ──────────────────────────────────────────────────────


    record PerFileStat(int added, int removed, boolean isBinary, boolean isUntracked) {}


    record NumstatResult(DiffData.Stats stats, Map<String, PerFileStat> perFileStats) {}

    /** Mutable accumulator for one hunk while scanning a file diff. */
    private static final class HunkBuilder {
        final int oldStart;
        final int oldLines;
        final int newStart;
        final int newLines;
        final List<String> lines = new ArrayList<>();

        HunkBuilder(int oldStart, int oldLines, int newStart, int newLines) {
            this.oldStart = oldStart;
            this.oldLines = oldLines;
            this.newStart = newStart;
            this.newLines = newLines;
        }

        StructuredPatchHunk build() {
            return new StructuredPatchHunk(oldStart, oldLines, newStart, newLines, lines);
        }
    }

    private record GitResult(int code, String stdout) {}
}
