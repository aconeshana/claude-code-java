package com.claudecode.tools.bash;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Exit-code interpretation shared by the shell tools.
 */
public final class CommandSemantics {

    public record Interpretation(boolean isError, String message) {}

    private CommandSemantics() {}

    public static Interpretation bash(String command, int exitCode, String stdout, String stderr) {
        return interpret(baseCommandFromBash(command), exitCode, false);
    }

    public static Interpretation powerShell(String command, int exitCode, String stdout, String stderr) {
        return interpret(baseCommandFromPowerShell(command), exitCode, true);
    }

    private static Interpretation interpret(String base, int exitCode, boolean powerShell) {
        if (powerShell) {
            return switch (base) {
                case "grep", "rg", "findstr" -> search(exitCode);
                case "robocopy" -> robocopy(exitCode);
                default -> defaultSemantic(exitCode);
            };
        }
        return switch (base) {
            case "grep", "rg" -> search(exitCode);
            case "find" -> new Interpretation(exitCode >= 2,
                exitCode == 1 ? "Some directories were inaccessible" : null);
            case "diff" -> new Interpretation(exitCode >= 2,
                exitCode == 1 ? "Files differ" : null);
            case "test", "[" -> new Interpretation(exitCode >= 2,
                exitCode == 1 ? "Condition is false" : null);
            default -> defaultSemantic(exitCode);
        };
    }

    private static Interpretation defaultSemantic(int exitCode) {
        return new Interpretation(exitCode != 0,
            exitCode != 0 ? "Command failed with exit code " + exitCode : null);
    }

    private static Interpretation search(int exitCode) {
        return new Interpretation(exitCode >= 2,
            exitCode == 1 ? "No matches found" : null);
    }

    private static Interpretation robocopy(int exitCode) {
        String message = exitCode == 0 ? "No files copied (already in sync)"
            : exitCode < 8 ? ((exitCode & 1) != 0
                ? "Files copied successfully" : "Robocopy completed (no errors)") : null;
        return new Interpretation(exitCode >= 8, message);
    }

    private static String baseCommandFromBash(String command) {
        List<String> words = new ArrayList<>();
        if (command != null) {
            for (ShellQuoteParse.Token token : ShellQuoteParse.parse(command)) {
                if (token instanceof ShellQuoteParse.Op op) {
                    if (isSegmentBoundary(op.op())) {
                        words.clear();
                    }
                    continue;
                }
                String value = token.asString();
                if (StringUtils.isNotBlank(value)) words.add(value);
            }
        }
        return words.isEmpty() ? "" : words.getFirst().toLowerCase(Locale.ROOT);
    }

    private static String baseCommandFromPowerShell(String command) {
        String last = lastPowerShellSegment(command == null ? "" : command);
        String stripped = last.trim().replaceFirst("^[&.]\\s+", "");
        String token = stripped.split("\\s+", 2)[0];
        if (token.length() >= 2
                && ((Strings.CS.startsWith(token, "'") &&Strings.CS.endsWith( token, "'"))
                    || (Strings.CS.startsWith(token, "\"") &&Strings.CS.endsWith( token, "\"")))) {
            token = token.substring(1, token.length() - 1);
        }
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        if (slash >= 0) token = token.substring(slash + 1);
        if (Strings.CS.endsWith(token.toLowerCase(Locale.ROOT), ".exe")) {
            token = token.substring(0, token.length() - 4);
        }
        return token.toLowerCase(Locale.ROOT);
    }

    private static String lastPowerShellSegment(String command) {
        boolean single = false;
        boolean doubleQuote = false;
        boolean backtick = false;
        String last = command;
        int segmentStart = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (backtick) {
                backtick = false;
                continue;
            }
            if (c == '`' && !single) {
                backtick = true;
                continue;
            }
            if (c == '\'' && !doubleQuote) {
                single = !single;
            } else if (c == '"' && !single) {
                doubleQuote = !doubleQuote;
            } else if (!single && !doubleQuote && (c == ';' || c == '|')) {
                last = command.substring(segmentStart, i);
                segmentStart = i + 1;
                if (c == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                    segmentStart++;
                    i++;
                }
            }
        }
        return StringUtils.isBlank(command.substring(segmentStart)) ? last : command.substring(segmentStart);
    }

    private static boolean isSegmentBoundary(String op) {
        return switch (op) {
            case "|", "|&", ";", "&&", "||", "&" -> true;
            default -> false;
        };
    }

}
