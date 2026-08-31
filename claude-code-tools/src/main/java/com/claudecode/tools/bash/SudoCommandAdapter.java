package com.claudecode.tools.bash;

import com.claudecode.core.platform.Platform;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.Strings;

/**
 * Shared phase-one sudo preparation for model Bash and local {@code !} Bash mode.
 */
public final class SudoCommandAdapter {

    private SudoCommandAdapter() {}

    public static Result prepare(String command, SudoPasswordInteraction interaction) {
        return prepare(command, trustedSudoExecutable().orElse(null), interaction);
    }

    static Result prepare(
            String command, Path trustedSudo, SudoPasswordInteraction interaction) {
        boolean directPasswordSudo = SudoCommandSupport.isDirectPasswordCommand(command);
        if (!directPasswordSudo && SudoCommandSupport.containsPasswordRequiringSudo(command)) {
            return new Result.Rejected("Error: sudo password input requires one direct sudo command; "
                + "compound shell commands are unavailable in phase one");
        }
        if (!directPasswordSudo) return new Result.Passthrough(command);
        if (trustedSudo == null) {
            return new Result.Rejected(
                "Error: sudo password input is unavailable because no trusted system sudo was found");
        }
        SudoCommandSupport.Prepared prepared = SudoCommandSupport
            .prepare(command, trustedSudo).orElse(null);
        if (prepared == null) {
            return new Result.Rejected(
                "Error: sudo password input is unavailable for this sudo executable");
        }

        SudoPasswordInteraction.Result promptResult;
        try {
            SudoPasswordInteraction safeInteraction = interaction != null
                ? interaction : SudoPasswordInteraction.UNAVAILABLE;
            promptResult = safeInteraction.request(new SudoPasswordInteraction.Request(
                firstWord(prepared.command()), command));
        } catch (RuntimeException _) {
            return new Result.Rejected("Error: sudo password input is unavailable");
        }
        if (promptResult instanceof SudoPasswordInteraction.Result.Cancelled) {
            return new Result.Rejected("Error: sudo password input was cancelled");
        }
        if (!(promptResult instanceof SudoPasswordInteraction.Result.Provided provided)) {
            return new Result.Rejected(
                "Error: sudo password input is unavailable in this session");
        }
        return new Result.Prepared(prepared.command(), provided);
    }

    public sealed interface Result permits Result.Passthrough, Result.Prepared, Result.Rejected {

        record Passthrough(String command) implements Result {}

        record Rejected(String message) implements Result {}

        final class Prepared implements Result, AutoCloseable {
            private final String command;
            private final SudoPasswordInteraction.Result.Provided password;

            private Prepared(
                    String command, SudoPasswordInteraction.Result.Provided password) {
                this.command = command;
                this.password = password;
            }

            public String command() { return command; }

            public void writePasswordTo(OutputStream destination) throws IOException {
                password.writeTo(destination);
            }

            @Override public void close() { password.close(); }

            @Override public String toString() { return "Prepared[redacted]"; }
        }
    }

    /** Finds a root-owned, non-writable system sudo without consulting PATH. */
    private static Optional<Path> trustedSudoExecutable() {
        if (Platform.IS_WINDOWS) return Optional.empty();
        for (Path candidate : List.of(Path.of("/usr/bin/sudo"), Path.of("/bin/sudo"))) {
            try {
                Path real = candidate.toRealPath();
                if (!Files.isRegularFile(real) || !Files.isExecutable(real)) continue;
                PosixFileAttributes attributes = Files.readAttributes(
                    real, PosixFileAttributes.class);
                var permissions = attributes.permissions();
                if (!Strings.CS.equals("root", attributes.owner().getName())
                        || permissions.contains(PosixFilePermission.GROUP_WRITE)
                        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                    continue;
                }
                return Optional.of(real);
            } catch (IOException | UnsupportedOperationException | SecurityException _) {
                // Try the next fixed system path; never fall back to PATH.
            }
        }
        return Optional.empty();
    }

    private static String firstWord(String command) {
        int end = 0;
        while (end < command.length() && !Character.isWhitespace(command.charAt(end))) end++;
        return command.substring(0, end);
    }
}
