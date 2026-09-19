package com.claudecode.services.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AwaySummaryServiceTest {

    private String savedUserDir;

    @AfterEach
    void restoreUserDir() {
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        }
    }

    /** Records every fork request and streams back a single text block. */
    private static final class RecordingStreamingClient implements StreamingClient {
        final List<StreamRequest> requests = new ArrayList<>();
        volatile String response;

        RecordingStreamingClient(String response) {
            this.response = response;
        }

        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            requests.add(request);
            List<StreamingEvent> events = new ArrayList<>();
            events.add(new StreamingEvent.MessageStartEvent(
                "msg-recap", request.model(), List.of(), Usage.EMPTY));
            if (response != null) {
                events.add(new StreamingEvent.ContentBlockStartEvent(0, "text", null, null));
                events.add(new StreamingEvent.ContentBlockDeltaEvent(0, "text_delta", response));
                events.add(new StreamingEvent.ContentBlockStopEvent(0));
            }
            events.add(new StreamingEvent.MessageDeltaEvent("end_turn", Usage.EMPTY));
            events.add(new StreamingEvent.MessageStopEvent());
            return events.iterator();
        }

        @Override
        public String getModel() {
            return "stub-model";
        }

        StreamRequest onlyRequest() {
            assertEquals(1, requests.size(), "the recap is a single forked turn");
            return requests.getFirst();
        }
    }

    private static final class BashOnlyToolExecutor implements ToolExecutor {
        @Override
        public ToolResult execute(String toolName, JsonNode input, ToolExecutionContext context) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StreamingClient.StreamRequest.ToolDef> getToolDefinitions() {
            return List.of(new StreamingClient.StreamRequest.ToolDef(
                "Bash", "Run a command", new ObjectMapper().createObjectNode()));
        }
    }

    /** A recap service over a live session, the shape both callers wire up. */
    private record Harness(AwaySummaryService service, RecordingStreamingClient client,
                           DefaultQuerySession engine) {
    }

    private static Harness harness(String response) {
        RecordingStreamingClient client = new RecordingStreamingClient(response);
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(client)
            .model("claude-sonnet-5")
            .systemPrompt("main-loop-system-prompt")
            .maxTokens(32_000)
            .toolExecutor(new BashOnlyToolExecutor())
            .tools(List.of("Bash"))
            .sessionIdentity(SessionIdentity.of("session-recap"))
            .build();
        DefaultQuerySession engine = new DefaultQuerySession(config);
        return new Harness(new AwaySummaryService(client, () -> engine), client, engine);
    }

    private static List<Message> sampleMessages(int n) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(new UserMessage("uuid-" + i, MessageContent.ofText("message " + i)));
        }
        return msgs;
    }

    /** A conversation that already spent a turn calling a tool. */
    private static List<Message> conversationThatCalledATool() {
        ObjectMapper mapper = new ObjectMapper();
        return List.of(
            new UserMessage("u1", MessageContent.ofText("list the files")),
            new AssistantMessage("a1", AssistantContent.of(List.of(
                new TextBlock("Running it."),
                new ToolUseBlock("toolu_1", "Bash",
                    mapper.createObjectNode().put("command", "ls"))))),
            new UserMessage("u2", MessageContent.ofBlocks(List.of(
                new ToolResultBlock("toolu_1", List.of(new TextBlock("a.txt")), false)))),
            new UserMessage("u3", MessageContent.ofText("thanks")));
    }

    private static Message awaySummaryMessage() {
        return MessageFactoryBridge.awaySummary();
    }

    /** Bridges to the core MessageFactory without widening its test surface. */
    private static final class MessageFactoryBridge {
        static Message awaySummary() {
            return MessageFactory.createAwaySummaryMessage("previous recap");
        }
    }

    private void setFlag(String flag, boolean value) throws IOException {
        savedUserDir = System.getProperty("user.dir");
        Path cwd = Files.createTempDirectory("cc-away-settings");
        Path settings = cwd.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"" + flag + "\": " + value + "}");
        System.setProperty("user.dir", cwd.toString());
    }

    /**
     * The bug this class is the regression guard for: the recap used to be a
     * bare side query with {@code tools: []} and no system prompt, so every
     * conversation that had already called a tool produced a request the
     * Messages API rejects — {@code tool_use} blocks with no declared tools —
     * and {@code /recap} answered "Couldn't generate a recap" forever.
     */
    @Test
    void recapForkCarriesTheToolCatalogForAConversationThatCalledATool() {
        Harness h = harness("Refactoring the parser.");

        AwaySummaryService.RecapResult result =
            h.service().synthesizeRecap(conversationThatCalledATool());

        assertEquals(AwaySummaryService.Kind.OK, result.kind());
        StreamingClient.StreamRequest request = h.client().onlyRequest();
        assertEquals(List.of("Bash"), request.tools().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name).toList(),
            "a history holding tool_use blocks is only valid alongside its tools");
        assertFalse(request.systemPrompt().isEmpty(),
            "the recap forks the main loop, system prompt included");
        assertEquals("away_summary", request.querySource());
        assertTrue(request.skipCacheWrite(),
            "the recap is a throwaway fork; the prefix keeps the main loop's cache marker");
        assertTrue(Strings.CS.contains(
            request.messages().getLast().content().toString(),
            "The user stepped away"));
    }

    /**
     * 236 {@code JXn} prefers the saved main-loop parameters over a rebuild:
     * reusing them verbatim is what keeps the prompt-cache prefix intact.
     */
    @Test
    void recapPrefersTheSavedMainLoopRequestOverARebuild() {
        Harness h = harness("Back to the parser.");
        StreamingClient.StreamRequest.ToolDef savedTool =
            new StreamingClient.StreamRequest.ToolDef(
                "Read", "Read a file", new ObjectMapper().createObjectNode());
        StreamingClient.StreamRequest saved = h.engine().forks().buildCacheSharingRequest(
            List.of(new UserMessage("s1", MessageContent.ofText("saved turn"))),
            "saved prompt", "user");
        saved = new StreamingClient.StreamRequest(
            saved.model(), saved.maxTokens(), "saved-system-prompt", saved.messages(),
            true, List.of(savedTool), null, saved.effort(), saved.fallbackModel(),
            null, null, null, null, saved.thinkingEnabled(), saved.sessionId(),
            null, false, "user", saved.abortController(), saved.thinkingBudgetTokens());
        h.engine().forks().setLastCacheSafeForkRequest(saved);

        h.service().synthesizeRecap(sampleMessages(4));

        StreamingClient.StreamRequest request = h.client().onlyRequest();
        assertEquals("saved-system-prompt", request.systemPrompt(),
            "the saved prefix is reused verbatim or the cache misses on every recap");
        assertEquals(List.of("Read"), request.tools().stream()
            .map(StreamingClient.StreamRequest.ToolDef::name).toList());
        assertEquals(saved.messages().size() + 1, request.messages().size(),
            "the recap prompt is appended, the prefix is untouched");
        assertSame(saved.messages().getFirst(), request.messages().getFirst());
        assertEquals("away_summary", request.querySource(),
            "the snapshot is a main-loop request; querySource must be overridden");
        assertTrue(request.skipCacheWrite(),
            "the snapshot carries skipCacheWrite=false; the fork must override it");
    }

    /**
     * 236 {@code $lT} refuses to rebuild when the conversation holds nothing
     * summarizable, so the caller says "nothing to recap" instead of spending a
     * request that can only fail.
     */
    @Test
    void rebuildWithoutAnythingSummarizableReportsNoTurnWithoutCallingTheModel() {
        Harness h = harness("unused");

        List<Message> nothingToSay = List.of(
            new UserMessage("m1", MessageContent.ofText("<local-command-stdout>ok</local-command-stdout>")));

        assertEquals(AwaySummaryService.Kind.NO_TURN,
            h.service().synthesizeRecap(nothingToSay).kind());
        assertEquals(AwaySummaryService.Kind.NO_TURN,
            h.service().synthesizeRecap(List.of()).kind());
        assertTrue(h.client().requests.isEmpty(), "no request is worth sending");
    }

    @Test
    void generateAwaySummary_nullOrEmpty_returnsNull() {
        Harness h = harness("recap");
        assertNull(h.service().generateAwaySummary(null));
        assertNull(h.service().generateAwaySummary(List.of()));
    }

    @Test
    void generateAwaySummary_sendsConversationPlusPromptAndTrims() {
        Harness h = harness("  The user is refactoring the parser.  ");

        String result = h.service().generateAwaySummary(sampleMessages(5));

        assertEquals("The user is refactoring the parser.", result);
        // One forked turn; the fixed 236 FlT prompt closes it (JXn appends it
        // after the conversation, it is not the first message).
        StreamingClient.StreamRequest request = h.client().onlyRequest();
        String wire = request.messages().getLast().content().toString();
        assertTrue(Strings.CS.contains(wire, "stepped away"));
        assertTrue(Strings.CS.contains(wire, "under 40 words"));
        assertTrue(Strings.CS.contains(wire, "message 4"),
            "the conversation is the cached prefix the recap prompt is appended to");
    }

    @Test
    void generateAwaySummary_llmReturnsNull_returnsNull() {
        Harness h = harness(null);
        assertNull(h.service().generateAwaySummary(sampleMessages(3)));
    }

    @Test
    void generateAwaySummary_llmReturnsBlank_returnsNull() {
        Harness h = harness("   ");
        assertNull(h.service().generateAwaySummary(sampleMessages(3)));
    }

    @Test
    void generateAwaySummary_capsAt400CharsOnWordBoundary() {
        Harness h = harness(("word ".repeat(120)) + "tail");

        String result = h.service().generateAwaySummary(sampleMessages(4));

        assertNotNull(result);
        assertTrue(result.length() <= AwaySummaryService.RECAP_CHAR_CAP + 1,
            "cap 400 plus ellipsis");
        assertTrue(Strings.CS.endsWith(result, "…"));
        assertTrue(Strings.CS.contains(result, " "),
            "long recaps cut at a word boundary, not mid-word");
    }

    @Test
    void capRecapText_shortTextUnchanged() {
        assertEquals("short", AwaySummaryService.capRecapText("short"));
        assertEquals("x".repeat(400), AwaySummaryService.capRecapText("x".repeat(400)));
    }

    @Test
    void capRecapText_longTextCutsAtWordBoundary() {
        String text = "word ".repeat(150);
        String capped = AwaySummaryService.capRecapText(text);
        assertTrue(capped.length() <= 401);
        assertTrue(Strings.CS.endsWith(capped, "…"));
        assertFalse(Strings.CS.endsWith(capped.substring(0, capped.length() - 1), " "),
            "no trailing space before the ellipsis");
    }

    @Test
    void shouldRecap_requiresThreeUserMessages() {
        AwaySummaryService svc = harness("recap").service();
        assertFalse(svc.shouldRecap(sampleMessages(2)), "236 Ze0=3 minimum user turns");
        assertTrue(svc.shouldRecap(sampleMessages(3)));
    }

    @Test
    void shouldRecap_afterRecapRequiresTwoNewUserMessages() {
        AwaySummaryService svc = harness("recap").service();
        List<Message> msgs = new ArrayList<>(sampleMessages(3));
        msgs.add(awaySummaryMessage());
        assertFalse(svc.shouldRecap(msgs), "236 Qe0=2 new user turns after a recap");

        msgs.add(new UserMessage("u-new-1", MessageContent.ofText("next 1")));
        assertFalse(svc.shouldRecap(msgs), "still one short");

        msgs.add(new UserMessage("u-new-2", MessageContent.ofText("next 2")));
        assertTrue(svc.shouldRecap(msgs), "two new user turns unlock the next recap");
    }

    @Test
    void lastMessageIsAwaySummary_matches236Hqg() {
        AwaySummaryService svc = harness("recap").service();
        List<Message> msgs = new ArrayList<>(sampleMessages(3));
        assertFalse(svc.lastMessageIsAwaySummary(msgs));

        msgs.add(awaySummaryMessage());
        assertTrue(svc.lastMessageIsAwaySummary(msgs), "last message is a recap");

        msgs.add(new UserMessage("u-after", MessageContent.ofText("after recap")));
        assertFalse(svc.lastMessageIsAwaySummary(msgs), "a later message resets hQg");
    }

    @Test
    void maybePublish_disabled_isNoOp() throws IOException {
        setFlag("awaySummaryEnabled", false);
        Harness h = harness("recap");
        assertFalse(h.service().isEnabled());

        AtomicReference<String> published = new AtomicReference<>();
        h.service().maybePublishAwaySummary(sampleMessages(4), published::set);

        assertNull(published.get());
        assertTrue(h.client().requests.isEmpty());
    }

    @Test
    void maybePublish_enabled_publishes() throws IOException {
        setFlag("awaySummaryEnabled", true);
        Harness h = harness("You were debugging the auth flow.");
        assertTrue(h.service().isEnabled());

        AtomicReference<String> published = new AtomicReference<>();
        h.service().maybePublishAwaySummary(sampleMessages(4), published::set);

        assertEquals("You were debugging the auth flow.", published.get());
    }

    @Test
    void maybePublish_gatesFail_doesNotCallLlm() throws IOException {
        setFlag("awaySummaryEnabled", true);
        Harness h = harness("recap");

        AtomicReference<String> published = new AtomicReference<>();
        // Fewer than 3 user messages → et0 gate → no LLM call.
        h.service().maybePublishAwaySummary(sampleMessages(2), published::set);

        assertNull(published.get());
        assertTrue(h.client().requests.isEmpty());
    }

    @Test
    void withDisableHint_firstThreeOnly() {
        assertEquals("recap (disable recaps in /config)",
            AwaySummaryService.withDisableHint("recap", 0));
        assertEquals("recap (disable recaps in /config)",
            AwaySummaryService.withDisableHint("recap", 2));
        assertEquals("recap", AwaySummaryService.withDisableHint("recap", 3));
    }
}
