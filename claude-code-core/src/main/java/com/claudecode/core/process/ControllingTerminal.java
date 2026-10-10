package com.claudecode.core.process;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Connects a child process that drives the screen itself — an interactive editor, a pager — to the
 * controlling terminal.
 *
 * <p>{@link ProcessBuilder#inheritIO()} is not enough: a full-screen host redirects its own
 * standard streams away from the terminal so stray output cannot corrupt the alternate-screen
 * buffer, and a child that inherits those descriptors writes into a log file instead of the
 * screen. Opening the controlling terminal device gives the child a descriptor that is a terminal
 * no matter what the host did with its own, which is also what {@code isatty} checks in editors
 * decide on.
 *
 * <p>Standard input stays inherited: a host suspends its own reader for the duration of the
 * handoff rather than redirecting the descriptor, so it is still the terminal.
 */
@Explanation("""
    No counterpart in the original: it renders through process.stdout and opens /dev/tty only to \
    recover piped input, so plain stdio inheritance already hands an editor the terminal. This \
    port diverts its own stdout at the descriptor level, which makes inheritance insufficient.""")
public final class ControllingTerminal {

    private static final String TTY_DEVICE = "/dev/tty";

    private ControllingTerminal() {}

    /**
     * Points {@code builder} at the controlling terminal and returns it for chaining. Falls back
     * to plain inheritance when there is no terminal device to open — a Windows host, or a process
     * detached from any terminal — which leaves the child exactly where it would have been.
     */
    public static ProcessBuilder connect(ProcessBuilder builder) {
        File device = terminalDevice();
        if (device == null) return builder.inheritIO();
        return builder
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            // appendTo rather than to: O_TRUNC is meaningless for a character device, and the
            // append form cannot be mistaken for an instruction to discard what is on screen.
            .redirectOutput(ProcessBuilder.Redirect.appendTo(device))
            .redirectError(ProcessBuilder.Redirect.appendTo(device));
    }

    /**
     * The controlling terminal, or {@code null} when this process has none. Openability is probed
     * rather than inferred from the path existing: {@code /dev/tty} is present on every Unix host
     * but only resolves for a process that still has a controlling terminal.
     */
    private static File terminalDevice() {
        if (!Platform.IS_DARWIN && !Platform.IS_LINUX) return null;
        File device = new File(TTY_DEVICE);
        try (FileOutputStream probe = new FileOutputStream(device, true)) {
            return probe.getFD().valid() ? device : null;
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }
}
