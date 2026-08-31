package com.claudecode.tools.powershell;

import java.util.Locale;

import com.claudecode.permissions.DecisionReason;
import com.claudecode.permissions.PathValidation;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.tools.bash.BashPermissions;

/**
 * Permission checks for PowerShellTool commands — the PowerShell analogue of {@link
 * BashPermissions}.
 */
public final class PowerShellPermissions {

    private PowerShellPermissions() {}

    /** Checks permissions for a PowerShell command (legacy shape, no context). */
    public static PermissionDecision check(String command) {
        return check(command, null);
    }

    /**
     * Checks permissions for a PowerShell command, with optional working-directory
     * context for path-constraint validation.
     */
    public static PermissionDecision check(String command, ToolPermissionContext permCtx) {
        if (StringUtils.isBlank(command)) {
            return PermissionDecision.deny();
        }
        if (permCtx != null) {
            PermissionDecision constrained = checkPathConstraints(command, permCtx.workingDirectory());
            if (constrained != null) {
                return constrained;
            }
        }


        // read-only command. The isolated checker never evaluates the command;
        // parser absence/failure/timeout is deliberately fail-safe ASK.
        PowerShellParser.ParseResult parsed = PowerShellParser.parse(command);
        if (!parsed.available() || !parsed.valid()) {
            String detail = StringUtils.isBlank(parsed.detail())
                ? "PowerShell syntax could not be validated safely"
                : parsed.detail();
            return new PermissionDecision.Ask(null, null, detail, null, null);
        }


        // lexical guard remains an additional conservative check for constructs
        // whose AST details are not yet projected into this port.
        String securityConcern = PowerShellSecurity.concern(command);
        if (securityConcern != null) {
            return new PermissionDecision.Ask(null, null, securityConcern, null, null);
        }
        if (isReadOnlyPowerShellCommand(command)) {
            return PermissionDecision.allow();
        }
        return PermissionDecision.ask();
    }

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    private static final Map<String, String> COMMON_ALIASES = new HashMap<>();

    static {
        Map<String, String> a = COMMON_ALIASES;
        a.put("ls", "get-childitem"); a.put("dir", "get-childitem"); a.put("gci", "get-childitem");
        a.put("cat", "get-content"); a.put("type", "get-content"); a.put("gc", "get-content");
        a.put("cd", "set-location"); a.put("sl", "set-location"); a.put("chdir", "set-location");
        a.put("pushd", "push-location"); a.put("popd", "pop-location");
        a.put("pwd", "get-location"); a.put("gl", "get-location");
        a.put("gi", "get-item"); a.put("gp", "get-itemproperty"); a.put("ni", "new-item");
        a.put("mkdir", "new-item"); a.put("md", "new-item");
        a.put("ri", "remove-item"); a.put("del", "remove-item"); a.put("rd", "remove-item");
        a.put("rmdir", "remove-item"); a.put("rm", "remove-item"); a.put("erase", "remove-item");
        a.put("mi", "move-item"); a.put("mv", "move-item"); a.put("move", "move-item");
        a.put("ci", "copy-item"); a.put("cp", "copy-item"); a.put("copy", "copy-item");
        a.put("cpi", "copy-item"); a.put("si", "set-item"); a.put("rni", "rename-item");
        a.put("ren", "rename-item");
        a.put("ps", "get-process"); a.put("gps", "get-process"); a.put("kill", "stop-process");
        a.put("spps", "stop-process"); a.put("start", "start-process"); a.put("saps", "start-process");
        a.put("sajb", "start-job"); a.put("ipmo", "import-module");
        a.put("echo", "write-output"); a.put("write", "write-output"); a.put("sleep", "start-sleep");
        a.put("help", "get-help"); a.put("man", "get-help"); a.put("gcm", "get-command");
        a.put("gsv", "get-service");
        a.put("gv", "get-variable"); a.put("sv", "set-variable");
        a.put("h", "get-history"); a.put("history", "get-history");
        a.put("iex", "invoke-expression"); a.put("iwr", "invoke-webrequest");
        a.put("irm", "invoke-restmethod"); a.put("icm", "invoke-command"); a.put("ii", "invoke-item");
        a.put("nsn", "new-pssession"); a.put("etsn", "enter-pssession");
        a.put("exsn", "exit-pssession"); a.put("gsn", "get-pssession"); a.put("rsn", "remove-pssession");
        a.put("cls", "clear-host"); a.put("clear", "clear-host");
        a.put("select", "select-object"); a.put("where", "where-object");
        a.put("foreach", "foreach-object"); a.put("%", "foreach-object"); a.put("?", "where-object");
        a.put("measure", "measure-object"); a.put("ft", "format-table"); a.put("fl", "format-list");
        a.put("fw", "format-wide"); a.put("oh", "out-host"); a.put("ogv", "out-gridview");
        a.put("ac", "add-content"); a.put("clc", "clear-content");
        a.put("tee", "tee-object"); a.put("epcsv", "export-csv"); a.put("sp", "set-itemproperty");
        a.put("rp", "remove-itemproperty"); a.put("cli", "clear-item"); a.put("epal", "export-alias");
        a.put("sls", "select-string");
    }

    /** PowerShell executable extensions stripped from bare command names. */
    private static final Pattern PATHEXT = Pattern.compile("\\.(exe|cmd|bat|com)$");

    static String resolveToCanonical(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!Strings.CS.contains(lower, "\\") && !Strings.CS.contains(lower, "/")) {
            lower = PATHEXT.matcher(lower).replaceAll("");
        }
        String alias = COMMON_ALIASES.get(lower);
        return alias != null ? alias : lower;
    }

    private static boolean isCwdChangingCmdlet(String name) {
        String canonical = resolveToCanonical(name);
        return Strings.CS.equals(canonical, "set-location") || Strings.CS.equals(canonical, "push-location")
            || Strings.CS.equals(canonical, "pop-location") || Strings.CS.equals(canonical, "new-psdrive");
    }

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    private static final List<String> COMMON_SWITCHES = List.of("-verbose", "-debug");
    private static final List<String> COMMON_VALUE_PARAMS = List.of(
        "-erroraction", "-warningaction", "-informationaction", "-progressaction",
        "-errorvariable", "-warningvariable", "-informationvariable", "-outvariable",
        "-outbuffer", "-pipelinevariable");

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    /**
     * Per-cmdlet parameter configuration. {@code pathParams} accept file paths
     * (validated); {@code knownSwitches} take no value; {@code knownValueParams}
     * take a value that is NOT a path; {@code leafOnlyPathParams} accept a simple
     * leaf filename only (non-leaf → unvalidatable); {@code positionalSkip}
     * skips leading positional args that are not paths; {@code optionalWrite}
     * cmdlets only write when a path param is present.
     */
    record CmdletPathConfig(
        String operationType,
        List<String> pathParams,
        List<String> knownSwitches,
        List<String> knownValueParams,
        List<String> leafOnlyPathParams,
        int positionalSkip,
        boolean optionalWrite
    ) {}

    private static final Map<String, CmdletPathConfig> CMDLET_PATH_CONFIG = new HashMap<>();

    static {
        Map<String, CmdletPathConfig> c = CMDLET_PATH_CONFIG;
        List<String> stdPath = List.of("-path", "-literalpath", "-pspath", "-lp");

        c.put("set-content", new CmdletPathConfig("write", stdPath,
            List.of("-passthru", "-force", "-whatif", "-confirm", "-usetransaction",
                "-nonewline", "-asbytestream"),
            List.of("-value", "-filter", "-include", "-exclude", "-credential",
                "-encoding", "-stream"), List.of(), 0, false));
        c.put("add-content", new CmdletPathConfig("write", stdPath,
            List.of("-passthru", "-force", "-whatif", "-confirm", "-usetransaction",
                "-nonewline", "-asbytestream"),
            List.of("-value", "-filter", "-include", "-exclude", "-credential",
                "-encoding", "-stream"), List.of(), 0, false));
        c.put("remove-item", new CmdletPathConfig("write", stdPath,
            List.of("-recurse", "-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-stream"),
            List.of(), 0, false));
        c.put("clear-content", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-stream"),
            List.of(), 0, false));
        c.put("out-file", new CmdletPathConfig("write",
            List.of("-filepath", "-path", "-literalpath", "-pspath", "-lp"),
            List.of("-append", "-force", "-noclobber", "-nonewline", "-whatif", "-confirm"),
            List.of("-inputobject", "-encoding", "-width"), List.of(), 0, false));
        c.put("tee-object", new CmdletPathConfig("write",
            List.of("-filepath", "-path", "-literalpath", "-pspath", "-lp"),
            List.of("-append"),
            List.of("-inputobject", "-variable", "-encoding"), List.of(), 0, false));
        c.put("export-csv", new CmdletPathConfig("write", stdPath,
            List.of("-append", "-force", "-noclobber", "-notypeinformation",
                "-includetypeinformation", "-useculture", "-noheader", "-whatif", "-confirm"),
            List.of("-inputobject", "-delimiter", "-encoding", "-quotefields", "-usequotes"),
            List.of(), 0, false));
        c.put("export-clixml", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-noclobber", "-whatif", "-confirm"),
            List.of("-inputobject", "-depth", "-encoding"), List.of(), 0, false));
        c.put("new-item", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-itemtype", "-value", "-credential", "-type"),
            List.of("-name"), 0, false));
        c.put("copy-item", new CmdletPathConfig("write",
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destination"),
            List.of("-container", "-force", "-passthru", "-recurse", "-whatif",
                "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-fromsession", "-tosession"),
            List.of(), 0, false));
        c.put("move-item", new CmdletPathConfig("write",
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destination"),
            List.of("-force", "-passthru", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential"), List.of(), 0, false));
        c.put("rename-item", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-passthru", "-whatif", "-confirm", "-usetransaction"),
            List.of("-newname", "-credential", "-filter", "-include", "-exclude"),
            List.of(), 0, false));
        c.put("set-item", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-passthru", "-whatif", "-confirm", "-usetransaction"),
            List.of("-value", "-credential", "-filter", "-include", "-exclude"),
            List.of(), 0, false));
        c.put("get-content", new CmdletPathConfig("read", stdPath,
            List.of("-force", "-usetransaction", "-wait", "-raw", "-asbytestream"),
            List.of("-readcount", "-totalcount", "-tail", "-first", "-head", "-last",
                "-filter", "-include", "-exclude", "-credential", "-delimiter",
                "-encoding", "-stream"), List.of(), 0, false));
        c.put("get-childitem", new CmdletPathConfig("read", stdPath,
            List.of("-recurse", "-force", "-name", "-usetransaction", "-followsymlink",
                "-directory", "-file", "-hidden", "-readonly", "-system"),
            List.of("-filter", "-include", "-exclude", "-depth", "-attributes", "-credential"),
            List.of(), 0, false));
        c.put("get-item", new CmdletPathConfig("read", stdPath,
            List.of("-force", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential", "-stream"),
            List.of(), 0, false));
        c.put("get-itemproperty", new CmdletPathConfig("read", stdPath,
            List.of("-usetransaction"),
            List.of("-name", "-filter", "-include", "-exclude", "-credential"),
            List.of(), 0, false));
        c.put("get-itempropertyvalue", new CmdletPathConfig("read", stdPath,
            List.of("-usetransaction"),
            List.of("-name", "-filter", "-include", "-exclude", "-credential"),
            List.of(), 0, false));
        c.put("get-filehash", new CmdletPathConfig("read", stdPath,
            List.of(),
            List.of("-algorithm", "-inputstream"), List.of(), 0, false));
        c.put("get-acl", new CmdletPathConfig("read", stdPath,
            List.of("-audit", "-allcentralaccesspolicies", "-usetransaction"),
            List.of("-inputobject", "-filter", "-include", "-exclude"), List.of(), 0, false));
        c.put("format-hex", new CmdletPathConfig("read", stdPath,
            List.of("-raw"),
            List.of("-inputobject", "-encoding", "-count", "-offset"), List.of(), 0, false));
        c.put("test-path", new CmdletPathConfig("read", stdPath,
            List.of("-isvalid", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-pathtype", "-credential",
                "-olderthan", "-newerthan"), List.of(), 0, false));
        c.put("resolve-path", new CmdletPathConfig("read", stdPath,
            List.of("-relative", "-usetransaction", "-force"),
            List.of("-credential", "-relativebasepath"), List.of(), 0, false));
        c.put("convert-path", new CmdletPathConfig("read", stdPath,
            List.of("-usetransaction"), List.of(), List.of(), 0, false));
        c.put("select-string", new CmdletPathConfig("read", stdPath,
            List.of("-simplematch", "-casesensitive", "-quiet", "-list", "-notmatch",
                "-allmatches", "-noemphasis", "-raw"),
            List.of("-inputobject", "-pattern", "-include", "-exclude", "-encoding",
                "-context", "-culture"), List.of(), 0, false));
        c.put("set-location", new CmdletPathConfig("read", stdPath,
            List.of("-passthru", "-usetransaction"),
            List.of("-stackname"), List.of(), 0, false));
        c.put("push-location", new CmdletPathConfig("read", stdPath,
            List.of("-passthru", "-usetransaction"),
            List.of("-stackname"), List.of(), 0, false));
        c.put("pop-location", new CmdletPathConfig("read", List.of(),
            List.of("-passthru", "-usetransaction"),
            List.of("-stackname"), List.of(), 0, false));
        c.put("select-xml", new CmdletPathConfig("read", stdPath,
            List.of(),
            List.of("-xml", "-content", "-xpath", "-namespace"), List.of(), 0, false));
        c.put("get-winevent", new CmdletPathConfig("read", List.of("-path"),
            List.of("-force", "-oldest"),
            List.of("-listlog", "-logname", "-listprovider", "-providername", "-maxevents",
                "-computername", "-credential", "-filterxpath", "-filterxml", "-filterhashtable"),
            List.of(), 0, false));
        c.put("invoke-webrequest", new CmdletPathConfig("write",
            List.of("-outfile", "-infile"),
            List.of("-allowinsecureredirect", "-allowunencryptedauthentication",
                "-disablekeepalive", "-nobodyprogress", "-passthru",
                "-preservefileauthorizationmetadata", "-resume", "-skipcertificatecheck",
                "-skipheadervalidation", "-skiphttperrorcheck", "-usebasicparsing",
                "-usedefaultcredentials"),
            List.of("-uri", "-method", "-body", "-contenttype", "-headers",
                "-maximumredirection", "-maximumretrycount", "-proxy", "-proxycredential",
                "-retryintervalsec", "-sessionvariable", "-timeoutsec", "-token",
                "-transferencoding", "-useragent", "-websession", "-credential",
                "-authentication", "-certificate", "-certificatethumbprint", "-form",
                "-httpversion"), List.of(), 1, true));
        c.put("invoke-restmethod", new CmdletPathConfig("write",
            List.of("-outfile", "-infile"),
            List.of("-allowinsecureredirect", "-allowunencryptedauthentication",
                "-disablekeepalive", "-followrellink", "-nobodyprogress", "-passthru",
                "-preservefileauthorizationmetadata", "-resume", "-skipcertificatecheck",
                "-skipheadervalidation", "-skiphttperrorcheck", "-usebasicparsing",
                "-usedefaultcredentials"),
            List.of("-uri", "-method", "-body", "-contenttype", "-headers",
                "-maximumfollowrellink", "-maximumredirection", "-maximumretrycount",
                "-proxy", "-proxycredential", "-responseheaderstvariable",
                "-retryintervalsec", "-sessionvariable", "-statuscodevariable",
                "-timeoutsec", "-token", "-transferencoding", "-useragent", "-websession",
                "-credential", "-authentication", "-certificate",
                "-certificatethumbprint", "-form", "-httpversion"), List.of(), 1, true));
        c.put("expand-archive", new CmdletPathConfig("write",
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destinationpath"),
            List.of("-force", "-passthru", "-whatif", "-confirm"),
            List.of("-compressionlevel"), List.of(), 0, false));
        c.put("compress-archive", new CmdletPathConfig("write",
            List.of("-path", "-literalpath", "-pspath", "-lp", "-destinationpath"),
            List.of("-force", "-update", "-passthru", "-whatif", "-confirm"),
            List.of("-compressionlevel"), List.of(), 0, false));
        c.put("set-itemproperty", new CmdletPathConfig("write", stdPath,
            List.of("-passthru", "-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-name", "-value", "-type", "-filter", "-include", "-exclude",
                "-credential", "-inputobject"), List.of(), 0, false));
        c.put("new-itemproperty", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-name", "-value", "-propertytype", "-type", "-filter", "-include",
                "-exclude", "-credential"), List.of(), 0, false));
        c.put("remove-itemproperty", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-name", "-filter", "-include", "-exclude", "-credential"),
            List.of(), 0, false));
        c.put("clear-item", new CmdletPathConfig("write", stdPath,
            List.of("-force", "-whatif", "-confirm", "-usetransaction"),
            List.of("-filter", "-include", "-exclude", "-credential"), List.of(), 0, false));
        c.put("export-alias", new CmdletPathConfig("write", stdPath,
            List.of("-append", "-force", "-noclobber", "-passthru", "-whatif", "-confirm"),
            List.of("-name", "-description", "-scope", "-as"), List.of(), 0, false));
    }

    /** Read-only cmdlets that auto-allow (no path-operation concern). */
    private static final Set<String> READ_CMDLETS = new HashSet<>(Arrays.asList(
        "get-content", "get-childitem", "get-item", "get-itemproperty",
        "get-itempropertyvalue", "get-filehash", "get-acl", "format-hex", "test-path",
        "resolve-path", "convert-path", "select-string", "set-location",
        "push-location", "pop-location",
        // Read-only cmdlets without a path config (no paths to validate).
        "get-location", "select-object", "where-object", "foreach-object",
        "measure-object", "format-table", "format-list", "format-wide", "out-host",
        "get-process", "get-service", "get-variable", "get-history", "get-help",
        "get-command", "get-pssession", "clear-host", "write-output", "start-sleep",
        "invoke-expression", "invoke-command", "invoke-item", "import-module"
    ));

    // ─────────────────────────────────────────────────────────────────────
    // Path-constraint driver
    // ─────────────────────────────────────────────────────────────────────

    private static final Pattern PROVIDER_PATH_RE = Pattern.compile("^[a-z0-9]+:");
    private static final Pattern GLOB_RE = Pattern.compile("[*?\\[\\]]");

    private static PermissionDecision checkPathConstraints(String command, Path cwd) {
        String cwdStr = cwd != null ? cwd.toString() : ".";
        List<String> statements = splitStatements(command);
        boolean compoundCommandHasCd = statements.size() > 1
            && statements.stream().anyMatch(s -> {
                String f = firstCmdletOf(s);
                return f != null && isCwdChangingCmdlet(f);
            });
        for (String stmt : statements) {
            PermissionDecision result = validateStatement(stmt, cwdStr, compoundCommandHasCd);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private static PermissionDecision validateStatement(
        String statement, String cwdStr, boolean compoundCommandHasCd) {
        List<List<String>> segments = splitPipeline(statement);
        boolean hasExprSource = false;
        for (List<String> seg : segments) {
            if (seg.isEmpty()) {
                continue;
            }
            String first = seg.getFirst();
            if (isExpressionToken(first)) {
                hasExprSource = true;
                continue;
            }
            String canonical = resolveToCanonical(first);
            if (hasExprSource) {
                // A cmdlet receiving a piped expression source cannot be
                // statically validated — ask.
                return PermissionDecision.ask();
            }
            CmdletPathConfig config = CMDLET_PATH_CONFIG.get(canonical);
            if (config == null) {
                continue; // not a path command — let other checks handle it
            }
            ExtractResult er = extractPaths(seg.subList(1, seg.size()), config);
            if (er.hasUnvalidatablePathArg()) {
                return PermissionDecision.ask();
            }
            if (!Strings.CS.equals("read", er.operationType()) && !er.optionalWrite()
                    && er.paths().isEmpty()) {
                // Write cmdlet with no determinable target path → ask.
                return PermissionDecision.ask();
            }
            if (compoundCommandHasCd) {

                // cannot be validated against the stale cwd → ask.
                return PermissionDecision.ask();
            }
            for (String p : er.paths()) {
                if (Strings.CS.equals(canonical, "remove-item")
                        && PathValidation.isDangerousRemovalPath(
                            expandAndNormalize(p, cwdStr))) {
                    String protectedPath = expandAndNormalize(p, cwdStr);
                    return new PermissionDecision.Deny(
                        "Remove-Item on system path '" + protectedPath
                            + "' is blocked. This path is protected from removal.",
                        new DecisionReason.Other(
                            "Removal targets a protected system path"));
                }
                PermissionDecision pr = validatePowerShellPath(p, cwdStr, er.operationType());
                if (pr != null) {
                    return pr;
                }
            }
            PermissionDecision redirect = validateSegmentRedirects(seg, cwdStr);
            if (redirect != null) {
                return redirect;
            }
        }
        return null;
    }

    private record ExtractResult(
        List<String> paths, String operationType,
        boolean hasUnvalidatablePathArg, boolean optionalWrite) {}

    private static ExtractResult extractPaths(List<String> args, CmdletPathConfig config) {
        List<String> switchParams = new ArrayList<>(config.knownSwitches());
        switchParams.addAll(COMMON_SWITCHES);
        List<String> valueParams = new ArrayList<>(config.knownValueParams());
        valueParams.addAll(COMMON_VALUE_PARAMS);

        List<String> paths = new ArrayList<>();
        boolean hasUnvalidatable = false;
        int positionalsSeen = 0;
        int positionalSkip = config.positionalSkip();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg == null) {
                continue;
            }
            if (Strings.CS.startsWith(arg, "-")) {
                int colonIdx = arg.indexOf(':');
                String paramName = colonIdx > 0 ? arg.substring(0, colonIdx) : arg;
                String paramLower = paramName.toLowerCase(Locale.ROOT);
                if (matchesParam(paramLower, config.pathParams())) {
                    String value = extractParamValue(arg, colonIdx, i, args);
                    if (value != null) {
                        paths.add(stripQuotes(value));
                    }
                } else if (config.leafOnlyPathParams() != null
                        && matchesParam(paramLower, config.leafOnlyPathParams())) {
                    String value = extractParamValue(arg, colonIdx, i, args);
                    if (value != null) {
                        if (Strings.CS.contains(value, "/") || Strings.CS.contains(value, "\\")
                                || Strings.CS.equals(value, ".") || Strings.CS.equals(value, "..")) {
                            hasUnvalidatable = true;
                        } else {
                            paths.add(stripQuotes(value));
                        }
                    }
                } else if (matchesParam(paramLower, switchParams)) {
                    // Switch parameter: consumes no value, nothing to validate.
                    continue;
                } else if (matchesParam(paramLower, valueParams)) {
                    // value param — consume its value, not path-validated
                    if (colonIdx > 0) {
                        String raw = arg.substring(colonIdx + 1);
                        if (hasComplexColonValue(raw)) {
                            hasUnvalidatable = true;
                        }
                    } else {
                        String next = i + 1 < args.size() ? args.get(i + 1) : null;
                        if (next != null && !Strings.CS.startsWith(next, "-")) {
                            i++;
                        }
                    }
                } else {
                    // Unknown parameter — cannot validate this invocation.
                    hasUnvalidatable = true;
                    if (colonIdx > 0) {
                        String raw = arg.substring(colonIdx + 1);
                        if (!hasComplexColonValue(raw)) {
                            paths.add(stripQuotes(raw));
                        }
                    }
                }
                continue;
            }
            // Positional argument.
            if (positionalsSeen < positionalSkip) {
                positionalsSeen++;
                continue;
            }
            positionalsSeen++;
            paths.add(stripQuotes(arg));
        }
        return new ExtractResult(paths, config.operationType(), hasUnvalidatable, config.optionalWrite());
    }

    /** Returns the bound value for a {@code -Param value} / {@code -Param:value}. */
    private static String extractParamValue(String arg, int colonIdx, int i, List<String> args) {
        if (colonIdx > 0) {
            String raw = arg.substring(colonIdx + 1);
            return hasComplexColonValue(raw) ? null : raw;
        }
        String next = i + 1 < args.size() ? args.get(i + 1) : null;
        if (next != null && !Strings.CS.startsWith(next, "-")) {
            return next;
        }
        return null;
    }

    /** PowerShell prefix matching: {@code -Lit} matches {@code -LiteralPath}. */
    private static boolean matchesParam(String paramLower, List<String> paramList) {
        for (String p : paramList) {
            if (Strings.CS.equals(p, paramLower) || (paramLower.length() > 1 && Strings.CS.startsWith(p, paramLower))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasComplexColonValue(String raw) {
        return Strings.CS.contains(raw, ",") || Strings.CS.startsWith(raw, "(")
            || Strings.CS.startsWith(raw, "[") || Strings.CS.contains(raw, "`")
            || Strings.CS.contains(raw, "@(") || Strings.CS.startsWith(raw, "@{")
            || Strings.CS.contains(raw, "$");
    }

    /**
     * Validates a single extracted path with PowerShell-specific guards layered
     * on top of the shell-agnostic {@link PathValidation} primitives.
     */
    private static PermissionDecision validatePowerShellPath(String path, String cwdStr, String opType) {
        String clean = PathValidation.expandTilde(stripQuotes(path));
        String normalized = clean.replace('\\', '/');
        // Backtick escape — cannot statically validate.
        if (Strings.CS.contains(normalized, "`")) {
            return new PermissionDecision.Ask(normalized);
        }
        // Provider-qualified path (::) — cannot statically validate.
        if (Strings.CS.contains(normalized, "::")) {
            return new PermissionDecision.Ask(normalized);
        }
        // UNC / WebDAV.
        if (Strings.CS.startsWith(normalized, "//")
                || Strings.CI.contains(normalized, "davwwwroot")
                || Strings.CI.contains(normalized, "@ssl@")) {
            return new PermissionDecision.Ask(normalized);
        }
        // Shell expansion syntax.
        if (Strings.CS.contains(normalized, "$") || Strings.CS.contains(normalized, "%")
                || Strings.CS.startsWith(normalized, "=")) {
            return new PermissionDecision.Ask(normalized);
        }
        // Non-filesystem provider path (env:, HKLM:, drive letters on POSIX).
        // Prefix match: "env:HOME" / "C:/foo" carry a provider/drive prefix.
        if (PROVIDER_PATH_RE.matcher(normalized).find()) {
            return new PermissionDecision.Ask(normalized);
        }
        // Glob patterns cannot be statically validated (reads leak data, writes
        // destroy it) — always ask.
        if (GLOB_RE.matcher(normalized).find()) {
            return new PermissionDecision.Ask(normalized);
        }
        // Residual string-level checks (UNC, tilde-variant, glob-write).
        PathValidation.PathValidationResult r = PathValidation.validatePath(
            path, cwdStr, !Strings.CS.equals("write", opType));
        if (!r.allowed()) {
            return new PermissionDecision.Ask(r.resolvedPath());
        }
        return null;
    }

    private static PermissionDecision validateSegmentRedirects(List<String> argv, String cwdStr) {
        for (int i = 0; i < argv.size(); i++) {
            String tok = argv.get(i);
            boolean isRedirect = false;
            String target = null;
            if (Strings.CS.equals(tok, ">") || Strings.CS.equals(tok, ">>")
                    || Strings.CS.equals(tok, ">|") || Strings.CS.equals(tok, "&>")
                    || Strings.CS.equals(tok, "&>>")) {
                isRedirect = true;
                if (i + 1 < argv.size()) {
                    target = argv.get(i + 1);
                }
            } else if (Strings.CS.startsWith(tok, ">&")) {
                String rest = tok.substring(2);
                if (!rest.matches("\\d+")) {
                    isRedirect = true;
                    target = rest;
                }
            }
            if (!isRedirect) {
                continue;
            }
            if (target != null && !Strings.CS.equals(target, "$null") && !Strings.CS.equals(target, "null")) {
                PermissionDecision pr = validatePowerShellPath(target, cwdStr, "write");
                if (pr != null) {
                    return pr;
                }
            }
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Read-only classification (for the Allow fast-path)
    // ─────────────────────────────────────────────────────────────────────

    private static boolean isReadOnlyPowerShellCommand(String command) {
        List<String> statements = splitStatements(command);
        for (String stmt : statements) {
            List<List<String>> segments = splitPipeline(stmt);
            if (segments.isEmpty()) return false;
            for (List<String> segment : segments) {
                if (segment.isEmpty()) return false;
                String cmd = firstCmdletOfTokens(segment);
                if (cmd == null || !READ_CMDLETS.contains(resolveToCanonical(cmd))) {
                    return false; // expression-only, mutating, or unknown → Ask
                }
            }
        }
        return true;
    }

    /**
     * Public read-only classification used by {@code PowerShellTool.isConcurrencySafe}.
     */
    public static boolean isReadOnlyCommand(String command) {
        if (StringUtils.isBlank(command)) {
            return false;
        }
        if (hasSyncSecurityConcerns(command)) {
            return false;
        }
        return isReadOnlyPowerShellCommand(command);
    }

    /**
     * Conservative guard rejecting commands the text heuristic cannot safely clear as read-only:
     * scriptblocks ({@code {}}), splatting/array/hash literals ({@code @(...) / @{...}}), and
     * obviously-mutating cmdlets ({@code Invoke-Expression}/{@code iex}, {@code Start-Process}).
     */
    private static boolean hasSyncSecurityConcerns(String command) {
        if (PowerShellSecurity.concern(command) != null) {
            return true;
        }
        if (command.indexOf('{') >= 0 || command.indexOf('}') >= 0) {
            return true; // scriptblock
        }
        if (Strings.CS.contains(command, "@(") || Strings.CS.contains(command, "@{")) {
            return true; // splatting / array / hash literal
        }
        if (containsWord(command, "iex") || containsWord(command, "invoke-expression")) {
            return true;
        }
        return containsWord(command, "start-process");
    }

    /** Quote-aware whole-word test (so {@code iex} doesn't match {@code detailed}). */
    private static boolean containsWord(String command, String word) {
        Pattern p = Pattern.compile("(^|[^a-zA-Z])" + Pattern.quote(word) + "([^a-zA-Z]|$)");
        return p.matcher(command.toLowerCase(Locale.ROOT)).find();
    }

    // ─────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────

    /** Splits a command into statements on unquoted {@code ;} and newlines. */
    private static List<String> splitStatements(String command) {
        List<String> statements = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        int n = command.length();
        for (int i = 0; i < n; i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                cur.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                cur.append(c);
            } else if (c == ';' || c == '\n' || c == '\r') {
                if (!cur.isEmpty()) {
                    statements.add(cur.toString().trim());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            statements.add(cur.toString().trim());
        }
        return statements;
    }

    /** Splits a statement into pipeline segments on unquoted {@code |}. */
    private static List<List<String>> splitPipeline(String statement) {
        List<List<String>> segments = new ArrayList<>();
        List<String> current = new ArrayList<>();
        List<String> tokens = tokenizeArgs(statement);
        for (String tok : tokens) {
            if (Strings.CS.equals(tok, "|")) {
                if (!current.isEmpty()) {
                    segments.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(tok);
            }
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    /** Quote-aware whitespace tokenizer (keeps quoted strings as one token). */
    private static List<String> tokenizeArgs(String statement) {
        List<String> tokens = new ArrayList<>();
        StringBuilder tok = new StringBuilder();
        char quote = 0;
        int n = statement.length();
        for (int i = 0; i < n; i++) {
            char c = statement.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    tok.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ' ' || c == '\t') {
                if (!tok.isEmpty()) {
                    tokens.add(tok.toString());
                    tok.setLength(0);
                }
            } else {
                tok.append(c);
            }
        }
        if (!tok.isEmpty()) {
            tokens.add(tok.toString());
        }
        return tokens;
    }

    /** Returns the first command/cmdlet name in a statement, or {@code null}. */
    private static String firstCmdletOf(String statement) {
        return firstCmdletOfTokens(tokenizeArgs(statement));
    }

    private static String firstCmdletOfTokens(List<String> tokens) {
        for (String tok : tokens) {
            if (Strings.CS.startsWith(tok, "-")) {
                continue;
            }
            if (isExpressionToken(tok)) {
                return null;
            }
            return tok;
        }
        return null;
    }

    private static boolean isExpressionToken(String tok) {
        return Strings.CS.startsWith(tok, "'") || Strings.CS.startsWith(tok, "\"")
            || Strings.CS.startsWith(tok, "$");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Small helpers
    // ─────────────────────────────────────────────────────────────────────

    private static String expandAndNormalize(String path, String cwd) {
        String expanded = PathValidation.expandTilde(stripQuotes(path)).replace('\\', '/');
        if (Strings.CS.equals(expanded, "*") ||Strings.CS.startsWith( expanded, "/")) {
            return expanded;
        }
        return Path.of(StringUtils.isBlank(cwd) ? "." : cwd, expanded)
            .normalize()
            .toString()
            .replace('\\', '/');
    }

    private static String stripQuotes(String path) {
        if (StringUtils.length(path) >= 2
                && ((Strings.CS.startsWith(path, "'") && Strings.CS.endsWith(path, "'"))
                    || (Strings.CS.startsWith(path, "\"") && Strings.CS.endsWith(path, "\"")))) {
            return path.substring(1, path.length() - 1);
        }
        return path;
    }
}
