package com.claudecode.core.process;

import com.claudecode.core.platform.Platform;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Starts a shell command in a session of its own, so it has no controlling terminal.
 *
 * <ul>
 *   <li>The shell provider's {@code detached: true}, which the shared exec path hands to
 *       {@code child_process.spawn} for every Bash tool command — foreground, background,
 *       {@code !} bash mode and skill {@code !}-expansion alike (2.1.236 bundle).</li>
 * </ul>
 *
 * <p>A child left in the host's session can take the terminal from it. An interactive shell
 * ({@code zsh -i}) enables job control and makes itself the terminal's foreground process group;
 * the host's input reader is then a background reader and stops on {@code SIGTTIN}, the login
 * shell reclaims the terminal, loses it again, exits on a read error, and the kernel hangs up the
 * whole session — taking the host and the terminal window with it.
 *
 * <p>{@link ProcessBuilder} cannot call {@code setsid()} between fork and exec, so the command is
 * prefixed with a launcher that does and then execs it in place: the started {@link Process} is
 * still the shell, and its pid, streams and exit status are unchanged. Linux ships
 * {@code setsid(1)}; macOS has no such command, so its system Perl stands in. Without either, or
 * on Windows, the command runs as before.
 */
public final class DetachedSession {

    private static final List<String> SETSID_COMMANDS = List.of("/usr/bin/setsid", "/bin/setsid");
    private static final String SYSTEM_PERL = "/usr/bin/perl";

    /**
     * Variables Perl reads before running any code. A user's {@code PERL5LIB} may point at modules
     * built for another Perl, which the system Perl would fail to load; they are parked under a
     * prefixed name for the launcher and restored before the command is exec'd.
     */
    private static final List<String> PERL_STARTUP_VARIABLES =
        List.of("PERL5LIB", "PERLLIB", "PERL5OPT");
    static final String PARKED_PREFIX = "CLAUDE_CODE_DETACHED_";

    private static final String PERL_LAUNCHER = """
        for my $k (qw(%s)) { my $p = "%s$k"; $ENV{$k} = delete $ENV{$p} if exists $ENV{$p} }
        require POSIX; POSIX::setsid();
        exec { $ARGV[0] } @ARGV or do { print STDERR "$ARGV[0]: $!", $/; exit 127 };
        """.formatted(String.join(" ", PERL_STARTUP_VARIABLES), PARKED_PREFIX);

    sealed interface Launcher {
        record Setsid(String path) implements Launcher {}
        record Perl(String path) implements Launcher {}
        record None() implements Launcher {}
    }

    private static final Launcher LAUNCHER = launcher(Platform.CURRENT, Files::isExecutable);

    private DetachedSession() {}

    /**
     * Prefixes {@code builder}'s command with the session launcher and returns the builder for
     * chaining. Call it last, once the environment is final: the Perl launcher rewrites the
     * variables Perl itself would read.
     */
    public static ProcessBuilder apply(ProcessBuilder builder) {
        return apply(builder, LAUNCHER);
    }

    static ProcessBuilder apply(ProcessBuilder builder, Launcher launcher) {
        List<String> command = new ArrayList<>();
        switch (launcher) {
            case Launcher.Setsid(String path) -> command.add(path);
            case Launcher.Perl(String path) -> {
                command.addAll(List.of(path, "-e", PERL_LAUNCHER, "--"));
                parkPerlStartupVariables(builder.environment());
            }
            case Launcher.None() -> {
                return builder;
            }
        }
        command.addAll(builder.command());
        return builder.command(command);
    }

    static Launcher launcher(Platform platform, Predicate<Path> executable) {
        if (platform != Platform.DARWIN && platform != Platform.LINUX) return new Launcher.None();
        if (platform == Platform.LINUX) {
            for (String candidate : SETSID_COMMANDS) {
                if (executable.test(Path.of(candidate))) return new Launcher.Setsid(candidate);
            }
        }
        return executable.test(Path.of(SYSTEM_PERL))
            ? new Launcher.Perl(SYSTEM_PERL) : new Launcher.None();
    }

    private static void parkPerlStartupVariables(Map<String, String> environment) {
        for (String name : PERL_STARTUP_VARIABLES) {
            String value = environment.remove(name);
            if (value != null) environment.put(PARKED_PREFIX + name, value);
        }
    }
}
