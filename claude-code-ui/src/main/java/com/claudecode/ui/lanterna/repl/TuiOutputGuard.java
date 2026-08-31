package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
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
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Protects Lanterna's full-screen buffer from process-global output produced outside the renderer.
 */
public final class TuiOutputGuard implements AutoCloseable {

    private static final String DIAGNOSTIC_PREFIX = "claude-code-java-tui-output-";

    private static volatile PrintStream activeTerminalOut;

    private final PrintStream terminalOut;
    private final PrintStream terminalErr;
    private final PrintStream capturedOutput;
    private final Logger julRoot;
    private final Handler[] previousJulHandlers;
    private final Handler julCaptureHandler;
    private final NativeStderrRedirect nativeStderrRedirect;
    private boolean closed;

    private TuiOutputGuard(PrintStream terminalOut, PrintStream terminalErr,
            PrintStream capturedOutput, Logger julRoot,
            Handler[] previousJulHandlers, Handler julCaptureHandler,
            NativeStderrRedirect nativeStderrRedirect) {
        this.terminalOut = terminalOut;
        this.terminalErr = terminalErr;
        this.capturedOutput = capturedOutput;
        this.julRoot = julRoot;
        this.previousJulHandlers = previousJulHandlers;
        this.julCaptureHandler = julCaptureHandler;
        this.nativeStderrRedirect = nativeStderrRedirect;
    }

    static TuiOutputGuard install() throws IOException {
        PrintStream terminalOut = System.out;
        PrintStream terminalErr = System.err;
        Path diagnosticPath = diagnosticPath();
        OutputStream sink = Files.newOutputStream(diagnosticPath,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        PrintStream capturedOutput = new PrintStream(
            new SynchronizedOutputStream(sink), true, StandardCharsets.UTF_8);
        // The Java append handle above also guarantees the path exists, so
        // open(2) can use its portable non-variadic two-argument form.
        NativeStderrRedirect nativeRedirect = NativeStderrRedirect.install(diagnosticPath);
        capturedOutput.println("\n[" + Instant.now() + "] TUI output guard installed"
            + "; native stderr redirected=" + (nativeRedirect != null));

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

        activeTerminalOut = terminalOut;
        System.setOut(capturedOutput);
        System.setErr(capturedOutput);
        return new TuiOutputGuard(terminalOut, terminalErr, capturedOutput,
            julRoot, previousHandlers, julCapture, nativeRedirect);
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

        if (nativeStderrRedirect != null) nativeStderrRedirect.close();
        System.setOut(terminalOut);
        System.setErr(terminalErr);
        activeTerminalOut = null;
        julRoot.removeHandler(julCaptureHandler);
        for (Handler handler : previousJulHandlers) {
            if (handler instanceof ConsoleHandler) julRoot.addHandler(handler);
        }
        capturedOutput.flush();
        capturedOutput.close();
    }

    /** Process-level fd 2 redirect for output that never enters Java streams. */
    @Explanation("Captures GraalVM/native-library stderr that bypasses Java streams")
    private static final class NativeStderrRedirect implements AutoCloseable {
        private static final int STDERR_FILENO = 2;
        private static final int O_WRONLY = 1;
        private static final int O_APPEND_DARWIN = 0x0008;
        private static final int O_APPEND_LINUX = 0x0400;

        private final int savedStderr;
        private boolean closed;

        private NativeStderrRedirect(int savedStderr) {
            this.savedStderr = savedStderr;
        }

        static NativeStderrRedirect install(Path path) {
            if (!Platform.IS_DARWIN && !Platform.IS_LINUX) return null;
            int saved = -1;
            int target = -1;
            try (Arena arena = Arena.ofConfined()) {
                saved = (int) UnixCalls.DUP.invokeExact(STDERR_FILENO);
                if (saved < 0) return null;
                int appendFlag = Platform.IS_DARWIN ? O_APPEND_DARWIN : O_APPEND_LINUX;
                target = (int) UnixCalls.OPEN.invokeExact(
                    arena.allocateFrom(path.toString()), O_WRONLY | appendFlag);
                if (target < 0
                        || (int) UnixCalls.DUP2.invokeExact(target, STDERR_FILENO) < 0) {
                    closeFd(saved);
                    return null;
                }
                closeFd(target);
                return new NativeStderrRedirect(saved);
            } catch (Throwable _) {
                closeFd(target);
                closeFd(saved);
                return null;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                int ignored = (int) UnixCalls.DUP2.invokeExact(savedStderr, STDERR_FILENO);
            } catch (Throwable _) {
                // Best effort during terminal teardown; Java streams still restore below.
            } finally {
                closeFd(savedStderr);
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
