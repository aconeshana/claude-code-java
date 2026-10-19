package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.claudecode.core.platform.Platform;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DetachedSessionTest {

    /**
     * The property that keeps an interactive shell from taking the terminal: a session leader is
     * also the leader of its own process group, and a session started by {@code setsid()} has no
     * controlling terminal to open.
     */
    @Test
    void commandRunsAsTheLeaderOfANewSessionWithoutATerminal() throws Exception {
        assumeTrue(Platform.IS_DARWIN || Platform.IS_LINUX);

        String output = run(DetachedSession.apply(new ProcessBuilder("/bin/sh", "-c",
            "echo $$ $(ps -o pgid= -p $$); if : </dev/tty; then echo tty; else echo no-tty; fi"
                + " 2>/dev/null")));

        String[] fields = output.strip().split("\\s+");
        assertEquals(fields[0], fields[1], "the shell leads its own process group");
        assertEquals("no-tty", fields[2]);
    }

    @Test
    void withoutTheLauncherTheCommandSharesThisProcessGroup() throws Exception {
        assumeTrue(Platform.IS_DARWIN || Platform.IS_LINUX);

        String output = run(new ProcessBuilder("/bin/sh", "-c", "echo $$ $(ps -o pgid= -p $$)"));

        String[] fields = output.strip().split("\\s+");
        assertNotEquals(fields[0], fields[1]);
    }

    @Test
    void perlStartupVariablesReachTheCommandUnchanged() throws Exception {
        assumeTrue(DetachedSession.launcher(Platform.CURRENT, path -> path.toFile().canExecute())
            instanceof DetachedSession.Launcher.Perl);

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c",
            "echo \"$PERL5OPT|$PERL5LIB|${" + DetachedSession.PARKED_PREFIX + "PERL5OPT-unset}\"");
        // Fatal for the launcher if Perl saw it at startup.
        builder.environment().put("PERL5OPT", "-MNo::Such::Module");
        builder.environment().put("PERL5LIB", "/nonexistent/lib");

        String output = run(DetachedSession.apply(builder, new DetachedSession.Launcher.Perl("/usr/bin/perl")));

        assertEquals("-MNo::Such::Module|/nonexistent/lib|unset", output.strip());
    }

    @Test
    void reportsAMissingCommandAsAFailedExec() throws Exception {
        assumeTrue(Platform.IS_DARWIN || Platform.IS_LINUX);

        Process child = DetachedSession.apply(new ProcessBuilder("/nonexistent/command"))
            .redirectErrorStream(true).start();

        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertNotEquals(0, child.exitValue());
    }

    @Test
    void choosesSetsidOnLinuxAndThePerlLauncherOnMacOs() {
        Set<Path> all = Set.of(Path.of("/usr/bin/setsid"), Path.of("/usr/bin/perl"));

        assertEquals(new DetachedSession.Launcher.Setsid("/usr/bin/setsid"),
            DetachedSession.launcher(Platform.LINUX, all::contains));
        assertEquals(new DetachedSession.Launcher.Perl("/usr/bin/perl"),
            DetachedSession.launcher(Platform.LINUX, Set.of(Path.of("/usr/bin/perl"))::contains));
        assertEquals(new DetachedSession.Launcher.Perl("/usr/bin/perl"),
            DetachedSession.launcher(Platform.DARWIN, all::contains));
        assertEquals(new DetachedSession.Launcher.None(),
            DetachedSession.launcher(Platform.DARWIN, Set.of()::contains));
        assertEquals(new DetachedSession.Launcher.None(),
            DetachedSession.launcher(Platform.WIN32, all::contains));
    }

    @Test
    void leavesTheCommandAloneWithoutALauncher() {
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", "true");

        assertSame(builder, DetachedSession.apply(builder, new DetachedSession.Launcher.None()));
        assertEquals(List.of("bash", "-c", "true"), builder.command());
    }

    @Test
    void prefixesTheCommandWithTheLauncher() {
        ProcessBuilder builder = new ProcessBuilder("bash", "-c", "true");

        DetachedSession.apply(builder, new DetachedSession.Launcher.Setsid("/usr/bin/setsid"));

        assertEquals(List.of("/usr/bin/setsid", "bash", "-c", "true"), builder.command());
    }

    private static String run(ProcessBuilder builder) throws IOException, InterruptedException {
        Process child = builder.redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue(), output);
        return output;
    }
}
