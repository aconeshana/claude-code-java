package com.claudecode.tools.sandbox;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.config.SettingsPathResolver;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxConfig.SandboxFilesystemConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.state.CwdState;
import com.claudecode.core.platform.Platform;
import com.claudecode.tools.tasks.TaskOutputPaths;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a bash command should run inside a native sandbox and, if so, builds the wrapped
 * argv (a {@code sandbox-exec}/{@code bwrap} prefix that ends in {@code bash -c <command>}).
 */
public abstract class SandboxManager {

    /** File-backed {@code --settings} source, installed by the CLI composition root. */
    private static volatile Path flagSettingsPath;


    public static void setFlagSettingsPath(Path path) {
        flagSettingsPath = path == null ? null : path.toAbsolutePath().normalize();
    }


    private static final Pattern ENV_ASSIGN =
        Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)=([A-Za-z0-9_./:-]+)[ \t]+");


    private static final Pattern HIJACK_VAR = Pattern.compile("^(LD_|DYLD_|PATH$)");


    private static final List<Pattern> SAFE_WRAPPER_PATTERNS = List.of(
        Pattern.compile("^timeout[ \t]+(?:(?:--(?:foreground|preserve-status|verbose)"
            + "|--(?:kill-after|signal)=[A-Za-z0-9_.+-]+"
            + "|--(?:kill-after|signal)[ \t]+[A-Za-z0-9_.+-]+|-v"
            + "|-[ks][ \t]+[A-Za-z0-9_.+-]+|[ks][A-Za-z0-9_.+-]+)[ \t]+)*(?:--[ \t]+)?"
            + "\\d+(?:\\.\\d+)?[smhd]?[ \t]+"),
        Pattern.compile("^time[ \t]+(?:--[ \t]+)?"),
        Pattern.compile("^nice(?:[ \t]+-n[ \t]+-?\\d+|[ \t]+-\\d+)?[ \t]+(?:--[ \t]+)?"),
        Pattern.compile("^stdbuf(?:[ \t]+-[ioe][LN0-9]+)+[ \t]+(?:--[ \t]+)?"),
        Pattern.compile("^nohup[ \t]+(?:--[ \t]+)?"));

    /**
     * Decide how a command should run. Applies the shared rules:
     * disabled config / excluded command / explicit opt-out → unsandboxed;
     * sandbox wanted but backend unavailable → reject (when
     * {@code failIfUnavailable}) or silently degrade to unsandboxed.
     */
    public SandboxDecision decide(String command, boolean dangerouslyDisableSandbox, SandboxConfig cfg) {
        if (cfg == null || !cfg.enabled()) {
            return SandboxDecision.unsandboxed();
        }
        if (!isPlatformSupported(cfg)) {

            // set and excludes this host (or is an empty list, disabling all
            // platforms) the sandbox is simply inactive here — commands run
            // unsandboxed, no per-command reject.
            return SandboxDecision.unsandboxed();
        }
        if (isExcluded(command, cfg.excludedCommands())) {
            return SandboxDecision.unsandboxed();
        }
        if (dangerouslyDisableSandbox) {


            // policy permits unsandboxed commands (allowUnsandboxedCommands, which
            // defaults to true). If the policy forbids it, the command stays sandboxed.
            if (cfg.allowUnsandboxedCommands()) {
                return SandboxDecision.unsandboxed();
            }
        }
        if (!available()) {
            if (cfg.failIfUnavailable()) {
                return SandboxDecision.reject(unavailableReason());
            }
            return SandboxDecision.unsandboxed();
        }
        return SandboxDecision.sandbox();
    }

    /**
     * Build the wrapped argv for a command already confirmed to run sandboxed.
     * The returned list ends in {@code bash -c <command>} (or equivalent) so the
     * caller can hand it straight to {@link ProcessBuilder}.
     */
    public abstract List<String> wrap(String command, Path cwd, SandboxConfig cfg);

    /** Whether the native sandbox backend is installed/usable on this host. */
    public abstract boolean available();

    /** Human-readable reason the backend is unavailable (for reject messages / startup warnings). */
    public abstract String unavailableReason();


    private static Set<String> currentPlatformNames() {
        return switch (Platform.CURRENT) {
            case DARWIN -> Set.of("darwin", "macos");
            case WIN32  -> Set.of("win32", "windows");
            case LINUX  -> Set.of("linux", "wsl");
            default     -> Set.of("other");
        };
    }

    /** Native platform support, independent of the enterprise enabledPlatforms filter. */
    public boolean isNativePlatformSupported() {
        if (Platform.CURRENT == Platform.OTHER) return false;
        return !Platform.IS_WSL || Platform.WSL_VERSION >= 2;
    }

    /** Whether the current platform survives the optional enabledPlatforms filter. */
    public boolean isPlatformInEnabledList(SandboxConfig cfg) {
        List<String> enabled = cfg == null ? null : cfg.enabledPlatforms();
        if (enabled == null) return true;
        if (enabled.isEmpty()) return false;
        Set<String> here = currentPlatformNames();
        return enabled.stream().anyMatch(here::contains);
    }

    /**
     * Whether the current host is allowed to sandbox under {@code cfg}.
     */
    public boolean isPlatformSupported(SandboxConfig cfg) {
        return isNativePlatformSupported() && isPlatformInEnabledList(cfg);
    }

    /**
     * Linux/WSL glob-pattern warnings.
     */
    public List<String> globPatternWarnings(SandboxConfig cfg) {
        if (!Platform.IS_LINUX) return List.of();
        if (cfg == null || !cfg.enabled()) return List.of();
        if (cfg.permissionGlobWarnings() != null) {
            return cfg.permissionGlobWarnings();
        }

        // Directly constructed snapshots from older callers do not carry the
        // raw effective permission list. Keep the old filesystem-path scan as
        // a compatibility fallback for those callers only.
        List<String> warnings = new ArrayList<>();
        SandboxFilesystemConfig fs = cfg.filesystem();
        if (fs != null) {
            for (String p : fs.allowWrite()) {
                if (hasGlobs(p)) warnings.add(p);
            }
            for (String p : fs.denyWrite()) {
                if (hasGlobs(p)) warnings.add(p);
            }
            for (String p : fs.denyRead()) {
                if (hasGlobs(p)) warnings.add(p);
            }
            for (String p : fs.allowRead()) {
                if (hasGlobs(p)) warnings.add(p);
            }
        }
        // Also surface any glob in the raw permission-derived paths is already
        // captured above; permission rules themselves are scanned by the caller
        // (SandboxSettings) when building the config.
        return warnings;
    }

    /** True when {@code path} contains a glob char, ignoring a trailing {@code /**}. */
    private static boolean hasGlobs(String path) {
        String stripped = path.replaceAll("/\\*\\*$", "");
        return stripped.matches(".*[*?\\[\\]].*");
    }



    /** Bare-repo files an attacker may plant at cwd to make git treat it as a repo. */
    private static final List<String> BARE_GIT_FILES =
        List.of("HEAD", "objects", "refs", "hooks", "config");

/**
     * Built-in writable paths always allowed: the Claude temp dir.
     */
    static List<String> builtInAllowWrite() {
        String tmp = TaskOutputPaths.claudeTempDir().toString();
        return List.of(tmp);
    }


    static List<String> builtInDenyWrite(Path cwd) {
        List<String> paths = new ArrayList<>();
        Path orig = CwdState.getOriginalCwd();
        if (orig == null) orig = cwd;
        // Settings files across tiers. The flag source is installed by the CLI
        // because tools intentionally cannot depend on the services module.
        paths.add(SettingsPathResolver.userSettingsPath().toString());
        paths.add(orig.resolve(Path.of(".claude", "settings.json")).toString());
        paths.add(orig.resolve(Path.of(".claude", "settings.local.json")).toString());
        Path policy = SettingsPathResolver.policySettingsPath();
        paths.add(policy.toString());
        Path policyParent = policy.getParent();
        if (policyParent != null) {


            paths.add(policyParent.resolve("managed-settings.d").toString());
        }
        Path flag = flagSettingsPath;
        if (flag != null) paths.add(flag.toString());
        if (!cwd.equals(orig)) {
            paths.add(cwd.resolve(Path.of(".claude", "settings.json")).toString());
            paths.add(cwd.resolve(Path.of(".claude", "settings.local.json")).toString());
        }
        // .claude/skills (auto-discovered, full capabilities) needs OS-level denial.
        paths.add(orig.resolve(Path.of(".claude", "skills")).toString());
        if (!cwd.equals(orig)) {
            paths.add(cwd.resolve(Path.of(".claude", "skills")).toString());
        }
        // Existing bare-repo files → deny; planted (non-existent) ones are scrubbed.
        for (Path base : (cwd.equals(orig) ? List.of(orig) : List.of(orig, cwd))) {
            for (String name : BARE_GIT_FILES) {
                Path p = base.resolve(name);
                if (Files.exists(p)) paths.add(p.toString());
            }
        }
        return paths;
    }


    public static Path detectWorktreeMainRepoPath(Path cwd) {
        Path git = cwd.resolve(".git");
        if (!Files.isRegularFile(git)) return null;
        try {
            String gitdir = null;
            for (String line : Files.readAllLines(git)) {
                String t = line.trim();
                if (Strings.CS.startsWith(t, "gitdir:")) { gitdir = t.substring(7).trim(); break; }
            }
            if (gitdir == null) return null;
            Path gd = Path.of(gitdir);
            if (!gd.isAbsolute()) gd = cwd.resolve(gd).normalize();
            Path commondir = gd.resolve("commondir");
            if (!Files.isRegularFile(commondir)) return null;
            String main = Files.readString(commondir).trim();
            if (main.isEmpty()) return null;
            Path mainGit = Path.of(main);
            if (!mainGit.isAbsolute()) mainGit = gd.resolve(main).normalize();
            Path mainRepo = mainGit.getParent();
            return mainRepo != null && !mainRepo.equals(cwd) ? mainRepo : null;
        } catch (IOException _) {
            return null;
        }
    }

    /** Snapshot of bare-repo files already present at cwd/originalCwd (pre-command). */
    public static Set<Path> bareGitFilesSnapshot(Path cwd) {
        Set<Path> existing = new HashSet<>();
        Path orig = CwdState.getOriginalCwd();
        if (orig == null) orig = cwd;
        for (Path base : (cwd.equals(orig) ? List.of(orig) : List.of(orig, cwd))) {
            for (String name : BARE_GIT_FILES) {
                Path p = base.resolve(name);
                if (Files.exists(p)) existing.add(p);
            }
        }
        return existing;
    }

    /**
     * Delete bare-repo files planted during a sandboxed command (those not present in the pre-command
     * {@code before} snapshot), so a subsequent unsandboxed git call cannot be fooled into treating cwd
     * as a bare repo.
     */
    public static void scrubBareGitRepoFiles(Path cwd, Set<Path> before) {
        for (Path p : bareGitFilesSnapshot(cwd)) {
            if (!before.contains(p)) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * Whether {@code command} matches any of {@code excludedCommands}.
     */
    protected boolean isExcluded(String command, List<String> excludedCommands) {
        if (excludedCommands == null || excludedCommands.isEmpty() || command == null) {
            return false;
        }
        Set<String> patterns = new LinkedHashSet<>();
        for (String ex : excludedCommands) {
            if (StringUtils.isNotBlank(ex)) patterns.add(ex.trim());
        }
        if (patterns.isEmpty()) return false;

        for (String sub : splitSubcommands(command)) {
            // Fixed-point peel of env-var prefixes and safe wrappers.
            Set<String> candidates = new LinkedHashSet<>();
            candidates.add(sub);
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String cand : new ArrayList<>(candidates)) {
                    String envStripped = stripLeadingEnvVars(cand);
                    if (!envStripped.equals(cand) && candidates.add(envStripped)) changed = true;
                    String wrapStripped = stripSafeWrappers(envStripped);
                    if (!wrapStripped.equals(envStripped) && candidates.add(wrapStripped)) changed = true;
                }
            }
            for (String cand : candidates) {
                for (String pattern : patterns) {
                    if (matchesPattern(pattern, cand)) return true;
                }
            }
        }
        return false;
    }

    /** Split a compound shell command into individual subcommands. */
    private static List<String> splitSubcommands(String command) {
        List<String> out = new ArrayList<>();
        // Split on &&, ||, ;, |, &, newlines (longest separators first).
        for (String part : command.split("(\\|\\||&&|;|\\||&|\\r?\\n)")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Strip leading hijackable env assignments ({@code LD_/DYLD_/PATH=…}), repeating. */
    private static String stripLeadingEnvVars(String command) {
        String s = command;
        while (true) {
            Matcher m = ENV_ASSIGN.matcher(s);
            if (!m.lookingAt()) break;
            String varName = m.group(1);
            if (!HIJACK_VAR.matcher(varName).find()) break;
            s = s.substring(m.end());
        }
        return s.stripLeading();
    }

    /** Strip safe wrapper commands (with flags/args), repeating until stable. */
    private static String stripSafeWrappers(String command) {
        String s = command;
        String prev;
        do {
            prev = s;
            for (Pattern p : SAFE_WRAPPER_PATTERNS) {
                s = p.matcher(s).replaceFirst("");
            }
        } while (!s.equals(prev));
        return s.trim();
    }

    /** Whether {@code candidate} matches an excluded-command {@code pattern}. */
    private static boolean matchesPattern(String pattern, String candidate) {
        if (Strings.CS.contains(pattern, "*") || Strings.CS.contains(pattern, "?") || Strings.CS.contains(pattern, "[")) {
            return globMatches(pattern, candidate);
        }
// Prefix match (covers both bare tokens like `rm` and space-containing patterns like `git
// commit`).
        return candidate.equals(pattern) || Strings.CS.startsWith(candidate, pattern + " ");
    }

    /** Glob → regex full match (supports * ? and [..]). */
    private static boolean globMatches(String pattern, String candidate) {
        StringBuilder re = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> re.append(".*");
                case '?' -> re.append('.');
                case '[' -> { int j = i + 1; re.append('[');
                    while (j < pattern.length() && pattern.charAt(j) != ']') { re.append(pattern.charAt(j)); j++; }
                    if (j < pattern.length()) { re.append(']'); i = j; } else { re.append("\\["); } }
                case '.', '\\', '+', '(', ')', '{', '}', '^', '$', '|' -> re.append('\\').append(c);
                default -> re.append(c);
            }
        }
        return candidate.matches(re.toString());
    }

    /** Whether network access should be permitted inside the sandbox. */
    protected boolean networkAllowed(SandboxConfig cfg) {
        return cfg.network() != null
            && (cfg.network().networkAllowed() || cfg.enableWeakerNetworkIsolation());
    }

    /**
     * True when a domain proxy is needed — i.e.
     */
    protected boolean usesDomainProxy(SandboxConfig cfg) {
        if (cfg == null || cfg.network() == null) return false;
        SandboxConfig.SandboxNetworkConfig net = cfg.network();
        return !net.allowedDomains().isEmpty() || !net.deniedDomains().isEmpty();
    }

    /** Lazily-started domain-allowlist proxy (null when not needed). */
    private SandboxNetworkProxy networkProxy;

    /**
     * Returns the active domain proxy, starting it on first use when {@code allowedDomains} is
     * configured.
     */
    synchronized SandboxNetworkProxy ensureProxy(SandboxConfig cfg) {
        if (!usesDomainProxy(cfg)) {
            return null;
        }
        if (networkProxy == null) {
            try {
                networkProxy = new SandboxNetworkProxy(
                    cfg.network().allowedDomains(), cfg.network().deniedDomains());
            } catch (IOException _) {
                return null;
            }
        }
        return networkProxy;
    }

    /**
     * Proxy env vars ({@code HTTP_PROXY}/{@code HTTPS_PROXY}/{@code ALL_PROXY} and
     * lowercase) to merge into the sandboxed process environment, or empty when no
     * domain proxy is active. Calling this also lazily starts the proxy.
     */
    public Map<String, String> sandboxEnvironment(SandboxConfig cfg) {
        SandboxNetworkProxy p = ensureProxy(cfg);
        return p != null ? p.proxyEnvironment() : Map.of();
    }

    /** Stops the proxy (if any). Call on shutdown to release the listener port. */
    public void close() {
        if (networkProxy != null) {
            networkProxy.stop();
            networkProxy = null;
        }
    }

    /** True when {@code exe} exists and is executable on the current {@code PATH}. */
    protected static boolean isExecutableOnPath(String exe) {
        String pathEnv = SubprocessEnvironment.get("PATH");
        if (pathEnv == null) return false;
        for (String dir : pathEnv.split(File.pathSeparator)) {
            if (StringUtils.isBlank(dir)) continue;
            Path p = Path.of(dir, exe);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) return true;
        }
        return false;
    }
}
