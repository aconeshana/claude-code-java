package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import java.util.*;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Renders a persisted transcript onto a {@link MessagePanel} through the same {@link
 * MessageCollapser}/{@link LanternaMessageDispatcher} pipeline that live streaming uses, so a
 * replayed conversation is indistinguishable from the view it had while it was streaming.
 */
public final class TranscriptReplay {

    private TranscriptReplay() {}

    /** Loads sidechain-owned messages for one stable Agent invocation id. */
    @FunctionalInterface
    public interface AgentReplaySource {
        List<Message> load(String agentId);
    }

    /**
     * Replay {@code msgs} into {@code panel}. {@code recorder} receives every
     * wrapped {@link SDKMessage} in transcript order — the REPL passes
     * {@code MessageHistory::record} so {@code Ctrl+O}'s overlay can
     * re-dispatch the same events; the preview passes {@code null} because its
     * panel is discarded when the dialog closes.
     */
    public static void replay(List<Message> msgs, MessageCollapser collapser,
                              MessagePanel panel, Consumer<SDKMessage> recorder) {
        replay(msgs, collapser, panel, recorder, null);
    }

    public static void replay(List<Message> msgs, MessageCollapser collapser,
                              MessagePanel panel, Consumer<SDKMessage> recorder,
                              AgentReplaySource agentReplaySource) {
        if (msgs == null || msgs.isEmpty()) return;
        collapser.resetTurn();
        Map<String, AgentUse> agentUses = new HashMap<>();
        Set<String> hydratedAgentUses = new HashSet<>();
        for (Message m : msgs) {
            rememberAgentUses(m, agentUses);
            if (m instanceof UserMessage um && isInterruptMessage(um)) {
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(Figures.RESULT_PREFIX, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Interrupted ", LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("· What should Claude do instead?", LanternaTheme.welcomeDim())
                ));
                if (recorder != null) recorder.accept(new SDKMessage.User(um));
                continue;
            }
            for (SDKMessage.Progress progress : hydrateAgentProgress(
                    m, agentUses, hydratedAgentUses, agentReplaySource)) {
                if (recorder != null) recorder.accept(progress);
                collapser.dispatch(progress, panel);
            }
            SDKMessage sdk = switch (m) {
                case UserMessage um       -> new SDKMessage.User(um);
                case AssistantMessage am  -> new SDKMessage.Assistant(am, Usage.EMPTY);
                case SystemMessage sm     -> new SDKMessage.System(sm);
                default                   -> null;
            };
            if (sdk == null) continue;
            if (recorder != null) recorder.accept(sdk);
            collapser.dispatch(sdk, panel);
        }
        // Flush any pending tool-result buffer left over from the final turn —
        // otherwise a trailing tool-use would sit invisibly in the collapser.
        collapser.resetTurn();
    }

    private record AgentUse(String prompt) {}

    private static void rememberAgentUses(Message message, Map<String, AgentUse> uses) {
        if (!(message instanceof AssistantMessage assistant)
                || assistant.message() == null || assistant.message().content() == null) {
            return;
        }
        for (var block : assistant.message().content()) {
            if (!(block instanceof ToolUseBlock use)
                    || !Strings.CS.equals("Agent", use.name()) || use.id() == null) {
                continue;
            }
            String prompt = use.input() == null ? null : use.input().path("prompt").asText(null);
            uses.put(use.id(), new AgentUse(prompt));
        }
    }

    private static List<SDKMessage.Progress> hydrateAgentProgress(Message message,
            Map<String, AgentUse> uses, Set<String> hydrated,
            AgentReplaySource source) {
        if (source == null || !(message instanceof UserMessage user)
                || user.message() == null || user.message().blocks() == null) {
            return List.of();
        }
        var metadata = user.toolUseResult() == null ? null
            : JsonUtils.getMapper().valueToTree(user.toolUseResult());
        if (metadata == null || !metadata.isObject()) return List.of();
        String agentId = metadata.path("agentId").asText(null);
        if (StringUtils.isBlank(agentId)) return List.of();

        List<SDKMessage.Progress> result = new ArrayList<>();
        for (var block : user.message().blocks()) {
            if (!(block instanceof ToolResultBlock toolResult)
                    || toolResult.toolUseId() == null
                    || !uses.containsKey(toolResult.toolUseId())
                    || !hydrated.add(toolResult.toolUseId())) {
                continue;
            }
            List<Message> children;
            try {
                children = source.load(agentId);
            } catch (RuntimeException _) {
                children = List.of();
            }
            if (children == null || children.isEmpty()) continue;
            String prompt = metadata.path("prompt").asText(
                uses.get(toolResult.toolUseId()).prompt());
            for (Message child : children) {
                if (child instanceof UserMessage childUser
                        && !containsToolResult(childUser)) {
                    continue;
                }
                var data = new ProgressMessage.ProgressData(
                    "agent_progress", null, null, null, null, null, null, null,
                    false, child, prompt, agentId);
                result.add(new SDKMessage.Progress(MessageFactory.createProgressMessage(
                    toolResult.toolUseId(), null, data)));
            }
        }
        return List.copyOf(result);
    }

    private static boolean containsToolResult(UserMessage user) {
        return user.message() != null && user.message().blocks() != null
            && user.message().blocks().stream().anyMatch(ToolResultBlock.class::isInstance);
    }

    /** True when the user message is exactly the interrupt sentinel text. */
    public static boolean isInterruptMessage(UserMessage um) {
        MessageContent c = um.message();
        if (c == null) return false;
        String text = c.text();
        if (text == null && c.blocks() != null && c.blocks().size() == 1
                && c.blocks().getFirst() instanceof TextBlock(String text1)) {
            text = text1;
        }
        return MessageConstants.INTERRUPT_MESSAGE.equals(text)
            || MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text);
    }
}
