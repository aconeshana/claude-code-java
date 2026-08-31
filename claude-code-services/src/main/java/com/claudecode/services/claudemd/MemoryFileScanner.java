package com.claudecode.services.claudemd;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.util.FrontmatterParser;
import com.claudecode.core.io.PathUtils;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;

/**
 * Discovers CLAUDE.md memory files and recursively follows {@code @path} imports, producing a flat
 * parent-before-children ordering suitable for a picker UI.
 */
public final class MemoryFileScanner {


    static final int MAX_INCLUDE_DEPTH = 5;

    /**
     * File extensions the {@code @include} directive will follow.
     */
    static final Set<String> TEXT_FILE_EXTENSIONS = Set.of(
        // Markdown / plain
        ".md", ".txt", ".text",
        // Data
        ".json", ".yaml", ".yml", ".toml", ".xml", ".csv",
        // Web
        ".html", ".htm", ".css", ".scss", ".sass", ".less",

        ".js", ".ts", ".tsx", ".jsx", ".mjs", ".cjs", ".mts", ".cts",
        // Python / Ruby / Go / Rust / JVM
        ".py", ".pyi", ".pyw",
        ".rb", ".erb", ".rake",
        ".go",
        ".rs",
        ".java", ".kt", ".kts", ".scala",
        // C family
        ".c", ".cpp", ".cc", ".cxx", ".h", ".hpp", ".hxx",
        ".cs",
        ".swift",
        // Shell / config
        ".sh", ".bash", ".zsh", ".fish", ".ps1", ".bat", ".cmd",
        ".env", ".ini", ".cfg", ".conf", ".config", ".properties",
        // DB / proto
        ".sql", ".graphql", ".gql", ".proto",
        // Frontend / templating
        ".vue", ".svelte", ".astro",
        ".ejs", ".hbs", ".pug", ".jade",
        // Other langs
        ".php", ".pl", ".pm", ".lua", ".r", ".R",
        ".dart", ".ex", ".exs", ".erl", ".hrl",
        ".clj", ".cljs", ".cljc", ".edn",
        ".hs", ".lhs", ".elm", ".ml", ".mli",
        ".f", ".f90", ".f95", ".for",
        // Build
        ".cmake", ".make", ".makefile", ".gradle", ".sbt",
        // Docs
        ".rst", ".adoc", ".asciidoc", ".org", ".tex", ".latex",
        // Misc
        ".lock", ".log", ".diff", ".patch"
    );


    private static final Pattern INCLUDE_REGEX =
        Pattern.compile("(?:^|\\s)@((?:[^\\s\\\\]|\\\\ )+)");
    private static final Parser MARKDOWN_PARSER = Parser.builder().build();

    private final Path homeDir;
    private final Path userClaudeDir;
    private final Parser markdownParser;
    private final FrontmatterParser frontmatterParser;
    /**
     * Compiled glob matchers from {@code settings.claudeMdExcludes}.
     */
    private final List<PathMatcher> excludeMatchers;

    private final HookDispatcher hookDispatcher;


    private final AtomicReference<CacheSlot> cache = new AtomicReference<>();

    private record CacheKey(Path cwd, List<Path> additionalDirs, Set<MemoryType> enabledScopes) {}
    private record CacheSlot(CacheKey key, List<MemoryFileInfo> result) {}

    /**
     * Force the next {@code scan(...)} call to re-read the disk.
     */
    public void clearCache() {
        cache.set(null);
    }

    public MemoryFileScanner(Path homeDir) {
        this(homeDir, List.of(), null);
    }

    public MemoryFileScanner(Path homeDir, List<String> excludePatterns) {
        this(homeDir, excludePatterns, null);
    }

    /**
     * @param excludePatterns raw glob patterns from {@code WorkspaceSettings.loadClaudeMdExcludes}.
     */
    public MemoryFileScanner(Path homeDir, List<String> excludePatterns,
                              HookDispatcher hookDispatcher) {
        this(homeDir, homeDir.resolve(".claude"), excludePatterns, hookDispatcher);
    }

    /** Constructs a scanner whose user scope is rooted directly at {@code CLAUDE_CONFIG_DIR}. */
    public static MemoryFileScanner forConfigHome(Path configHome, List<String> excludePatterns,
                                                   HookDispatcher hookDispatcher) {
        return forConfigHome(Path.of(System.getProperty("user.home")), configHome,
            excludePatterns, hookDispatcher);
    }

    /** Explicit-home variant for diagnostics/tests that must keep {@code @~/} resolution injectable. */
    public static MemoryFileScanner forConfigHome(Path homeDir, Path configHome,
                                                   List<String> excludePatterns,
                                                   HookDispatcher hookDispatcher) {
        return new MemoryFileScanner(homeDir, configHome, excludePatterns, hookDispatcher);
    }

    private MemoryFileScanner(Path homeDir, Path userClaudeDir, List<String> excludePatterns,
                              HookDispatcher hookDispatcher) {
        this.homeDir = homeDir;
        this.userClaudeDir = userClaudeDir;
        this.markdownParser = MARKDOWN_PARSER;
        this.frontmatterParser = FrontmatterParser.shared();
        this.excludeMatchers = buildMatchers(excludePatterns);
        this.hookDispatcher = hookDispatcher;
    }

    private static List<PathMatcher> buildMatchers(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        var fs = FileSystems.getDefault();
        List<PathMatcher> out = new ArrayList<>();
        for (String pat : patterns) {
            if (StringUtils.isBlank(pat)) continue;
            String normalized = pat.replace('\\', '/');
            try {
                out.add(fs.getPathMatcher("glob:" + normalized));
            } catch (Exception _) { /* malformed → skip silently */ }
// Absolute pattern: try to resolve the static prefix's realpath and add that variant
// too, so /tmp/...
            if (Strings.CS.startsWith(normalized, "/")) {
                Path resolved = tryResolveStaticPrefix(normalized);
                if (resolved != null) {
                    try {
                        out.add(fs.getPathMatcher("glob:" + resolved));
                    } catch (Exception _) {}
                }
            }
        }
        return out;
    }

    /** Split pattern at first glob metachar, realpath the static prefix, re-attach glob tail. */
    private static Path tryResolveStaticPrefix(String pattern) {
        int globAt = -1;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' || c == '?' || c == '{' || c == '[') { globAt = i; break; }
        }
        String staticPart = globAt < 0 ? pattern : pattern.substring(0, globAt);
        Path dir = Path.of(staticPart).getParent();
        if (dir == null || !Files.isDirectory(dir)) return null;
        try {
            String realDir = dir.toRealPath().toString().replace('\\', '/');
            String rest = staticPart.substring(dir.toString().length());
            String tail = globAt < 0 ? "" : pattern.substring(globAt);
            String resolved = realDir + rest + tail;
            return resolved.equals(pattern) ? null : Path.of(resolved);
        } catch (IOException _) {
            return null;
        }
    }

    /**
     * Discover memory files for a working directory.
     */
    public List<MemoryFileInfo> scan(Path cwd) {
        return scan(cwd, List.of(), Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL));
    }

    public List<MemoryFileInfo> scan(Path cwd, List<Path> additionalDirs) {
        return scan(cwd, additionalDirs, Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL));
    }

    /**
     * @param additionalDirs extra project roots to include (e.g.
     */
    public List<MemoryFileInfo> scan(Path cwd, List<Path> additionalDirs, Set<MemoryType> enabledScopes) {
        // Normalise inputs for cache key (defensive copy, stable equality).
        List<Path> additionalDirsKey = additionalDirs == null ? List.of() : List.copyOf(additionalDirs);
        Set<MemoryType> scopesKey = enabledScopes == null
            ? Set.of() : Set.copyOf(enabledScopes);
        CacheKey key = new CacheKey(cwd.toAbsolutePath().normalize(), additionalDirsKey, scopesKey);
        CacheSlot slot = cache.get();
        if (slot != null && slot.key().equals(key)) {
            return slot.result();
        }
        List<MemoryFileInfo> fresh = scanUncached(cwd, additionalDirsKey, scopesKey);
        cache.set(new CacheSlot(key, fresh));


        // scan actually re-read the disk, not on cached hits.
        if (hookDispatcher != null) {
            for (MemoryFileInfo f : fresh) {
                String reason = f.parent() != null ? "include"
                    : (f.globs() != null && !f.globs().isEmpty()) ? "path_glob_match"
                    : "nested_traversal";
                try {
                    hookDispatcher.dispatchInstructionsLoaded(
                        f.path().toString(), typeToTsLabel(f.type()), reason, f.globs());
                } catch (Throwable _) { /* audit event, non-fatal */ }
            }
        }
        return fresh;
    }


    private static String typeToTsLabel(MemoryType t) {
        return switch (t) {
            case USER    -> "User";
            case PROJECT -> "Project";
            case LOCAL   -> "Local";
        };
    }

    private List<MemoryFileInfo> scanUncached(Path cwd, List<Path> additionalDirs, Set<MemoryType> enabledScopes) {
        List<MemoryFileInfo> result = new ArrayList<>();
        Set<Path> processedPaths = new HashSet<>();
        boolean userOn = enabledScopes.contains(MemoryType.USER);
        boolean projectOn = enabledScopes.contains(MemoryType.PROJECT);
        boolean localOn = enabledScopes.contains(MemoryType.LOCAL);

        // 1. User scope: ~/.claude/CLAUDE.md + ~/.claude/rules/*.md
        if (userOn) {
            result.addAll(processMemoryFile(
                userClaudeDir.resolve("CLAUDE.md"), MemoryType.USER, processedPaths, 0, null));
            result.addAll(processMdRules(
                userClaudeDir.resolve("rules"), MemoryType.USER, processedPaths));
        }

        // 2. Project scope: walk root → cwd, checking CLAUDE.md + .claude/CLAUDE.md + .claude/rules/*.md
        //    at each level. Ordered root → cwd so files closer to the user take precedence,

        List<Path> ancestors = new ArrayList<>();
        for (Path dir = cwd.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            ancestors.add(dir);
        }
        Collections.reverse(ancestors);

        for (Path dir : ancestors) {
            if (projectOn) {
                result.addAll(processMemoryFile(
                    dir.resolve("CLAUDE.md"), MemoryType.PROJECT, processedPaths, 0, null));
                result.addAll(processMemoryFile(
                    dir.resolve(".claude").resolve("CLAUDE.md"), MemoryType.PROJECT, processedPaths, 0, null));
                result.addAll(processMdRules(
                    dir.resolve(".claude").resolve("rules"), MemoryType.PROJECT, processedPaths));
            }

            // 3. Local scope: CLAUDE.local.md at each level (gitignored per-checkout).
            if (localOn) {
                result.addAll(processMemoryFile(
                    dir.resolve("CLAUDE.local.md"), MemoryType.LOCAL, processedPaths, 0, null));
            }
        }

        // 4. Additional directories from --add-dir. Loaded only when the env
        //    gate is on so users don't get surprise memory leakage from
        //    unrelated projects added to the permission context.

        //    Additional dirs load as PROJECT scope so they follow the same
        //    enabledScopes gate.
        if (projectOn && additionalDirs != null && !additionalDirs.isEmpty() && isAdditionalDirsEnabled()) {
            for (Path extra : additionalDirs) {
                Path dir = extra.toAbsolutePath().normalize();
                result.addAll(processMemoryFile(
                    dir.resolve("CLAUDE.md"), MemoryType.PROJECT, processedPaths, 0, null));
                result.addAll(processMemoryFile(
                    dir.resolve(".claude").resolve("CLAUDE.md"), MemoryType.PROJECT, processedPaths, 0, null));
                result.addAll(processMdRules(
                    dir.resolve(".claude").resolve("rules"), MemoryType.PROJECT, processedPaths));
            }
        }

        return result;
    }

    /**
     * Scan ONE directory (no ancestor walk) for its {@code CLAUDE.md} + {@code.claude/CLAUDE.md} +
     * {@code.claude/rules/*.md} across the given scopes.
     */
    List<MemoryFileInfo> scanDirectory(Path dir, Set<MemoryType> scopes) {
        List<MemoryFileInfo> result = new ArrayList<>();
        if (scopes.contains(MemoryType.USER)) {
            result.addAll(processMemoryFile(userClaudeDir.resolve("CLAUDE.md"), MemoryType.USER, new HashSet<>(), 0, null));
            result.addAll(processMdRules(userClaudeDir.resolve("rules"), MemoryType.USER, new HashSet<>()));
        }
        if (scopes.contains(MemoryType.PROJECT)) {
            result.addAll(processMemoryFile(dir.resolve("CLAUDE.md"), MemoryType.PROJECT, new HashSet<>(), 0, null));
            result.addAll(processMemoryFile(dir.resolve(".claude").resolve("CLAUDE.md"), MemoryType.PROJECT, new HashSet<>(), 0, null));
            result.addAll(processMdRules(dir.resolve(".claude").resolve("rules"), MemoryType.PROJECT, new HashSet<>()));
        }
        if (scopes.contains(MemoryType.LOCAL)) {
            result.addAll(processMemoryFile(dir.resolve("CLAUDE.local.md"), MemoryType.LOCAL, new HashSet<>(), 0, null));
        }
        return result;
    }

    /**
     * Read a single file (if present) and follow its {@code @path} imports.
     */
    List<MemoryFileInfo> processMemoryFile(Path file, MemoryType type,
                                           Set<Path> processed, int depth, Path parent) {
        Path normalized = file.toAbsolutePath().normalize();
        if (depth >= MAX_INCLUDE_DEPTH || processed.contains(normalized)) {
            return List.of();
        }
        if (!Files.isRegularFile(file)) {
            return List.of();
        }

        // Extension gate — @include may reach beyond .md, but non-text files
        // are skipped so binaries don't get read into memory.
        String ext = PathUtils.extensionOf(file);
        if (!ext.isEmpty() && !TEXT_FILE_EXTENSIONS.contains(ext)) {
            return List.of();
        }

        // claudeMdExcludes gate — user-configured glob patterns skip the file


        // no explicit exemption needed.
        if (isExcluded(normalized)) {
            return List.of();
        }

        processed.add(normalized);

// Also mark the realpath (symlink-resolved) so a second @include of the underlying file —
// via a different symlink or the real path — hits the dedup Set.

        try {
            Path real = file.toRealPath();
            Path realNormalized = real.toAbsolutePath().normalize();
            if (!realNormalized.equals(normalized)) {
                if (processed.contains(realNormalized)) {
                    // Already loaded via a different symlink alias — skip this dup.
                    return List.of();
                }
                processed.add(realNormalized);
            }
        } catch (IOException _) {
            // realpath failure — non-fatal; original path already in processed.
        }

        String raw;
        try {
            raw = Files.readString(file, UTF_8);
        } catch (IOException _) {
// ENOENT / permission — silently skip.
            return List.of();
        }

        // Frontmatter strip + optional path globs
        FrontmatterParser.ParseResult fm = frontmatterParser.parse(raw);
        List<String> globs = normaliseGlobs(fm.paths());
        String body = stripHtmlComments(fm.body());
        if (StringUtils.isBlank(body)) {
            return List.of();
        }


// from the on-disk bytes whenever frontmatter / HTML comments were removed.
        boolean differs = !body.equals(raw);

        List<MemoryFileInfo> collected = new ArrayList<>();
        collected.add(new MemoryFileInfo(normalized, type, body, globs, parent, differs, differs ? raw : null));

        // Recurse into @path imports discovered in this file's body.
        for (Path child : extractIncludePaths(body, normalized)) {
            collected.addAll(processMemoryFile(child, type, processed, depth + 1, normalized));
        }
        return collected;
    }

    /**
     * Scan a {@code .claude/rules/} directory for {@code *.md} rule files.
     */
    List<MemoryFileInfo> processMdRules(Path rulesDir, MemoryType type, Set<Path> processed) {
        return processMdRules(rulesDir, type, processed, new HashSet<>());
    }

    private List<MemoryFileInfo> processMdRules(Path rulesDir, MemoryType type,
                                                 Set<Path> processed, Set<Path> visitedDirs) {
        if (!Files.isDirectory(rulesDir)) return List.of();
        Path canonical;
        try {
            canonical = rulesDir.toRealPath();
        } catch (IOException _) {
            canonical = rulesDir.toAbsolutePath().normalize();
        }
        if (!visitedDirs.add(canonical)) return List.of();  // symlink cycle guard

        List<MemoryFileInfo> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rulesDir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    out.addAll(processMdRules(entry, type, processed, visitedDirs));
                } else if (Files.isRegularFile(entry)
                        && Strings.CS.endsWith(entry.getFileName().toString(), ".md")) {


                    out.addAll(processMemoryFile(entry, type, processed, 0, null));
                }
            }
        } catch (IOException _) {
            // Directory disappeared mid-scan — non-fatal.
        }
        return out;
    }

    /**
     * Extract {@code @path} references from the file body.
     */
    List<Path> extractIncludePaths(String body, Path basePath) {
        Set<Path> unique = new LinkedHashSet<>();
        Node root = markdownParser.parse(body);
        root.accept(new AbstractVisitor() {
            @Override public void visit(FencedCodeBlock block) { /* skip */ }
            @Override public void visit(IndentedCodeBlock block) { /* skip */ }
            @Override public void visit(Code code) { /* skip inline `code` */ }
            @Override public void visit(Text text) {
                Matcher m = INCLUDE_REGEX.matcher(text.getLiteral());
                while (m.find()) {
                    String candidate = m.group(1);
// Strip fragment identifier (#heading).
                    int hash = candidate.indexOf('#');
                    if (hash != -1) candidate = candidate.substring(0, hash);
                    if (candidate.isEmpty()) continue;
                    // Unescape spaces "\ " → " ".
                    candidate = candidate.replace("\\ ", " ");
                    if (!isPlausiblePath(candidate)) continue;
                    Path resolved = resolveIncludePath(candidate, basePath.getParent());
                    if (resolved != null) unique.add(resolved);
                }
            }
        });
        return new ArrayList<>(unique);
    }


    private static boolean isPlausiblePath(String p) {
        if (Strings.CS.startsWith(p, "./")) return true;
        if (Strings.CS.startsWith(p, "~/")) return true;
        if (Strings.CS.startsWith(p, "/") && !Strings.CS.equals(p, "/")) return true;
        if (Strings.CS.startsWith(p, "@")) return false;
        // Leading punctuation like "#chan", "%topic" is not a path.
        if (p.matches("^[#%^&*()].*")) return false;
        return p.matches("^[a-zA-Z0-9._-].*");
    }

    private Path resolveIncludePath(String raw, Path baseDir) {
        try {
            if (Strings.CS.startsWith(raw, "~/")) {
                return homeDir.resolve(raw.substring(2)).toAbsolutePath().normalize();
            }
            if (Strings.CS.startsWith(raw, "/")) {
                return Path.of(raw).toAbsolutePath().normalize();
            }
            String stripped = Strings.CS.startsWith(raw, "./") ? raw.substring(2) : raw;
            Path base = baseDir != null ? baseDir : Path.of(".");
            return base.resolve(stripped).toAbsolutePath().normalize();
        } catch (RuntimeException _) {

            return null;
        }
    }


    static String stripHtmlComments(String content) {
        if (content == null || !Strings.CS.contains(content, "<!--")) return content;
        // (?ms) — multiline + dotall (. matches newlines too). Match:
        //   line-start · optional-hspace · <!--...--> · optional-hspace · line-end
        // Trailing \n consumed so the residue doesn't leave a blank line gap.
        return content.replaceAll("(?ms)^[ \\t]*<!--.*?-->[ \\t]*\\r?\\n?", "");
    }

    /** Whether {@code CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD} is truthy. */
    private static boolean isAdditionalDirsEnabled() {
        return EnvUtils.isEnvTruthy(
            SubprocessEnvironment.get(
                "CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD"));
    }

    /**
     * Whether {@code path} matches any user-configured exclude glob. Also
     * checks the realpath variant to catch symlink aliases like
     * {@code /tmp → /private/tmp} on macOS.
     */
    private boolean isExcluded(Path path) {
        if (excludeMatchers.isEmpty()) return false;
        for (var m : excludeMatchers) {
            if (m.matches(path)) return true;
        }
        try {
            Path real = path.toRealPath();
            if (!real.equals(path)) {
                for (var m : excludeMatchers) {
                    if (m.matches(real)) return true;
                }
            }
        } catch (IOException _) {
            // realpath failed — non-symlink or gone; original check already ran.
        }
        return false;
    }


    private static List<String> normaliseGlobs(List<String> paths) {
        if (paths == null || paths.isEmpty()) return null;
        List<String> patterns = new ArrayList<>();
        for (String pattern : paths) {
            if (pattern == null) continue;
            String p = Strings.CS.endsWith(pattern, "/**") ? pattern.substring(0, pattern.length() - 3) : pattern;
            if (!p.isEmpty()) patterns.add(p);
        }
        if (patterns.isEmpty()) return null;
        // All patterns are "**" (match-all) → no gate.
        boolean allWildcard = patterns.stream().allMatch(p -> Strings.CS.equals(p, "**"));
        return allWildcard ? null : List.copyOf(patterns);
    }

    /**
     * Whether any of {@code globs} matches {@code anchor} or any of its parent-path suffixes.
     */
    static boolean matchGlobs(List<String> globs, Path anchor) {
        if (globs == null || globs.isEmpty()) return true;
        // Build all path suffixes of anchor: for /a/b/c/d, suffixes are
        //   "d", "c/d", "b/c/d", "a/b/c/d", and "/a/b/c/d" (absolute).
        // Glob "src/**" matches "src" or "src/nested" suffix.
        List<Path> candidates = new ArrayList<>();
        candidates.add(anchor);  // absolute path
        Path acc = null;
        for (int i = anchor.getNameCount() - 1; i >= 0; i--) {
            Path seg = anchor.getName(i);
            acc = acc == null ? seg : seg.resolve(acc);
            candidates.add(acc);
        }
        for (String pattern : globs) {
            PathMatcher m;
            try {
                m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            } catch (Exception _) {
                continue; // malformed glob → skip silently
            }
            for (Path c : candidates) {
                try {
                    if (m.matches(c)) return true;
                } catch (Exception _) {
                    // malformed match → skip
                }
            }
        }
        return false;
    }
}
