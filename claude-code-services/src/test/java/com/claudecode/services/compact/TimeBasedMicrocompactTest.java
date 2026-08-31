package com.claudecode.services.compact;

import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


class TimeBasedMicrocompactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final TimeBasedMcConfig ENABLED = new TimeBasedMcConfig(true, 60, 2);

    private final DefaultMicrocompactStrategy strategy = new DefaultMicrocompactStrategy();

    private AssistantMessage assistantWithToolUses(Instant timestamp, String... toolUseIds) {
        List<ContentBlock> blocks = new ArrayList<>();
        for (String id : toolUseIds) {
            blocks.add(new ToolUseBlock(id, "Bash", MAPPER.createObjectNode()));
        }
        return new AssistantMessage("asst-" + toolUseIds[0], AssistantContent.of(blocks),
            false, null, timestamp);
    }

    private UserMessage userWithToolResult(String toolUseId, String text) {
        ToolResultBlock tr = new ToolResultBlock(toolUseId, List.of(new TextBlock(text)), false);
        return new UserMessage("user-" + toolUseId, MessageContent.ofBlocks(List.of(tr)),
            false, false, null, MessageOrigin.TOOL_RESULT, null, NOW, null, null);
    }

    /** 3 tool rounds, last assistant message stamped {@code gap} before NOW. */
    private List<Message> conversation(Duration gap) {
        List<Message> messages = new ArrayList<>();
        messages.add(assistantWithToolUses(NOW.minus(gap).minusSeconds(120), "tu-1", "tu-2"));
        messages.add(userWithToolResult("tu-1", "result one"));
        messages.add(userWithToolResult("tu-2", "result two"));
        messages.add(assistantWithToolUses(NOW.minus(gap), "tu-3"));
        messages.add(userWithToolResult("tu-3", "result three"));
        return messages;
    }

    @Test
    void disabledConfig_doesNotTrigger() {
        assertNull(strategy.maybeTimeBasedMicrocompact(
            conversation(Duration.ofHours(2)), TimeBasedMcConfig.DEFAULTS, NOW));
    }

    @Test
    void gapUnderThreshold_doesNotTrigger() {
        assertNull(strategy.maybeTimeBasedMicrocompact(
            conversation(Duration.ofMinutes(30)), ENABLED, NOW));
    }

    @Test
    void noAssistantMessage_doesNotTrigger() {
        List<Message> messages = List.of(new UserMessage("u1", MessageContent.ofText("hi")));
        assertNull(strategy.maybeTimeBasedMicrocompact(messages, ENABLED, NOW));
    }

    @Test
    void assistantWithoutTimestamp_doesNotTrigger() {
        AssistantMessage noTs = new AssistantMessage("a1",
            AssistantContent.of(List.of(new TextBlock("hi"))), false, null, null);
        assertNull(strategy.maybeTimeBasedMicrocompact(List.of(noTs), ENABLED, NOW));
    }

    @Test
    void gapOverThreshold_clearsAllButMostRecentN() {
        MessageCompactor.MicrocompactResult result = strategy.maybeTimeBasedMicrocompact(
            conversation(Duration.ofHours(2)), ENABLED, NOW);

        assertNotNull(result, "2h gap > 60min threshold must trigger");
        // keepRecent=2 → tu-2/tu-3 kept, tu-1 (oldest) cleared.
        assertEquals(DefaultMicrocompactStrategy.TIME_BASED_MC_CLEARED_MESSAGE, toolResultText(result, "tu-1"));
        assertEquals("result two", toolResultText(result, "tu-2"));
        assertEquals("result three", toolResultText(result, "tu-3"));
    }

    @Test
    void keepRecentZero_floorsAtOne() {
        TimeBasedMcConfig keepZero = new TimeBasedMcConfig(true, 60, 0);
        MessageCompactor.MicrocompactResult result = strategy.maybeTimeBasedMicrocompact(
            conversation(Duration.ofHours(2)), keepZero, NOW);

        assertNotNull(result);
        // Floor at 1: the most recent (tu-3) survives even with keepRecent=0.
        assertEquals("result three", toolResultText(result, "tu-3"));
        assertEquals(DefaultMicrocompactStrategy.TIME_BASED_MC_CLEARED_MESSAGE, toolResultText(result, "tu-1"));
        assertEquals(DefaultMicrocompactStrategy.TIME_BASED_MC_CLEARED_MESSAGE, toolResultText(result, "tu-2"));
    }

    @Test
    void alreadyClearedResults_doNotRetrigger() {
        // The only clear-candidate (tu-1; keepRecent=2 keeps tu-2/tu-3) is
        // already cleared — charsSaved stays 0 and the trigger must return

        List<Message> messages = new ArrayList<>();
        messages.add(assistantWithToolUses(NOW.minus(Duration.ofHours(2)).minusSeconds(60), "tu-1", "tu-2", "tu-3"));
        messages.add(userWithToolResult("tu-1", DefaultMicrocompactStrategy.TIME_BASED_MC_CLEARED_MESSAGE));
        messages.add(userWithToolResult("tu-2", "kept"));
        messages.add(userWithToolResult("tu-3", "kept too"));
        messages.add(new AssistantMessage("asst-final",
            AssistantContent.of(List.of(new TextBlock("done"))), false, null,
            NOW.minus(Duration.ofHours(2))));

        assertNull(strategy.maybeTimeBasedMicrocompact(messages, ENABLED, NOW));
    }

    @Test
    void nonLivePath_neverTriggersTimeBased() {
        // apply(messages, false) must behave exactly like the legacy 1-arg
        // path — /context and manual /compact preprocessing never time-trigger.
        List<Message> messages = conversation(Duration.ofHours(2));
        MessageCompactor.MicrocompactResult viaFlag = strategy.apply(messages, false);
        MessageCompactor.MicrocompactResult legacy = strategy.apply(messages);
        assertEquals(legacy.messages(), viaFlag.messages());
        assertEquals("result one", toolResultText(viaFlag, "tu-1"),
            "short results stay untouched on the non-live path");
    }

    private static String toolResultText(MessageCompactor.MicrocompactResult result, String toolUseId) {
        for (Message msg : result.messages()) {
            if (msg instanceof UserMessage um && um.message() != null && um.message().blocks() != null) {
                for (ContentBlock block : um.message().blocks()) {
                    if (block instanceof ToolResultBlock tr && toolUseId.equals(tr.toolUseId())
                            && tr.content() != null && !tr.content().isEmpty()
                            && tr.content().getFirst() instanceof TextBlock tb) {
                        return tb.text();
                    }
                }
            }
        }
        return null;
    }
}
