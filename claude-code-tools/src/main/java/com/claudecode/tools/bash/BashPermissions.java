package com.claudecode.tools.bash;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.model.PermissionModeKind;
import com.claudecode.permissions.PathValidation;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.permissions.WorkingDirectoryPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Permission checks for BashTool commands.
 */
public final class BashPermissions {

    private BashPermissions() {}

    /**
     * Checks permissions for a bash command (legacy shape, no working directory context).
     */
    public static PermissionDecision check(String command) {
        return check(command, null);
    }

    /**
     * Checks permissions for a bash command, with optional working-directory
     * context for path-constraint validation.
     *
     * @param command the command to check
     * @param permCtx the active tool permission context (may be {@code null}, in
     *                which case path constraints are not evaluated)
     * @return the permission decision (possibly an {@link PermissionDecision.Ask}
     *         carrying a {@code blockedPath})
     */
    public static PermissionDecision check(String command, ToolPermissionContext permCtx) {
        // Empty command — deny
        if (StringUtils.isBlank(command)) {
            return PermissionDecision.deny();
        }

        // Incomplete command (trailing |, &&, ||, ;) — deny
        if (BashTool.isIncompleteCommand(command)) {
            return PermissionDecision.deny();
        }

        // Run the shell-security preflight before path extraction and the
        // read-only fast path.  A command-substitution or parser differential
        // must never become auto-allowed merely because its visible command

        // bashSecurity validators fail safe to ASK when syntax cannot be
        // represented consistently; BashSecurity keeps the same invariant in
        // the Java path without executing a parser process.
        PermissionDecision security = BashSecurity.check(command);
        if (security != null) {
            return security;
        }


        // before the read-only fast-path so a read command targeting a UNC path

        if (permCtx != null) {
            PermissionDecision constrained = checkPathConstraints(command, permCtx);
            if (constrained != null) {
                return constrained;
            }
        }

        // Read-only / search commands — allow
        if (BashTool.isSearchOrReadCommand(command)) {
            return PermissionDecision.allow();
        }


        // in acceptEdits only after their paths passed the containment gate.
        if (permCtx != null
                && permCtx.mode() == PermissionMode.ACCEPT_EDITS
                && isSingleAcceptEditsCommand(command)) {
            return PermissionDecision.allow();
        }

        // Everything else — ask
        return PermissionDecision.ask();
    }

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    /** Commands whose arguments are filesystem paths and get validated. */
    private static final Set<String> SUPPORTED_PATH_COMMANDS = Set.of(
        "cd", "ls", "find", "mkdir", "touch", "rm", "rmdir", "mv", "cp",
        "cat", "head", "tail", "sort", "uniq", "wc", "cut", "paste", "column",
        "file", "stat", "diff", "awk", "strings", "hexdump", "od", "base64",
        "nl", "sha256sum", "sha1sum", "md5sum", "tr", "grep", "rg", "sed",
        "jq", "git"
    );

    /** File-operation type per path command (read/create/write). */
    private static final Map<String, String> COMMAND_OPERATION_TYPE = Map.ofEntries(
        Map.entry("cd", "read"),
        Map.entry("ls", "read"),
        Map.entry("find", "read"),
        Map.entry("mkdir", "create"),
        Map.entry("touch", "create"),
        Map.entry("rm", "write"),
        Map.entry("rmdir", "write"),
        Map.entry("mv", "write"),
        Map.entry("cp", "write"),
        Map.entry("cat", "read"),
        Map.entry("head", "read"),
        Map.entry("tail", "read"),
        Map.entry("sort", "read"),
        Map.entry("uniq", "read"),
        Map.entry("wc", "read"),
        Map.entry("cut", "read"),
        Map.entry("paste", "read"),
        Map.entry("column", "read"),
        Map.entry("file", "read"),
        Map.entry("stat", "read"),
        Map.entry("diff", "read"),
        Map.entry("awk", "read"),
        Map.entry("strings", "read"),
        Map.entry("hexdump", "read"),
        Map.entry("od", "read"),
        Map.entry("base64", "read"),
        Map.entry("nl", "read"),
        Map.entry("grep", "read"),
        Map.entry("rg", "read"),
        Map.entry("sed", "write"),
        Map.entry("jq", "read"),
        Map.entry("git", "read"),
        Map.entry("sha256sum", "read"),
        Map.entry("sha1sum", "read"),
        Map.entry("md5sum", "read")
    );


    private static final Set<String> ACCEPT_EDITS_ALLOWED_COMMANDS = Set.of(
        "mkdir", "touch", "rm", "rmdir", "mv", "cp", "sed"
    );


    private static final Set<String> FLAG_RESTRICTED_COMMANDS = Set.of("mv", "cp");


    private static final Set<String> PATTERN_FLAGS_WITH_ARGS = Set.of(
        "-e", "--regexp", "-f", "--file", "--exclude", "--include",
        "--exclude-dir", "--include-dir", "-m", "--max-count", "-A",
        "--after-context", "-B", "--before-context", "-C", "--context"
    );


    private static final Set<String> RG_PATTERN_FLAGS_WITH_ARGS = Set.of(
        "-e", "--regexp", "-f", "--file", "-t", "--type", "-T", "--type-not",
        "-g", "--glob", "-m", "--max-count", "--max-depth", "-r", "--replace",
        "-A", "--after-context", "-B", "--before-context", "-C", "--context"
    );

    /** Jq filter-then-files flags that consume a following argument. */
    private static final Set<String> JQ_FLAGS_WITH_ARGS = Set.of(
        "-e", "--expression", "-f", "--from-file", "--arg", "--argjson",
        "--slurpfile", "--rawfile", "--args", "--jsonargs", "-L",
        "--library-path", "--indent", "--tab"
    );

    /**
     * Per-command path extractors.
     */
    private static final Map<String, Function<List<String>, List<String>>> PATH_EXTRACTORS =
        Map.ofEntries(
            // cd: whole arg is one path (or home when empty)
            Map.entry("cd", BashPermissions::cdExtractor),
            // ls: filter flags, default to "."
            Map.entry("ls", BashPermissions::lsExtractor),
            // find: collect paths until a real flag, also check path-taking flags
            Map.entry("find", BashPermissions::findExtractor),
            // read/write commands: just filter out flags
            Map.entry("mkdir", BashPermissions::filterOutFlags),
            Map.entry("touch", BashPermissions::filterOutFlags),
            Map.entry("rm", BashPermissions::filterOutFlags),
            Map.entry("rmdir", BashPermissions::filterOutFlags),
            Map.entry("mv", BashPermissions::filterOutFlags),
            Map.entry("cp", BashPermissions::filterOutFlags),
            Map.entry("cat", BashPermissions::filterOutFlags),
            Map.entry("head", BashPermissions::filterOutFlags),
            Map.entry("tail", BashPermissions::filterOutFlags),
            Map.entry("sort", BashPermissions::filterOutFlags),
            Map.entry("uniq", BashPermissions::filterOutFlags),
            Map.entry("wc", BashPermissions::filterOutFlags),
            Map.entry("cut", BashPermissions::filterOutFlags),
            Map.entry("paste", BashPermissions::filterOutFlags),
            Map.entry("column", BashPermissions::filterOutFlags),
            Map.entry("file", BashPermissions::filterOutFlags),
            Map.entry("stat", BashPermissions::filterOutFlags),
            Map.entry("diff", BashPermissions::filterOutFlags),
            Map.entry("awk", BashPermissions::filterOutFlags),
            Map.entry("strings", BashPermissions::filterOutFlags),
            Map.entry("hexdump", BashPermissions::filterOutFlags),
            Map.entry("od", BashPermissions::filterOutFlags),
            Map.entry("base64", BashPermissions::filterOutFlags),
            Map.entry("nl", BashPermissions::filterOutFlags),
            Map.entry("sha256sum", BashPermissions::filterOutFlags),
            Map.entry("sha1sum", BashPermissions::filterOutFlags),
            Map.entry("md5sum", BashPermissions::filterOutFlags),
            // tr: skip SET1/SET2
            Map.entry("tr", BashPermissions::trExtractor),
            // grep / rg: pattern then paths
            Map.entry("grep", BashPermissions::grepExtractor),
            Map.entry("rg", BashPermissions::rgExtractor),
            // sed: script file / edit files
            Map.entry("sed", BashPermissions::sedExtractor),
            // jq: filter then files
            Map.entry("jq", BashPermissions::jqExtractor),
            // git: only diff --no-index takes paths
            Map.entry("git", BashPermissions::gitExtractor)
        );

    private static List<String> cdExtractor(List<String> args) {

        if (args.isEmpty()) {
            return List.of("~");
        }
        return List.of(String.join(" ", args));
    }

    private static List<String> lsExtractor(List<String> args) {
        List<String> paths = filterOutFlags(args);
        return paths.isEmpty() ? List.of(".") : paths;
    }

    /**
     * SECURITY: extract positional (non-flag) arguments, correctly handling the
     * POSIX {@code --} end-of-options delimiter. Without this, attack payloads
     * like  are dropped by a naive
     * {@code !startsWith("-")} filter and validation silently skips them.
     */
    private static List<String> filterOutFlags(List<String> args) {
        List<String> result = new ArrayList<>();
        boolean afterDoubleDash = false;
        for (String arg : args) {
            if (afterDoubleDash) {
                result.add(arg);
                continue;
            }
            if (Strings.CS.equals(arg, "--")) {
                afterDoubleDash = true;
                continue;
            }
            if (!Strings.CS.startsWith(arg, "-")) {
                result.add(arg);
            }
        }
        return result;
    }

    private static List<String> trExtractor(List<String> args) {
        boolean hasDelete = args.stream()
            .anyMatch(a -> Strings.CS.equals(a, "-d") || Strings.CS.equals(a, "--delete")
                || (Strings.CS.startsWith(a, "-") && Strings.CS.contains(a, "d")));
        List<String> nonFlags = filterOutFlags(args);
        // Skip SET1, or SET1+SET2 (the character sets are never file paths).
        return nonFlags.subList(Math.min(hasDelete ? 1 : 2, nonFlags.size()), nonFlags.size());
    }

    private static List<String> parsePatternCommand(
        List<String> args, Set<String> flagsWithArgs, List<String> defaults) {
        List<String> paths = new ArrayList<>();
        boolean patternFound = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;
            if (!afterDoubleDash && Strings.CS.equals(arg, "--")) {
                afterDoubleDash = true;
                continue;
            }
            if (!afterDoubleDash && Strings.CS.startsWith(arg, "-")) {
                String flag = arg.split("=", 2)[0];
                if (Strings.CS.equals(flag, "-e") || Strings.CS.equals(flag, "--regexp")
                        || Strings.CS.equals(flag, "-f") || Strings.CS.equals(flag, "--file")) {
                    patternFound = true;
                }
                if (flagsWithArgs.contains(flag) && !Strings.CS.contains(arg, "=")) {
                    i++;
                }
                continue;
            }
            if (!patternFound) {
                patternFound = true;
                continue;
            }
            paths.add(arg);
        }
        return paths.isEmpty() ? defaults : paths;
    }

    private static List<String> grepExtractor(List<String> args) {
        List<String> paths = parsePatternCommand(args, PATTERN_FLAGS_WITH_ARGS, List.of());

        // searches the current directory.
        if (paths.isEmpty()
                && args.stream().anyMatch(a -> Strings.CS.equals("-r", a) || Strings.CS.equals("-R", a) || Strings.CS.equals("--recursive", a))) {
            return List.of(".");
        }
        return paths;
    }

    private static List<String> rgExtractor(List<String> args) {
        return parsePatternCommand(args, RG_PATTERN_FLAGS_WITH_ARGS, List.of("."));
    }

    private static List<String> sedExtractor(List<String> args) {
        List<String> paths = new ArrayList<>();
        boolean skipNext = false;
        boolean scriptFound = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;
            if (skipNext) {
                skipNext = false;
                continue;
            }
            if (!afterDoubleDash && Strings.CS.equals(arg, "--")) {
                afterDoubleDash = true;
                continue;
            }
            if (!afterDoubleDash && Strings.CS.startsWith(arg, "-")) {
                if (Strings.CS.equals(arg, "-f") || Strings.CS.equals(arg, "--file")) {
                    String next = i + 1 < args.size() ? args.get(i + 1) : null;
                    if (next != null) {
                        paths.add(next);
                        skipNext = true;
                    }
                    scriptFound = true;
                } else if (Strings.CS.equals(arg, "-e") || Strings.CS.equals(arg, "--expression")) {
                    skipNext = true;
                    scriptFound = true;
                } else if (Strings.CS.contains(arg, "e") || Strings.CS.contains(arg, "f")) {
                    scriptFound = true;
                }
                continue;
            }
            if (!scriptFound) {
                scriptFound = true;
                continue;
            }
            paths.add(arg);
        }
        return paths;
    }

    private static List<String> jqExtractor(List<String> args) {
        List<String> paths = new ArrayList<>();
        boolean filterFound = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) continue;
            if (!afterDoubleDash && Strings.CS.equals(arg, "--")) {
                afterDoubleDash = true;
                continue;
            }
            if (!afterDoubleDash && Strings.CS.startsWith(arg, "-")) {
                String flag = arg.split("=", 2)[0];
                if (Strings.CS.equals(flag, "-e") || Strings.CS.equals(flag, "--expression")) {
                    filterFound = true;
                }
                if (JQ_FLAGS_WITH_ARGS.contains(flag) && !Strings.CS.contains(arg, "=")) {
                    i++;
                }
                continue;
            }
            if (!filterFound) {
                filterFound = true;
                continue;
            }
            paths.add(arg);
        }
        return paths;
    }

    private static List<String> gitExtractor(List<String> args) {
        if (!args.isEmpty() && Strings.CS.equals(args.getFirst(), "diff") && args.contains("--no-index")) {
            // git diff --no-index explicitly compares arbitrary filesystem paths.
            List<String> filePaths = filterOutFlags(args.subList(1, args.size()));
            return filePaths.size() >= 2 ? filePaths.subList(0, 2) : filePaths;
        }
        // Other git subcommands operate within the repository context.
        return List.of();
    }

    /** find pathFlags that consume a following path argument. */
    private static final Set<String> FIND_PATH_FLAGS = Set.of(
        "-newer", "-anewer", "-cnewer", "-mnewer", "-samefile",
        "-path", "-wholename", "-ilname", "-lname", "-ipath", "-iwholename"
    );

    private static final Pattern FIND_NEWER_PATTERN = Pattern.compile("^-newer[acmBt][acmtB]$");

    private static List<String> findExtractor(List<String> args) {
        List<String> paths = new ArrayList<>();
        boolean foundNonGlobalFlag = false;
        boolean afterDoubleDash = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (StringUtils.isEmpty(arg)) continue;
            if (afterDoubleDash) {
                paths.add(arg);
                continue;
            }
            if (Strings.CS.equals(arg, "--")) {
                afterDoubleDash = true;
                continue;
            }
            if (Strings.CS.startsWith(arg, "-")) {
                if (Set.of("-H", "-L", "-P").contains(arg)) continue;
                foundNonGlobalFlag = true;
                if (FIND_PATH_FLAGS.contains(arg) || FIND_NEWER_PATTERN.matcher(arg).matches()) {
                    String next = i + 1 < args.size() ? args.get(i + 1) : null;

                    if (StringUtils.isNotEmpty(next)) {
                        paths.add(next);
                        i++;
                    }
                }
                continue;
            }
            if (!foundNonGlobalFlag) {
                paths.add(arg);
            }
        }
        return paths.isEmpty() ? List.of(".") : paths;
    }

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    /** Matches process-substitution syntax {@code >(...)} / {@code <(...)}. */
    private static final Pattern PROCESS_SUBSTITUTION = Pattern.compile("[<>]\\s*\\(");

    private static PermissionDecision checkPathConstraints(
        String command, ToolPermissionContext permCtx) {
        Path cwd = permCtx.workingDirectory();
        String cwdStr = cwd != null ? cwd.toString() : ".";

        // SECURITY: process substitution can execute commands that write to
        // files without those files appearing as redirect targets.
        if (PROCESS_SUBSTITUTION.matcher(command).find()) {
            return PermissionDecision.ask();
        }

        // Tokenize with the shell-quote algorithm (correct quote / escape / glob
        // / comment handling) and split into statement segments.
        List<ShellQuoteParse.Token> allTokens = ShellQuoteParse.parse(command);
        List<List<ShellQuoteParse.Token>> segments = splitSegments(allTokens);
        boolean compoundCommandHasCd = segments.stream()
            .map(BashPermissions::argvFromTokens)
            .filter(argv -> !argv.isEmpty())
            .anyMatch(argv -> Strings.CS.equals("cd", argv.getFirst()));

        for (List<ShellQuoteParse.Token> seg : segments) {
            List<String> argv = argvFromTokens(seg);
            if (argv.isEmpty()) continue;
            PermissionDecision result = validateSinglePathCommand(
                argv, cwdStr, compoundCommandHasCd, permCtx);
            if (result != null) {
                return result;
            }
        }

// SECURITY: output-redirection targets are validated for EVERY command (not just path
// commands) — a `rm`-less command like `echo` can still write to a blocked location via
// `>`.
        return validateSegmentRedirects(
            allTokens, cwdStr, compoundCommandHasCd, permCtx);
    }

    /**
     * Returns explicit regular-file operands read by shell commands such as grep/rg/cat.
     */
    static List<Path> extractReadableFilePaths(String command, Path cwd) {
        List<Path> paths = new ArrayList<>();
        for (List<ShellQuoteParse.Token> segment : splitSegments(ShellQuoteParse.parse(command))) {
            List<String> argv = stripWrappersFromArgv(argvFromTokens(segment));
            if (argv.isEmpty()) continue;
            String baseCommand = argv.getFirst();
            if (!Strings.CS.equals("read", COMMAND_OPERATION_TYPE.get(baseCommand))) continue;
            Function<List<String>, List<String>> extractor = PATH_EXTRACTORS.get(baseCommand);
            if (extractor == null) continue;
            for (String operand : extractor.apply(argv.subList(1, argv.size()))) {
                if (StringUtils.isBlank(operand)) continue;
                Path resolved = Path.of(operand);
                if (!resolved.isAbsolute()) resolved = cwd.resolve(resolved);
                resolved = resolved.normalize();
                if (Files.isRegularFile(resolved)) paths.add(resolved);
            }
        }
        return List.copyOf(paths);
    }

    /** Splits a token stream into statement segments on shell separators. */
    private static List<List<ShellQuoteParse.Token>> splitSegments(List<ShellQuoteParse.Token> tokens) {
        List<List<ShellQuoteParse.Token>> segments = new ArrayList<>();
        List<ShellQuoteParse.Token> current = new ArrayList<>();
        for (ShellQuoteParse.Token t : tokens) {
            if (t instanceof ShellQuoteParse.Op(String op1) && SEGMENT_SEPARATORS.contains(op1)) {
                if (!current.isEmpty()) {
                    segments.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(t);
            }
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    /** Extracts the argv (positional words + glob patterns) from a segment. */
    private static List<String> argvFromTokens(List<ShellQuoteParse.Token> seg) {
        List<String> argv = new ArrayList<>();
        for (ShellQuoteParse.Token t : seg) {
            if (t instanceof ShellQuoteParse.Word(String value)) {
                argv.add(value);
            } else if (t instanceof ShellQuoteParse.Glob(String pattern)) {
                argv.add(pattern);
            }
        }
        return argv;
    }

    private static final Set<String> SEGMENT_SEPARATORS =
        Set.of(";", "&", "|", "&&", "||", "|&", "(", ")");

    private static PermissionDecision validateSinglePathCommand(
        List<String> argv, String cwdStr, boolean compoundCommandHasCd,
        ToolPermissionContext permCtx) {
        // SECURITY: strip wrapper commands (timeout/nice/nohup/time/stdbuf/env)
        // before extracting the base command — a wrapped `rm -rf /` would
        // otherwise see the wrapper as the base command and skip validation.
        argv = stripWrappersFromArgv(argv);
        if (argv.isEmpty()) {
            return null;
        }
        String baseCmd = argv.getFirst();
        if (!SUPPORTED_PATH_COMMANDS.contains(baseCmd)) {
            return null; // not a path command — let other checks handle it
        }
        List<String> args = argv.subList(1, argv.size());

        // SECURITY: mv/cp with ANY flag forces manual approval (some flags like
        // --target-directory=PATH bypass path extraction).
        if (FLAG_RESTRICTED_COMMANDS.contains(baseCmd)
                && args.stream().anyMatch(a -> Strings.CS.startsWith(a, "-"))) {
            return PermissionDecision.ask();
        }

        Function<List<String>, List<String>> extractor = PATH_EXTRACTORS.get(baseCmd);
        List<String> paths = extractor.apply(args);
        String opType = COMMAND_OPERATION_TYPE.get(baseCmd);
        boolean isRead = Strings.CS.equals("read", opType);

        // SECURITY: block write operations in compound commands containing 'cd'
        // (final CWD can't be reliably determined).
        if (compoundCommandHasCd && !isRead) {
            return PermissionDecision.ask();
        }


        PermissionDecision pathDecision = null;
        for (String p : paths) {
            PathValidation.PathValidationResult r = PathValidation.validatePath(p, cwdStr, isRead);
            if (!r.allowed()) {
                pathDecision = pathAsk(r.resolvedPath(), cwdStr, opType);
                break;
            }
            if (!isPathAllowedForOperation(r.resolvedPath(), permCtx, isRead)) {
                pathDecision = pathAsk(r.resolvedPath(), cwdStr, opType);
                break;
            }
        }

        // Check dangerous removal paths (rm/rmdir) AFTER generic validation,

        // intentionally overrides a generic containment/glob Ask so `rm --
        // -rf /` reports `/`, not the first earlier path token.
        if (Strings.CS.equals(baseCmd, "rm") || Strings.CS.equals(baseCmd, "rmdir")) {
            PermissionDecision dangerous = checkDangerousRemovalPaths(paths, cwdStr);
            if (dangerous != null) {
                return dangerous;
            }
        }
        return pathDecision;
    }


    private static PermissionDecision checkDangerousRemovalPaths(
        List<String> paths, String cwdStr) {
        for (String path : paths) {
            String clean = PathValidation.expandTilde(stripQuotes(path));
            String absolute = isAbsolute(clean) ? clean : resolve(clean, cwdStr);
            if (PathValidation.isDangerousRemovalPath(absolute)) {
                return new PermissionDecision.Ask(absolute);
            }
        }
        return null;
    }

    /**
     * Validates output-redirection targets across the whole token stream.
     */
    private static PermissionDecision validateSegmentRedirects(
        List<ShellQuoteParse.Token> tokens, String cwdStr,
        boolean compoundCommandHasCd, ToolPermissionContext permCtx) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof ShellQuoteParse.Op(String operator))) {
                continue;
            }
          boolean isRedirect;
            if (Strings.CS.equals(operator, ">") || Strings.CS.equals(operator, ">>") || Strings.CS.equals(operator, "&>")) {
                isRedirect = true;
            } else if (Strings.CS.equals(operator, ">&")) {
                // File-descriptor duplication (2>&1, >&1) writes nothing to a path.
                ShellQuoteParse.Token nx = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
                isRedirect = !(nx instanceof ShellQuoteParse.Word(String value) && value.matches("\\d+"));
            } else {
                isRedirect = false;
            }
            if (!isRedirect) {
                continue;
            }

// SECURITY: any write-via-redirection in a cd-compound requires approval (final CWD
// can't be reliably determined).
            if (compoundCommandHasCd) {
                return PermissionDecision.ask();
            }

            // Resolve the target token. `>|` / `>>|` (force clobber) split as
            // '>'/'>>' then '|'; the target is the token after the '|'.
            ShellQuoteParse.Token targetTok;
            if ((Strings.CS.equals(operator, ">") || Strings.CS.equals(operator, ">>")) && i + 2 < tokens.size()
                    && tokens.get(i + 1) instanceof ShellQuoteParse.Op(String op)
                    && Strings.CS.equals(op, "|")) {
                targetTok = tokens.get(i + 2);
            } else if (i + 1 < tokens.size()) {
                targetTok = tokens.get(i + 1);
            } else {
                targetTok = null;
            }
            String target = targetValue(targetTok);
            if (target == null || Strings.CS.equals(target, "/dev/null")) {
                continue;
            }
            PathValidation.PathValidationResult r =
                PathValidation.validatePath(target, cwdStr, false);
            if (!r.allowed()) {
                return redirectPathAsk(r.resolvedPath(), cwdStr);
            }
            if (!isPathAllowedForOperation(r.resolvedPath(), permCtx, false)) {
                return redirectPathAsk(r.resolvedPath(), cwdStr);
            }
        }
        return null;
    }

    /**
     * matches {@code createPathChecker}'s.
     */
    private static PermissionDecision.Ask pathAsk(
            String blockedPath, String cwdStr, String operationType) {
        List<PermissionUpdate> suggestions = new ArrayList<>();
        String directory = directoryForPath(blockedPath, cwdStr);
        if (Strings.CS.equals("read", operationType)) {
            PermissionUpdate readRule = readRuleSuggestion(directory);
            if (readRule != null) suggestions.add(readRule);
        } else if (StringUtils.isNotBlank(directory)) {
            suggestions.add(new PermissionUpdate.AddDirectories(
                List.of(directory), PermissionUpdate.Destination.SESSION));
        }
        if (Strings.CS.equals("write", operationType)
                || Strings.CS.equals("create", operationType)) {
            suggestions.add(new PermissionUpdate.SetMode(
                PermissionModeKind.ACCEPT_EDITS,
                PermissionUpdate.Destination.SESSION));
        }
        return new PermissionDecision.Ask(
            blockedPath, null, null, null, null, suggestions);
    }

/**
     * Output redirection has the narrower.
     */
    private static PermissionDecision.Ask redirectPathAsk(
            String blockedPath, String cwdStr) {
        String directory = directoryForPath(blockedPath, cwdStr);
        List<PermissionUpdate> suggestions = StringUtils.isBlank(directory)
            ? List.of()
            : List.of(new PermissionUpdate.AddDirectories(
                List.of(directory), PermissionUpdate.Destination.SESSION));
        return new PermissionDecision.Ask(
            blockedPath, null, null, null, null, suggestions);
    }


    private static String directoryForPath(String pathText, String cwdStr) {
        if (StringUtils.isBlank(pathText)) return null;
        try {
            Path path = Path.of(pathText);
            if (!path.isAbsolute() && cwdStr != null && !StringUtils.isBlank(cwdStr)) {
                path = Path.of(cwdStr).resolve(path);
            }
            path = path.normalize();
            if (!Strings.CS.startsWith(pathText, "//") && Files.isDirectory(path)) {
                return path.toString();
            }
            Path parent = path.getParent();
            return parent == null ? path.toString() : parent.toString();
        } catch (RuntimeException _) {
            int slash = Math.max(pathText.lastIndexOf('/'), pathText.lastIndexOf('\\'));
            return slash <= 0 ? pathText : pathText.substring(0, slash);
        }
    }


    private static PermissionUpdate readRuleSuggestion(String directory) {
        if (StringUtils.isBlank(directory) ||Strings.CS.equals( "/", directory)) {
            return null;
        }
        String posix = directory.replace('\\', '/');
        String ruleContent =Strings.CS.startsWith( posix, "/")
            ? "/" + posix + "/**"
            : posix + "/**";
        return new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue("Read", ruleContent)),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.SESSION);
    }


    private static boolean isPathAllowedForOperation(
        String resolvedPath, ToolPermissionContext permCtx, boolean readOnly) {
        if (resolvedPath == null) {
            return true;
        }
        boolean withinWorkingDirectories = WorkingDirectoryPaths.isWithinWorkingDirectories(
            Path.of(resolvedPath), permCtx);
        return withinWorkingDirectories
            && (readOnly || permCtx.mode() == PermissionMode.ACCEPT_EDITS);
    }

    private static boolean isSingleAcceptEditsCommand(String command) {
        List<List<ShellQuoteParse.Token>> segments = splitSegments(ShellQuoteParse.parse(command));
        if (segments.size() != 1) {
            return false;
        }
        List<String> argv = stripWrappersFromArgv(argvFromTokens(segments.getFirst()));
        return !argv.isEmpty() && ACCEPT_EDITS_ALLOWED_COMMANDS.contains(argv.getFirst());
    }

    /** Returns the path-like value of a redirect target token (word or glob), else null. */
    private static String targetValue(ShellQuoteParse.Token tok) {
        if (tok instanceof ShellQuoteParse.Word(String value)) {
            return value;
        }
        if (tok instanceof ShellQuoteParse.Glob(String pattern)) {
            return pattern;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    private static final Pattern TIMEOUT_VALUE_RE = Pattern.compile("[A-Za-z0-9_.+-]+");
    private static final Pattern DURATION_RE = Pattern.compile("\\d+(?:\\.\\d+)?[smhd]?");

    private static List<String> stripWrappersFromArgv(List<String> argv) {
        List<String> a = new ArrayList<>(argv);
        for (;;) {
            if (a.isEmpty()) {
                return a;
            }
            String head = a.getFirst();
            switch (head) {
                case "time", "nohup" -> {
                    if (a.size() > 1 && Strings.CS.equals(a.get(1), "--")) {
                        a = new ArrayList<>(a.subList(2, a.size()));
                    } else {
                        a = new ArrayList<>(a.subList(1, a.size()));
                    }
                }
                case "timeout" -> {
                    int i = skipTimeoutFlags(a);
                    if (i < 0 || i >= a.size()) {
                        return a;
                    }
                    if (!DURATION_RE.matcher(a.get(i)).matches()) {
                        return a;
                    }
                    a = new ArrayList<>(a.subList(i + 1, a.size()));
                }
                case "nice" -> {
                    if (a.size() > 1 && Strings.CS.equals(a.get(1), "-n") && a.size() > 2
                        && a.get(2).matches("-?\\d+")) {
                        a = new ArrayList<>(
                            a.subList(a.size() > 3 && Strings.CS.equals(a.get(3), "--") ? 4 : 3, a.size()));
                    } else if (a.size() > 1 && a.get(1).matches("-\\d+")) {
                        a = new ArrayList<>(
                            a.subList(a.size() > 2 && Strings.CS.equals(a.get(2), "--") ? 3 : 2, a.size()));
                    } else {
                        a = new ArrayList<>(
                            a.subList(a.size() > 1 && Strings.CS.equals(a.get(1), "--") ? 2 : 1, a.size()));
                    }
                }
                case "stdbuf" -> {
                    int i = skipStdbufFlags(a);
                    if (i < 0) {
                        return a;
                    }
                    a = new ArrayList<>(a.subList(i, a.size()));
                }
                case "env" -> {
                    int i = skipEnvFlags(a);
                    if (i < 0) {
                        return a;
                    }
                    a = new ArrayList<>(a.subList(i, a.size()));
                }
                default -> {
                    return a;
                }
            }
        }
    }

    private static int skipTimeoutFlags(List<String> a) {
        int i = 1;
        while (i < a.size()) {
            String arg = a.get(i);
            String next = i + 1 < a.size() ? a.get(i + 1) : null;
            if (Strings.CS.equals(arg, "--foreground") || Strings.CS.equals(arg, "--preserve-status")
                    || Strings.CS.equals(arg, "--verbose")) {
                i++;
            } else if (Pattern.matches("--(?:kill-after|signal)=[A-Za-z0-9_.+-]+", arg)) {
                i++;
            } else if ((Strings.CS.equals(arg, "--kill-after") || Strings.CS.equals(arg, "--signal")) && next != null
                    && TIMEOUT_VALUE_RE.matcher(next).matches()) {
                i += 2;
            } else if (Strings.CS.equals(arg, "--")) {
                i++;
                break;
            } else if (Strings.CS.startsWith(arg, "--")) {
                return -1;
            } else if (Strings.CS.equals(arg, "-v")) {
                i++;
            } else if ((Strings.CS.equals(arg, "-k") || Strings.CS.equals(arg, "-s")) && next != null
                    && TIMEOUT_VALUE_RE.matcher(next).matches()) {
                i += 2;
            } else if (Pattern.matches("-[ks][A-Za-z0-9_.+-]+", arg)) {
                i++;
            } else if (Strings.CS.startsWith(arg, "-")) {
                return -1;
            } else {
                break;
            }
        }
        return i;
    }

    private static int skipStdbufFlags(List<String> a) {
        int i = 1;
        while (i < a.size()) {
            String arg = a.get(i);
            if (Pattern.matches("^-[ioe]$", arg) && i + 1 < a.size()) {
                i += 2;
            } else if (Pattern.matches("^-[ioe].", arg)) {
                i++;
            } else if (Pattern.matches("--(?:input|output|error)=", arg)) {
                i++;
            } else if (Strings.CS.startsWith(arg, "-")) {
                return -1;
            } else {
                break;
            }
        }
        return i > 1 && i < a.size() ? i : -1;
    }

    private static int skipEnvFlags(List<String> a) {
        int i = 1;
        while (i < a.size()) {
            String arg = a.get(i);
            if (Strings.CS.contains(arg, "=") && !Strings.CS.startsWith(arg, "-")) {
                i++;
            } else if (Strings.CS.equals(arg, "-i") || Strings.CS.equals(arg, "-0") || Strings.CS.equals(arg, "-v")) {
                i++;
            } else if (Strings.CS.equals(arg, "-u") && i + 1 < a.size()) {
                i += 2;
            } else if (Strings.CS.startsWith(arg, "-")) {
                return -1;
            } else {
                break;
            }
        }
        return i < a.size() ? i : -1;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tokenizer (quote-aware, splits on unquoted ; && || |)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Quote-aware tokenizer: splits the command into segments delimited by the
     * unquoted operators {@code ; && || |}, and within each segment into tokens
     * split on unquoted whitespace with surrounding quotes stripped. Operators
     * themselves are dropped. Redirect operators ({@code >}, {@code >>}) are
     * retained as ordinary tokens so callers can detect redirection targets.
     */
    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    private static boolean isAbsolute(String p) {
        return Path.of(p).isAbsolute();
    }

    private static String resolve(String p, String cwd) {
        return Path.of(cwd == null ? "." : cwd, p).normalize().toString();
    }

    private static String stripQuotes(String path) {

        // quote char independently (so mismatched quotes like 'abc" also strip).
        int start = 0;
        int end = path.length();
        if (end > 0 && (path.charAt(0) == '\'' || path.charAt(0) == '"')) {
            start = 1;
        }
        if (end > start && (path.charAt(end - 1) == '\'' || path.charAt(end - 1) == '"')) {
            end--;
        }
        return path.substring(start, end);
    }
}
