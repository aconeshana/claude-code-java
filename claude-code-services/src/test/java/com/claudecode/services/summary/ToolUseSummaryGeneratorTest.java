package com.claudecode.services.summary;

import org.apache.commons.lang3.Strings;

import com.claudecode.api.*;
import com.claudecode.core.engine.ToolCallInfo;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;


class ToolUseSummaryGeneratorTest {

    /** Test double for {@link LlmClient} — records the user prompt it was called with. */
    private static final class StubLlmClient implements LlmClient {
        volatile String response;
        final List<String> systemPrompts = new ArrayList<>();
        final List<String> userPrompts = new ArrayList<>();

        StubLlmClient(String response) {
            this.response = response;
        }

        @Override
        public Iterator<StreamEvent> createMessageStream(CreateMessageRequest request) {
            systemPrompts.add(request.systemPrompt());
            userPrompts.add((String) request.messages().getFirst().content());
            if (response == null) return List.<StreamEvent>of(new StreamEvent.Ping()).iterator();
            return List.<StreamEvent>of(
                new StreamEvent.MessageStart(ApiMessage.builder()
                    .id("msg-summary").model(request.model()).usage(Usage.EMPTY).build()),
                new StreamEvent.ContentBlockStart(0, new TextBlock("")),
                new StreamEvent.ContentBlockDelta(0, new Delta.TextDelta(response)),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageDelta(
                    new MessageDeltaData("end_turn", null), Usage.EMPTY),
                new StreamEvent.MessageStop()).iterator();
        }

        @Override
        public ApiMessage createMessage(CreateMessageRequest request) {
            systemPrompts.add(request.systemPrompt());
            userPrompts.add((String) request.messages().getFirst().content());
            if (response == null) return null;
            return ApiMessage.stub(request.model(), response);
        }

        @Override
        public String getModel() {
            return "stub-model";
        }
    }

    @Test
    void emptyToolListReturnsNullWithoutCallingLlm() throws Exception {
        StubLlmClient client = new StubLlmClient("Fixed NPE in UserService");
        var gen = new ToolUseSummaryGenerator(client);

        String result = gen.summarizeAsync(List.of(), null, false).get();

        assertNull(result);
        assertTrue(client.userPrompts.isEmpty());
    }

    @Test
    void generatesSummaryFromToolBatch() throws Exception {
        StubLlmClient client = new StubLlmClient("Fixed NPE in UserService");
        var gen = new ToolUseSummaryGenerator(client);

        List<ToolCallInfo> tools = List.of(
            new ToolCallInfo("FileEditTool", "{\"file_path\":\"UserService.java\"}", "OK"));

        String result = gen.summarizeAsync(tools, null, false).get();

        assertEquals("Fixed NPE in UserService", result);
        assertEquals(1, client.userPrompts.size());
        String prompt = client.userPrompts.getFirst();
        assertTrue(Strings.CS.contains(prompt, "Tool: FileEditTool"));
        assertTrue(Strings.CS.contains(prompt, "Input:"));
        assertTrue(Strings.CS.contains(prompt, "Output:"));
        assertTrue(Strings.CS.endsWith(prompt, "Label:"));

        assertEquals(1, client.systemPrompts.size());
        assertTrue(Strings.CS.contains(client.systemPrompts.getFirst(), "git-commit-subject, not sentence"));
        assertTrue(Strings.CS.contains(client.systemPrompts.getFirst(), "- Fixed NPE in UserService"));
    }

    @Test
    void prependsLastAssistantTextAsIntentContext() throws Exception {
        StubLlmClient client = new StubLlmClient("Searched in auth/");
        var gen = new ToolUseSummaryGenerator(client);

        List<ToolCallInfo> tools = List.of(new ToolCallInfo("GrepTool", "{\"pattern\":\"login\"}", "3 matches"));

        gen.summarizeAsync(tools, "Let me find the login handler", false).get();

        String prompt = client.userPrompts.getFirst();
        assertTrue(Strings.CS.startsWith(prompt, """
            User's intent (from assistant's last message): \
            Let me find the login handler

            """));
    }

    @Test
    void truncatesOverlongAssistantTextTo200Chars() throws Exception {
        StubLlmClient client = new StubLlmClient("Ran failing tests");
        var gen = new ToolUseSummaryGenerator(client);
        String longText = "x".repeat(500);

        gen.summarizeAsync(List.of(new ToolCallInfo("Bash", "{}", "ok")), longText, false).get();

        String prompt = client.userPrompts.getFirst();
        assertTrue(Strings.CS.contains(prompt, "x".repeat(200) + "\n\n"));
        assertFalse(Strings.CS.contains(prompt, "x".repeat(201)));
    }

    @Test
    void truncatesOverlongInputOutputTo300Chars() throws Exception {
        StubLlmClient client = new StubLlmClient("Ran a command");
        var gen = new ToolUseSummaryGenerator(client);
        String longOutput = "y".repeat(1000);

        gen.summarizeAsync(List.of(new ToolCallInfo("Bash", "{}", longOutput)), null, false).get();

        String prompt = client.userPrompts.getFirst();
        assertTrue(Strings.CS.contains(prompt, "..."));
        assertFalse(Strings.CS.contains(prompt, "y".repeat(400)));
    }

    @Test
    void llmFailureReturnsNullInsteadOfThrowing() throws Exception {
        StubLlmClient client = new StubLlmClient(null); // simulates API failure
        var gen = new ToolUseSummaryGenerator(client);

        String result = gen.summarizeAsync(
            List.of(new ToolCallInfo("Bash", "{}", "ok")), null, false).get();

        assertNull(result);
    }

    @Test
    void blankResponseIsNormalizedToNull() throws Exception {
        StubLlmClient client = new StubLlmClient("   ");
        var gen = new ToolUseSummaryGenerator(client);

        String result = gen.summarizeAsync(
            List.of(new ToolCallInfo("Bash", "{}", "ok")), null, false).get();

        assertNull(result);
    }
}
