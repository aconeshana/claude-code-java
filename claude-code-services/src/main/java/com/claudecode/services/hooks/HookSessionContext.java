package com.claudecode.services.hooks;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Who and where am I" for hook input construction: session id, working
 * directory, live permission mode, current prompt id, and the conversation
 * view used by Stop-family hooks. One instance per dispatcher; a child agent
 * dispatcher gets its own scoped copy.
 *
 * <ul>
 *   <li>{@code src/utils/hooks.ts} — the common {@code session_id}, {@code cwd},
 *       {@code permission_mode} fields of every hook input.</li>
 *   <li>{@code src/utils/hooks.ts} — {@code last_assistant_message} extraction
 *       for Stop / StopFailure inputs.</li>
 *   <li>{@code src/utils/hooks/registerFrontmatterHooks.ts} — the
 *       {@code agent_id} / {@code agent_type} / transcript scope of a child
 *       agent's SubagentStop input.</li>
 * </ul>
 */
public final class HookSessionContext {

    private static final Logger LOG = LoggerFactory.getLogger(HookSessionContext.class);

    /** Non-null only on a context scoped to one child agent invocation. */
    record SubAgentScope(
        String agentId,
        String agentType,
        String agentTranscriptPath,
        String permissionMode,
        String effort
    ) {}

    /**
     * Shared session-id holder — included in every hook input as {@code session_id}. Composition
     * roots that also construct a {@code QuerySession} for the same session pass its
     * {@code sessionIdentity()} so a single {@code switchToSession} call is visible to both.
     */
    private final SessionIdentity sessionIdentity;
    /**
     * When non-null, hooks always run in (and report) this fixed directory — used by tests that pin
     * a temp dir and by child agent dispatchers. {@code null} follows the live JVM cwd.
     */
    private final String fixedWorkingDirectory;
    private final SubAgentScope subAgent;

    private volatile String permissionMode;
    private volatile Supplier<String> permissionModeSupplier = () -> permissionMode;
    private volatile Supplier<String> promptIdSupplier = () -> null;
    private volatile Supplier<List<Message>> messagesSupplier;

    HookSessionContext(SessionIdentity sessionIdentity, String fixedWorkingDirectory) {
        this(sessionIdentity, fixedWorkingDirectory, null);
    }

    private HookSessionContext(SessionIdentity sessionIdentity, String fixedWorkingDirectory,
                               SubAgentScope subAgent) {
        this.sessionIdentity = sessionIdentity != null ? sessionIdentity : SessionIdentity.newRandom();
        this.fixedWorkingDirectory = fixedWorkingDirectory;
        this.subAgent = subAgent;
    }

    HookSessionContext forChild(SubAgentLifecycleListener.SubAgentHookContext context) {
        HookSessionContext child = new HookSessionContext(sessionIdentity, context.workingDirectory(),
            new SubAgentScope(context.agentId(), context.agentType(), context.agentTranscriptPath(),
                context.permissionMode(), context.effort()));
        child.permissionMode = context.permissionMode();
        child.permissionModeSupplier = context::permissionMode;
        child.messagesSupplier = context.messagesSupplier();
        child.promptIdSupplier = context.promptIdSupplier();
        return child;
    }

    public String sessionId() {
        return sessionIdentity.get();
    }

    /** The directory hooks run in and report in their {@code cwd} JSON field. */
    public String cwd() {
        return fixedWorkingDirectory != null ? fixedWorkingDirectory : System.getProperty("user.dir");
    }

    SubAgentScope subAgent() {
        return subAgent;
    }

    /** Live permission-mode source so UI/control-channel changes reach hook input. */
    public void bindPermissionMode(Supplier<String> supplier) {
        this.permissionModeSupplier = supplier != null ? supplier : () -> permissionMode;
    }

    /** Fixed permission mode used when no live supplier is bound. */
    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    public String permissionMode() {
        try {
            return permissionModeSupplier.get();
        } catch (RuntimeException _) {
            return permissionMode;
        }
    }

    /** Wires the active queue turn's prompt id (UserPromptSubmit, Stop, PreCompact inputs). */
    public void bindPromptId(Supplier<String> supplier) {
        this.promptIdSupplier = supplier != null ? supplier : () -> null;
    }

    String promptId() {
        return promptIdSupplier.get();
    }

    /**
     * Wires the live conversation view so Stop / StopFailure hook inputs can carry
     * {@code last_assistant_message} and the goal evaluator can read the transcript.
     * Typically {@code queryEngine::getMessages}.
     */
    public void bindMessages(Supplier<List<Message>> supplier) {
        this.messagesSupplier = supplier;
    }

    /** Immutable copy of the live conversation, or empty when unavailable. */
    List<Message> transcript() {
        List<Message> messages;
        try {
            messages = messagesSupplier != null ? messagesSupplier.get() : List.of();
        } catch (Throwable _) {
            messages = List.of();
        }
        return messages == null ? List.of() : List.copyOf(messages);
    }

    /** Text of the last assistant message, or {@code null} when unavailable. */
    String lastAssistantText() {
        try {
            if (messagesSupplier == null) return null;
            List<Message> messages = messagesSupplier.get();
            if (messages == null) return null;
            var last = MessageConstants.getLastAssistantMessage(messages);
            if (last == null || last.message() == null || last.message().content() == null) return null;
            String text = MessageConstants.extractTextContent(last.message().content(), "\n").trim();
            return text.isEmpty() ? null : text;
        } catch (Throwable t) {
            LOG.debug("last_assistant_message extraction failed: {}", t.getMessage());
            return null;
        }
    }
}
