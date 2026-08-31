package com.claudecode.ui.lanterna.bashmode;

import com.claudecode.commands.XmlConstants;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.process.ProcessTreeTerminator;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.text.XmlEscaper;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.ui.lanterna.repl.InteractiveSessionPort;
import com.claudecode.tools.bash.BashTimeouts;
import com.claudecode.tools.bash.ShellQuoteParse;
import com.claudecode.tools.bash.ShellWorkingDirectoryTracker;
import com.claudecode.tools.bash.SudoCommandAdapter;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.ui.lanterna.transcript.ShellOutputFormatter;
import com.googlecode.lanterna.TextColor;
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
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.LoggerFactory;

/**
 * Local shell execution for bash mode (input prefixed with {@code !}).
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
    private final AtomicReference<Process> currentProcess = new AtomicReference<>(null);
/** Set once {@link #interrupt} is called for the current execution;
     *  the executor branch treats stdout/stderr as user-interrupted. */
    private volatile boolean interrupted = false;

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
    public boolean isRunning() { return currentProcess.get() != null; }

    /**
     * Cancel the in-flight shell command. Called from the Ctrl+C signal
     * handler; kills the process. The runner thread's normal completion
     * path handles the interruption message.
     */
    public void interrupt() {
        Process p = currentProcess.get();
        if (p == null) return;
        try {
            ProcessTreeTerminator.terminate(p, Duration.ofMillis(250));
            interrupted = true;
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
        if (StringUtils.isBlank(command)) return;

// Render user-visible bash-input pill.
        messagePanel.appendLine("", TextColor.ANSI.DEFAULT);
        messagePanel.appendMixed(List.of(
            new MessagePanel.Segment("! ",    LanternaTheme.bashBorder(), LanternaTheme.bashBg()),
            new MessagePanel.Segment(command, LanternaTheme.inputText(),  LanternaTheme.bashBg()),
            new MessagePanel.Segment(" ",     LanternaTheme.inputText(),  LanternaTheme.bashBg())
        ));

        Thread.ofVirtual().name("bash-mode-" + System.currentTimeMillis())
            .start(() -> execute(command));
    }

    private void execute(String command) {

        String cwd = queryEngine.configuration().getConfig().workingDirectory();
        if (StringUtils.isBlank(cwd)) cwd = System.getProperty("user.dir");

        if (isImgcatCommand(command)) {
            executeImgcatPreview(command, cwd);
            return;
        }

        SudoCommandAdapter.Result sudoResult =
            prepareSudoCommand(command, sudoPasswordInteraction);
        if (sudoResult instanceof SudoCommandAdapter.Result.Rejected rejected) {
            finishRejected(command, rejected.message());
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
        interrupted = false;

        try {
            cwdTracker = ShellWorkingDirectoryTracker.start(
                Path.of(cwd), queryEngine.configuration().workingDirectoryController(),
                SubprocessEnvironment::get);
            ProcessBuilder pb = new ProcessBuilder(
                ExecutableFinder.bashExecutable(), "-c",
                cwdTracker.wrap(commandToExecute));
            if (Platform.IS_WINDOWS) {
                pb.environment().put("SHELL", ExecutableFinder.bashExecutable());
            }
            pb.directory(Path.of(cwd).toFile());
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            currentProcess.set(proc);
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
                () -> streamOutput(proc.getInputStream(), stdout, TextColor.ANSI.DEFAULT));
            Thread stderrReader = Thread.ofVirtual().start(
                () -> streamOutput(proc.getErrorStream(), stderr, LanternaTheme.toolError()));

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
            currentProcess.set(null);
        }

        // Show final UI hints — must happen on GUI thread.
        final boolean anyOutput = !stdout.isEmpty() || !stderr.isEmpty();
        final boolean didTimeOut = timedOut;
        final boolean didInterrupt = interrupted;
        final String finalCwdResetWarning = cwdResetWarning;
        gui.getGUIThread().invokeLater(() -> {
            if (didInterrupt) {

                messagePanel.appendLine("  ⎿  " + MessageConstants.INTERRUPT_MESSAGE,
                    LanternaTheme.toolError());
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

        appendHistoryEntries(command, stdout.toString(), stderr.toString(),
            interrupted, timedOut, failure);
    }

    static SudoCommandAdapter.Result prepareSudoCommand(
            String command, SudoPasswordInteraction interaction) {
        return SudoCommandAdapter.prepare(command, interaction);
    }

    private void finishRejected(String command, String message) {
        gui.getGUIThread().invokeLater(() ->
            messagePanel.appendLine("  ⎿  " + message, LanternaTheme.toolError()));
        appendHistoryEntries(command, "", message + "\n", false, false, null);
    }

    /** Opens a modal TUI image preview without executing a shell process. */
    @Explanation("Explicit ! imgcat commands preview local images inside the Lanterna alternate screen")
    private void executeImgcatPreview(String command, String cwd) {
        try {
            Path image = resolveImgcatPath(command, Path.of(cwd));
            if (!Files.isRegularFile(image)) {
                throw new IOException("Image file not found: " + image);
            }
            imagePreviewLauncher.show(image, () ->
                messagePanel.appendLine("  ⎿  Image preview closed",
                    LanternaTheme.welcomeDim()));
            appendHistoryEntries(command, "", "", false, false, null);
        } catch (IOException | IllegalArgumentException failure) {
            appendHistoryEntries(command, "", "", false, false, failure);
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
        if (index >= tokens.size() || !(tokens.get(index) instanceof ShellQuoteParse.Word executable)
                || !Strings.CS.equals("imgcat", basename(executable.value()))) {
            throw new IllegalArgumentException("Expected: imgcat <image-path>");
        }
        index++;
        if (index < tokens.size() && word(tokens.get(index), "--")) index++;
        if (index < tokens.size() && tokens.get(index) instanceof ShellQuoteParse.Op operator
                && Strings.CS.equals("<", operator.op())) index++;
        if (index + 1 != tokens.size()
                || !(tokens.get(index) instanceof ShellQuoteParse.Word imagePath)) {
            throw new IllegalArgumentException("imgcat preview accepts exactly one image path");
        }
        String raw = imagePath.value();
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
        return token instanceof ShellQuoteParse.Word value
            && Strings.CS.equals(expected, value.value());
    }

    private static String basename(String executable) {
        int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        return slash >= 0 ? executable.substring(slash + 1) : executable;
    }

    private static String failureMessage(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.toString();
    }


    private void streamOutput(InputStream stream, StringBuilder sink, TextColor color) {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sink.append(line).append('\n');
                String formatted = ShellOutputFormatter.linkifyUrls(
                    ShellOutputFormatter.tryFormatJson(line));
                for (String subLine : formatted.split("\n", -1)) {
                    final String out = subLine;
                    gui.getGUIThread().invokeLater(() ->
                        messagePanel.appendLine("    " + out, color));
                }
            }
        } catch (IOException _) {}
    }


    private void appendHistoryEntries(String command,
                                       String stdout, String stderr,
                                       boolean wasInterrupted,
                                       boolean wasTimeout,
                                       Throwable failure) {
        List<Message> history = queryEngine.conversation().getMessages();
        String caveat = "<" + XmlConstants.LOCAL_COMMAND_CAVEAT_TAG + ">"
            + "Caveat: The messages below were generated by the user while running local commands. "
            + "DO NOT respond to these messages or otherwise consider them in your response "
            + "unless the user explicitly asks you to."
            + "</" + XmlConstants.LOCAL_COMMAND_CAVEAT_TAG + ">";

        synchronized (history) {
            history.add(MessageFactory.createUserMessage(caveat, true));

            history.add(MessageFactory.createUserMessage(
                "<bash-input>" + command + "</bash-input>"));

            if (wasInterrupted) {

                history.add(MessageFactory.createUserInterruptionMessage(false));
                return;
            }
            if (failure != null) {

                String msg = failure.getMessage() == null ? failure.toString() : failure.getMessage();
                history.add(MessageFactory.createUserMessage(
                    "<bash-stderr>Command failed: " + XmlEscaper.escapeText(msg) + "</bash-stderr>"));
                return;
            }
            // Normal / timeout path: both tags.
            String stderrEscaped = XmlEscaper.escapeText(
                wasTimeout ? stderr + "Command timed out after "
                    + (BashTimeouts.defaultTimeoutMs() / 1000) + "s\n"
                           : stderr);
            String stdoutForModel = persistIfLarge(stdout);
            history.add(MessageFactory.createUserMessage(
                "<bash-stdout>" + stdoutForModel + "</bash-stdout>"
                    + "<bash-stderr>" + stderrEscaped + "</bash-stderr>"));
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
