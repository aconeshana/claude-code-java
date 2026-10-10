package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Protects Lanterna's full-screen buffer from process-global output produced outside the renderer.
 *
 * <p>Two separate hazards are covered. Java-level output is diverted by swapping
 * {@code System.out}/{@code System.err} and the JUL console handlers onto a diagnostic file.
 * Output that never enters a Java stream — GraalVM substrate messages, native libraries, children
 * launched with inherited stdio — is diverted at the descriptor level with {@code dup2}.
 *
 * <p>The descriptor-level half only works if the renderer stops using fd 1 itself, so
 * {@link #openTerminalChannel()} claims a private handle on {@code /dev/tty} and the terminal is
 * built on that instead. Without it the renderer and every foreign writer share one descriptor:
 * a buffered flush can split an escape sequence between its {@code ESC} and the rest, a foreign
 * byte lands in the gap, and the terminal abandons the sequence and prints the remainder as text.
 * Those characters are invisible to Lanterna's delta renderer, which therefore never erases them.
 */
public final class TuiOutputGuard implements AutoCloseable {

    private static final String DIAGNOSTIC_PREFIX = "claude-code-java-tui-output-";
    private static final String TTY_DEVICE = "/dev/tty";
    private static final int STDOUT_FILENO = 1;

    /**
     * Matches the capacity Lanterna inherited from {@code System.out} so a full repaint still
     * coalesces into few writes. Buffering is safe here precisely because the descriptor is
     * private: a flush that splits a sequence has no other writer to interleave with.
     */
    private static final int TERMINAL_CHANNEL_BUFFER_BYTES = 1 << 16;

    private static volatile PrintStream activeTerminalOut;

    private final PrintStream terminalOut;
    private final PrintStream terminalErr;
    private final PrintStream terminalChannel;
    private final PrintStream capturedOutput;
    private final Logger julRoot;
    private final Handler[] previousJulHandlers;
    private final Handler julCaptureHandler;
    private final NativeStdioRedirect nativeStdioRedirect;
    private boolean closed;

    private TuiOutputGuard(PrintStream terminalOut, PrintStream terminalErr,
            PrintStream terminalChannel, PrintStream capturedOutput, Logger julRoot,
            Handler[] previousJulHandlers, Handler julCaptureHandler,
            NativeStdioRedirect nativeStdioRedirect) {
        this.terminalOut = terminalOut;
        this.terminalErr = terminalErr;
        this.terminalChannel = terminalChannel;
        this.capturedOutput = capturedOutput;
        this.julRoot = julRoot;
        this.previousJulHandlers = previousJulHandlers;
        this.julCaptureHandler = julCaptureHandler;
        this.nativeStdioRedirect = nativeStdioRedirect;
    }

    static TuiOutputGuard install() throws IOException {
        return install(null);
    }

    /**
     * Guards process output, routing the renderer onto {@code terminalChannel} when one was
     * obtained. fd 1 is only redirected in that case: doing it without a private channel would
     * silence the terminal instead of protecting it.
     */
    static TuiOutputGuard install(PrintStream terminalChannel) throws IOException {
        PrintStream terminalOut = System.out;
        PrintStream terminalErr = System.err;
        Path diagnosticPath = diagnosticPath();
        OutputStream sink = Files.newOutputStream(diagnosticPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        PrintStream capturedOutput = new PrintStream(
            new SynchronizedOutputStream(sink), true, StandardCharsets.UTF_8);
        // The Java append handle above also guarantees the path exists, so
        // open(2) can use its portable non-variadic two-argument form.
        NativeStdioRedirect nativeRedirect =
            NativeStdioRedirect.install(diagnosticPath, terminalChannel != null);
        capturedOutput.println("\n[" + Instant.now() + "] TUI output guard installed"
            + "; private terminal channel=" + (terminalChannel != null)
            + "; native stdio redirected=" + (nativeRedirect != null));

        Logger julRoot = Logger.getLogger("");
        Handler[] previousHandlers = julRoot.getHandlers();
        for (Handler handler : previousHandlers) {
            if (handler instanceof ConsoleHandler) julRoot.removeHandler(handler);
        }
        Handler julCapture = new Handler() {
            private final SimpleFormatter formatter = new SimpleFormatter();

            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) capturedOutput.print(formatter.format(record));
            }

            @Override public void flush() { capturedOutput.flush(); }
            @Override public void close() { flush(); }
        };
        julRoot.addHandler(julCapture);

        activeTerminalOut = terminalChannel != null ? terminalChannel : terminalOut;
        System.setOut(capturedOutput);
        System.setErr(capturedOutput);
        return new TuiOutputGuard(terminalOut, terminalErr, terminalChannel, capturedOutput,
            julRoot, previousHandlers, julCapture, nativeRedirect);
    }

    /**
     * Claims a private write handle on the controlling terminal, so the renderer keeps reaching
     * the screen after fd 1 is redirected away. Returns {@code null} when fd 1 is not a terminal
     * — a piped or redirected run has nothing to protect and must keep writing where it was
     * pointed — or when the device cannot be opened, in which case the caller falls back to the
     * shared descriptor.
     */
    static PrintStream openTerminalChannel() {
        if (!NativeStdioRedirect.isTerminal(STDOUT_FILENO)) return null;
        try {
            OutputStream device = Files.newOutputStream(
                Path.of(TTY_DEVICE), StandardOpenOption.WRITE);
            return new PrintStream(
                new BufferedOutputStream(device, TERMINAL_CHANNEL_BUFFER_BYTES),
                false, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException _) {
            return null;
        }
    }

    /** Releases a channel from {@link #openTerminalChannel()} that no guard took ownership of. */
    static void closeTerminalChannel(PrintStream channel) {
        if (channel == null) return;
        channel.flush();
        channel.close();
    }

    /** Process input seam used only while constructing the terminal adapter. */
    static InputStream terminalInput() {
        return System.in;
    }

    /** Process output seam used only while constructing the terminal adapter. */
    static PrintStream terminalOutput() {
        return System.out;
    }

    static Path diagnosticPath() {
        return diagnosticPathForPid(ProcessHandle.current().pid());
    }

    static Path diagnosticPathForPid(long pid) {
        return Path.of("/tmp", DIAGNOSTIC_PREFIX + pid + ".log");
    }

    /**
     * Persists a GUI-thread terminal failure through a fresh file handle. This
     * deliberately does not reuse {@link #capturedOutput}: another JVM or test
     * may have unlinked that handle's directory entry while this process was
     * still running, which was the failure mode that hid Ctrl+O crashes.
     */
    static void recordFatalThreadFailure(Thread thread, Throwable failure) {
        try (OutputStream sink = Files.newOutputStream(diagnosticPath(),
                 StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
             PrintStream output = new PrintStream(
                 new SynchronizedOutputStream(sink), true, StandardCharsets.UTF_8)) {
            String threadName = thread == null ? "unknown" : thread.getName();
            output.println("\n[" + Instant.now() + "] fatal thread failure: " + threadName);
            if (failure != null) failure.printStackTrace(output);
        } catch (IOException _) {
            // A diagnostic failure must never mask the original terminal failure.
        }
    }

    /** Emits an intentional terminal control sequence while global stdout is guarded. */
    public static void writeToTerminal(String value) {
        PrintStream output = activeTerminalOut;
        if (output == null) output = System.out;
        output.print(value);
        output.flush();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        if (nativeStdioRedirect != null) nativeStdioRedirect.close();
        System.setOut(terminalOut);
        System.setErr(terminalErr);
        activeTerminalOut = null;
        julRoot.removeHandler(julCaptureHandler);
        for (Handler handler : previousJulHandlers) {
            if (handler instanceof ConsoleHandler) julRoot.addHandler(handler);
        }
        capturedOutput.flush();
        capturedOutput.close();
        // Last, so a terminal still draining its teardown sequences through this
        // channel is not writing into a closed stream.
        closeTerminalChannel(terminalChannel);
    }

    /**
     * Process-level stdio redirect for output that never enters Java streams.
     *
     * <p>fd 2 is always diverted. fd 1 is diverted only when the renderer holds a private handle
     * on the terminal, otherwise the redirect would take the screen away from the renderer along
     * with the foreign writers.
     */
    @Explanation("Captures GraalVM/native-library stdio that bypasses Java streams")
    private static final class NativeStdioRedirect implements AutoCloseable {
        private static final int STDERR_FILENO = 2;
        private static final int O_WRONLY = 1;
        private static final int O_APPEND_DARWIN = 0x0008;
        private static final int O_APPEND_LINUX = 0x0400;

        private final int[] redirectedDescriptors;
        private final int[] savedDescriptors;
        private boolean closed;

        private NativeStdioRedirect(int[] redirectedDescriptors, int[] savedDescriptors) {
            this.redirectedDescriptors = redirectedDescriptors;
            this.savedDescriptors = savedDescriptors;
        }

        static boolean isTerminal(int descriptor) {
            if (!Platform.IS_DARWIN && !Platform.IS_LINUX) return false;
            try {
                return (int) UnixCalls.ISATTY.invokeExact(descriptor) == 1;
            } catch (Throwable _) {
                return false;
            }
        }

        static NativeStdioRedirect install(Path path, boolean includeStdout) {
            if (!Platform.IS_DARWIN && !Platform.IS_LINUX) return null;
            int[] targets = includeStdout
                ? new int[] {STDERR_FILENO, STDOUT_FILENO}
                : new int[] {STDERR_FILENO};
            int[] saved = new int[targets.length];
            Arrays.fill(saved, -1);
            int target = -1;
            try (Arena arena = Arena.ofConfined()) {
                int appendFlag = Platform.IS_DARWIN ? O_APPEND_DARWIN : O_APPEND_LINUX;
                target = (int) UnixCalls.OPEN.invokeExact(
                    arena.allocateFrom(path.toString()), O_WRONLY | appendFlag);
                if (target < 0) return null;
                for (int index = 0; index < targets.length; index++) {
                    saved[index] = (int) UnixCalls.DUP.invokeExact(targets[index]);
                    if (saved[index] < 0
                            || (int) UnixCalls.DUP2.invokeExact(target, targets[index]) < 0) {
                        restore(targets, saved, index);
                        return null;
                    }
                }
                return new NativeStdioRedirect(targets, saved);
            } catch (Throwable _) {
                restore(targets, saved, targets.length);
                return null;
            } finally {
                closeFd(target);
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            restore(redirectedDescriptors, savedDescriptors, redirectedDescriptors.length);
        }

        /** Restores the first {@code count} descriptors, releasing every saved duplicate. */
        private static void restore(int[] targets, int[] saved, int count) {
            for (int index = 0; index < saved.length; index++) {
                if (saved[index] < 0) continue;
                if (index < count) {
                    try {
                        int ignored = (int) UnixCalls.DUP2.invokeExact(saved[index], targets[index]);
                    } catch (Throwable _) {
                        // Best effort during teardown; Java streams still restore separately.
                    }
                }
                closeFd(saved[index]);
                saved[index] = -1;
            }
        }

        private static void closeFd(int descriptor) {
            if (descriptor < 0) return;
            try {
                int ignored = (int) UnixCalls.CLOSE.invokeExact(descriptor);
            } catch (Throwable _) {
                // Closing an auxiliary diagnostic descriptor is best effort.
            }
        }

        /** Lazily initialized only on Unix; Windows has no libc dup/open symbols. */
        private static final class UnixCalls {
            private static final Linker LINKER = Linker.nativeLinker();
            private static final SymbolLookup LIBC = LINKER.defaultLookup();
            private static final MemoryLayout C_INT = LINKER.canonicalLayouts().get("int");
            private static final MemoryLayout C_POINTER =
                LINKER.canonicalLayouts().get("void*");
            private static final MethodHandle DUP = downcall("dup",
                FunctionDescriptor.of(C_INT, C_INT));
            private static final MethodHandle DUP2 = downcall("dup2",
                FunctionDescriptor.of(C_INT, C_INT, C_INT));
            private static final MethodHandle OPEN = downcall("open",
                FunctionDescriptor.of(C_INT, C_POINTER, C_INT));
            private static final MethodHandle CLOSE = downcall("close",
                FunctionDescriptor.of(C_INT, C_INT));
            private static final MethodHandle ISATTY = downcall("isatty",
                FunctionDescriptor.of(C_INT, C_INT));

            private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
                return LINKER.downcallHandle(LIBC.find(name).orElseThrow(), descriptor);
            }
        }
    }

    private static final class SynchronizedOutputStream extends OutputStream {
        private final OutputStream delegate;

        private SynchronizedOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override public synchronized void write(int value) throws IOException {
            delegate.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length)
                throws IOException {
            delegate.write(bytes, offset, length);
        }

        @Override public synchronized void flush() throws IOException { delegate.flush(); }
        @Override public synchronized void close() throws IOException { delegate.close(); }
    }
}
