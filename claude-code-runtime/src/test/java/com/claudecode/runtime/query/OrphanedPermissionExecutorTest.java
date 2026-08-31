package com.claudecode.runtime.query;

import com.claudecode.core.engine.OrphanedPermission;
import com.claudecode.core.engine.StreamingClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.ToolUseBlock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;


class OrphanedPermissionExecutorTest {

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

    private static OrphanedPermission denyResult(String toolUseId, boolean interrupt) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("behavior", "deny");
        result.put("message", "denied by controller");
        result.put("toolUseID", toolUseId);
        if (interrupt) result.put("interrupt", true);
        return new OrphanedPermission(toolUseId, result);
    }

    @Test
    void denyWithInterruptAbortsAfterReplay() {
        DefaultQuerySession engine = newEngine();
        engine.getMutableMessages().add(assistantWithToolUse("tuX", "Bash"));

        // Sanity: not aborted before replay.
        assertFalse(engine.getAbortController().isAborted());

        List<SDKMessage> emitted = new ArrayList<>();
        OrphanedPermissionExecutor.execute(denyResult("tuX", true), engine, emitted::add);

        // Rejection tool_result must have been emitted, then the session aborted.
        // Rejection tool_result (emitted as a User message) must have been emitted,
        // then the session aborted.
        assertTrue(emitted.stream().anyMatch(SDKMessage.User.class::isInstance),
            "deny replay must emit a rejection tool_result");
        assertTrue(engine.getAbortController().isAborted(),
            "deny+interrupt must abort the engine after the tool_result is emitted");
    }

    @Test
    void denyWithoutInterruptKeepsSessionAlive() {
        DefaultQuerySession engine = newEngine();
        engine.getMutableMessages().add(assistantWithToolUse("tuY", "Bash"));

        List<SDKMessage> emitted = new ArrayList<>();
        OrphanedPermissionExecutor.execute(denyResult("tuY", false), engine, emitted::add);

        // Rejection tool_result (emitted as a User message) must have been emitted.
        assertTrue(emitted.stream().anyMatch(SDKMessage.User.class::isInstance),
            "deny replay must emit a rejection tool_result");
        assertFalse(engine.getAbortController().isAborted(),
            "deny without interrupt must NOT abort the engine (model continues)");
    }
}
