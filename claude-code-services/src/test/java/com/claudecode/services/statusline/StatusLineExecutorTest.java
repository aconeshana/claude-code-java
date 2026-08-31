package com.claudecode.services.statusline;

import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class StatusLineExecutorTest {

    @TempDir Path tmp;

    private StatusLineExecutor executor() {
        return new StatusLineExecutor(tmp.toString());
    }

    @Test
    void echoesStaticOutput() {
        Optional<String> out = executor().execute(
            new StatusLineConfig("echo 'hello world'", 0), "{}");
        assertEquals("hello world", out.orElse(null));
    }

    @Test
    void receivesJsonOnStdin() {
        // The command reads the piped JSON and echoes a field back.
        Optional<String> out = executor().execute(
            new StatusLineConfig("cat | tr -d '\\n'", 0),
            "{\"model\":\"opus\"}");
        assertEquals("{\"model\":\"opus\"}", out.orElse(null));
    }

    @Test
    void nonZeroExitYieldsEmpty() {
        assertTrue(executor().execute(
            new StatusLineConfig("echo out; exit 3", 0), "{}").isEmpty());
    }

    @Test
    void emptyOutputYieldsEmpty() {
        assertTrue(executor().execute(
            new StatusLineConfig("true", 0), "{}").isEmpty());
    }

    @Test
    void multiLineOutputIsTrimmedPerLineBlanksDropped() {
        Optional<String> out = executor().execute(
            new StatusLineConfig("printf '  line1  \\n\\n  line2  \\n'", 0), "{}");
        assertEquals("line1\nline2", out.orElse(null));
    }

    @Test
    void timeoutYieldsEmpty() {
        // Sleeps well past the 5s budget → destroyed → empty.
        long start = System.currentTimeMillis();
        Optional<String> out = executor().execute(
            new StatusLineConfig("sleep 30; echo late", 0), "{}");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(out.isEmpty());
        assertTrue(elapsed < 15_000, "should abort near the 5s timeout, took " + elapsed + "ms");
    }

    @Test
    void interruptAbortsRunningCommandImmediately() throws Exception {
        Path started = tmp.resolve("statusline-started");
        var result = new AtomicReference<Optional<String>>();
        Thread execution = Thread.ofVirtual().start(() -> result.set(executor().execute(
            new StatusLineConfig("touch statusline-started; sleep 30; echo late", 0), "{}")));

        for (int i = 0; i < 300 && !Files.exists(started); i++) Thread.sleep(10);
        assertTrue(Files.exists(started), "command should have started before cancellation");

        execution.interrupt();
        execution.join(3_000);

        assertFalse(execution.isAlive(), "interrupted status line must not wait for its 5s timeout");
        assertTrue(result.get().isEmpty());
    }

    @Test
    void normalizeOutput_dropsBlanksAndTrims() {
        assertEquals(Optional.of("a\nb"),
            StatusLineExecutor.normalizeOutput("\n  a  \n \n b \n"));
        assertEquals(Optional.empty(), StatusLineExecutor.normalizeOutput("   \n  \n"));
    }

    @Test
    void normalizeOutput_preservesLeadingEscByte() {
        // String.trim strips ESC (0x1B <= 0x20), which would eat the leading
        // SGR of a colored status line. strip() must keep it.
        String colored = "[36mHI[0m";
        Optional<String> out = StatusLineExecutor.normalizeOutput(colored);
        assertTrue(out.isPresent());
        assertEquals('', out.get().charAt(0), "leading ESC must survive normalization");
    }

    @Test
    void receivesEscColoredOutputThroughFullExecute() {
        // End-to-end through the real subprocess: a printf that emits a leading
        // SGR reset+color must arrive with its ESC intact.
        Optional<String> out = executor().execute(
            new StatusLineConfig("printf '\\033[0m\\033[36mLINE\\033[0m'", 0), "{}");
        assertTrue(out.isPresent());
        assertEquals('', out.get().charAt(0));
        assertTrue(Strings.CS.contains(out.get(), "LINE"));
    }

    @Test
    void executionUsesTheResolvedPlatformShellCommand() {
        AtomicReference<String> resolved = new AtomicReference<>();
        StatusLineExecutor executor = new StatusLineExecutor(tmp.toString(), command -> {
            resolved.set(command);
            return List.of("bash", "-c", "printf resolved-shell");
        });

        assertEquals("resolved-shell",
            executor.execute(new StatusLineConfig("ignored command", 0), "{}").orElseThrow());
        assertEquals("ignored command", resolved.get());
    }
}
