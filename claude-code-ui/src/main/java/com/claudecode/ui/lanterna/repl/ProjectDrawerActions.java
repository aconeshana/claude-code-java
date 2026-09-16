package com.claudecode.ui.lanterna.repl;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.RetractedMessages;
import com.claudecode.ui.lanterna.features.projects.ProjectPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import java.util.List;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Row actions for the left-docked project drawer: resume, delete, and preview a session entry.
 * Disk I/O runs on virtual threads; every UI mutation hops back through {@code guiInvoker}.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code components/ProjectPanel/} — session drawer row actions (resume / delete /
 *       preview) wired from the drawer into the resume flow.</li>
 * </ul>
 */
final class ProjectDrawerActions {

    private static final Logger log = LoggerFactory.getLogger(ProjectDrawerActions.class);

    private final Consumer<Runnable> guiInvoker;
    private final SessionController sessions;
    private final CommandContext commandContext;
    private final InteractiveSessionPort interactiveSessions;
    private final ProjectPanel panel;
    private final ReplTranscriptSink transcript;

    ProjectDrawerActions(Consumer<Runnable> guiInvoker, SessionController sessions,
                         CommandContext commandContext, InteractiveSessionPort interactiveSessions,
                         ProjectPanel panel, ReplTranscriptSink transcript) {
        this.guiInvoker = guiInvoker;
        this.sessions = sessions;
        this.commandContext = commandContext;
        this.interactiveSessions = interactiveSessions;
        this.panel = panel;
        this.transcript = transcript;
    }

    void resume(ProjectCatalogPort.ProjectSessionEntry entry) {
        guiInvoker.accept(() -> {
            String targetCwd = StringUtils.isBlank(entry.cwd())
                ? commandContext.session().workingDirectory() : entry.cwd();
            sessions.resume(new ResumeRequest(
                entry.id(), entry.transcriptPath(), targetCwd,
                ResumeRequest.Entrypoint.SLASH_COMMAND_PICKER));
        });
    }

    /** Drawer delete — disk I/O off the GUI thread; the panel already removed the row. */
    void delete(ProjectCatalogPort.ProjectSessionEntry entry) {
        Thread.ofVirtual().name("project-panel-delete").start(() -> {
            try {
                InteractiveSessionPort.SessionEntry sessionEntry = new InteractiveSessionPort.SessionEntry(
                    entry.id(), entry.lastModified(), entry.createdAt(), entry.messageCount(),
                    entry.summary(), entry.gitBranch(), entry.cwd(), entry.tag(),
                    entry.transcriptPath(), null, entry.customTitle(), entry.fileSize(), false);
                boolean deleted = interactiveSessions.deleteSession(sessionEntry,
                    commandContext.session().workingDirectory());
                if (!deleted) {
                    guiInvoker.accept(() -> transcript.line(
                        "  Could not delete session " + entry.id(), LanternaTheme.welcomeDim()));
                }
            } catch (RuntimeException failure) {
                guiInvoker.accept(() -> transcript.line(
                    "  Failed to delete session " + entry.id() + ": " + failure.getMessage(),
                    LanternaTheme.welcomeDim()));
            }
        });
    }

    /** Drawer preview — transcript read off the GUI thread, replayed when it lands. */
    void preview(ProjectCatalogPort.ProjectSessionEntry entry) {
        Thread.ofVirtual().name("project-panel-preview").start(() -> {
            List<Message> messages;
            try {
                // Same filter the resume picker applies: retracted turns are not
                // part of the conversation the user would resume into.
                messages = RetractedMessages.filter(
                    interactiveSessions.readMessages(entry.transcriptPath()));
            } catch (RuntimeException failure) {
                log.warn("Failed to read the transcript for the drawer preview", failure);
                messages = null;   // null reports the failure to the panel
            }
            List<Message> result = messages;
            guiInvoker.accept(() -> panel.showPreviewMessages(entry, result));
        });
    }
}
