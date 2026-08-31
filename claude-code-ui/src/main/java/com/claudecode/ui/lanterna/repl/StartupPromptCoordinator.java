package com.claudecode.ui.lanterna.repl;

import java.util.Objects;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
/**
 * One-shot handshake that runs the startup actions an argv launch asked for — a target-less {@code
 * -r} picker, a positional prompt, or both — only after the REPL is safe to accept them.
 */
final class StartupPromptCoordinator {

    private final String prompt;
    private final Consumer<Runnable> resumePickerLauncher;
    private final Consumer<Runnable> uiScheduler;
    private final Consumer<String> submitter;
    private boolean setupReady;
    private boolean transcriptReady;
    private boolean submitted;

    StartupPromptCoordinator(String prompt,
                             Consumer<Runnable> uiScheduler,
                             Consumer<String> submitter) {
        this(prompt, null, uiScheduler, submitter);
    }

    /**
     * @param resumePickerLauncher opens the session picker and runs the supplied continuation
     *                             once the picker has settled; {@code null} when argv did not
     *                             request one
     */
    StartupPromptCoordinator(String prompt,
                             Consumer<Runnable> resumePickerLauncher,
                             Consumer<Runnable> uiScheduler,
                             Consumer<String> submitter) {
        this.prompt = prompt;
        this.resumePickerLauncher = resumePickerLauncher;
        this.uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    void markSetupReady() {
        scheduleIfReady(markReady(true));
    }

    void markTranscriptReady() {
        scheduleIfReady(markReady(false));
    }

    private synchronized Runnable markReady(boolean setup) {
        if (setup) setupReady = true;
        else transcriptReady = true;
        boolean hasPrompt = StringUtils.isNotBlank(prompt);
        if (submitted || !setupReady || !transcriptReady
                || (!hasPrompt && resumePickerLauncher == null)) {
            return null;
        }
        submitted = true;
        Runnable submission = hasPrompt ? () -> submitter.accept(prompt) : () -> { };
        return resumePickerLauncher == null
            ? submission
            : () -> resumePickerLauncher.accept(submission);
    }

    private void scheduleIfReady(Runnable task) {
        if (task != null) uiScheduler.accept(task);
    }
}
