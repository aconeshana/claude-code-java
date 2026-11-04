package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.StreamingClient.StreamRequest.ToolDef;
import com.claudecode.core.engine.ToolExecutor;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelApiProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The compaction fork must declare only the tools the 2.1.236 bundle keeps for
 * compaction — Read, ToolSearch (when tool search is on for the model), and MCP
 * tools. A model with weak instruction-following otherwise reaches for a
 * declared writer such as Bash, spends its single turn on a tool call, and the
 * summary is never written (see {@code LlmCompactSummarizer}).
 *
 * <p>Every other fork — {@code /recap} ({@code "away_summary"}) above all — must
 * keep the session's full catalog, so the narrowing is asserted to be
 * compaction-only.
 */
class CompactForkToolNarrowingTest {

    /** A catalog containing the tools compaction keeps plus several it must drop. */
    private record CatalogToolExecutor() implements ToolExecutor {
        @Override
        public ToolResult execute(String toolName, com.fasterxml.jackson.databind.JsonNode input,
                                   ToolExecutionContext context) {
            return ToolResult.success("unused");
        }

        @Override
        public List<ToolDef> getToolDefinitions() {
            return catalog();
        }

        @Override
        public List<ToolDef> getToolDefinitions(ToolExecutionContext context) {
            return catalog();
        }

        private static List<ToolDef> catalog() {
            return List.of(
                new ToolDef("Read", "read", null),
                new ToolDef("ToolSearch", "search", null),
                new ToolDef("Bash", "bash", null),
                new ToolDef("Write", "write", null),
                new ToolDef("Grep", "grep", null),
                new ToolDef("mcp__github__create_issue", "mcp", null));
        }
    }

    /** Never invoked — only satisfies the spec builder. */
    private record NoopClient() implements StreamingClient {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return List.<StreamingEvent>of().iterator();
        }

        @Override
        public String getModel() { return "noop"; }
    }

    @BeforeEach
    @AfterEach
    void resetGate() {
        ToolSearchGate.configureResolvedBaseUrl(null);
        ToolSearchGate.configureBaseUrlResolver(null);
        ToolSearchGate.configureProtocolResolver(null);
    }

    private static void enableToolSearch() {
        ToolSearchGate.configureResolvedBaseUrl("https://api.anthropic.com");
        ToolSearchGate.configureProtocolResolver(_ -> ModelApiProtocol.ANTHROPIC);
    }

    /**
     * Tool search defaults ON for an empty base URL (it counts as first-party),
     * so "disabled" has to be a genuine third-party endpoint.
     */
    private static void disableToolSearch() {
        ToolSearchGate.configureResolvedBaseUrl("https://localhost.example.invalid");
        ToolSearchGate.configureProtocolResolver(_ -> ModelApiProtocol.ANTHROPIC);
    }

    private static DefaultQuerySession engineWithCatalog(String model) {
        QuerySessionSpec config = QuerySessionSpec.builder()
            .llmClient(new NoopClient())
            .toolExecutor(new CatalogToolExecutor())
            .model(model)
            .maxTurns(3)
            .build();
        return new DefaultQuerySession(config);
    }

    private static List<String> toolNames(StreamingClient.StreamRequest request) {
        return request.tools().stream().map(ToolDef::name).toList();
    }

    private static List<Message> oneTurn() {
        return List.of(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText("hi")));
    }

    @Test
    void compactForkKeepsReadToolSearchAndMcpWhenToolSearchEnabled() {
        enableToolSearch();
        DefaultQuerySession engine = engineWithCatalog("claude-opus-4-8");

        StreamingClient.StreamRequest request = engine.forks()
            .buildCacheSharingRequest(oneTurn(), "Summarize.", "compact");

        assertEquals(List.of("Read", "ToolSearch", "mcp__github__create_issue"),
            toolNames(request),
            "compaction must keep exactly Read + ToolSearch + MCP tools");
    }

    @Test
    void compactForkKeepsOnlyReadWhenToolSearchDisabled() {
        disableToolSearch();
        DefaultQuerySession engine = engineWithCatalog("some-local-model");

        StreamingClient.StreamRequest request = engine.forks()
            .buildCacheSharingRequest(oneTurn(), "Summarize.", "compact");

        assertEquals(List.of("Read"), toolNames(request),
            "with tool search off, compaction must declare only Read");
    }

    @Test
    void recapForkKeepsFullCatalog() {
        enableToolSearch();
        DefaultQuerySession engine = engineWithCatalog("claude-opus-4-8");

        StreamingClient.StreamRequest request = engine.forks()
            .buildCacheSharingRequest(oneTurn(), "Recap.", "away_summary");

        assertEquals(List.of("Read", "ToolSearch", "Bash", "Write", "Grep",
                "mcp__github__create_issue"),
            toolNames(request),
            "recap is not compaction and must keep the session's full catalog");
    }

    @Test
    void buildWithoutToolsEmitsEmptyCatalog() {
        enableToolSearch();
        DefaultQuerySession engine = engineWithCatalog("claude-opus-4-8");

        StreamingClient.StreamRequest request = engine.forks()
            .buildWithoutTools(oneTurn(), "Summarize.", "compact");

        assertFalse(request.tools().stream().findAny().isPresent(),
            "the compaction fallback must declare no tools at all");
    }
}