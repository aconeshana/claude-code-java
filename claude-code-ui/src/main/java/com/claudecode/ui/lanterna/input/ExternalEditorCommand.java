package com.claudecode.ui.lanterna.input;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Parsed, shell-free external-editor command.
 */
public record ExternalEditorCommand(List<String> executableAndArguments) {

    public ExternalEditorCommand {
        if (executableAndArguments == null || executableAndArguments.isEmpty()
                || executableAndArguments.stream().anyMatch(StringUtils::isEmpty)) {
            throw new IllegalArgumentException("Editor command must contain a non-empty executable");
        }
        executableAndArguments = List.copyOf(executableAndArguments);
    }

    public static ExternalEditorCommand resolve(String configured) {
        List<String> argv = parseArgv(configured);
        String executable = basename(argv.getFirst()).toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(executable, "code") && !hasWaitArgument(argv)) {
            argv = withAppendedArgument(argv, "-w");
        } else if (Strings.CS.equals(executable, "subl") && !hasWaitArgument(argv)) {
            argv = withAppendedArgument(argv, "--wait");
        }
        return new ExternalEditorCommand(argv);
    }

    public List<String> argvFor(Path file) {
        if (file == null) throw new IllegalArgumentException("Editor file must not be null");
        List<String> argv = new ArrayList<>(executableAndArguments);
        argv.add(file.toString());
        return List.copyOf(argv);
    }

    String executable() {
        return executableAndArguments.getFirst();
    }

    private static List<String> parseArgv(String configured) {
        if (StringUtils.isBlank(configured)) {
            throw new IllegalArgumentException("Editor command is empty");
        }
        List<String> argv = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean tokenStarted = false;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < configured.length(); i++) {
            char c = configured.charAt(i);
            if (c == '\0') throw new IllegalArgumentException("Editor command contains NUL");
            if (escaped) {
                token.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\' && quote != '\'') {
                tokenStarted = true;
                if (i + 1 >= configured.length()) {
                    escaped = true;
                    continue;
                }
                char next = configured.charAt(i + 1);
                if (Character.isWhitespace(next) || next == '"'
                        || (quote == 0 && next == '\'')) {
                    escaped = true;
                } else {
                    token.append(c);
                }
                continue;
            }
            if (quote != 0) {
                if (c == quote) quote = 0;
                else token.append(c);
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                tokenStarted = true;
            } else if (Character.isWhitespace(c)) {
                if (tokenStarted) {
                    argv.add(token.toString());
                    token.setLength(0);
                    tokenStarted = false;
                }
            } else {
                token.append(c);
                tokenStarted = true;
            }
        }
        if (escaped || quote != 0) {
            throw new IllegalArgumentException("Unterminated escape or quote in editor command");
        }
        if (tokenStarted) argv.add(token.toString());
        if (argv.isEmpty() || StringUtils.isBlank(argv.getFirst())) {
            throw new IllegalArgumentException("Editor command has no executable");
        }
        return argv;
    }

    private static List<String> withAppendedArgument(List<String> argv, String argument) {
        List<String> result = new ArrayList<>(argv);
        result.add(argument);
        return result;
    }

    private static boolean hasWaitArgument(List<String> argv) {
        return argv.stream().skip(1).anyMatch(argument ->Strings.CS.equals(
            argument, "-w")
                ||Strings.CS.equals( argument, "--wait")
                ||Strings.CS.equals( argument, "--wait-for-close"));
    }

    private static String basename(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return slash >= 0 ? executable.substring(slash + 1) : executable;
    }
}
