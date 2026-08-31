package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineFindUnresolvedToolUseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingClient.StreamingEvent> createStream(StreamingClient.StreamRequest request) {
            return Collections.emptyIterator();
        }
        @Override public String getModel() { return "test-model"; }
    };

    private static DefaultQuerySession newEngine() {
        return new DefaultQuerySession(QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build());
    }

    private static AssistantMessage assistantWithToolUse(String id, String name) {
        ToolUseBlock tub = new ToolUseBlock(id, name, MAPPER.createObjectNode());
        return new AssistantMessage("am-" + id, AssistantContent.of(List.of(tub)));
    }

    @Test
    void findsUnresolvedToolUse() {
        DefaultQuerySession engine = newEngine();
        engine.getMutableMessages().add(assistantWithToolUse("tu1", "Bash"));

        Optional<AssistantMessage> found = engine.findUnresolvedToolUse("tu1");

        assertTrue(found.isPresent());
        assertTrue(found.get().message().content().getFirst() instanceof ToolUseBlock tu && Strings.CS.equals("tu1", tu.id()));
    }

    @Test
    void unknownToolUseIdReturnsEmpty() {
        DefaultQuerySession engine = newEngine();
        engine.getMutableMessages().add(assistantWithToolUse("tu1", "Bash"));

        assertFalse(engine.findUnresolvedToolUse("does-not-exist").isPresent());
    }

    @Test
    void resolvedToolUseReturnsEmpty() {
        DefaultQuerySession engine = newEngine();
        engine.getMutableMessages().add(assistantWithToolUse("tu1", "Bash"));
        // A subsequent user message carrying the tool_result resolves it.
        engine.getMutableMessages().add(
            new UserMessage("u1",
                MessageContent.ofToolResult("tu1", List.of(), false)));

        assertFalse(engine.findUnresolvedToolUse("tu1").isPresent());
    }

    @Test
    void onlyMatchesRequestedToolUseId() {
        DefaultQuerySession engine = newEngine();
        engine.getMutableMessages().add(assistantWithToolUse("tu1", "Bash"));
        // A second tool_use in a different message does not satisfy tu1's resolution.
        AssistantMessage other = assistantWithToolUse("tu2", "Read");
        engine.getMutableMessages().add(other);

        assertTrue(engine.findUnresolvedToolUse("tu1").isPresent());
        assertTrue(engine.findUnresolvedToolUse("tu2").isPresent());
    }
}
