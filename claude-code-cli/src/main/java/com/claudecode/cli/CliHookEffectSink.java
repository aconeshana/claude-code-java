package com.claudecode.cli;

import com.claudecode.commands.CommandRegistry;
import com.claudecode.core.engine.HookEffectSink;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.services.hooks.FileChangedHookWatcher;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CLI composition-root implementation of model-independent hook effects.
 */
final class CliHookEffectSink implements HookEffectSink, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(CliHookEffectSink.class);

    private final QuerySession session;
    private final TranscriptRecorder transcript;
    private final SkillLoader skills;
    private final FileChangedHookWatcher watcher;
    private final CliOutput errorOutput;
    private final CliOutput terminalOutput;
    private final boolean interactive;
    private final boolean streamJson;
    private final boolean terminalSequencesAllowed;
    private final CliSkillCommandSync skillCommandSync = new CliSkillCommandSync();
    private final AtomicReference<Consumer<String>> systemMessageUi = new AtomicReference<>();
    private final AtomicReference<Consumer<String>> titleUi = new AtomicReference<>();
    private final AtomicReference<CommandRegistry> commandRegistry = new AtomicReference<>();
    private final Object pendingUiLock = new Object();
    private final List<String> pendingUiMessages = new ArrayList<>();
    private String pendingUiTitle;

    CliHookEffectSink(QuerySession session, TranscriptRecorder transcript,
                      SkillLoader skills, FileChangedHookWatcher watcher,
                      CliOutput terminalOutput, CliOutput errorOutput,
                      boolean interactive, boolean streamJson) {
        this.session = Objects.requireNonNull(session);
        this.transcript = Objects.requireNonNull(transcript);
        this.skills = Objects.requireNonNull(skills);
        this.watcher = Objects.requireNonNull(watcher);
        this.terminalOutput = Objects.requireNonNull(terminalOutput);
        this.errorOutput = Objects.requireNonNull(errorOutput);
        this.interactive = interactive;
        this.streamJson = streamJson;
        this.terminalSequencesAllowed = interactive && !streamJson
            && System.console() != null;
        watcher.setDiagnosticSink(message -> showSystemMessage(
            "FileChanged", "FileChanged", message));
    }

    void bindUi(Consumer<String> messages, Consumer<String> titles) {
        systemMessageUi.set(messages);
        titleUi.set(titles);
        synchronized (pendingUiLock) {
            pendingUiMessages.forEach(messages);
            pendingUiMessages.clear();
            if (pendingUiTitle != null) titles.accept(pendingUiTitle);
            pendingUiTitle = null;
        }
    }

    void bindCommandRegistry(CommandRegistry registry) {
        commandRegistry.set(registry);
    }

    int syncSkillSnapshot(CommandRegistry registry, List<Skill> snapshot,
                          Path cwd) {
        return skillCommandSync.sync(registry, snapshot, cwd);
    }

    CliSkillCommandSync skillCommandSync() {
        return skillCommandSync;
    }

    @Override
    public void showSystemMessage(String event, String hookName, String message) {
        Consumer<String> ui = systemMessageUi.get();
        if (interactive && ui != null) {
            ui.accept(hookName + " says: " + message);
        } else if (interactive) {
            synchronized (pendingUiLock) {
                pendingUiMessages.add(hookName + " says: " + message);
            }
        } else if (!streamJson) {
            errorOutput.println(hookName + " says: " + message);
            errorOutput.flush();
        }
    }

    @Override
    public void showSuccessOutput(String event, String hookName, String output) {
        Consumer<String> ui = systemMessageUi.get();
        if (interactive && ui != null) ui.accept("[hook] " + output);
        else if (interactive) {
            synchronized (pendingUiLock) {
                pendingUiMessages.add("[hook] " + output);
            }
        } else if (!streamJson) {
            errorOutput.println(output);
            errorOutput.flush();
        }
    }

    @Override
    public void emitTerminalSequence(String sequence) {
        if (!terminalSequencesAllowed) {
            LOG.debug("Discarding hook terminalSequence outside an interactive TTY");
            return;
        }
        terminalOutput.print(sequence);
        terminalOutput.flush();
    }

    @Override
    public void applySessionTitle(String title) {
        transcript.recordSessionTitle(session.conversation().getSessionId(), title);
        Consumer<String> ui = titleUi.get();
        if (ui != null) ui.accept(title);
        else if (interactive) {
            synchronized (pendingUiLock) {
                pendingUiTitle = title;
            }
        }
    }

    @Override
    public void reloadSkills() {
        skills.invalidateCache();
        var snapshot = List.copyOf(skills.loadAll());
        CommandRegistry registry = commandRegistry.get();
        if (registry != null) {
            skillCommandSync.sync(registry, snapshot,
                Path.of(System.getProperty("user.dir")));
        }
    }

    @Override
    public void replaceWatchPaths(List<Path> paths) {
        watcher.replaceWatchPaths(paths);
    }

    @Override
    public void cwdChanged(Path oldCwd, Path newCwd) {
        watcher.rebase(newCwd);
    }

    @Override
    public void close() {
        watcher.close();
    }
}
