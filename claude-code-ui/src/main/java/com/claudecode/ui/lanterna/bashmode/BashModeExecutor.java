package com.claudecode.ui.lanterna.bashmode;

import com.claudecode.commands.XmlConstants;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.process.DetachedSession;
import com.claudecode.core.process.ProcessTreeTerminator;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.XmlEscaper;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.UserMessage;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.claudecode.tools.bash.BashTimeouts;
import com.claudecode.tools.bash.ShellQuoteParse;
import com.claudecode.tools.bash.ShellWorkingDirectoryTracker;
import com.claudecode.tools.bash.SudoCommandAdapter;
import com.claudecode.ui.lanterna.dialog.SudoPasswordDialog;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.ShellOutputFormatter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.LoggerFactory;

/**
 * Local shell execution for bash mode (input prefixed with {@code !}).
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code utils/processUserInput/processBashCommand.tsx} — the interrupted outcome keeps
 *       only the input and the interruption message, and the {@code BashModeProgress} JSX is
 *       cleared once the command settles (authoritative logic from the 2.1.236 bundle).</li>
 *   <li>{@code components/messages/UserTextMessage.tsx} — that interruption message renders
 *       as {@code InterruptedByUser}, the dim {@code Interrupted · What should Claude do
 *       instead?} row (authoritative logic from the 2.1.236 bundle).</li>
 * </ul>
 */
public final class BashModeExecutor {

    private static final Logger log = LoggerFactory.getLogger(BashModeExecutor.class);


    private static final int MAX_INLINE_CHARS = 30_000;

    private static final int PREVIEW_SIZE     = 2_000;
    private static final Pattern IMGCAT_COMMAND = Pattern.compile(
        "^\\s*(?:command\\s+)?(?:[^\\s;&|()<>]*/)?imgcat(?:\\s|$)");

    private final WindowBasedTextGUI gui;
    private final MessagePanel       messagePanel;
    private final QuerySession        queryEngine;
    private final InteractiveSessionPort sessions;
    private final ImagePreviewLauncher imagePreviewLauncher;
    private final SudoPasswordInteraction sudoPasswordInteraction;

    @FunctionalInterface
    public interface ImagePreviewLauncher {
        void show(Path image, Runnable onClose) throws IOException;
    }

/** In-flight process ref for {@link #interrupt} — null when idle. */
    private final AtomicReference<Run> currentRun = new AtomicReference<>(null);
    
    public BashModeExecutor(WindowBasedTextGUI gui,
                            MessagePanel messagePanel,
                            QuerySession queryEngine,
                            InteractiveSessionPort sessions,
                            SudoPasswordInteraction sudoPasswordInteraction,
                            ImagePreviewLauncher imagePreviewLauncher) {
        this.gui          = gui;
        this.messagePanel = messagePanel;
        this.queryEngine  = queryEngine;
        this.sessions = sessions;
        this.sudoPasswordInteraction = sudoPasswordInteraction != null
            ? sudoPasswordInteraction : SudoPasswordInteraction.UNAVAILABLE;
        this.imagePreviewLauncher = imagePreviewLauncher != null
            ? imagePreviewLauncher
            : (_, _) -> { throw new IOException("Terminal image preview is unavailable"); };
    }

    /** {@code true} between {@link #handle(String)} start and history append. */
    public boolean isRunning() {
        Run run = currentRun.get();
        return run != null && run.process != null;
    }

    /**
     * Cancel the in-flight shell command (Esc / Ctrl+C): closes a pending sudo prompt, kills the
     * process, and withdraws the run's progress rows the way 236 drops the
     * {@code BashModeProgress} JSX. The runner thread's completion path then records the
     * interruption, or hands it to the caller to resurrect.
     */
    public void interrupt() {
        Run run = currentRun.get();
        if (run == null) return;
        run.interrupted = true;
        gui.getGUIThread().invokeLater(() -> {
            withdrawDisplay(run);
            if (run.prompting) closeSudoPrompt();
        });
        Process p = run.process;
        if (p == null) return;
        try {
            ProcessTreeTerminator.terminate(p, Duration.ofMillis(250));
        } catch (RuntimeException destroyFailure) {

            // was actually delivered to the process.
            log.warn("Failed to interrupt bash-mode process", destroyFailure);
        }
    }

    /**
     * Handle a bash-mode submission.
     * @param command shell command text (leading {@code !} already stripped by caller)
     */
    public void handle(String command) {
        handle(command, () -> { });
    }

    /** {@link #handle(String, Runnable, Predicate)} that always appends its result. */
    public void handle(String command, Runnable onFinished) {
        handle(command, onFinished, _ -> false);
    }

    /**
     * Handle a bash-mode submission and report when it has fully finished.
     *
     * @param command    shell command text (leading {@code !} already stripped by caller)
     * @param onFinished runs once on the GUI thread after every outcome, including failures,
     *                   so the caller can release the busy state 236 holds for the whole
     *                   {@code processBashCommand} await
     * @param divertResult offered the command's transcript messages before they are appended
     *                     to the conversation; returning {@code true} takes them over instead
     *                     (236's {@code run} resurrecting a late result into the queue)
     */
    public void handle(String command, Runnable onFinished,
                       Predicate<List<UserMessage>> divertResult) {
        if (StringUtils.isBlank(command)) {
            onFinished.run();
            return;
        }

        Run run = new Run(divertResult, messagePanel.snapshotLineCount(),
            messagePanel.contentEpoch());
        renderInputPill(command);
        // Registered before the worker starts so an immediate Esc still reaches this run. A
        // force-ended run can still be winding down when the next command starts, so the
        // process slot and the interrupt flag belong to this run alone.
        currentRun.set(run);

        Thread.ofVirtual().name("bash-mode-" + System.currentTimeMillis())
            .start(() -> {
                try {
                    execute(command, run);
                } finally {
                    currentRun.compareAndSet(run, null);
                    gui.getGUIThread().invokeLater(onFinished);
                }
            });
    }

    /** Render the user-visible bash-input pill. */
    private void renderInputPill(String command) {
        messagePanel.appendLine("", TextColor.ANSI.DEFAULT);
        messagePanel.appendMixed(List.of(
            new MessagePanel.Segment("! ",    LanternaTheme.bashBorder(), LanternaTheme.bashBg()),
            new MessagePanel.Segment(command, LanternaTheme.inputText(),  LanternaTheme.bashBg()),
            new MessagePanel.Segment(" ",     LanternaTheme.inputText(),  LanternaTheme.bashBg())
        ));
    }

    /** Drop the rows this run has rendered so far; later output rows are suppressed. */
    private void withdrawDisplay(Run run) {
        if (run.displayWithdrawn) return;
        run.displayWithdrawn = true;
        int end = messagePanel.snapshotLineCount();
        if (messagePanel.contentEpoch() == run.displayEpoch && end >= run.displayStart) {
            messagePanel.replaceLines(run.displayStart, end - run.displayStart, List.of());
        }
    }

    private void closeSudoPrompt() {
        gui.getWindows().stream()
            .filter(SudoPasswordDialog.class::isInstance)
            .toList()
            .forEach(Window::close);
    }

    private void execute(String command, Run run) {

        String cwd = queryEngine.configuration().getConfig().workingDirectory();
        if (StringUtils.isBlank(cwd)) cwd = System.getProperty("user.dir");

        if (isImgcatCommand(command)) {
            executeImgcatPreview(command, cwd, run);
            return;
        }

        run.prompting = true;
        SudoCommandAdapter.Result sudoResult;
        try {
            sudoResult = prepareSudoCommand(command, sudoPasswordInteraction);
        } finally {
            run.prompting = false;
        }
        if (run.interrupted) {
            if (sudoResult instanceof SudoCommandAdapter.Result.Prepared prepared) prepared.close();
            currentRun.compareAndSet(run, null);
            finish(run, command, "", "", false, null, null);
            return;
        }
        if (sudoResult instanceof SudoCommandAdapter.Result.Rejected(String message)) {
            currentRun.compareAndSet(run, null);
            finishRejected(command, message, run);
            return;
        }
        SudoCommandAdapter.Result.Prepared sudoCommand =
            sudoResult instanceof SudoCommandAdapter.Result.Prepared prepared ? prepared : null;
        String commandToExecute = sudoCommand != null ? sudoCommand.command() : command;

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        boolean timedOut = false;
        Throwable failure = null;
        String cwdResetWarning = null;
        ShellWorkingDirectoryTracker cwdTracker = null;

        try {
            Path path = Path.of(cwd);
            cwdTracker = ShellWorkingDirectoryTracker.start(
                path, queryEngine.configuration().workingDirectoryController(),
                SubprocessEnvironment::get);
            ProcessBuilder pb = new ProcessBuilder(
                ExecutableFinder.bashExecutable(), "-c",
                cwdTracker.wrap(commandToExecute));
            if (Platform.IS_WINDOWS) {
                pb.environment().put("SHELL", ExecutableFinder.bashExecutable());
            }
            pb.directory(path.toFile());
            pb.redirectErrorStream(false);
            Process proc = DetachedSession.apply(pb).start();
            run.process = proc;
            // An interrupt that ran before the process slot was filled could not kill it.
            if (run.interrupted) ProcessTreeTerminator.terminate(proc, Duration.ZERO);
            try {
                if (sudoCommand != null) {
                    sudoCommand.writePasswordTo(proc.getOutputStream());
                }
            } catch (IOException writeFailure) {
                ProcessTreeTerminator.terminate(proc, Duration.ZERO);
                throw writeFailure;
            } finally {
                if (sudoCommand != null) sudoCommand.close();
                try { proc.getOutputStream().close(); } catch (IOException _) {}
            }

            Thread stdoutReader = Thread.ofVirtual().start(
                () -> streamOutput(run, proc.getInputStream(), stdout, TextColor.ANSI.DEFAULT));
            Thread stderrReader = Thread.ofVirtual().start(
                () -> streamOutput(run, proc.getErrorStream(), stderr, LanternaTheme.toolError()));

            long timeoutMs = BashTimeouts.defaultTimeoutMs();
            boolean finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                ProcessTreeTerminator.terminate(proc, Duration.ofMillis(500));
                timedOut = true;
                cwdTracker.discard();
            }
            stdoutReader.join(1000);
            stderrReader.join(1000);
            if (!timedOut) {
                cwdResetWarning = cwdTracker.finish();
                if (cwdResetWarning != null) {
                    stderr.append(cwdResetWarning).append('\n');
                }
            }
        } catch (IOException | InterruptedException e) {
            failure = e;
            if (cwdTracker != null) cwdTracker.discard();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (sudoCommand != null) sudoCommand.close();
            run.process = null;
            currentRun.compareAndSet(run, null);
        }

        finish(run, command, stdout.toString(), stderr.toString(), timedOut, failure,
            cwdResetWarning);
    }

    /** Record (or hand over) the run's transcript messages, then post its final UI hints. */
    private void finish(Run run, String command, String stdout, String stderr,
                        boolean didTimeOut, Throwable failure, String cwdResetWarning) {
        boolean diverted = appendHistoryEntries(run, command, stdout, stderr,
            run.interrupted, didTimeOut, failure);
        final boolean anyOutput = !stdout.isEmpty() || !stderr.isEmpty();
        final boolean didInterrupt = run.interrupted;
        final String finalCwdResetWarning = cwdResetWarning;
        gui.getGUIThread().invokeLater(() -> {
            if (run.displayWithdrawn) {
                // 236 appends only the input and the interruption to an idle transcript; a
                // resurrected result shows in the queue instead.
                if (!diverted) {
                    renderInputPill(command);
                    LanternaMessageDispatcher.renderInterruptedByUser(messagePanel);
                }
                return;
            }
            if (didInterrupt) {

                LanternaMessageDispatcher.renderInterruptedByUser(messagePanel);
            } else if (didTimeOut) {
                messagePanel.appendLine("  ⎿  Command timed out after "
                    + (BashTimeouts.defaultTimeoutMs() / 1000) + "s",
                    LanternaTheme.toolError());
            } else if (!anyOutput) {

                messagePanel.appendLine("    (No output)", LanternaTheme.welcomeDim());
            }
            if (finalCwdResetWarning != null) {
                messagePanel.appendLine("    " + finalCwdResetWarning,
                    LanternaTheme.welcomeDim());
            }
        });
    }

    static SudoCommandAdapter.Result prepareSudoCommand(
            String command, SudoPasswordInteraction interaction) {
        return SudoCommandAdapter.prepare(command, interaction);
    }

    private void finishRejected(String command, String message, Run run) {
        gui.getGUIThread().invokeLater(() ->
            messagePanel.appendLine("  ⎿  " + message, LanternaTheme.toolError()));
        appendHistoryEntries(run, command, "", message + "\n", false, false, null);
    }

    /** Opens a modal TUI image preview without executing a shell process. */
    @Explanation("Explicit ! imgcat commands preview local images inside the Lanterna alternate screen")
    private void executeImgcatPreview(String command, String cwd, Run run) {
        try {
            Path image = resolveImgcatPath(command, Path.of(cwd));
            if (!Files.isRegularFile(image)) {
                throw new IOException("Image file not found: " + image);
            }
            imagePreviewLauncher.show(image, () ->
                messagePanel.appendLine("  ⎿  Image preview closed",
                    LanternaTheme.welcomeDim()));
            appendHistoryEntries(run, command, "", "", false, false, null);
        } catch (IOException | IllegalArgumentException failure) {
            appendHistoryEntries(run, command, "", "", false, false, failure);
            gui.getGUIThread().invokeLater(() -> messagePanel.appendLine(
                "  ⎿  Failed to preview image: " + failureMessage(failure),
                LanternaTheme.toolError()));
        }
    }

    static boolean isImgcatCommand(String command) {
        return command != null && IMGCAT_COMMAND.matcher(command).find();
    }

    static Path resolveImgcatPath(String command, Path cwd) {
        List<ShellQuoteParse.Token> tokens = ShellQuoteParse.parse(command);
        int index = 0;
        if (tokens.size() > 1 && word(tokens.getFirst(), "command")) index++;
        if (index >= tokens.size() || !(tokens.get(index) instanceof ShellQuoteParse.Word(
            String value
        )) || !Strings.CS.equals("imgcat", basename(value))) {
            throw new IllegalArgumentException("Expected: imgcat <image-path>");
        }
        index++;
        if (index < tokens.size() && word(tokens.get(index), "--")) index++;
        if (index < tokens.size() && tokens.get(index) instanceof ShellQuoteParse.Op(String op)
                && Strings.CS.equals("<", op)) index++;
        if (index + 1 != tokens.size()
                || !(tokens.get(index) instanceof ShellQuoteParse.Word(String raw))) {
            throw new IllegalArgumentException("imgcat preview accepts exactly one image path");
        }
      if (Strings.CS.equals("~", raw)) {
            raw = System.getProperty("user.home");
        } else if (Strings.CS.startsWith(raw, "~/")) {
            raw = Path.of(System.getProperty("user.home"), raw.substring(2)).toString();
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) path = cwd.resolve(path);
        return path.toAbsolutePath().normalize();
    }

    private static boolean word(ShellQuoteParse.Token token, String expected) {
        return token instanceof ShellQuoteParse.Word(String value1)
            && Strings.CS.equals(expected, value1);
    }

    private static String basename(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return slash >= 0 ? executable.substring(slash + 1) : executable;
    }

    private static String failureMessage(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }


    private void streamOutput(Run run, InputStream stream, StringBuilder sink, TextColor color) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sink.append(line).append('\n');
                String formatted = ShellOutputFormatter.linkifyUrls(
                    ShellOutputFormatter.tryFormatJson(line));
                for (String subLine : formatted.split("\n", -1)) {
                    final String out = subLine;
                    gui.getGUIThread().invokeLater(() -> {
                        if (!run.displayWithdrawn) messagePanel.appendLine("    " + out, color);
                    });
                }
            }
        } catch (IOException _) {}
    }


    /** @return whether the caller took the messages over instead of the conversation */
    private boolean appendHistoryEntries(Run run, String command,
                                       String stdout, String stderr,
                                       boolean wasInterrupted,
                                       boolean wasTimeout,
                                       Throwable failure) {
        List<UserMessage> entries = historyEntries(
            command, stdout, stderr, wasInterrupted, wasTimeout, failure);
        if (run.divertResult.test(entries)) return true;
        List<Message> history = queryEngine.conversation().getMessages();
        synchronized (history) {
            history.addAll(entries);
        }
        return false;
    }

    private List<UserMessage> historyEntries(String command,
                                             String stdout, String stderr,
                                             boolean wasInterrupted,
                                             boolean wasTimeout,
                                             Throwable failure) {
        String caveat = "<" + XmlConstants.LOCAL_COMMAND_CAVEAT_TAG + ">"
            + "Caveat: The messages below were generated by the user while running local commands. "
            + "DO NOT respond to these messages or otherwise consider them in your response "
            + "unless the user explicitly asks you to."
            + "</" + XmlConstants.LOCAL_COMMAND_CAVEAT_TAG + ">";
        UserMessage caveatMessage = MessageFactory.createUserMessage(caveat, true);
        UserMessage input = MessageFactory.createUserMessage(
            "<bash-input>" + command + "</bash-input>");

        if (wasInterrupted) {
            return List.of(caveatMessage, input, MessageFactory.createUserInterruptionMessage(false));
        }
        if (failure != null) {
            String msg = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return List.of(caveatMessage, input, MessageFactory.createUserMessage(
                "<bash-stderr>Command failed: " + XmlEscaper.escapeText(msg) + "</bash-stderr>"));
        }
        // Normal / timeout path: both tags.
        String stderrEscaped = XmlEscaper.escapeText(
            wasTimeout ? stderr + "Command timed out after "
                + (BashTimeouts.defaultTimeoutMs() / 1000) + "s\n"
                       : stderr);
        String stdoutForModel = persistIfLarge(stdout);
        return List.of(caveatMessage, input, MessageFactory.createUserMessage(
            "<bash-stdout>" + stdoutForModel + "</bash-stdout>"
                + "<bash-stderr>" + stderrEscaped + "</bash-stderr>"));
    }

    /** One {@link #handle} call: its process, interrupt flag, rendered rows, and result routing. */
    private static final class Run {
        private final Predicate<List<UserMessage>> divertResult;
        /** First message-panel row of this run and the panel epoch it is valid for. */
        private final int displayStart;
        private final long displayEpoch;
        private volatile Process process;
        private volatile boolean interrupted;
        private volatile boolean prompting;
        /** GUI-thread only: the run's rows were removed by {@link #interrupt}. */
        private boolean displayWithdrawn;

        private Run(Predicate<List<UserMessage>> divertResult, int displayStart,
                    long displayEpoch) {
            this.divertResult = divertResult;
            this.displayStart = displayStart;
            this.displayEpoch = displayEpoch;
        }
    }

    /**
     * If {@code stdout} exceeds {@link #MAX_INLINE_CHARS}, persist the full output to disk and return a
     * {@code <persisted-output>} preview wrapper.
     */
    private String persistIfLarge(String stdout) {
        if (stdout == null || stdout.length() <= MAX_INLINE_CHARS) return XmlEscaper.escapeText(stdout);

        try {
            String cwd = queryEngine.configuration().getConfig().workingDirectory();
            if (StringUtils.isBlank(cwd)) cwd = System.getProperty("user.dir");
            Path dir = sessions.toolResultsDirectory(cwd,
                queryEngine.conversation().sessionIdentity().get());
            Files.createDirectories(dir);
            String id = UUID.randomUUID().toString();
            Path file = dir.resolve(id + ".txt");
            Files.writeString(file, stdout);

            String preview = generatePreview(stdout);
            boolean hasMore = stdout.length() > preview.length();
            // Content of <persisted-output> is human-readable, not XML —

            // buildLargeToolResultMessage which does NOT escape either.
            return "<persisted-output>\n"
                + "Output too large (" + FormatUtils.formatFileSize(stdout.length()) + "). "
                + "Full output saved to: " + file + "\n\n"
                + "Preview (first " + FormatUtils.formatFileSize(PREVIEW_SIZE) + "):\n"
                + preview
                + (hasMore ? "\n...\n" : "\n")
                + "</persisted-output>";
        } catch (Exception _) {

            return XmlEscaper.escapeText(stdout);
        }
    }


    private static String generatePreview(String content) {
        if (content.length() <= PREVIEW_SIZE) return content;
        String truncated = content.substring(0, PREVIEW_SIZE);
        int lastNewline = truncated.lastIndexOf('\n');
        int cutPoint = lastNewline > PREVIEW_SIZE * 0.5 ? lastNewline : PREVIEW_SIZE;
        return content.substring(0, cutPoint);
    }

}
