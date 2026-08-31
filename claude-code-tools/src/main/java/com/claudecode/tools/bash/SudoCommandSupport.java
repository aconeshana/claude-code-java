package com.claudecode.tools.bash;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/** Strict parser/rewriter for the deliberately narrow phase-one sudo flow. */
final class SudoCommandSupport {

    record Prepared(String command, boolean requiresPassword) {}

    private SudoCommandSupport() {}

    static boolean isDirectPasswordCommand(String command) {
        if (StringUtils.isBlank(command) || containsUnsafeSyntax(command)) return false;
        List<ShellQuoteParse.Token> tokens;
        try {
            tokens = ShellQuoteParse.parse(command);
        } catch (RuntimeException _) {
            return false;
        }
        if (tokens.isEmpty() || tokens.stream().anyMatch(token ->
                token instanceof ShellQuoteParse.Op
                    || token instanceof ShellQuoteParse.Comment
                    || token instanceof ShellQuoteParse.Glob)) {
            return false;
        }
        String executable = tokens.getFirst().asString();
        return isRecognizedSudo(executable) && requiresPassword(tokens);
    }

    /**
     * Detects an interactive sudo invocation even when shell composition makes
     * it ineligible for phase-one credential injection. Callers use this to
     * fail closed instead of launching a process that will wait forever on the
     * terminal-less Bash stdin channel.
     */
    static boolean containsPasswordRequiringSudo(String command) {
        if (StringUtils.isBlank(command)) return false;
        List<ShellQuoteParse.Token> tokens;
        try {
            tokens = ShellQuoteParse.parse(command);
        } catch (RuntimeException _) {
            return false;
        }
        List<ShellQuoteParse.Token> segment = new ArrayList<>();
        for (ShellQuoteParse.Token token : tokens) {
            if (token instanceof ShellQuoteParse.Op) {
                if (requiresPassword(segment)) return true;
                segment.clear();
            } else if (!(token instanceof ShellQuoteParse.Comment)) {
                segment.add(token);
            }
        }
        return requiresPassword(segment);
    }

    /**
     * Prepares one simple, direct sudo command for stdin password delivery.
     * Shell composition is rejected so no wrapper or neighboring command can
     * impersonate the trusted sudo prompt or inherit credential stdin.
     */
    static Optional<Prepared> prepare(String command, Path trustedSudo) {
        if (StringUtils.isBlank(command) || trustedSudo == null
                || !trustedSudo.isAbsolute() || !isDirectPasswordCommand(command)) {
            return Optional.empty();
        }
        List<ShellQuoteParse.Token> tokens;
        try {
            tokens = ShellQuoteParse.parse(command);
        } catch (RuntimeException _) {
            return Optional.empty();
        }
        if (tokens.isEmpty() || tokens.stream().anyMatch(token ->
                token instanceof ShellQuoteParse.Op
                    || token instanceof ShellQuoteParse.Comment
                    || token instanceof ShellQuoteParse.Glob)) {
            return Optional.empty();
        }
        String executable = tokens.getFirst().asString();
        String trusted = trustedSudo.normalize().toString();
        try {
            if (!Strings.CS.equals("sudo", executable)
                    && !Strings.CS.equals(trusted, Path.of(executable).normalize().toString())) {
                return Optional.empty();
            }
        } catch (RuntimeException _) {
            return Optional.empty();
        }
        int executableEnd = firstWordEnd(command);
        if (executableEnd < 0) return Optional.empty();
        String remainder = command.substring(executableEnd);
        boolean hasStdin = hasToken(tokens, "-S") || hasToken(tokens, "--stdin");
        boolean hasPrompt = hasToken(tokens, "-p") || hasToken(tokens, "--prompt");
        StringBuilder prepared = new StringBuilder(trusted);
        if (!hasStdin) prepared.append(" -S");
        if (!hasPrompt) prepared.append(" -p ''");
        prepared.append(remainder);
        return Optional.of(new Prepared(prepared.toString(), true));
    }

    private static boolean containsUnsafeSyntax(String command) {
        return command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0
            || command.indexOf('`') >= 0 || Strings.CS.contains(command, "$(");
    }

    private static boolean hasToken(List<ShellQuoteParse.Token> tokens, String expected) {
        return tokens.stream().skip(1)
            .map(ShellQuoteParse.Token::asString)
            .anyMatch(expected::equals);
    }

    private static boolean requiresPassword(List<ShellQuoteParse.Token> tokens) {
        if (tokens.isEmpty() || !isRecognizedSudo(tokens.getFirst().asString())) return false;
        if (isCredentialInvalidationOnly(tokens)) return false;
        return !hasToken(tokens, "-n") && !hasToken(tokens, "--non-interactive")
            && !hasToken(tokens, "-A") && !hasToken(tokens, "--askpass");
    }

    private static boolean isCredentialInvalidationOnly(List<ShellQuoteParse.Token> tokens) {
        return tokens.size() == 2 && (hasToken(tokens, "-k") || hasToken(tokens, "-K")
            || hasToken(tokens, "--reset-timestamp") || hasToken(tokens, "--remove-timestamp"));
    }

    private static boolean isRecognizedSudo(String executable) {
        return Strings.CS.equals("sudo", executable)
            || Strings.CS.equals("/usr/bin/sudo", executable)
            || Strings.CS.equals("/bin/sudo", executable);
    }

    private static int firstWordEnd(String command) {
        int index = 0;
        while (index < command.length() && Character.isWhitespace(command.charAt(index))) index++;
        while (index < command.length() && !Character.isWhitespace(command.charAt(index))) index++;
        return index;
    }
}
