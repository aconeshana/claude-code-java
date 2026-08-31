package com.claudecode.tools.files;


import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.FileReadIgnorePattern;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.process.SubprocessEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.PatternSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared launcher for {@code ripgrep} ({@code rg}) and the helpers GlobTool/GrepTool need to build
 * rg argument lists.
 */
public final class RipGrepUtil {

    private static final Logger log = LoggerFactory.getLogger(RipGrepUtil.class);


    private static final long MAX_BUFFER_SIZE = 20_000_000; // 20MB; large monorepos can have 200k+ files

/**
     * Falsy values for {@link #envTruthy(String, boolean)}.
     */
    private static final Set<String> FALSY = Set.of("false", "0", "no", "off", "f");


    public enum RipgrepMode {
        SYSTEM,
        BUILTIN
    }


    public record RipgrepStatus(
        boolean working,
        RipgrepMode mode,
        String systemPath
    ) {}

    private RipGrepUtil() {}


    static String ripgrepCommand() {
        String cached = resolvedCommand.get();
        if (cached != null) return cached;
        String cmd = computeRipgrepCommand();
        resolvedCommand.compareAndSet(null, cmd);
        return resolvedCommand.get();
    }

    private static final AtomicReference<String> resolvedCommand = new AtomicReference<>();

    private static String computeRipgrepCommand() {
        if (envDefinedFalsy("USE_BUILTIN_RIPGREP")) {
            // Opt-out → system rg only when PATH resolution succeeds. Preserve the
            // original bare command name after the secure lookup; otherwise fall
            // through to the bundled binary.
            if (ExecutableFinder.find("rg").isPresent()) return "rg";
        }
        // Default (and the only other mode besides explicit opt-out): the builtin vendored binary.
        // Aligned with the original — do NOT fall back to system rg if it is missing;
// findBundledRipgrep returns the would-be path so the spawn fails with ENOENT (a
        // critical error), exactly as the original does.
        String bundled = findBundledRipgrep();
        if (bundled != null) {
            return bundled;
        }
        // Reached only for an unrecognized platform (no bundled binary could ever exist there);
        // fall back to system rg as a last resort rather than returning null.
        log.debug("[RIPGREP] unrecognized platform, falling back to system 'rg'");
        return "rg";
    }

    /**
     * True only when {@code name} is set to a falsy value (0/false/no/off); unset/empty is false.
     */
    private static boolean envDefinedFalsy(String name) {
        String v = SubprocessEnvironment.get(name);
        if (StringUtils.isBlank(v)) return false;
        return Set.of("0", "false", "no", "off").contains(v.trim().toLowerCase(Locale.ROOT));
    }


    private static String findBundledRipgrep() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String platform = Strings.CS.contains(os, "win") ? "win32"
            : (Strings.CS.contains(os, "mac") || Strings.CS.contains(os, "darwin")) ? "darwin"
            : Strings.CS.contains(os, "linux") ? "linux"
            : null;
        if (platform == null) return null;
        if (Strings.CS.equals(arch, "x86_64") || Strings.CS.equals(arch, "amd64")) arch = "x64";
        else if (Strings.CS.equals(arch, "aarch64")) arch = "arm64";
        String rgName = Strings.CS.equals(platform, "win32") ? "rg.exe" : "rg";
        String resource = "/vendor/ripgrep/" + arch + "-" + platform + "/" + rgName;
        Path out = stateDir().resolve(arch + "-" + platform).resolve(rgName);
        // Extract the bundled binary if it is not already on disk. On any failure we still return
        // {@code out} so the caller attempts to run the (missing) file and gets ENOENT, exactly as
        // the original does for an absent vendored binary.
        try (InputStream in = RipGrepUtil.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.debug("[RIPGREP] bundled rg resource missing (spawn will ENOENT): {}", resource);
                return out.toAbsolutePath().toString();
            }
            Files.createDirectories(out.getParent());
            // Reuse an already-extracted binary; only (re)copy if missing or empty.
            if (!Files.isRegularFile(out) || Files.size(out) == 0) {
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.debug("[RIPGREP] bundled rg extract failed (spawn will ENOENT): {}", e.toString());
        }
        boolean madeExecutable = out.toFile().setExecutable(true);
        if (!madeExecutable) {
            log.debug("[RIPGREP] could not set executable bit on extracted rg: {}", out);
        }
        if (Strings.CS.equals(platform, "darwin")) {
            codesignAdHoc(out);
        }
        return out.toAbsolutePath().toString();
    }

    /**
     * Ad-hoc codesign + quarantine removal for the extracted builtin binary (macOS only).
     */
    private static void codesignAdHoc(Path p) {
        if (!codesignIsLinkerSigned(p)) {
            return;
        }
        try {
            runQuiet("codesign", "--sign", "-", "--force",
                "--preserve-metadata=entitlements,requirements,flags,runtime", p.toString());
            runQuiet("xattr", "-d", "com.apple.quarantine", p.toString());
        } catch (Exception e) {
            log.debug("[RIPGREP] codesign failed (best-effort): {}", e.toString());
        }
    }

    /** True when {@code codesign -vv -d} reports the binary as "linker-signed" (needs re-signing). */
    private static boolean codesignIsLinkerSigned(Path p) {
        try {
            Process proc = new ProcessBuilder("codesign", "-vv", "-d", p.toString())
                .redirectErrorStream(true).start();
            String out;
            try (var in = proc.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            proc.waitFor(30, TimeUnit.SECONDS);
            return Strings.CS.contains(out, "linker-signed");
        } catch (Exception _) {
            // If we cannot probe, don't sign (best-effort; the original would skip too).
            return false;
        }
    }

    private static void runQuiet(String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (var drain = p.getInputStream()) {
            drain.transferTo(OutputStream.nullOutputStream());
        }
        p.waitFor(30, TimeUnit.SECONDS);
    }

    /** State dir for the extracted binary: {@code <configDir|~/.claude>/cache/ripgrep}. */
    static Path stateDir() {
        return ClaudePaths.CACHE_DIR.resolve("ripgrep");
    }

    /**
     * Returns true when a usable {@code rg} binary is on {@code PATH}.
     * Public so {@code DoctorDiagnosticsCollector} can surface a "Search" diagnostic.
     */
    private static final AtomicReference<Boolean> availableCache = new AtomicReference<>();

    public static boolean isAvailable() {
        Boolean cached = availableCache.get();
        if (cached != null) return cached;
        boolean ok = computeAvailable();
        availableCache.compareAndSet(null, ok);
        return availableCache.get();
    }

    /**
     * Returns the active mode and cached/probed availability for Doctor diagnostics.
     */
    public static RipgrepStatus status() {
        String command = ripgrepCommand();
        RipgrepMode mode = Strings.CS.equals("rg", command) ? RipgrepMode.SYSTEM : RipgrepMode.BUILTIN;
        return new RipgrepStatus(isAvailable(), mode,
            mode == RipgrepMode.SYSTEM ? command : null);
    }

    /** Clears the cached availability result (e.g. for tests). */
    static void clearAvailabilityCache() {
        availableCache.set(null);
    }

    private static boolean computeAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ripgrepCommand(), "--version");
            pb.redirectErrorStream(true);
            long t0 = System.currentTimeMillis();
            Process p = pb.start();
            try { p.getOutputStream().close(); } catch (IOException _) {}
            StringBuilder out = new StringBuilder();
            try (var in = p.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            boolean completed = p.waitFor(5, TimeUnit.SECONDS);
            String version = out.toString();
            // Prefix check guards against a coincidentally-named binary on PATH that is not

            boolean ok = completed && p.exitValue() == 0 && Strings.CS.startsWith(version, "ripgrep ");
            log.debug("[RIPGREP] isAvailable: completed={} exitCode={} ms={} prefixOk={}",
                completed, completed ? p.exitValue() : -1, System.currentTimeMillis() - t0,
                Strings.CS.startsWith(version, "ripgrep "));
            return ok;
        } catch (IOException | InterruptedException e) {
            log.debug("[RIPGREP] isAvailable: exception={}", e.toString());
            return false;
        }
    }

    /**
     * Truthiness of an environment variable.
     */
    static boolean envTruthy(String name, boolean dflt) {
        String v = SubprocessEnvironment.get(name);
        if (StringUtils.isBlank(v)) return dflt;
        return !FALSY.contains(v.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Runs {@code rg} with the given argument list (which must include {@code "rg"} as the first
     * element) rooted at {@code cwd}, returning the lines of stdout.
     */
    public static List<String> run(List<String> args, Path cwd) throws IOException, InterruptedException {
        return run(args, cwd, true);
    }

    private static List<String> run(List<String> args, Path cwd, boolean allowRetry)
            throws IOException, InterruptedException {
        long timeoutMs = resolveTimeoutMs();

        // "rg" as args[0]; substitute the resolved command so builtin/system selection applies.
        List<String> full = new ArrayList<>(args);
        if (!full.isEmpty() && Strings.CS.equals("rg", full.getFirst())) {
            full.set(0, ripgrepCommand());
        }
        ProcessBuilder pb = new ProcessBuilder(full);
        // Resolve the child's cwd to its real (symlink-resolved) path. ripgrep, when launched
        // with no positional PATH argument, anchors its search to the process cwd — and on macOS
        // a symlinked cwd (e.g. /var/folders → /private/var/folders, as JUnit @TempDir or a
        // user's symlinked project dir produces) is NOT searched unless the link is resolved
        // first. Resolving here keeps the no-PATH invocation (required so leading-slash
        // read/deny --globs anchor correctly) working for symlinked directories too.
        Path realCwd;
        try {
            realCwd = cwd.toRealPath();
        } catch (IOException _) {
            realCwd = cwd.toAbsolutePath();
        }
        pb.directory(realCwd.toFile());
        pb.redirectErrorStream(false);

        log.debug("[RIPGREP] start: args={}", args.subList(0, Math.min(6, args.size())));
        Process process = pb.start();
        // Critical: close child's stdin immediately. Without this, rg sees its stdin attached
        // to a pipe (Java's default Redirect.PIPE) and — when no PATH arguments are given —
        // waits forever for input instead of recursively searching cwd.
        try { process.getOutputStream().close(); } catch (IOException _) {}

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        AtomicBoolean outCapped = new AtomicBoolean();
        AtomicBoolean errCapped = new AtomicBoolean();
        Thread outT = Thread.startVirtualThread(() -> drain(process.getInputStream(), stdout, outCapped));
        Thread errT = Thread.startVirtualThread(() -> drain(process.getErrorStream(), stderr, errCapped));

        boolean completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!completed) {

            // child.kill('SIGTERM') + 5s SIGKILL escalation.)
            process.destroy();
            boolean killed = process.waitFor(5, TimeUnit.SECONDS);
            if (!killed) process.destroyForcibly();
            try { outT.join(); errT.join(); } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }

// 20MB buffer was not hit — rg may be killed mid-write. match that by treating
            // the output as capped here so parseLines drops the last line.
            List<String> partial = parseLines(stdout, true);
            if (!partial.isEmpty()) {
                log.warn("[RIPGREP] timed out after {}s but returning {} partial results",
                    timeoutMs / 1000, partial.size());
                return partial;
            }
            throw new IOException("ripgrep timed out after " + (timeoutMs / 1000) + "s");
        }
        try { outT.join(); errT.join(); } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        int exit = process.exitValue();
        if (exit == 0 || exit == 1) {
            log.debug("[RIPGREP] done: exit={} lines={}", exit, countLines(stdout, outCapped.get()));
            return parseLines(stdout, outCapped.get());
        }

        String errMsg = stderr.toString();
        if (allowRetry && isEagainError(errMsg) && !args.contains("-j")) {
            // Retry this specific call single-threaded; do NOT persist globally (it caused
            // timeouts on large repos where EAGAIN was just a transient startup error).
            log.warn("[RIPGREP] EAGAIN detected, retrying with -j 1");
            List<String> retryArgs = new ArrayList<>(args);
            retryArgs.add(1, "-j");
            retryArgs.add(2, "1");
            return run(retryArgs, cwd, false);
        }

        // Non-critical error with partial output: return it (drop torn trailing line) rather
        // than failing the whole search.
        List<String> partial = parseLines(stdout, outCapped.get());
        if (!partial.isEmpty()) {
            log.debug("[RIPGREP] exited {} with partial results ({} lines): {}", exit, partial.size(), errMsg);
            return partial;
        }

        // regex / permission errors) — only a timeout-with-no-output is a failure (handled above).
        // Match that: return empty instead of throwing, so callers report "No matches found".
        log.debug("[RIPGREP] exited {} with no output, returning empty (TS resolve []): {}", exit, errMsg);
        return List.of();
    }


    public static List<Path> listMarkdownFiles(Path dir) throws IOException, InterruptedException {
        if (!envTruthy("CLAUDE_CODE_USE_NATIVE_FILE_SEARCH", false) && isAvailable()) {
            try {
                List<String> args = List.of("rg", "--files", "--hidden", "--follow",
                    "--no-ignore", "--glob", "*.md");
                List<String> lines = run(args, dir);
                if (!lines.isEmpty()) {
                    List<Path> result = new ArrayList<>(lines.size());
                    for (String l : lines) {
                        Path p = Path.of(l);
                        if (!p.isAbsolute()) p = dir.resolve(p);
                        p = p.normalize();
                        if (acceptMarkdown(p)) result.add(p);
                    }
                    return result;
                }
                // Empty rg result (no *.md, or a non-timeout rg error) → still try Java so a
                // transient rg failure doesn't silently drop files. Fall through.
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                // rg failure → Java fallback below.
                log.debug("[RIPGREP] listMarkdownFiles rg failed, using Java fallback: {}", e.toString());
            }
        }
        return listMarkdownFilesNative(dir);
    }

/**
     * Whether {@code p} is a regular {@code.md} file.
     */
    private static boolean acceptMarkdown(Path p) {
        return Files.isRegularFile(p) && Strings.CS.endsWith(p.getFileName().toString(), ".md");
    }

    /** Native Java recursive {@code *.md} discovery, equivalent to the rg fallback above. */
    private static List<Path> listMarkdownFilesNative(Path dir) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(dir)) return result;
        try (var stream = Files.walk(dir, FileVisitOption.FOLLOW_LINKS)) {
            stream.forEach(p -> {
                if (acceptMarkdown(p)) {
                    result.add(p.toAbsolutePath().normalize());
                }
            });
        }
        return result;
    }

    /** Reads {@code in} to EOF, appending decoded text to {@code out} until {@code MAX_BUFFER_SIZE}. */
    private static void drain(InputStream in, StringBuilder out, AtomicBoolean capped) {
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                if (capped.get()) continue; // keep draining so rg isn't blocked, but stop appending
                int room = (int) Math.min(n, MAX_BUFFER_SIZE - total);
                out.append(new String(buf, 0, room, StandardCharsets.UTF_8));
                total += room;
                if (total >= MAX_BUFFER_SIZE) capped.set(true);
            }
        } catch (IOException e) {
            log.debug("[RIPGREP] drain error: {}", e.toString());
        }
    }


    private static List<String> parseLines(StringBuilder sb, boolean capped) {
        String s = sb.toString();
        if (s.isEmpty()) return List.of();
        List<String> lines = new ArrayList<>();
        for (String line : s.split("\n", -1)) {
            if (Strings.CS.endsWith(line, "\r")) line = line.substring(0, line.length() - 1);
            if (!line.isEmpty()) lines.add(line);
        }
        // If the buffer was capped, the last element may be a torn (incomplete) line — drop it.
        if (capped && !lines.isEmpty()) lines.removeLast();
        return lines;
    }

    private static int countLines(StringBuilder sb, boolean capped) {
        return parseLines(sb, capped).size();
    }

    /**
     * Checks whether an rg error is EAGAIN (resource temporarily unavailable) — happens in
     * resource-constrained environments (Docker, CI) when ripgrep tries to spawn too many threads.
     */
    private static boolean isEagainError(String stderr) {
        return Strings.CS.contains(stderr, "os error 11") || Strings.CS.contains(stderr, "Resource temporarily unavailable");
    }

    /** rg timeout in ms: {@code CLAUDE_CODE_GLOB_TIMEOUT_SECONDS} env, else 20s (60s on WSL). */
    private static long resolveTimeoutMs() {
        String secs = SubprocessEnvironment.get("CLAUDE_CODE_GLOB_TIMEOUT_SECONDS");
        if (StringUtils.isNotBlank(secs)) {
            try {
                int s = Integer.parseInt(secs.trim());
                if (s > 0) return s * 1000L;
            } catch (NumberFormatException _) {
                // fall through to platform default
            }
        }
        return isWsl() ? 60_000L : 20_000L;
    }


    private static boolean isWsl() {
        if (System.getenv("WSL_DISTRO_NAME") != null) return true;
        if (!Strings.CI.contains(System.getProperty("os.name", ""), "linux")) return false;
        try {
            String v = Files.readString(Path.of("/proc/version"));
            return Strings.CI.contains(v, "microsoft");
        } catch (IOException _) {
            return false;
        }
    }

    /**
     * Resolves read/deny ignore patterns against a concrete search directory, producing the list of
     * glob strings to pass as {@code --glob !<pattern>}.
     */
    public static List<String> normalizeIgnorePatterns(List<FileReadIgnorePattern> patterns, Path searchDir) {
        List<String> result = new ArrayList<>();
        // Normalize to posix separators so the comparisons in normalizePatternToPath
        // (which build posix full patterns via joinPosix/prependDirSep) are separator-
// consistent on Windows, where Path.toString uses backslashes. On Unix this is
        // a no-op. Without this, a deny rule rooted under a nested dir inside searchDir
        // could be wrongly dropped (fullPattern.startsWith(rootPath + "/") mismatched on
        // '\' vs '/'), silently disabling the Read-deny mask.
        String rootPath = toPosix(searchDir.toString());
        for (FileReadIgnorePattern p : patterns) {
            if (p.rootPath() == null) {
                result.add(p.relativePattern());
            } else {
                String norm = normalizePatternToPath(toPosix(p.rootPath()), p.relativePattern(), rootPath);
                if (norm != null) result.add(norm);
            }
        }
        return result;
    }


    static String normalizePatternToPath(String patternRoot, String pattern, String rootPath) {
        String fullPattern = joinPosix(patternRoot, pattern);
        if (patternRoot.equals(rootPath)) {
            return prependDirSep(pattern);
        } else if (Strings.CS.startsWith(fullPattern, rootPath + "/")) {
            return prependDirSep(fullPattern.substring(rootPath.length()));
        } else {
            String relativePath = posixRelative(rootPath, patternRoot);
            if (relativePath.isEmpty() || Strings.CS.equals(relativePath, "..") || Strings.CS.startsWith(relativePath, "../")) {
                return null;
            }
            return prependDirSep(joinPosix(relativePath, pattern));
        }
    }

/**
     * Splits a glob input into rg {@code --glob} arguments: preserves brace patterns, splits the rest
     * on whitespace and commas.
     */
    public static List<String> splitGlob(String glob) {
        List<String> out = new ArrayList<>();
        for (String raw : glob.split("\\s+")) {
            if (Strings.CS.contains(raw, "{") && Strings.CS.contains(raw, "}")) {
                out.add(raw);
            } else {
                for (String part : raw.split(",")) {
                    if (!StringUtils.isBlank(part)) out.add(part);
                }
            }
        }
        return out;
    }

    private static String toPosix(String p) {
        return p.replace('\\', '/');
    }

    /**
     * Compiles gitignore-style read/deny exclusion globs (as produced by {@link
     * #normalizeIgnorePatterns}) into java.nio path matchers for the post-filter / Java fallback
     * walker.
     */
    static List<PathMatcher> compileDenyMatchers(List<String> denyGlobs) {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String g : denyGlobs) {
            boolean anchored = Strings.CS.startsWith(g, "/");
            String p = anchored ? g.substring(1) : g;
            // Emitted globs (each compiled independently, fail-open):
            //   anchored:   p, p/**            -> searchRoot/p (and subtree)
            //   rootless:   p, p/**, **/p, **/p/**  -> top-level + nested, entry + subtree
            List<String> globs = new ArrayList<>();
            globs.add(p);
            globs.add(p + "/**");
            if (!anchored) {
                globs.add("**/" + p);
                globs.add("**/" + p + "/**");
            }
            for (String glob : globs) {
                try {
                    matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
                } catch (PatternSyntaxException _) {
                    // skip unparseable rule (fail open)
                }
            }
        }
        return matchers;
    }

    /** True when {@code file} (under {@code searchRoot}) matches any compiled deny matcher. */
    static boolean isDenied(Path searchRoot, Path file, List<PathMatcher> denyMatchers) {
        if (denyMatchers.isEmpty()) {
            return false;
        }
        String rel = searchRoot.relativize(file).toString().replace('\\', '/');
        Path relPath = Path.of(rel);
        for (PathMatcher dm : denyMatchers) {
            if (dm.matches(relPath)) {
                return true;
            }
        }
        return false;
    }

    private static String joinPosix(String a, String b) {
        // path.posix.join semantics: an absolute second segment resets the path.
        if (Strings.CS.startsWith(b, "/")) return b;
        if (a.isEmpty()) return b;
        if (Strings.CS.endsWith(a, "/")) return a + b;
        return a + "/" + b;
    }

    private static String prependDirSep(String p) {
        return Strings.CS.startsWith(p, "/") ? p : "/" + p;
    }

    private static String posixRelative(String from, String to) {
        return Path.of(from).relativize(Path.of(to)).toString().replace('\\', '/');
    }
}
