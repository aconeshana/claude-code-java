package com.claudecode.tools.powershell;

import org.apache.commons.lang3.StringUtils;
import java.util.regex.Pattern;

/**
 * Isolated PowerShell security preflight used when the native PowerShell AST parser is unavailable.
 */
final class PowerShellSecurity {

    private static final Pattern CONTROL_CHARS = Pattern.compile(
        "[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]");
    private static final Pattern ENCODED_COMMAND = Pattern.compile(
        "(?i)(?:^|[\\s;&|])[-/\\u2013\\u2014\\u2015](?:e|ec|enc|enco|encod|encode|encoded|encodedcommand)(?:\\s|:|$)");
    private static final Pattern ASSIGNMENT = Pattern.compile(
        "(?i)(?:^|[;\\r\\n])\\s*\\$[a-z_][a-z0-9_:]*\\s*[+\\-*/%?]{0,2}=",
        Pattern.MULTILINE);
    private static final Pattern DANGEROUS_WORDS = Pattern.compile(
        "(?i)(?:^|[^a-z0-9_-])(?:invoke-expression|iex|invoke-item|ii|start-process|saps|start-job|"
            + "invoke-webrequest|iwr|invoke-restmethod|irm|start-bitstransfer|certutil|bitsadmin|"
            + "add-type|new-object|invoke-wmimethod|iwmi|invoke-cimmethod|schtasks|register-scheduledtask|"
            + "new-scheduledtask|new-scheduledtaskaction|set-scheduledtask|invoke-command|icm|"
            + "start-threadjob|register-scheduledjob|register-engineevent|register-objectevent|"
            + "register-wmievent|new-pssession|nsn|enter-pssession|etsn|import-module|ipmo|"
            + "install-module|save-module|update-module|install-script|save-script|set-alias|"
            + "new-alias|set-variable|new-variable)(?:[^a-z0-9_-]|$)");
    /**
     * ForEach-Object -MemberName (including positional binding) invokes a method by name on pipeline
     * objects.
     */
    private static final Pattern FOREACH_MEMBER_INVOCATION = Pattern.compile(
        "(?i)(?:^|[;|])\\s*(?:foreach-object|foreach|%)\\b[^;|\\r\\n]*"
            + "(?:\\s-+m(?:embername)?(?:\\s|:)|\\s+(?:-[a-z][a-z0-9-]*\\s+)*[^-\\s][^;|\\r\\n]*)");
    private static final Pattern ENV_MUTATION = Pattern.compile(
        "(?i)(?:set|new|remove|clear)-(?:item|content)\\s+[^;\\r\\n]*\\benv:");
    private static final Pattern MEMBER_INVOCATION = Pattern.compile(
        "\\$[a-z_][a-z0-9_]*\\s*(?:\\.|\\[[^]]+])|::|\\.[a-z_][a-z0-9_]*\\s*\\(",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STOP_PARSING = Pattern.compile("(?i)(?:^|[\\s;&|])--%(?:\\s|$)");
    private static final Pattern TYPE_LITERAL = Pattern.compile(
        "(?i)\\[(?:[a-z_][a-z0-9_]*\\.)+[a-z_][a-z0-9_]*]");
    private static final Pattern EXPANDABLE_STRING = Pattern.compile(
        "\"(?:[^\"`]|`.)*(?:\\$[a-z_][a-z0-9_:]*|\\$\\{|\\$\\(|\\$\\[)(?:[^\"`]|`.)*\"",
        Pattern.CASE_INSENSITIVE);

    private PowerShellSecurity() {}

    /** Returns a human-readable ASK reason, or {@code null} for a simple lexical command. */
    static String concern(String command) {
        if (StringUtils.isBlank(command)) return null;
        if (CONTROL_CHARS.matcher(command).find()) {
            return "PowerShell command contains non-printable control characters";
        }
        Scan scan = scan(command);
        if (!scan.valid) {
            return "Could not parse PowerShell command for security analysis";
        }
        if (scan.dynamicInvocation) {
            return "PowerShell command name is dynamic and cannot be statically validated";
        }
        if (scan.scriptBlock || scan.subExpression || scan.splatting) {
            return "PowerShell command contains a script block, subexpression, or splatting construct";
        }
        if (STOP_PARSING.matcher(command).find()) {
            return "PowerShell command uses the stop-parsing token (--%)";
        }
        if (EXPANDABLE_STRING.matcher(command).find()) {
            return "PowerShell command contains an expandable string with embedded expressions";
        }
        if (TYPE_LITERAL.matcher(command).find()) {
            return "PowerShell command uses a .NET type literal that cannot be validated safely";
        }
        if (ENCODED_COMMAND.matcher(command).find()) {
            return "PowerShell command uses an encoded command parameter";
        }
        if (ASSIGNMENT.matcher(command).find()) {
            return "PowerShell command assigns runtime state that cannot be validated statically";
        }
        if (ENV_MUTATION.matcher(command).find()) {
            return "PowerShell command modifies environment variables";
        }
        if (MEMBER_INVOCATION.matcher(command).find()) {
            return "PowerShell member or index invocation cannot be validated statically";
        }
        if (DANGEROUS_WORDS.matcher(command).find()) {
            return "PowerShell command can execute code, download content, or change runtime state";
        }
        if (FOREACH_MEMBER_INVOCATION.matcher(command).find()) {
            return "ForEach-Object invokes a method by name and cannot be validated safely";
        }
        return null;
    }

    private static Scan scan(String command) {
        char quote = 0;
        boolean escaped = false;
        int braces = 0;
        int parens = 0;
        int brackets = 0;
        boolean dynamic = false;
        boolean scriptBlock = false;
        boolean subExpression = false;
        boolean splatting = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    // PowerShell escapes a single quote in a single-quoted
                    // string by doubling it.
                    if (i + 1 < command.length() && command.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        quote = 0;
                    }
                }
                continue;
            }
            if (quote == '"') {
                if (escaped) {
                    escaped = false;
                } else if (c == '`') {
                    escaped = true;
                } else if (c == '"') {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '`') {
                // Outside strings this is either an escape or line
                // continuation; a dangling escape is not safe to classify.
                if (i + 1 == command.length()) return new Scan(false, false, false, false, false);
                i++;
                continue;
            }
            if (c == '&') {
                dynamic = true;
            }
            if (c == '{') {
                braces++;
                scriptBlock = true;
            } else if (c == '}') {
                if (braces == 0) return new Scan(false, false, false, false, false);
                braces--;
            }
            if (c == '(') {
                parens++;
                if (i > 0 && command.charAt(i - 1) == '$') subExpression = true;
                if (i > 0 && command.charAt(i - 1) == '@') splatting = true;
            } else if (c == ')') {
                if (parens == 0) return new Scan(false, false, false, false, false);
                parens--;
            }
            if (c == '[') {
                brackets++;
                if (i > 0 && command.charAt(i - 1) == '@') splatting = true;
            } else if (c == ']') {
                if (brackets == 0) return new Scan(false, false, false, false, false);
                brackets--;
            }
            if (c == '@' && i + 1 < command.length()
                    && (command.charAt(i + 1) == '(' || command.charAt(i + 1) == '{')) {
                splatting = true;
            }
        }
        boolean valid = quote == 0 && !escaped && braces == 0 && parens == 0 && brackets == 0
            && !command.matches("(?s).*\\|\\s*$")
            && !command.matches("(?s).*(?:&&|\\|\\|)\\s*$");
        return new Scan(valid,
            dynamic, scriptBlock, subExpression, splatting);
    }

    private record Scan(boolean valid, boolean dynamicInvocation, boolean scriptBlock,
                        boolean subExpression, boolean splatting) {}
}
