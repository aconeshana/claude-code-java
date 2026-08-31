package com.claudecode.tools.files;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.FileReadIgnorePattern;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.text.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;

/**
 * Tool for searching file contents using ripgrep (rg) or Java regex fallback.
 * Task 58.1 enhancements:
 * - Multi output mode (CONTENT / FILES_WITH_MATCHES / COUNT)
 * - Context lines (-A/-B/-C)
 * - Multi-line mode (--multiline-dotall)
 * - VCS exclusion (.git/.svn/.hg)
 * - Ignore patterns
 * - Result sorting by mtime
 * - Path relativization
 */
@BuiltInTool(
    name = "Grep",
    strict = true,
    readOnly = true,
    concurrencySafe = true,
    maxResultSizeChars = 20_000
)
public class GrepTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "search file contents with regex (ripgrep)";
    }



    private static final Logger log = LoggerFactory.getLogger(GrepTool.class);
    private static final JsonNode SCHEMA = buildSchema();
    /**
     * Default cap on grep results when {@code head_limit} is unspecified.
     */


    private static final int DEFAULT_HEAD_LIMIT = 250;


    // VCS_DIRECTORIES_TO_EXCLUDE: .git,.svn,.hg,.bzr,.jj,.sl)
    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    @Override
    public String description() {
        return ToolTexts.description("Grep");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }



    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        String pattern = input.path("pattern").asText("");
        String path = input.path("path").asText("");
        return org.apache.commons.lang3.StringUtils.isBlank(path) ? pattern : pattern + " in " + path;
    }


    @Override
    public SearchReadClassification searchReadClassification(JsonNode input) {
        return new SearchReadClassification(true, false, false);
    }


    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String pattern = input.has("pattern") ? input.get("pattern").asText("") : "";

        // `include`/`exclude` params, which the model never sends.
        String glob = input.has("glob") ? input.get("glob").asText("") : "";

        String type = input.has("type") ? input.get("type").asText("") : "";

        // (`output_mode = 'files_with_matches'`), NOT "content".
        String outputMode = input.has("output_mode")
            ? input.get("output_mode").asText("files_with_matches")
            : "files_with_matches";

        // old Java `case_sensitive` flag.
        boolean caseInsensitive = input.has("-i") && input.get("-i").asBoolean(false);
        boolean onlyMatching = input.has("-o") && input.get("-o").asBoolean(false);

        Integer contextC = input.has("-C") ? input.get("-C").asInt() : null;
        if (contextC == null && input.has("context")) {
            contextC = input.get("context").asInt();
        }
        Integer before = input.has("-B") ? input.get("-B").asInt() : null;
        Integer after = input.has("-A") ? input.get("-A").asInt() : null;

        boolean showLineNumbers = !input.has("-n") || input.get("-n").asBoolean(true);

        Integer headLimit = input.has("head_limit") ? input.get("head_limit").asInt() : null;
        int offset = input.has("offset") ? input.get("offset").asInt(0) : 0;
        boolean multiLine = input.has("multiline") && input.get("multiline").asBoolean(false);
        String path = input.has("path") ? input.get("path").asText("") : "";

        if (org.apache.commons.lang3.StringUtils.isBlank(pattern)) {
            return "Error: pattern is required";
        }

        Path cwd = Path.of(context.workingDirectory());
        Path searchRoot = org.apache.commons.lang3.StringUtils.isBlank(path) ? cwd : PathUtils.expandPath(path, context.workingDirectory());
        log.debug("[GREP] call: pattern={} cwd={} searchRoot={}",
            pattern.substring(0, Math.min(60, pattern.length())), cwd, searchRoot);

        log.debug("[GREP] checking ripgrep availability");
        boolean rgAvailable = RipGrepUtil.isAvailable();
        log.debug("[GREP] ripgrep available={}", rgAvailable);

        // Try ripgrep first, fall back to Java regex
        if (rgAvailable) {
            log.debug("[GREP] calling executeRipgrep");
            String result = executeRipgrep(pattern, glob, type, caseInsensitive, outputMode,
                contextC, before, after, showLineNumbers, onlyMatching, multiLine, headLimit, offset, cwd, searchRoot,
                context.readDenyIgnorePatterns());
            log.debug("[GREP] executeRipgrep done, result length={}", result.length());
            return result;
        } else {
            log.debug("[GREP] calling executeJavaGrep");
            String result = executeJavaGrep(pattern, glob, caseInsensitive, outputMode,
                contextC, before, after, showLineNumbers, multiLine, headLimit, offset, cwd, searchRoot,
                context.readDenyIgnorePatterns());
            log.debug("[GREP] executeJavaGrep done, result length={}", result.length());
            return result;
        }
    }


    private String executeRipgrep(String pattern, String glob, String type,
                                   boolean caseInsensitive, String outputMode,
                                   Integer contextC, Integer before, Integer after,
                                   boolean showLineNumbers, boolean onlyMatching, boolean multiLine,
                                   Integer headLimit, int offset, Path cwd, Path searchRoot,
                                   List<FileReadIgnorePattern> denyPatterns) {
        Path searchBase = searchBase(cwd, searchRoot);
        List<String> args = new ArrayList<>();
        args.add("rg");
        args.add("--hidden");
        args.add("--no-heading");
        // Single-file searches otherwise omit the path, preventing deny-mask post-filtering.
        args.add("--with-filename");
        args.add("--color=never");
        args.add("--max-columns=500");

        for (String dir : VCS_DIRS) {
            args.add("--glob=!" + dir);
        }

        // These use a `!**/.../**` form (unanchored) so they work regardless of how rg anchors globs.
        for (String ex : PluginCacheGlobExclusions.getExclusions(searchBase.toString())) {
            args.add("--glob=" + ex);
        }

        // as `--glob` here: a positional PATH argument (required so rg actually searches the directory
        // when its stdin is a pipe, as it is under the JVM — otherwise rg reads stdin and returns nothing)
        // makes rg's leading-slash --glob anchor to the filesystem root, silently failing to exclude
        // nested files. Instead we honor them by post-filtering the results in {@link #filterDeniedPaths}.


        if (multiLine) {
            args.add("-U");
            args.add("--multiline-dotall");
        }
        if (caseInsensitive) {
            args.add("--ignore-case");
        }
        // Output mode flags.
        if (Strings.CS.equals("files_with_matches", outputMode)) {
            args.add("-l");
        } else if (Strings.CS.equals("count", outputMode)) {
            args.add("-c");
        }

        if (showLineNumbers && Strings.CS.equals("content", outputMode)) {
            args.add("-n");
        }
        if (onlyMatching && Strings.CS.equals("content", outputMode)) {
            args.add("-o");
        }

        if (Strings.CS.equals("content", outputMode)) {
            if (contextC != null) {
                args.add("-C");
                args.add(contextC.toString());
            } else {
                if (before != null) {
                    args.add("-B");
                    args.add(before.toString());
                }
                if (after != null) {
                    args.add("-A");
                    args.add(after.toString());
                }
            }
        }
        if (!org.apache.commons.lang3.StringUtils.isBlank(type)) {
            args.add("--type");
            args.add(type);
        }
        if (!org.apache.commons.lang3.StringUtils.isBlank(glob)) {
            for (String gp : RipGrepUtil.splitGlob(glob)) {
                args.add("--glob=" + gp);
            }
        }
        // If pattern starts with dash, use -e to avoid option parsing.
        if (Strings.CS.startsWith(pattern, "-")) {
            args.add("-e");
        }
        args.add(pattern);
        // Positional PATH = searchRoot so rg reliably searches the directory. Under the JVM rg's
        // stdin is a closed pipe; without a PATH it reads stdin and returns nothing (verified:
        // no-PATH content searches yield zero results). The process cwd is the searched directory,
        // or the searched file's parent directory, because ProcessBuilder requires a directory cwd.
        // rg may emit paths relative to that base, so rebaseToSearchRoot makes them absolute.
        // The read/deny mask is NOT injected as a leading-slash --glob: a positional PATH makes rg
        // anchor such globs to the filesystem root, silently failing to exclude nested files. We
        // honor it instead by post-filtering the results in {@link #filterDeniedPaths}.
        args.add(searchRoot.toString());

        try {

            List<String> rawLines = RipGrepUtil.run(args, searchBase);
            List<String> lines = new ArrayList<>(rawLines.size());
            for (String l : rawLines) {
                lines.add(rebaseToSearchRoot(l, searchBase));
            }
            lines = filterDeniedPaths(lines, searchBase, denyPatterns);

            // matching the Java-regex fallback (safeRelativize(cwd, file)) and GlobTool.
            // Relativize AFTER deny filtering so the matchers still see absolute paths.
            List<String> relativized = new ArrayList<>(lines.size());
            for (String l : lines) {
                relativized.add(relativizePathToCwd(l, cwd));
            }
            String output = String.join("\n", relativized);


            return formatGrepOutput(output, outputMode, headLimit, offset, cwd);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Error running ripgrep: " + e.getMessage();
        }
    }

    /**
     * Java-regex fallback used when ripgrep is unavailable. matches the rg path's
     * parameter contract (glob/type/case-insensitive/context/head_limit/offset);
     * {@code type} is unsupported in the fallback (rg --type has no direct
     * equivalent) and is ignored.
     */
    private String executeJavaGrep(String pattern, String glob,
                                    boolean caseInsensitive, String outputMode,
                                    Integer contextC, Integer before, Integer after,
                                    boolean showLineNumbers, boolean multiLine,
                                    Integer headLimit, int offset, Path cwd, Path searchRoot,
                                    List<FileReadIgnorePattern> denyPatterns) {

        Path searchBase = searchBase(cwd, searchRoot);
        int beforeN = contextC != null ? contextC : (before != null ? before : 0);
        int afterN = contextC != null ? contextC : (after != null ? after : 0);
        try {
            int flags = caseInsensitive ? 0 : Pattern.CASE_INSENSITIVE;
            if (multiLine) {
                flags |= Pattern.DOTALL;
            }
            Pattern regex = Pattern.compile(pattern, flags);

            PathMatcher includeMatcher = org.apache.commons.lang3.StringUtils.isBlank(glob) ? null :
                    FileSystems.getDefault().getPathMatcher("glob:" + glob);

            // Read/deny permission rules → exclusion matchers (best-effort java.nio glob).

            List<String> denyGlobs = RipGrepUtil.normalizeIgnorePatterns(denyPatterns, searchBase);
            List<PathMatcher> denyMatchers = RipGrepUtil.compileDenyMatchers(denyGlobs);

            List<GrepResult> results = new ArrayList<>();

            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // VCS directory exclusion
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(searchRoot) && VCS_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(searchRoot) && RipGrepUtil.isDenied(searchBase, dir, denyMatchers)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (results.size() >= DEFAULT_HEAD_LIMIT) return FileVisitResult.TERMINATE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (RipGrepUtil.isDenied(searchBase, file, denyMatchers)) {
                        return FileVisitResult.CONTINUE;
                    }

                    Path relative = safeRelativize(cwd, file);
                    if (includeMatcher != null && !includeMatcher.matches(relative)) {
                        return FileVisitResult.CONTINUE;
                    }

                    try {
                        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                        if (Strings.CS.equals("files_with_matches", outputMode)) {
                            for (String line : lines) {
                                if (regex.matcher(line).find()) {
                                  results.add(new GrepResult(relative.toString(), 0, "",
                                      attrs.lastModifiedTime().toMillis()));
                                  return FileVisitResult.CONTINUE;
                                }
                            }
                        } else if (Strings.CS.equals("count", outputMode)) {
                            int count = 0;
                            for (String line : lines) {
                                if (regex.matcher(line).find()) count++;
                            }
                            if (count > 0) {
                                results.add(new GrepResult(relative.toString(), 0, String.valueOf(count), attrs.lastModifiedTime().toMillis()));
                            }
                        } else {
                            for (int i = 0; i < lines.size() && results.size() < DEFAULT_HEAD_LIMIT; i++) {
                                if (regex.matcher(lines.get(i)).find()) {
                                    StringBuilder matchLine = new StringBuilder();
                                    if (showLineNumbers) {
                                        for (int b = Math.max(0, i - beforeN); b < i; b++) {
                                            matchLine.append(relative).append(":").append(b + 1).append("-").append(lines.get(b)).append("\n");
                                        }
                                        matchLine.append(relative).append(":").append(i + 1).append(":").append(lines.get(i));
                                        for (int a = i + 1; a <= Math.min(lines.size() - 1, i + afterN); a++) {
                                            matchLine.append("\n").append(relative).append(":").append(a + 1).append("-").append(lines.get(a));
                                        }
                                    } else {
                                        // Without -n, just emit the matched line(s); when
                                        // context is requested, prefix with "path:-" like rg.
                                        if (beforeN > 0 || afterN > 0) {
                                            for (int b = Math.max(0, i - beforeN); b < i; b++) {
                                                matchLine.append(relative).append(":-:").append(lines.get(b)).append("\n");
                                            }
                                            matchLine.append(relative).append(":-:").append(lines.get(i));
                                            for (int a = i + 1; a <= Math.min(lines.size() - 1, i + afterN); a++) {
                                                matchLine.append("\n").append(relative).append(":-:").append(lines.get(a));
                                            }
                                        } else {
                                            matchLine.append(lines.get(i));
                                        }
                                    }
                                    results.add(new GrepResult(relative.toString(), i + 1, matchLine.toString(), attrs.lastModifiedTime().toMillis()));
                                }
                            }
                        }
                    } catch (IOException _) {
                        // Skip files that can't be read
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });


            if (Strings.CS.equals("files_with_matches", outputMode)) {
                results.sort(Comparator.comparingLong(GrepResult::mtime).reversed());
            }


            int total = results.size();
            int limit = headLimitOrDefault(headLimit);
            int start = Math.min(offset, total);
            int end = Math.min(total, start + limit);
            List<GrepResult> limited = results.subList(start, end);

            Integer appliedLimit = (total > limit) ? limit : null;
            Integer appliedOffset = offset > 0 ? offset : null;

            if (limited.isEmpty()) {
                return Strings.CS.equals("files_with_matches", outputMode) ? "No files found" : "No matches found";
            }

            if (Strings.CS.equals("files_with_matches", outputMode)) {

                String limitInfo = formatLimitInfo(appliedLimit, appliedOffset);
                StringBuilder sb = new StringBuilder();
                sb.append("Found ").append(limited.size()).append(StringUtils.plural(limited.size(), "file"));
                if (!limitInfo.isEmpty()) sb.append(' ').append(limitInfo);
                sb.append('\n').append(limited.stream()
                    .map(GrepResult::relativePath)
                    .reduce((a, b) -> a + "\n" + b).orElse(""));
                return sb.toString();
            }
            if (Strings.CS.equals("count", outputMode)) {

                StringBuilder raw = new StringBuilder();
                int totalMatches = 0;
                for (GrepResult r : limited) {
                    int c;
                    try {
                        c = Integer.parseInt(r.content());
                    } catch (NumberFormatException _) {
                        c = 0;
                    }
                    totalMatches += c;
                    if (!raw.isEmpty()) raw.append('\n');
                    raw.append(r.relativePath()).append(':').append(r.content());
                }
                String limitInfo = formatLimitInfo(appliedLimit, appliedOffset);
                StringBuilder sb = new StringBuilder(raw);
                sb.append("\n\nFound ").append(totalMatches).append(" total ")
                  .append(totalMatches == 1 ? "occurrence" : "occurrences")
                  .append(" across ").append(limited.size()).append(limited.size() == 1 ? " file" : " files")
                  .append('.');
                if (!limitInfo.isEmpty()) sb.append(" with pagination = ").append(limitInfo);
                return sb.toString();
            }

// content mode: lines + optional pagination footer.
            String limitInfo = formatLimitInfo(appliedLimit, appliedOffset);
            String content = limited.stream()
                .map(GrepResult::content)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
            if (limitInfo.isEmpty()) return content;
            return content + "\n\n[Showing results with pagination = " + limitInfo + "]";
        } catch (PatternSyntaxException e) {
            return "Error: invalid regex pattern: " + e.getMessage();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }


    private String formatGrepOutput(String rawOutput, String outputMode,
                                    Integer headLimit, int offset, Path cwd) {
        if (org.apache.commons.lang3.StringUtils.isBlank(rawOutput)) {
            return Strings.CS.equals("files_with_matches", outputMode) ? "No files found" : "No matches found";
        }
        String[] lines = rawOutput.split("\n");
        int total = lines.length;
        int limit = headLimitOrDefault(headLimit);
        int start = Math.min(offset, total);
        int end = Math.min(total, start + limit);
        Integer appliedLimit = (headLimit != null && headLimit != 0 && total > headLimit) ? headLimit : null;
        Integer appliedOffset = offset > 0 ? offset : null;

        if (Strings.CS.equals("files_with_matches", outputMode)) {

            List<String> sorted = new ArrayList<>(Arrays.asList(lines));
            sorted.sort((a, b) -> Long.compare(mtimeOf(b, cwd), mtimeOf(a, cwd)));
            List<String> limited = sorted.subList(start, end);
            if (limited.isEmpty()) return "No files found";
            String limitInfo = formatLimitInfo(appliedLimit, appliedOffset);
            StringBuilder sb = new StringBuilder();
            sb.append("Found ").append(limited.size()).append(StringUtils.plural(limited.size(), "file"));
            if (!limitInfo.isEmpty()) sb.append(' ').append(limitInfo);
            sb.append('\n').append(String.join("\n", limited));
            return sb.toString();
        }
        if (Strings.CS.equals("count", outputMode)) {
            List<String> limited = Arrays.asList(Arrays.copyOfRange(lines, start, end));
            if (limited.isEmpty()) return "No matches found";
            StringBuilder raw = new StringBuilder();
            int totalMatches = 0;
            for (String line : limited) {
                int idx = line.lastIndexOf(':');
                int c = 0;
                if (idx > 0) {
                    try {
                        c = Integer.parseInt(line.substring(idx + 1).trim());
                    } catch (NumberFormatException _) {
                        // leave c at 0 when the trailing token isn't a number
                    }
                }
                totalMatches += c;
                if (!raw.isEmpty()) raw.append('\n');
                raw.append(line);
            }
            String limitInfo = formatLimitInfo(appliedLimit, appliedOffset);
            StringBuilder sb = new StringBuilder(raw);
            sb.append("\n\nFound ").append(totalMatches).append(" total ")
              .append(totalMatches == 1 ? "occurrence" : "occurrences")
              .append(" across ").append(limited.size()).append(limited.size() == 1 ? " file" : " files")
              .append('.');
            if (!limitInfo.isEmpty()) sb.append(" with pagination = ").append(limitInfo);
            return sb.toString();
        }
        // content mode
        List<String> limited = Arrays.asList(Arrays.copyOfRange(lines, start, end));
        if (limited.isEmpty()) return "No matches found";
        String limitInfo = formatLimitInfo(appliedLimit, appliedOffset);
        String content = String.join("\n", limited);
        if (limitInfo.isEmpty()) return content;
        return content + "\n\n[Showing results with pagination = " + limitInfo + "]";
    }

    /** mtime (ms) of a file path, resolving relative paths against cwd; 0 on error. */
    private static long mtimeOf(String path, Path cwd) {
        try {
            Path p = Path.of(path);
            if (!p.isAbsolute()) p = cwd.resolve(p);
            return FileUtils.modificationTimeMillis(p);
        } catch (IOException | InvalidPathException _) {
            return 0;
        }
    }


    private static String formatLimitInfo(Integer appliedLimit, Integer appliedOffset) {
        StringBuilder sb = new StringBuilder();
        if (appliedLimit != null) sb.append("limit: ").append(appliedLimit);
        if (appliedOffset != null) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append("offset: ").append(appliedOffset);
        }
        return sb.toString();
    }


    private static int headLimitOrDefault(Integer headLimit) {
        if (headLimit == null) return DEFAULT_HEAD_LIMIT;
        return headLimit == 0 ? Integer.MAX_VALUE : headLimit;
    }

    /**
     * Relativizes {@code file} against {@code cwd}; falls back to the absolute
     * path when {@code file} is not under {@code cwd} (e.g. an /add-dir root),
     * instead of throwing like {@link Path#relativize}.
     */
    private static Path safeRelativize(Path cwd, Path file) {
        // Normalize to absolute so prefix comparisons are exact. A plain startsWith is wrong:
        // cwd="/a/foo" would wrongly relativize "/a/foobar". We instead require the result of
        // relativize to not escape upward (no leading ".."), which only holds for true
// descendants. matches GlobTool's safeRelativize.
        Path cwdN = cwd.toAbsolutePath().normalize();
        Path fileN = file.toAbsolutePath().normalize();
        if (fileN.startsWith(cwdN)) {
            Path rel = cwdN.relativize(fileN);
            if (rel.toString().isEmpty() || !rel.startsWith("..")) {
                return rel;
            }
        }
        return file;
    }

    @Explanation(
        "Windows file searches run ripgrep from the parent directory because ProcessBuilder requires a directory cwd."
    )
    private static Path searchBase(Path cwd, Path searchRoot) {
        Path normalized = searchRoot.toAbsolutePath().normalize();
        if (Files.isDirectory(normalized)) {
            return normalized;
        }
        Path parent = normalized.getParent();
        return parent != null && Files.isDirectory(parent)
            ? parent
            : cwd.toAbsolutePath().normalize();
    }

    /**
     * Re-bases an rg output line relative to the process search directory to an absolute path, so
     * displayed/relativized paths and deny-mask filtering use a stable representation. Handles
     * content ({@code path:line:...}), count ({@code path:N}), and bare paths.
     */
    static String rebaseToSearchRoot(String line, Path searchRoot) {
        int idx = ripgrepMetadataSeparatorIndex(line);
        String pathText = idx < 0 ? line : line.substring(0, idx);
        if (looksLikeWindowsAbsolutePath(pathText)) {
            return line;
        }
        Path p = Path.of(pathText);
        if (!p.isAbsolute()) p = searchRoot.resolve(p).normalize();
        return idx < 0 ? p.toString() : p.toString() + line.substring(idx);
    }


    private static String relativizePathToCwd(String line, Path cwd) {
        int idx = ripgrepMetadataSeparatorIndex(line);
        String pathText = idx < 0 ? line : line.substring(0, idx);
        Path p = Path.of(pathText);
        String relative = safeRelativize(cwd, p).toString();
        return idx < 0 ? relative : relative + line.substring(idx);
    }

    @Explanation("Windows ripgrep output contains a drive-letter colon before the path metadata separator.")
    private static int ripgrepMetadataSeparatorIndex(String line) {
        int driveColon = windowsDriveColonIndex(line);
        return line.indexOf(':', driveColon >= 0 ? driveColon + 1 : 0);
    }

    private static boolean looksLikeWindowsAbsolutePath(String path) {
        return windowsDriveColonIndex(path) >= 0 || Strings.CS.startsWith(path, "\\\\");
    }

    private static int windowsDriveColonIndex(String path) {
        if (path.length() >= 3 && Character.isLetter(path.charAt(0)) && path.charAt(1) == ':'
                && isWindowsSeparator(path.charAt(2))) {
            return 1;
        }
        if (path.length() >= 7
                && (Strings.CS.startsWith(path, "\\\\?\\") || Strings.CS.startsWith(path, "\\\\.\\"))
                && Character.isLetter(path.charAt(4)) && path.charAt(5) == ':'
                && isWindowsSeparator(path.charAt(6))) {
            return 5;
        }
        return -1;
    }

    private static boolean isWindowsSeparator(char value) {
        return value == '\\' || value == '/';
    }

    /**
     * Post-filters rg output lines against the read/deny permission mask.
     */
    private static List<String> filterDeniedPaths(List<String> lines, Path searchRoot,
                                                  List<FileReadIgnorePattern> denyPatterns) {
        if (denyPatterns.isEmpty()) {
            return lines;
        }
        List<String> denyGlobs = RipGrepUtil.normalizeIgnorePatterns(denyPatterns, searchRoot);
        List<PathMatcher> denyMatchers = RipGrepUtil.compileDenyMatchers(denyGlobs);
        if (denyMatchers.isEmpty()) {
            return lines;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            Path file = pathOfLine(line);
            if (file != null && RipGrepUtil.isDenied(searchRoot, file, denyMatchers)) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /**
     * Extracts the file path from an rg output line, skipping a Windows drive-letter colon.
     * Returns {@code null} for empty lines or lines whose leading segment is not a valid
     * path, so {@link #filterDeniedPaths} can skip them instead of letting {@link Path#of}
     * throw — {@link Path#of} returns a Path or raises, it never returns {@code null}, so the
     * {@code file != null} guard in {@code filterDeniedPaths} is only meaningful because of this.
     */
    private static Path pathOfLine(String line) {
        int idx = ripgrepMetadataSeparatorIndex(line);
        String pathText = idx < 0 ? line : line.substring(0, idx);
        if (pathText.isEmpty()) {
            return null;
        }
        try {
            return Path.of(pathText);
        } catch (IllegalArgumentException _) {
            // InvalidPathException extends IllegalArgumentException — covers empty
            // or malformed path segments (e.g. a blank rg output line).
            return null;
        }
    }

    /**
     * Task 58.1: Grep result record for structured output.
     */
    private record GrepResult(String relativePath, int lineNumber, String content, long mtime) {}

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");



        ObjectNode patternProp = properties.putObject("pattern");
        patternProp.put("type", "string");
        patternProp.put("description", "The regular expression pattern to search for in file contents");

        ObjectNode pathProp = properties.putObject("path");
        pathProp.put("type", "string");
        pathProp.put("description",
            "File or directory to search in (rg PATH). Defaults to current working directory.");

        ObjectNode globProp = properties.putObject("glob");
        globProp.put("type", "string");
        globProp.put("description",
            "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob");

        ObjectNode modeProp = properties.putObject("output_mode");
        modeProp.put("type", "string");
        modeProp.set("enum", mapper().createArrayNode().add("content").add("files_with_matches").add("count"));
        modeProp.put("description",
            "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line "
          + "numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), "
          + "\"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".");

        ObjectNode flagB = properties.putObject("-B");
        flagB.put("type", "number");
        flagB.put("description",
            "Number of lines to show before each match (rg -B). Requires output_mode: \"content\", "
          + "ignored otherwise.");

        ObjectNode flagA = properties.putObject("-A");
        flagA.put("type", "number");
        flagA.put("description",
            "Number of lines to show after each match (rg -A). Requires output_mode: \"content\", "
          + "ignored otherwise.");

        ObjectNode flagC = properties.putObject("-C");
        flagC.put("type", "number");
        flagC.put("description", "Alias for context.");

        ObjectNode contextProp = properties.putObject("context");
        contextProp.put("type", "number");
        contextProp.put("description",
            "Number of lines to show before and after each match (rg -C). Requires output_mode: "
          + "\"content\", ignored otherwise.");

        ObjectNode flagN = properties.putObject("-n");
        flagN.put("type", "boolean");
        flagN.put("description",
            "Show line numbers in output (rg -n). Requires output_mode: \"content\", ignored "
          + "otherwise. Defaults to true.");

        ObjectNode flagI = properties.putObject("-i");
        flagI.put("type", "boolean");
        flagI.put("description", "Case insensitive search (rg -i)");

        ObjectNode flagO = properties.putObject("-o");
        flagO.put("type", "boolean");
        flagO.put("description",
            "Print only the matched (non-empty) parts of each matching line, one match per output "
          + "line (rg -o / --only-matching). Requires output_mode: \"content\", ignored otherwise. "
          + "Defaults to false.");

        ObjectNode typeProp = properties.putObject("type");
        typeProp.put("type", "string");
        typeProp.put("description",
            "File type to search (rg --type). Common types: js, py, rust, go, java, etc. "
          + "More efficient than include for standard file types.");

        ObjectNode headLimitProp = properties.putObject("head_limit");
        headLimitProp.put("type", "number");
        headLimitProp.put("description",
            "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all "
          + "output modes: content (limits output lines), files_with_matches (limits file paths), "
          + "count (limits count entries). Defaults to 250 when unspecified. Pass 0 for unlimited "
          + "(use sparingly — large result sets waste context).");

        ObjectNode offsetProp = properties.putObject("offset");
        offsetProp.put("type", "number");
        offsetProp.put("description",
            "Skip first N lines/entries before applying head_limit, equivalent to "
          + "\"| tail -n +N | head -N\". Works across all output modes. Defaults to 0.");

        ObjectNode multiProp = properties.putObject("multiline");
        multiProp.put("type", "boolean");
        multiProp.put("description",
            "Enable multiline mode where . matches newlines and patterns can span lines "
          + "(rg -U --multiline-dotall). Default: false.");

        ArrayNode required = schema.putArray("required");
        required.add("pattern");

        return schema;
    }
}
