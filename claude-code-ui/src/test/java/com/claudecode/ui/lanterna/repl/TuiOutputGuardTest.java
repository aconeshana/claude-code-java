package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.platform.Platform;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TuiOutputGuardTest {

    @Test
    void usesProcessScopedDiagnosticPath() {
        long pid = ProcessHandle.current().pid();

        assertTrue(Strings.CS.contains(
            TuiOutputGuard.diagnosticPathForPid(pid).getFileName().toString(),
            "-" + pid + ".log"));
    }

    @Test
    void capturesProcessOutputAndJdkLoggerThenRestoresProcessStreams() throws Exception {
        var diagnosticPath = TuiOutputGuard.diagnosticPath();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        var visibleOutBytes = new ByteArrayOutputStream();
        var visibleErrBytes = new ByteArrayOutputStream();
        PrintStream visibleOut = new PrintStream(visibleOutBytes, true, StandardCharsets.UTF_8);
        PrintStream visibleErr = new PrintStream(visibleErrBytes, true, StandardCharsets.UTF_8);
        System.setOut(visibleOut);
        System.setErr(visibleErr);
        try {
            Files.deleteIfExists(diagnosticPath);
            try (TuiOutputGuard ignored = TuiOutputGuard.install()) {
                System.out.println("stray stdout");
                System.err.println("stray stderr");
                System.getLogger("guard-test").log(System.Logger.Level.WARNING,
                    "third-party warning");
                if (Platform.IS_DARWIN || Platform.IS_LINUX) {
                    Process nativeWriter = new ProcessBuilder("/bin/sh", "-c",
                        "printf 'native fd stderr' >&2")
                        .inheritIO()
                        .start();
                    assertTrue(nativeWriter.waitFor(5, TimeUnit.SECONDS));
                    assertEquals(0, nativeWriter.exitValue());
                }
                TuiOutputGuard.writeToTerminal("intentional terminal control");
            }

            assertSame(System.out, visibleOut);
            assertSame(System.err, visibleErr);
            assertEquals("intentional terminal control",
                visibleOutBytes.toString(StandardCharsets.UTF_8));
            assertEquals("", visibleErrBytes.toString(StandardCharsets.UTF_8));
            String captured = Files.readString(diagnosticPath);
            assertTrue(Strings.CS.contains(captured, "stray stdout"));
            assertTrue(Strings.CS.contains(captured, "stray stderr"));
            assertTrue(Strings.CS.contains(captured, "third-party warning"));
            if (Platform.IS_DARWIN || Platform.IS_LINUX) {
                assertTrue(Strings.CS.contains(captured, "native fd stderr"));
            }
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    @Test
    void fatalGuiFailureRecreatesAnUnlinkedDiagnosticFile() throws Exception {
        var diagnosticPath = TuiOutputGuard.diagnosticPath();
        Files.deleteIfExists(diagnosticPath);

        TuiOutputGuard.recordFatalThreadFailure(
            Thread.currentThread(), new AssertionError("ctrl-o-render-failure"));

        String captured = Files.readString(diagnosticPath);
        assertTrue(Strings.CS.contains(captured, "fatal thread failure"));
        assertTrue(Strings.CS.contains(captured, "ctrl-o-render-failure"));
        assertTrue(Strings.CS.contains(captured, Thread.currentThread().getName()));
    }

    /**
     * The ghost-text regression. While the renderer holds a private channel, fd 1 belongs to the
     * diagnostic file, so no other writer in the process can land bytes between the {@code ESC}
     * and the remainder of a sequence the renderer is flushing.
     */
    @Test
    void divertsNativeStdoutWhileTheRendererKeepsItsPrivateChannel() throws Exception {
        assumeTrue(Platform.IS_DARWIN || Platform.IS_LINUX);
        var diagnosticPath = TuiOutputGuard.diagnosticPath();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        var channelBytes = new ByteArrayOutputStream();
        PrintStream channel = new PrintStream(channelBytes, true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            Files.deleteIfExists(diagnosticPath);
            try (TuiOutputGuard ignored = TuiOutputGuard.install(channel)) {
                Process nativeWriter = new ProcessBuilder("/bin/sh", "-c",
                    "printf 'native fd stdout'")
                    .inheritIO()
                    .start();
                assertTrue(nativeWriter.waitFor(5, TimeUnit.SECONDS));
                assertEquals(0, nativeWriter.exitValue());
                TuiOutputGuard.writeToTerminal("\u001B[49;4H");
            }

            String captured = Files.readString(diagnosticPath);
            assertTrue(Strings.CS.contains(captured, "native fd stdout"));
            // The renderer's sequence reached the channel whole, and nothing else did.
            assertEquals("\u001B[49;4H", channelBytes.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    /**
     * A piped or redirected run has no terminal to protect and must keep writing where the caller
     * pointed it, so no private channel is claimed and fd 1 is left alone.
     */
    @Test
    void claimsNoPrivateChannelWhenStdoutIsNotATerminal() throws Exception {
        assumeTrue(!stdoutIsTerminal());

        assertNull(TuiOutputGuard.openTerminalChannel());
    }

    private static boolean stdoutIsTerminal() throws Exception {
        if (!Platform.IS_DARWIN && !Platform.IS_LINUX) return false;
        Process probe = new ProcessBuilder("/bin/sh", "-c", "test -t 1").inheritIO().start();
        assertTrue(probe.waitFor(5, TimeUnit.SECONDS));
        return probe.exitValue() == 0;
    }
}
