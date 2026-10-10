package com.claudecode.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.claudecode.core.platform.Platform;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ControllingTerminalTest {

    /**
     * The guarantee the interactive launchers depend on. {@code inheritIO} cannot provide it: the
     * TUI points its own stdout at a log file, so a child that inherits the descriptor fails
     * {@code isatty} and an editor launched that way refuses to draw.
     */
    @Test
    void childSeesATerminalOnStdoutExactlyWhenThisProcessHasOne() throws Exception {
        assumeTrue(Platform.IS_DARWIN || Platform.IS_LINUX);

        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", "test -t 1");
        assertSame(builder, ControllingTerminal.connect(builder));
        // Standard input is never rerouted: the host stops reading for the duration of the
        // handoff, so the descriptor it already holds is the terminal.
        assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectInput());

        Process child = builder.start();
        assertTrue(child.waitFor(5, TimeUnit.SECONDS));
        assertEquals(hasControllingTerminal(), child.exitValue() == 0);
    }

    @Test
    void fallsBackToInheritanceWithoutAControllingTerminal() {
        assumeTrue(!hasControllingTerminal());

        ProcessBuilder builder = ControllingTerminal.connect(
            new ProcessBuilder("/bin/sh", "-c", "true"));

        assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectOutput());
        assertEquals(ProcessBuilder.Redirect.INHERIT, builder.redirectError());
    }

    private static boolean hasControllingTerminal() {
        if (!Platform.IS_DARWIN && !Platform.IS_LINUX) return false;
        try (FileOutputStream probe = new FileOutputStream("/dev/tty", true)) {
            return probe.getFD().valid();
        } catch (IOException | RuntimeException _) {
            return false;
        }
    }
}
