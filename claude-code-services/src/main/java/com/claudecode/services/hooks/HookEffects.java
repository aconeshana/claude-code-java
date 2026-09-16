package com.claudecode.services.hooks;

import com.claudecode.core.engine.HookEffectSink;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.HookNonBlockingErrorAttachment;
import com.claudecode.core.message.HookSystemMessageAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Every outward effect a hook can have beyond its own result: user/terminal
 * effects through the application-owned {@link HookEffectSink}, model wake-ups
 * through the session message queue, and attachment messages queued for
 * re-injection on the next turn.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — {@code systemMessage}, success-output
 *       display, and non-blocking hook error attachments.</li>
 *   <li>{@code src/utils/hooks.ts} — SessionStart {@code sessionTitle} /
 *       {@code reloadSkills} and {@code watchPaths} validation.</li>
 *   <li>{@code src/utils/hooks.ts} — {@code asyncRewake} task-notification
 *       enqueued when a Stop hook exits 2.</li>
 * </ul>
 */
public final class HookEffects {

    private volatile HookEffectSink sink = HookEffectSink.NOOP;
    private volatile MessageQueueManager messageQueue;
    private final ConcurrentLinkedQueue<Message> outbox = new ConcurrentLinkedQueue<>();

    HookEffects() {}

    /** Child agent dispatchers share the wake-up queue but never the UI sink or the outbox. */
    HookEffects forChild() {
        HookEffects child = new HookEffects();
        child.messageQueue = messageQueue;
        return child;
    }

    /** Installs the application-owned consumer for user/terminal/session effects. */
    public void setSink(HookEffectSink sink) {
        this.sink = sink == null ? HookEffectSink.NOOP : sink;
    }

    HookEffectSink sink() {
        return sink;
    }

    /**
     * Wires the per-session message queue so {@code asyncRewake} blocking errors (exit code 2)
     * can wake the model via {@code enqueuePendingNotification}.
     */
    public void setMessageQueue(MessageQueueManager messageQueue) {
        this.messageQueue = messageQueue;
    }

    /** Drains attachment messages queued since the previous call. */
    List<Message> drain() {
        List<Message> messages = new ArrayList<>();
        Message message;
        while ((message = outbox.poll()) != null) messages.add(message);
        return List.copyOf(messages);
    }

    void nonBlockingError(String hookName, String stderr, String stdout, String toolUseId,
                          String eventName, String command, long durationMs) {
        outbox.add(new AttachmentMessage(UUID.randomUUID().toString(),
            new HookNonBlockingErrorAttachment(hookName, stderr, stdout == null ? "" : stdout, 1,
                toolUseId, eventName, command, durationMs)));
    }

    /** Wakes the model with the Stop-hook blocking error of an {@code asyncRewake} hook. */
    void rewakeModel(String hookName, String body) {
        MessageQueueManager queue = messageQueue;
        if (queue == null) return;
        queue.enqueuePendingNotification(new QueuedCommand(
            MessageConstants.wrapInSystemReminder(
                "Stop hook blocking error from command \"" + hookName + "\": " + body),
            null, "task-notification", QueuePriority.LATER,
            false, null, false, false, null, null, null));
    }

    void publish(HookEvent event, HookInput input, List<HookExecution> executions) {
        HookEffectSink sink = this.sink;
        List<Path> watchPaths = new ArrayList<>();
        String sessionTitle = null;
        boolean reloadSkills = false;
        for (HookExecution execution : executions) {
            HookResult result = execution.result();
            if (result instanceof HookResult.Decorated(HookResult inner, HookResult.Effects effects)) {
                String hookName = HookOutcomes.commandText(execution.command());
                effects.systemMessage().filter(StringUtils::isNotBlank)
                    .ifPresent(message -> {
                        sink.showSystemMessage(event.displayName(), hookName, message);
                        outbox.add(new AttachmentMessage(UUID.randomUUID().toString(),
                            new HookSystemMessageAttachment(message, hookName,
                                input.toolUseId().orElseGet(() -> UUID.randomUUID().toString()),
                                event.displayName())));
                    });
                effects.successOutput().filter(StringUtils::isNotBlank)
                    .ifPresent(output -> sink.showSuccessOutput(
                        event.displayName(), hookName, output));
                effects.terminalSequence().ifPresent(sink::emitTerminalSequence);
                if (StringUtils.isNotBlank(effects.validationError())) {
                    effectError(event, input, execution.command(), effects.validationError());
                }
                result = inner;
            }
            if (!(result instanceof HookResult.Structured structured)
                    || structured.output() == null) continue;
            JsonNode output = structured.output();
            if (event == HookEvent.SESSION_START) {
                JsonNode title = output.get("sessionTitle");
                if (title != null && title.isTextual() && StringUtils.isNotBlank(title.asText())) {
                    sessionTitle = title.asText();
                }
                JsonNode reload = output.get("reloadSkills");
                reloadSkills |= reload != null && reload.isBoolean() && reload.asBoolean();
            }
            JsonNode paths = output.get("watchPaths");
            if (paths != null && !paths.isArray()) {
                continue;
            }
            if (paths != null) {
                collectWatchPaths(event, input, execution.command(), paths, watchPaths);
            }
        }
        String source = String.valueOf(input.extra().getOrDefault("source", ""));
        if (event == HookEvent.SESSION_START
                && Strings.CS.equalsAny(source, "startup", "resume")
                && StringUtils.isNotBlank(sessionTitle)) {
            sink.applySessionTitle(sessionTitle);
        }
        if (event == HookEvent.SESSION_START && reloadSkills) sink.reloadSkills();
        if (event == HookEvent.SESSION_START || event == HookEvent.CWD_CHANGED
                || event == HookEvent.FILE_CHANGED) {
            sink.replaceWatchPaths(List.copyOf(watchPaths));
        }
    }

    private void collectWatchPaths(HookEvent event, HookInput input, HookCommand command,
                                   JsonNode paths, List<Path> watchPaths) {
        for (JsonNode path : paths) {
            if (!path.isTextual()) {
                effectError(event, input, command, "watchPaths entries must be strings");
                continue;
            }
            String raw = path.asText();
            if (Strings.CS.startsWith(raw, "//") || Strings.CS.startsWith(raw, "\\\\")) {
                effectError(event, input, command, "watchPaths must not use remote UNC paths: " + raw);
                continue;
            }
            try {
                Path candidate = Path.of(raw);
                if (!candidate.isAbsolute()) {
                    effectError(event, input, command, "watchPaths must be absolute: " + raw);
                } else if (!watchPaths.contains(candidate.normalize())) {
                    watchPaths.add(candidate.normalize());
                }
            } catch (RuntimeException _) {
                effectError(event, input, command, "invalid watch path: " + raw);
            }
        }
    }

    private void effectError(HookEvent event, HookInput input, HookCommand command, String error) {
        nonBlockingError(event.displayName(), error, "",
            input.toolUseId().orElseGet(() -> UUID.randomUUID().toString()),
            event.displayName(), HookOutcomes.commandText(command), 0L);
    }
}
