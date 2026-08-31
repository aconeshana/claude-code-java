package com.claudecode.tools.files;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.FileReadIgnorePattern;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.io.PathUtils;
import com.claudecode.core.platform.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;

/**
 * Tool for finding files matching glob patterns.
 */
@BuiltInTool(
    name = "Glob",
    readOnly = true,
    concurrencySafe = true
)
public class GlobTool extends AnnotatedTool<JsonNode, String> {


    @Override
    public String searchHint() {
        return "find files by name pattern or wildcard";
    }

    private static final JsonNode SCHEMA = buildSchema();
    /**
     * Default max results when {@code max_results} input is not provided.
     */
    private static final int DEFAULT_MAX_RESULTS = 100;


    private static final Set<String> VCS_DIRS = Set.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");

    @Override
    public String description() {
        return ToolTexts.description("Glob");
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }



    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("pattern").asText("");
    }


    @Override
    public SearchReadClassification searchReadClassification(JsonNode input) {
        return new SearchReadClassification(true, false, false);
    }


    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String pattern = input.has("pattern") ? input.get("pattern").asText("") : "";

        // rg `--glob !<exclude>` exclusion (same shape as the read/deny mask).
        String exclude = input.has("exclude") ? input.get("exclude").asText("") : "";
        String path = input.has("path") ? input.get("path").asText("") : "";

// this as a tool input — it comes from server config `globLimits?.maxResults ?? 100`.
        int maxResults = input.has("max_results") ? input.get("max_results").asInt(DEFAULT_MAX_RESULTS) : DEFAULT_MAX_RESULTS;

// derived solely from the CLAUDE_CODE_GLOB_HIDDEN env (, default

        // and includeHidden is true unless the input explicitly sets it false.
        boolean includeHidden = !input.has("include_hidden") || input.get("include_hidden").asBoolean(true);


        // which errors and yields a silent "No files found". We return an explicit,
        // actionable error instead.
        if (StringUtils.isBlank(pattern)) {
            return "Error: pattern is required";
        }

        Path cwd = Path.of(context.workingDirectory());

        // Resolve the search directory and the (relative) glob rg will receive.
        Path searchDir = StringUtils.isBlank(path) ? cwd : PathUtils.expandPath(path, context.workingDirectory());
        Platform platform = Platform.CURRENT;
        String searchPattern = searchPatternForPlatform(pattern, platform);
        BaseDir base = extractGlobBaseDirectory(pattern);
        String absoluteBase = absoluteBaseDirectory(base, platform);
        if (absoluteBase != null) {
            searchDir = Path.of(absoluteBase);
            searchPattern = searchPatternForPlatform(base.relativePattern(), platform);
        }

        // NOTE: the java.nio glob matchers are compiled lazily inside fallbackGlob,
        // NOT here. ripgrep's gitignore-glob syntax is more permissive than java.nio's
        // (e.g. java.nio requires `**` to be a complete path segment, so `src**/x`
        // throws PatternSyntaxException while rg still matches). Pre-compiling here would
        // wrongly reject rg-valid patterns, so the rg path is the authority for glob syntax
        // and only the Java fallback is gated by java.nio's stricter parser.
        // Normalized read/deny exclusion globs (relative to searchDir, posix). The rg path
        // applies them as `--glob !<pattern>`; the Java fallback applies them via denyMatcher.
        List<String> denyGlobs = RipGrepUtil.normalizeIgnorePatterns(
                context.readDenyIgnorePatterns(), searchDir);
        GlobResult result;
        if (RipGrepUtil.isAvailable()) {
            try {
                result = runRipgrep(searchDir, searchPattern, context.readDenyIgnorePatterns(),
                        exclude, includeHidden, cwd, maxResults);
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // rg failed at runtime (bad glob, unreachable dir, …) — fall back, still
                // honouring the read/deny mask.
                result = fallbackGlob(searchDir, searchPattern, exclude, includeHidden, cwd, maxResults, denyGlobs);
            }
        } else {
            result = fallbackGlob(searchDir, searchPattern, exclude, includeHidden, cwd, maxResults, denyGlobs);
        }

        if (result == null) {
            return "Error: invalid glob pattern";
        }

        if (result.matches().isEmpty()) {
            return "No files found";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join("\n", result.matches()));


        if (result.truncated()) {
            sb.append("\n\n(Results are truncated. Consider using a more specific path or pattern.)");
        }

        return sb.toString();
    }

    /**
     * Runs {@code rg --files --glob <searchPattern> --sort=modified --no-ignore [--hidden] [--glob
     * !<deny>]* [--glob !<exclude>]} rooted at {@code searchDir}, returning cwd-relative paths.
     */
    private static GlobResult runRipgrep(Path searchDir, String searchPattern,
                                         List<FileReadIgnorePattern> denyPatterns, String exclude,
                                         boolean includeHidden, Path cwd, int maxResults)
            throws IOException, InterruptedException {
        List<String> args = new ArrayList<>();
        args.add("rg");
        args.add("--files");
        args.add("--glob");
        args.add(searchPattern);
        args.add("--sort=modified");

        // hidden = isEnvTruthy(CLAUDE_CODE_GLOB_HIDDEN || 'true'). The `include_hidden` input

        if (RipGrepUtil.envTruthy("CLAUDE_CODE_GLOB_NO_IGNORE", true)) {
            args.add("--no-ignore");
        }
        boolean hidden = includeHidden && RipGrepUtil.envTruthy("CLAUDE_CODE_GLOB_HIDDEN", true);
        if (hidden) {
            args.add("--hidden");
        }

        for (String ip : RipGrepUtil.normalizeIgnorePatterns(denyPatterns, searchDir)) {
            args.add("--glob");
            args.add("!" + ip);
        }

        for (String ex : PluginCacheGlobExclusions.getExclusions(searchDir.toString())) {
            args.add("--glob");
            args.add(ex);
        }
        if (!StringUtils.isBlank(exclude)) {
            args.add("--glob");
            args.add("!" + exclude);
        }

// INTENTIONAL DIVERGENCE from the compatibility baselinecompatibility contract build (
// calls ripGrep(args, searchDir) →: fullArgs=[...args, target], spawn with NO cwd override,
// so rg runs with the REAL cwd and searchDir is only a positional PATH).
        List<String> lines = RipGrepUtil.run(args, searchDir);
        // rg rooted at searchDir always emits searchDir-relative paths; make them absolute under

        // join(searchDir, p) followed by GlobTool's toRelativePath).
        List<String> matches = new ArrayList<>();
        for (String line : lines) {
            Path abs = Path.of(line);
            if (!abs.isAbsolute()) {
                abs = searchDir.resolve(line).normalize();
            }
            matches.add(safeRelativize(cwd, abs));
        }
        boolean truncated = matches.size() > maxResults;
        if (truncated) {
            matches = matches.subList(0, maxResults);
        }
        return new GlobResult(matches, truncated);
    }

    /**
     * Bridges to {@link #walkFileTreeGlob}, compiling the java.nio glob matchers here (so the
     * rg path is never gated by java.nio's stricter glob syntax). Returns {@code null} when the
     * pattern (or exclude) is not valid java.nio glob syntax.
     */
    private static GlobResult fallbackGlob(Path searchRoot, String searchPattern, String exclude,
                                           boolean includeHidden, Path cwd, int maxResults,
                                           List<String> denyPatterns) {
        PathMatcher matcher = compileGlob(searchPattern);
        if (matcher == null) {
            return null;
        }
        PathMatcher excludeMatcher = StringUtils.isBlank(exclude) ? null : compileGlob(exclude);
        return walkFileTreeGlob(searchRoot, matcher, excludeMatcher, includeHidden, cwd, maxResults, denyPatterns);
    }

    /** Compiles a java.nio glob; returns {@code null} on syntax error rather than throwing. */
    private static PathMatcher compileGlob(String pattern) {
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException _) {
            return null;
        }
    }

    /**
     * Java fallback used when ripgrep is unavailable, or if an rg run fails.
     */
    private static GlobResult walkFileTreeGlob(Path searchRoot, PathMatcher matcher,
                                               PathMatcher excludeMatcher, boolean includeHidden,
                                               Path cwd, int maxResults, List<String> denyPatterns) {
        List<PathMatcher> denyMatchers = RipGrepUtil.compileDenyMatchers(denyPatterns);
        List<String> matches = new ArrayList<>();
        boolean[] truncated = {false};

        try {
            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) return FileVisitResult.TERMINATE;
                    if (!Files.isReadable(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String dirName = dir.getFileName() != null ? dir.getFileName().toString() : "";
                    if (!dir.equals(searchRoot) && VCS_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!includeHidden && !dir.equals(searchRoot) && Strings.CS.startsWith(dirName, ".")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(searchRoot) && RipGrepUtil.isDenied(searchRoot, dir, denyMatchers)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= maxResults) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (!Files.isReadable(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String fileName = file.getFileName().toString();
                    if (!includeHidden && Strings.CS.startsWith(fileName, ".")) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path searchRelative = searchRoot.relativize(file);
                    if (matcher.matches(searchRelative)
                            && (excludeMatcher == null || !excludeMatcher.matches(searchRelative))
                            && !RipGrepUtil.isDenied(searchRoot, file, denyMatchers)) {
                        matches.add(safeRelativize(cwd, file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException _) {
            return new GlobResult(matches, truncated[0]);
        }
        return new GlobResult(matches, truncated[0]);
    }

    /**
     * Relativizes {@code file} against {@code cwd}; falls back to the absolute path when {@code file}
     * is not under {@code cwd} (e.g.
     */
    static String safeRelativize(Path cwd, Path file) {
// Normalize to absolute so prefix comparisons are exact.
        return PathUtils.toRelativePath(cwd, file);
    }


    private static BaseDir extractGlobBaseDirectory(String pattern) {
        int idx = -1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                idx = i;
                break;
            }
        }
        String staticPrefix = pattern.substring(0, idx < 0 ? pattern.length() : idx);
        int lastSep = Math.max(staticPrefix.lastIndexOf('/'), staticPrefix.lastIndexOf('\\'));
        if (lastSep < 0) {
            return new BaseDir("", pattern);
        }
        String baseDir = staticPrefix.substring(0, lastSep);
        String relativePattern = pattern.substring(lastSep + 1);
        if (baseDir.isEmpty()) {
            baseDir = String.valueOf(pattern.charAt(lastSep));
        }

        // Append the authored separator to get the real drive root "C:/" or "C:\".
        if (baseDir.length() == 2 && baseDir.charAt(1) == ':') {
            baseDir += pattern.charAt(lastSep);
        }
        return new BaseDir(baseDir, relativePattern);
    }

    static String absoluteBaseDirectory(String pattern, Platform platform) {
        return absoluteBaseDirectory(extractGlobBaseDirectory(pattern), platform);
    }

    static String searchPatternForPlatform(String pattern, Platform platform) {
        return platform == Platform.WIN32 ? pattern.replace('\\', '/') : pattern;
    }

    private static String absoluteBaseDirectory(BaseDir base, Platform platform) {
        String candidate = base.baseDir();
        if (StringUtils.isBlank(candidate)) return null;
        if (platform == Platform.WIN32) {
            if (candidate.matches("^/[A-Za-z](?:/.*)?$")) {
                return PathUtils.posixPathToWindowsPath(candidate);
            }
            if (candidate.matches("^[A-Za-z]:[\\\\/].*")
                    || Strings.CS.startsWith(candidate, "\\\\")
                    || Strings.CS.startsWith(candidate, "//")) {
                return candidate;
            }
            return null;
        }
        return Path.of(candidate).isAbsolute() ? candidate : null;
    }

    private record GlobResult(List<String> matches, boolean truncated) {}

    private record BaseDir(String baseDir, String relativePattern) {}

    private static JsonNode buildSchema() {
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = schema.putObject("properties");

        ObjectNode patternProp = properties.putObject("pattern");
        patternProp.put("type", "string");
        patternProp.put("description", "The glob pattern to match files against");


// directory other than cwd (call resolves it via PathUtils.expandPath).
        ObjectNode pathProp = properties.putObject("path");
        pathProp.put("type", "string");
        pathProp.put("description",
            "The directory to search in. If not specified, the current working "
            + "directory will be used. IMPORTANT: Omit this field to use the default "
            + "directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for "
            + "the default behavior. Must be a valid directory path if provided.");

        ArrayNode required = schema.putArray("required");
        required.add("pattern");

        return schema;
    }
}
