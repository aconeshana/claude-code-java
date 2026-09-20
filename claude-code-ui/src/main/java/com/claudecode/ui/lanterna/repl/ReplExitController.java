package com.claudecode.ui.lanterna.repl;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.shutdown.ShutdownPort;
import com.claudecode.tools.worktree.WorktreeSession;
import com.claudecode.ui.lanterna.dialog.WorktreeExitDialog;
import com.claudecode.ui.lanterna.features.ReplFeature;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.googlecode.lanterna.gui2.Component;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Owns REPL exit gestures, OS signal registration, worktree exit mediation, and the final
 * shutdown/stop decision.
 */
final class ReplExitController implements ReplFeature {

    private static final Logger log = LoggerFactory.getLogger(ReplExitController.class);
    static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;

    interface InterruptActions {
        boolean interruptBashIfRunning();
        boolean interruptTurnIfRunning();
        void softInterruptTurnIfRunning();
        boolean clearInputIfPresent();
        void showExitHint(String text, int durationMs);
    }

    interface JobControlActions {
        void beforeSuspend();
        void afterResume();
    }

    private final ShutdownPort shutdown;
    private final InterruptActions interruptActions;
    private final Supplier<WorktreeSession> currentWorktree;
    private final WorktreeExitDialog dialog;
    private final BiConsumer<WorktreeSession, Consumer<WorktreeExitDialog.Result>> worktreeExit;
    private final Consumer<WorktreeSession> persistWorktreeExit;
    private final Consumer<String> transcript;
    private final Runnable stop;
    private final JobControlActions jobControlActions;
    private final Runnable suspendProcess;
    private final IntConsumer halt;
    private final LongSupplier clock;
    private final AtomicLong lastCtrlC = new AtomicLong();
    private final AtomicLong lastCtrlD = new AtomicLong();
    private volatile boolean jobControlSuspended;

    /**
     * Builds the controller with a self-constructed {@link WorktreeExitDialog}, so callers no
     * longer hold the dialog field directly. Extracted from {@code LanternaReplScreen}.
     */
    static ReplExitController standard(ShutdownPort shutdown,
                                       InterruptActions interruptActions,
                                       Consumer<String> transcript,
                                       Runnable stop,
                                       JobControlActions jobControlActions,
                                       InteractiveSessionPort sessions,
                                       Supplier<WorktreeSession> currentWorktree,
                                       UserKeybindingsStore keybindingsStore) {
        WorktreeExitDialog dialog = new WorktreeExitDialog(); // inline, zero height until shown
        dialog.setKeybindingsStore(keybindingsStore);
        return new ReplExitController(
            shutdown,
            interruptActions,
            currentWorktree,
            dialog,
            dialog::show,
            sessions == null ? _ -> {} : sessions::persistWorktreeExit,
            transcript,
            stop,
            jobControlActions,
            JvmSignals::suspendSelf,
            code -> Runtime.getRuntime().halt(code),
            System::currentTimeMillis);
    }

    @Override public List<InlineOverlay> overlays() {
        return List.of(dialog);
    }

    Component view() {
        return dialog;
    }

    ReplExitController(ShutdownPort shutdown,
                       InterruptActions interruptActions,
                       Supplier<WorktreeSession> currentWorktree,
                       BiConsumer<WorktreeSession, Consumer<WorktreeExitDialog.Result>> worktreeExit,
                       Consumer<WorktreeSession> persistWorktreeExit,
                       Consumer<String> transcript,
                       Runnable stop,
                       IntConsumer halt,
                       LongSupplier clock) {
        this(shutdown, interruptActions, currentWorktree, null, worktreeExit,
            persistWorktreeExit, transcript, stop, null, () -> {}, halt, clock);
    }

    ReplExitController(ShutdownPort shutdown,
                       InterruptActions interruptActions,
                       Supplier<WorktreeSession> currentWorktree,
                       BiConsumer<WorktreeSession, Consumer<WorktreeExitDialog.Result>> worktreeExit,
                       Consumer<WorktreeSession> persistWorktreeExit,
                       Consumer<String> transcript,
                       Runnable stop,
                       JobControlActions jobControlActions,
                       Runnable suspendProcess,
                       IntConsumer halt,
                       LongSupplier clock) {
        this(shutdown, interruptActions, currentWorktree, null, worktreeExit,
            persistWorktreeExit, transcript, stop, jobControlActions, suspendProcess, halt, clock);
    }

    private ReplExitController(ShutdownPort shutdown,
                       InterruptActions interruptActions,
                       Supplier<WorktreeSession> currentWorktree,
                       WorktreeExitDialog dialog,
                       BiConsumer<WorktreeSession, Consumer<WorktreeExitDialog.Result>> worktreeExit,
                       Consumer<WorktreeSession> persistWorktreeExit,
                       Consumer<String> transcript,
                       Runnable stop,
                       JobControlActions jobControlActions,
                       Runnable suspendProcess,
                       IntConsumer halt,
                       LongSupplier clock) {
        this.shutdown = shutdown != null ? shutdown : ShutdownPort.noop();
        this.interruptActions = interruptActions;
        this.currentWorktree = currentWorktree != null ? currentWorktree : () -> null;
        this.dialog = dialog;
        this.worktreeExit = worktreeExit;
        this.persistWorktreeExit = persistWorktreeExit != null ? persistWorktreeExit : _ -> {};
        this.transcript = transcript != null ? transcript : _ -> {};
        this.stop = stop != null ? stop : () -> {};
        this.jobControlActions = jobControlActions != null ? jobControlActions
            : new JobControlActions() {
                @Override public void beforeSuspend() {}
                @Override public void afterResume() {}
            };
        this.suspendProcess = suspendProcess != null ? suspendProcess : () -> {};
        this.halt = halt != null ? halt : _ -> {};
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    void registerSignalHandlers() {
        registerSignal("INT", this::handleCtrlC, true);
        registerSignal("TERM", () -> handleTerminationSignal("sigterm", 143), false);
        registerSignal("HUP", () -> handleTerminationSignal("sighup", 129), false);
        for (String signalName : jobControlSignals()) {
            registerSignal(signalName,
                () -> handleJobControlSuspend("sig" + signalName.toLowerCase(Locale.ROOT)), false);
        }
        registerSignal("CONT", this::handleContinueSignal, false);
    }

    /**
     * Only an explicit terminal suspend belongs to the application lifecycle.
     */
    static List<String> jobControlSignals() {
        return List.of("TSTP");
    }

    private void registerSignal(String signalName, Runnable action, boolean warnOnFailure) {
        try {
            JvmSignals.register(signalName, action);
        } catch (ReflectiveOperationException | LinkageError e) {
            if (warnOnFailure) {
                log.warn("[LANTERNA] Could not register SIG{} handler: {}", signalName, e.getMessage());
            } else {
                log.debug("[LANTERNA] Could not register SIG{} handler: {}", signalName, e.getMessage());
            }
        }
    }

    void handleJobControlSuspend(String reason) {
        if (jobControlSuspended) return;
        jobControlSuspended = true;
        log.info("[LANTERNA] {} received — releasing terminal before suspension", reason);
        jobControlActions.beforeSuspend();
        try {
            suspendProcess.run();
        } catch (RuntimeException failure) {
            // The terminal is already released but the process kept running, so the user
            // would be left staring at a blank screen with no way back. Rebuilding the UI
            // is strictly better than honouring a suspend that never happened.
            jobControlSuspended = false;
            log.warn("[LANTERNA] suspend failed — restoring the terminal instead", failure);
            jobControlActions.afterResume();
        }
    }

    void handleContinueSignal() {
        if (!jobControlSuspended) return;
        jobControlSuspended = false;
        jobControlActions.afterResume();
    }

    void handleTerminationSignal(String reason, int exitCode) {
        log.info("[LANTERNA] {} received — running graceful shutdown", reason);
        requestShutdown(reason, exitCode);
    }

    void handleCtrlC() {
        log.info("[LANTERNA] SIGINT/Ctrl+C received on thread '{}'", Thread.currentThread().getName());
        if (interruptActions.interruptBashIfRunning()
                || interruptActions.interruptTurnIfRunning()
                || interruptActions.clearInputIfPresent()) {
            lastCtrlC.set(0);
            return;
        }
        handleDoublePress(lastCtrlC, "Press Ctrl+C again to exit");
    }

    void handleCtrlD() {
        handleDoublePress(lastCtrlD, "Press Ctrl+D again to exit");
    }

    private void handleDoublePress(AtomicLong state, String hint) {
        long now = clock.getAsLong();
        long previous = state.getAndSet(now);
        if (now - previous < DOUBLE_PRESS_TIMEOUT_MS) {
            requestShutdown("prompt_input_exit", 0);
        } else {
            interruptActions.showExitHint(hint, (int) DOUBLE_PRESS_TIMEOUT_MS);
        }
    }

    void requestShutdown(String reason, int exitCode) {
        WorktreeSession session = currentWorktree.get();
        if (Strings.CS.equals("prompt_input_exit", reason) && session != null && worktreeExit != null) {
            worktreeExit.accept(session, result -> {
                if (StringUtils.isNotBlank(result.message())) {
                    transcript.accept(result.message());
                }
                if (result.proceedExit()) {
                    persistWorktreeExit.accept(session);
                    shutdownAndStop(reason, exitCode);
                }
            });
            return;
        }
        shutdownAndStop(reason, exitCode);
    }

    private void shutdownAndStop(String reason, int exitCode) {
        log.warn("[LANTERNA] shutdownAndStop(reason={}, exitCode={}) on thread '{}' — will "
                + "softInterrupt any in-flight turn before teardown",
            reason, exitCode, Thread.currentThread().getName());

        interruptActions.softInterruptTurnIfRunning();
        shutdown.prepare(reason, exitCode);
        stop.run();
        shutdown.shutdown(reason, exitCode);
        if (exitCode != 0) halt.accept(exitCode);
    }

    private static final class JvmSignals {
        private static final Class<?> SIGNAL_CLASS = load("sun.misc.Signal");
        private static final Class<?> HANDLER_CLASS = load("sun.misc.SignalHandler");
        private static final Method HANDLE = method("handle", SIGNAL_CLASS, HANDLER_CLASS);
        private static final Field DEFAULT_HANDLER = defaultHandlerField();

        private JvmSignals() {}

        static void register(String name, Runnable action) throws ReflectiveOperationException {
            Object signal = SIGNAL_CLASS.getConstructor(String.class).newInstance(name);
            Object handler = Proxy.newProxyInstance(
                HANDLER_CLASS.getClassLoader(),
                new Class<?>[] { HANDLER_CLASS },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "handle" -> action.run();
                        case "equals" -> { return proxy == args[0]; }
                        case "hashCode" -> { return System.identityHashCode(proxy); }
                        case "toString" -> { return "SignalHandler[" + name + "]"; }
                        default -> { }
                    }
                    return null;
                });
            invokeStatic(HANDLE, signal, handler);
        }

        /**
         * Stops this process the way a shell expects a job to stop: hand SIGTSTP back to
         * the kernel, then have a short-lived {@code kill} deliver it.
         *
         * <p>{@code Signal.raise} cannot do this. It refuses any signal the JVM holds no
         * Java handler for, so SIGSTOP is out — and restoring SIG_DFL for SIGTSTP, which is
         * precisely what makes the kernel stop us, drops the signal from that same registry.
         * The handler is put back once SIGCONT resumes the process.
         */
        static void suspendSelf() {
            try {
                Object signal = SIGNAL_CLASS.getConstructor(String.class).newInstance("TSTP");
                Object previous = invokeStatic(HANDLE, signal, DEFAULT_HANDLER.get(null));
                try {
                    deliverSuspendSignal();
                } finally {
                    invokeStatic(HANDLE, signal, previous);
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new IllegalStateException("JVM signal support is unavailable", e);
            }
        }

        private static void deliverSuspendSignal() {
            try {
                Process kill = new ProcessBuilder("/usr/bin/env", "kill", "-s", "TSTP",
                        Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
                int status = kill.waitFor();
                if (status != 0) {
                    throw new IllegalStateException("kill -s TSTP exited with " + status);
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not deliver SIGTSTP", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while suspending", e);
            }
        }

        private static Class<?> load(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static Method method(String name, Class<?>... parameterTypes) {
            try {
                return SIGNAL_CLASS.getMethod(name, parameterTypes);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static Field defaultHandlerField() {
            try {
                return HANDLER_CLASS.getField("SIG_DFL");
            } catch (NoSuchFieldException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private static Object invokeStatic(Method method, Object... args)
                throws ReflectiveOperationException {
            try {
                return method.invoke(null, args);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof ReflectiveOperationException reflectionFailure) {
                    throw reflectionFailure;
                }
                throw e;
            }
        }
    }
}
