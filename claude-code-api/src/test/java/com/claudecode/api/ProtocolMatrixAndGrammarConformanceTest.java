package com.claudecode.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exhaustive orthogonal matrix, randomized property-based, and Lean 4 streaming grammar
 * conformance suite across all 4 wire protocols ({@code ANTHROPIC}, {@code OPENAI_CHAT},
 * {@code OPENAI_RESPONSES}, {@code GEMINI}).
 */
class ProtocolMatrixAndGrammarConformanceTest {

    private static final Logger log =
        LoggerFactory.getLogger(ProtocolMatrixAndGrammarConformanceTest.class);
    private static final ObjectMapper MAPPER = JsonUtils.getMapper();
    private static final String TINY_PNG_BASE64 =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    private static final ImageBlock TINY_IMAGE = new ImageBlock(JsonUtils.parseTree(
        "{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"" + TINY_PNG_BASE64 + "\"}"));
    private static final JsonNode SIMPLE_JSON_SCHEMA = JsonUtils.parseTree(
        "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},"
            + "\"required\":[\"answer\"],\"additionalProperties\":false}");
    private static final List<CreateMessageRequest.ToolDefinition> SAMPLE_TOOLS = List.of(
        new CreateMessageRequest.ToolDefinition(
            "read_file", "Read a file from disk",
            JsonUtils.parseTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}")),
        new CreateMessageRequest.ToolDefinition(
            "grep", "Search files",
            JsonUtils.parseTree("{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\"}},\"required\":[\"pattern\"]}")));

    private enum MultimodalMode {
        TEXT_ONLY,
        BASE64_IMAGE,
        MULTI_BLOCK_TEXT_AND_IMAGE
    }

    private enum ThinkingMode {
        NONE,
        DISABLED,
        ADAPTIVE,
        BUDGET_LOW,
        EFFORT_HIGH
    }

    private enum ToolMode {
        NO_TOOLS,
        SINGLE_TOOL_DEF,
        TOOL_USE_AND_RESULT_PAIR,
        PARALLEL_TOOL_CALLS_WITH_ORPHAN
    }

    private enum OutputFormatMode {
        FREE_TEXT,
        JSON_SCHEMA
    }

    /**
     * Exhaustively verifies the full Cartesian product of 4 protocols × 3 multimodal modes ×
     * 5 thinking modes × 4 tool modes × 2 stream flags × 2 output formats (960 wire encodings),
     * plus 4×4 cross-protocol request and response translations (1,216 total matrix checks).
     */
    @Test
    void exhaustiveFourProtocolOrthogonalMatrix() {
        ModelApiProtocol[] protocols = ModelApiProtocol.values();
        int encodedChecks = 0;

        for (ModelApiProtocol target : protocols) {
            for (MultimodalMode mm : MultimodalMode.values()) {
                for (ThinkingMode thinking : ThinkingMode.values()) {
                    for (ToolMode tools : ToolMode.values()) {
                        for (boolean stream : new boolean[] {false, true}) {
                            for (OutputFormatMode fmt : OutputFormatMode.values()) {
                                CreateMessageRequest req = buildRequest(mm, thinking, tools, stream, fmt);
                                String wire = ProtocolWireCodec.encodeRequest(target, req, "https://example.invalid", false);
                                JsonNode root = JsonUtils.parseTree(wire);
                                assertNotNull(root, "Encoded JSON must parse for " + target);
                                assertTrue(root.isObject(), "Encoded wire must be a JSON object for " + target);
                                assertProtocolWireInvariants(target, root, tools, stream);

                                // Round-trip back into canonical IR and verify core semantics survive.
                                CreateMessageRequest roundTripped = ProtocolWireCodec.parseRequest(target, wire);
                                assertNotNull(roundTripped, "Round-trip parse failed for " + target);
                                assertFalse(roundTripped.messages().isEmpty(),
                                    "Round-trip dropped all messages for " + target);
                                if (tools != ToolMode.NO_TOOLS) {
                                    assertNotNull(roundTripped.tools(),
                                        "Round-trip dropped tools for " + target);
                                    assertFalse(roundTripped.tools().isEmpty(),
                                        "Round-trip produced empty tools for " + target);
                                }
                                encodedChecks++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(960, encodedChecks, "Expected full 4x3x5x4x2x2 Cartesian product");

        // 4 x 4 = 16 cross-protocol request translations across 12 representative feature profiles (192 checks).
        int requestTranslations = 0;
        for (ModelApiProtocol from : protocols) {
            for (ModelApiProtocol to : protocols) {
                for (MultimodalMode mm : new MultimodalMode[] {MultimodalMode.TEXT_ONLY, MultimodalMode.BASE64_IMAGE}) {
                    for (ThinkingMode th : new ThinkingMode[] {ThinkingMode.NONE, ThinkingMode.ADAPTIVE, ThinkingMode.EFFORT_HIGH}) {
                        for (ToolMode tm : new ToolMode[] {ToolMode.NO_TOOLS, ToolMode.TOOL_USE_AND_RESULT_PAIR}) {
                            CreateMessageRequest req = buildRequest(mm, th, tm, true, OutputFormatMode.FREE_TEXT);
                            String sourceWire = ProtocolWireCodec.encodeRequest(from, req, "https://example.invalid", false);
                            String translated = ProtocolWireCodec.translateRequest(from, to, sourceWire, "https://example.invalid");
                            JsonNode out = JsonUtils.parseTree(translated);
                            assertTrue(out.isObject(), "Cross-protocol translation must produce JSON object");
                            requestTranslations++;
                        }
                    }
                }
            }
        }
        assertEquals(192, requestTranslations);

        // 4 x 4 = 16 cross-protocol response translations across 4 canonical response shapes (64 checks).
        List<ApiMessage> responseShapes = List.of(
            new ApiMessage("m-text", "message", "assistant",
                List.of(new TextBlock("Hello world")), "test-model", "end_turn", null, new Usage(10, 5, 0, 0)),
            new ApiMessage("m-think", "message", "assistant",
                List.of(new ThinkingBlock("let me think", "sig_1"), new TextBlock("Answer")),
                "test-model", "end_turn", null, new Usage(12, 8, 0, 0)),
            new ApiMessage("m-tool", "message", "assistant",
                List.of(new ToolUseBlock("call_1", "read_file", JsonUtils.parseTree("{\"path\":\"README.md\"}"))),
                "test-model", "tool_use", null, new Usage(15, 9, 0, 0)),
            new ApiMessage("m-parallel", "message", "assistant",
                List.of(
                    new TextBlock("Checking both"),
                    new ToolUseBlock("call_a", "read_file", JsonUtils.parseTree("{\"path\":\"a.java\"}")),
                    new ToolUseBlock("call_b", "grep", JsonUtils.parseTree("{\"pattern\":\"TODO\"}"))),
                "test-model", "tool_use", null, new Usage(20, 14, 0, 0))
        );
        int responseTranslations = 0;
        for (ModelApiProtocol from : protocols) {
            for (ModelApiProtocol to : protocols) {
                for (ApiMessage shape : responseShapes) {
                    String fromWire = ProtocolWireCodec.encodeResponse(from, shape);
                    String toWire = ProtocolWireCodec.translateResponse(from, to, fromWire, Set.of("read_file", "grep"));
                    ApiMessage decoded = ProtocolWireCodec.parseResponse(to, toWire, Set.of("read_file", "grep"));
                    assertFalse(decoded.content().isEmpty(),
                        "Response translation " + from + "->" + to + " lost all content");
                    long expectedTools = shape.content().stream().filter(ToolUseBlock.class::isInstance).count();
                    long actualTools = decoded.content().stream().filter(ToolUseBlock.class::isInstance).count();
                    assertEquals(expectedTools, actualTools,
                        "Response translation " + from + "->" + to + " tool_use count mismatch");
                    responseTranslations++;
                }
            }
        }
        assertEquals(64, responseTranslations);
    }

    /**
     * Decodes real SSE payloads from all 4 protocols (including OpenAI Chat legacy
     * {@code function_call}, OpenAI Responses, and Gemini {@code thought + functionCall})
     * AND all 4×4 = 16 cross-protocol SSE translations into canonical {@link StreamEvent}
     * sequences, then verifies every single stream against the Lean 4 {@code stream-verifier}.
     */
    @Test
    void fourByFourStreamingGrammarConformsToLean4Spec() throws Exception {
        Map<ModelApiProtocol, String> upstreamSseByProtocol = new EnumMap<>(ModelApiProtocol.class);
        upstreamSseByProtocol.put(ModelApiProtocol.ANTHROPIC, """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_a1","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-6","stop_reason":null,"usage":{"input_tokens":10,"output_tokens":0}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"hi"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: content_block_start
            data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file","input":{}}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":\\"a.java\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":2}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":7}}

            event: message_stop
            data: {"type":"message_stop"}

            """);
        upstreamSseByProtocol.put(ModelApiProtocol.OPENAI_CHAT, """
            data: {"id":"c1","model":"gpt-5","choices":[{"delta":{"role":"assistant","reasoning_content":"hmm"}}]}

            data: {"id":"c1","choices":[{"delta":{"content":"Hi "}}]}

            data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":"}}]}}]}

            data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.java\\"}"}}]}}]}

            data: {"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}

            data: {"id":"c1","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5}}

            data: [DONE]

            """);
        upstreamSseByProtocol.put(ModelApiProtocol.OPENAI_RESPONSES, """
            event: response.created
            data: {"type":"response.created","response":{"id":"resp_1","model":"gpt-5","status":"in_progress"}}

            event: response.output_item.added
            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[]}}

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hello"}

            event: response.output_item.done
            data: {"type":"response.output_item.done","output_index":0,"item":{"id":"msg_1","type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}}

            event: response.output_item.added
            data: {"type":"response.output_item.added","output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_r1","name":"read_file","arguments":""}}

            event: response.function_call_arguments.delta
            data: {"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\\"path\\":\\"b.java\\"}"}

            event: response.output_item.done
            data: {"type":"response.output_item.done","output_index":1,"item":{"id":"fc_1","type":"function_call","call_id":"call_r1","name":"read_file","arguments":"{\\"path\\":\\"b.java\\"}"}}

            event: response.completed
            data: {"type":"response.completed","response":{"id":"resp_1","model":"gpt-5","status":"completed","usage":{"input_tokens":10,"output_tokens":6}}}

            """);
        upstreamSseByProtocol.put(ModelApiProtocol.GEMINI, """
            data: {"candidates":[{"content":{"role":"model","parts":[{"text":"thinking...","thought":true},{"text":"Let me check"}]}}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}

            data: {"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"read_file","args":{"path":"c.java"}},"thoughtSignature":"sig_g1"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":8}}

            """);

        Map<String, List<StreamEvent>> recordedStreams = new LinkedHashMap<>();
        Set<String> offeredTools = Set.of("read_file", "grep");

        // 1) Direct decode of each of the 4 protocols + legacy OpenAI function_call
        for (Map.Entry<ModelApiProtocol, String> entry : upstreamSseByProtocol.entrySet()) {
            List<StreamEvent> decoded = decodeSse(entry.getKey(), entry.getValue(), offeredTools);
            assertFalse(decoded.isEmpty(), "Decoded stream empty for " + entry.getKey());
            recordedStreams.put("direct_" + entry.getKey().name().toLowerCase(Locale.ROOT), decoded);
        }

        String legacyFunctionCallSse = """
            data: {"id":"c_leg","model":"gpt-4","choices":[{"delta":{"role":"assistant","function_call":{"name":"read_file","arguments":"{\\"path\\":\\"old.java\\"}"}}}]}

            data: {"id":"c_leg","choices":[{"delta":{},"finish_reason":"function_call"}]}

            data: [DONE]

            """;
        recordedStreams.put("direct_openai_chat_legacy_function_call",
            decodeSse(ModelApiProtocol.OPENAI_CHAT, legacyFunctionCallSse, offeredTools));

        // 2) All 4 x 4 = 16 cross-protocol SSE translations re-decoded into canonical StreamEvents
        for (ModelApiProtocol from : ModelApiProtocol.values()) {
            for (ModelApiProtocol to : ModelApiProtocol.values()) {
                String translatedSse = ProtocolWireCodec.translateSseStream(
                    from, to, upstreamSseByProtocol.get(from), "test-model", offeredTools);
                List<StreamEvent> targetDecoded = decodeSse(to, translatedSse, offeredTools);
                assertFalse(targetDecoded.isEmpty(),
                    "Translated stream " + from + " -> " + to + " produced no events");
                recordedStreams.put(
                    "xproto_" + from.name().toLowerCase(Locale.ROOT)
                        + "_to_" + to.name().toLowerCase(Locale.ROOT),
                    targetDecoded);
            }
        }

        assertEquals(21, recordedStreams.size(), "Expected 5 direct + 16 cross-protocol streams");

        List<String> jsonLines = new ArrayList<>(recordedStreams.size());
        for (Map.Entry<String, List<StreamEvent>> entry : recordedStreams.entrySet()) {
            ObjectNode row = MAPPER.createObjectNode();
            row.put("scenario", entry.getKey());
            ArrayNode evArr = row.putArray("events");
            for (StreamEvent ev : entry.getValue()) {
                evArr.add(streamEventToJson(ev));
            }
            jsonLines.add(row.toString());
        }

        Path traceFile = Path.of("build", "formal-traces", "stream-events.jsonl");
        Files.createDirectories(traceFile.getParent());
        Files.write(traceFile, jsonLines);

        verifyWithLeanStreamVerifier(traceFile, jsonLines.size());
    }

    /**
     * Randomized property-based test (500 deterministic-seeded adversarial histories) verifying
     * that {@link ToolPairingHealer#healRequestMessages} satisfies the exact theorems proven in
     * {@code formal/Protocol/ToolPairing.lean}: exact pairing order, zero orphan tool_results,
     * and strict idempotence.
     */
    @Test
    void toolPairingHealerSatisfiesPairingNoOrphanAndIdempotenceProperties() {
        Random rng = new Random(0xC0DEC0DEL);
        for (int trial = 0; trial < 500; trial++) {
            List<CreateMessageRequest.RequestMessage> raw = generateAdversarialHistory(rng);
            List<ToolPairingHealer.HealedTurn> healed = ToolPairingHealer.healRequestMessages(raw);

            // Property 1 & 2: Every assistant tool_use list is immediately answered by matching
            // tool_results in exact order, and no orphan tool_result appears after any assistant turn.
            List<String> pendingUseIds = List.of();
            for (ToolPairingHealer.HealedTurn turn : healed) {
                if (Strings.CS.equals("assistant", turn.role())) {
                    assertTrue(pendingUseIds.isEmpty(),
                        "Trial " + trial + ": unanswered tool_use before next assistant turn: " + pendingUseIds);
                    pendingUseIds = turn.blocks().stream()
                        .filter(ToolUseBlock.class::isInstance)
                        .map(b -> ((ToolUseBlock) b).id())
                        .toList();
                } else if (Strings.CS.equals("tool_results", turn.role())) {
                    List<String> resultIds = turn.blocks().stream()
                        .filter(ToolResultBlock.class::isInstance)
                        .map(b -> ((ToolResultBlock) b).toolUseId())
                        .toList();
                    if (!pendingUseIds.isEmpty()) {
                        assertEquals(pendingUseIds, resultIds,
                            "Trial " + trial + ": tool_result IDs must match preceding tool_use IDs in exact order");
                        pendingUseIds = List.of();
                    }
                } else {
                    assertTrue(pendingUseIds.isEmpty(),
                        "Trial " + trial + ": user turn arrived with unanswered tool_use: " + pendingUseIds);
                    boolean hasToolResult = turn.blocks().stream().anyMatch(ToolResultBlock.class::isInstance);
                    assertFalse(hasToolResult,
                        "Trial " + trial + ": plain user turn must not contain orphan ToolResultBlock");
                }
            }
            // If the conversation ends with a user/tool_results turn (not an awaiting assistant tool_use),
            // there must be zero dangling unanswered tool_use calls.
            boolean endsWithAssistant = !healed.isEmpty()
                && Strings.CS.equals("assistant", healed.getLast().role());
            if (!endsWithAssistant) {
                assertTrue(pendingUseIds.isEmpty(),
                    "Trial " + trial + ": dangling unanswered tool_use before final user turn: " + pendingUseIds);
            }

            // Property 3: Idempotence — healing the healed conversation preserves all assistant/tool_results
            // pairing turns identically and preserves the exact user content stream without loss or duplication.
            List<CreateMessageRequest.RequestMessage> asRequests = healed.stream()
                .map(t -> new CreateMessageRequest.RequestMessage(
                    Strings.CS.equals("assistant", t.role()) ? "assistant" : "user",
                    t.blocks()))
                .toList();
            List<ToolPairingHealer.HealedTurn> healedTwice = ToolPairingHealer.healRequestMessages(asRequests);
            assertEquals(canonicalPairingSignature(healed), canonicalPairingSignature(healedTwice),
                "Trial " + trial + ": ToolPairingHealer pairing must be idempotent");
        }
    }

    private static List<String> canonicalPairingSignature(List<ToolPairingHealer.HealedTurn> turns) {
        List<String> sig = new ArrayList<>();
        StringBuilder pendingUser = null;
        for (ToolPairingHealer.HealedTurn t : turns) {
            if (Strings.CS.equals("user", t.role())) {
                if (pendingUser == null) {
                    pendingUser = new StringBuilder("user:");
                }
                appendBlockTokens(pendingUser, t.blocks());
                continue;
            }
            if (pendingUser != null) {
                sig.add(pendingUser.toString());
                pendingUser = null;
            }
            StringBuilder sb = new StringBuilder(t.role()).append(':');
            appendBlockTokens(sb, t.blocks());
            sig.add(sb.toString());
        }
        if (pendingUser != null) {
            sig.add(pendingUser.toString());
        }
        return sig;
    }

    private static void appendBlockTokens(StringBuilder sb, List<ContentBlock> blocks) {
        for (ContentBlock b : blocks) {
            switch (b) {
                case ToolUseBlock tu -> sb.append("U(").append(tu.id()).append("),");
                case ToolResultBlock tr -> sb.append("R(").append(tr.toolUseId()).append("),");
                case TextBlock _ -> sb.append("T,");
                case ImageBlock _ -> sb.append("I,");
                default -> sb.append(b.getClass().getSimpleName()).append(',');
            }
        }
    }

    private static List<CreateMessageRequest.RequestMessage> generateAdversarialHistory(Random rng) {
        int turnCount = 2 + rng.nextInt(6);
        List<CreateMessageRequest.RequestMessage> msgs = new ArrayList<>();
        msgs.add(new CreateMessageRequest.RequestMessage("user", "start"));
        int idSeq = 1;
        List<String> recentIds = new ArrayList<>();

        for (int i = 0; i < turnCount; i++) {
            // Ensure at least one assistant turn exists (i == 0) so multi-turn pairing rules apply.
            boolean assistant = (i == 0) || rng.nextBoolean();
            if (assistant) {
                List<ContentBlock> blocks = new ArrayList<>();
                if (i == 0 || rng.nextBoolean()) {
                    blocks.add(new TextBlock("step " + i));
                }
                int toolCalls = rng.nextInt(3);
                for (int t = 0; t < toolCalls; t++) {
                    String id = "call_" + (idSeq++);
                    recentIds.add(id);
                    blocks.add(new ToolUseBlock(id, "read_file", JsonUtils.parseTree("{\"path\":\"f.txt\"}")));
                }
                if (!blocks.isEmpty()) {
                    msgs.add(new CreateMessageRequest.RequestMessage("assistant", blocks));
                }
            } else {
                List<ContentBlock> blocks = new ArrayList<>();
                // Mix of valid results (possibly duplicated or out-of-order), orphan results, text, and images.
                if (!recentIds.isEmpty() && rng.nextBoolean()) {
                    String pick = recentIds.get(rng.nextInt(recentIds.size()));
                    blocks.add(new ToolResultBlock(pick, List.of(new TextBlock("ok")), false));
                    if (rng.nextInt(3) == 0) {
                        // Duplicate result for same callId
                        blocks.add(new ToolResultBlock(pick, List.of(new TextBlock("dup")), false));
                    }
                }
                if (rng.nextInt(3) == 0) {
                    // Orphan result with unknown callId
                    blocks.add(new ToolResultBlock("orphan_" + i, List.of(new TextBlock("stray")), false));
                }
                if (rng.nextBoolean()) {
                    blocks.add(new TextBlock("user note " + i));
                }
                if (rng.nextInt(4) == 0) {
                    blocks.add(TINY_IMAGE);
                }
                if (!blocks.isEmpty()) {
                    msgs.add(new CreateMessageRequest.RequestMessage("user", blocks));
                }
            }
        }
        return msgs;
    }

    private static CreateMessageRequest buildRequest(MultimodalMode mm,
                                                     ThinkingMode thinking,
                                                     ToolMode tools,
                                                     boolean stream,
                                                     OutputFormatMode fmt) {
        List<ContentBlock> userBlocks = switch (mm) {
            case TEXT_ONLY -> List.of(new TextBlock("Analyze this repository"));
            case BASE64_IMAGE -> List.of(TINY_IMAGE);
            case MULTI_BLOCK_TEXT_AND_IMAGE -> List.of(
                new TextBlock("Describe this diagram"),
                TINY_IMAGE);
        };

        List<CreateMessageRequest.RequestMessage> messages = new ArrayList<>();
        messages.add(new CreateMessageRequest.RequestMessage("user", userBlocks));
        if (tools == ToolMode.TOOL_USE_AND_RESULT_PAIR) {
            messages.add(new CreateMessageRequest.RequestMessage("assistant", List.of(
                new ToolUseBlock("call_1", "read_file", JsonUtils.parseTree("{\"path\":\"README.md\"}")))));
            messages.add(new CreateMessageRequest.RequestMessage("user", List.of(
                new ToolResultBlock("call_1", List.of(new TextBlock("# Title")), false))));
        } else if (tools == ToolMode.PARALLEL_TOOL_CALLS_WITH_ORPHAN) {
            messages.add(new CreateMessageRequest.RequestMessage("assistant", List.of(
                new ToolUseBlock("call_p1", "read_file", JsonUtils.parseTree("{\"path\":\"a.java\"}")),
                new ToolUseBlock("call_p2", "grep", JsonUtils.parseTree("{\"pattern\":\"TODO\"}")))));
            messages.add(new CreateMessageRequest.RequestMessage("user", List.of(
                new ToolResultBlock("call_p2", List.of(new TextBlock("hit")), false),
                new ToolResultBlock("orphan_99", List.of(new TextBlock("orphan text")), false))));
        }

        CreateMessageRequest.Builder b = CreateMessageRequest.builder()
            .model("claude-sonnet-4-6")
            .maxTokens(4096)
            .systemPrompt("You are a coding assistant.")
            .messages(messages)
            .stream(stream);

        if (tools != ToolMode.NO_TOOLS) {
            b.tools(SAMPLE_TOOLS);
        }

        switch (thinking) {
            case NONE -> { /* default */ }
            case DISABLED -> b.thinking(CreateMessageRequest.ThinkingConfig.disabled());
            case ADAPTIVE -> b.thinking(CreateMessageRequest.ThinkingConfig.adaptive());
            case BUDGET_LOW -> b.thinking(CreateMessageRequest.ThinkingConfig.enabled(2048));
            case EFFORT_HIGH -> b.effort("high");
        }

        if (fmt == OutputFormatMode.JSON_SCHEMA) {
            ObjectNode formatNode = MAPPER.createObjectNode();
            formatNode.put("type", "json_schema");
            formatNode.set("schema", SIMPLE_JSON_SCHEMA);
            b.outputConfig(new CreateMessageRequest.OutputConfig(null, formatNode, null));
        }

        return b.build();
    }

    private static void assertProtocolWireInvariants(ModelApiProtocol target,
                                                     JsonNode root,
                                                     ToolMode tools,
                                                     boolean stream) {
        switch (target) {
            case ANTHROPIC -> {
                assertTrue(root.hasNonNull("model"));
                assertTrue(root.has("max_tokens"));
                assertTrue(root.path("messages").isArray() && !root.path("messages").isEmpty());
                assertEquals(stream, root.path("stream").asBoolean());
                if (tools != ToolMode.NO_TOOLS) {
                    assertTrue(root.path("tools").isArray() && !root.path("tools").isEmpty());
                }
            }
            case OPENAI_CHAT -> {
                assertTrue(root.hasNonNull("model"));
                assertTrue(root.path("messages").isArray() && !root.path("messages").isEmpty());
                assertEquals(stream, root.path("stream").asBoolean());
                if (tools != ToolMode.NO_TOOLS) {
                    assertTrue(root.path("tools").isArray() && !root.path("tools").isEmpty());
                }
            }
            case OPENAI_RESPONSES -> {
                assertTrue(root.hasNonNull("model"));
                assertTrue(root.path("input").isArray() && !root.path("input").isEmpty());
                assertEquals(stream, root.path("stream").asBoolean());
                if (tools != ToolMode.NO_TOOLS) {
                    assertTrue(root.path("tools").isArray() && !root.path("tools").isEmpty());
                }
            }
            case GEMINI -> {
                assertTrue(root.path("contents").isArray() && !root.path("contents").isEmpty());
                for (JsonNode content : root.path("contents")) {
                    assertTrue(content.path("parts").isArray() && !content.path("parts").isEmpty(),
                        "Gemini content turn must have non-empty parts");
                }
                if (tools != ToolMode.NO_TOOLS) {
                    assertTrue(root.path("tools").isArray() && !root.path("tools").isEmpty());
                }
            }
        }
    }

    private static List<StreamEvent> decodeSse(ModelApiProtocol protocol,
                                               String sseBody,
                                               Set<String> offeredTools) {
        ProtocolCodec codec = switch (protocol) {
            case ANTHROPIC -> new AnthropicWireCodec();
            case OPENAI_CHAT -> new OpenAiChatWireCodec();
            case OPENAI_RESPONSES -> new OpenAiResponsesWireCodec();
            case GEMINI -> new GeminiWireCodec();
        };
        return WireCodecSupport.decodeSseToStreamEvents(
            codec.newSseTranslator("test-model", offeredTools), sseBody);
    }

    private static ObjectNode streamEventToJson(StreamEvent event) {
        ObjectNode n = MAPPER.createObjectNode();
        switch (event) {
            case StreamEvent.MessageStart _ -> n.put("kind", "message_start");
            case StreamEvent.ContentBlockStart(int index, _, _) -> {
                n.put("kind", "block_start");
                n.put("index", index);
            }
            case StreamEvent.ContentBlockDelta(int index, _, _) -> {
                n.put("kind", "block_delta");
                n.put("index", index);
            }
            case StreamEvent.ContentBlockStop(int index, _) -> {
                n.put("kind", "block_stop");
                n.put("index", index);
            }
            case StreamEvent.MessageDelta _ -> n.put("kind", "message_delta");
            case StreamEvent.MessageStop _ -> n.put("kind", "message_stop");
            case StreamEvent.Ping _ -> n.put("kind", "ping");
            case StreamEvent.RequestTiming _ -> n.put("kind", "request_timing");
            case StreamEvent.Error _ -> n.put("kind", "error");
        }
        return n;
    }

    private static void verifyWithLeanStreamVerifier(Path traceFile, int streamCount) throws Exception {
        Path repoRoot = findRepoRoot();
        Path verifierBin = repoRoot.resolve("formal/.lake/build/bin/stream-verifier");
        boolean requireVerifier = Boolean.getBoolean("formal.requireVerifier")
            || Strings.CI.equals("true", System.getenv("FORMAL_REQUIRE_VERIFIER"));

        if (!Files.isExecutable(verifierBin)) {
            assertFalse(requireVerifier,
                "Expected compiled Lean 4 stream-verifier at " + verifierBin
                    + " (run `cd formal && lake build` first)");
            log.info("[ProtocolMatrixAndGrammarConformanceTest] stream-verifier not built at {}; "
                    + "recorded {} canonical streams to {}.",
                verifierBin, streamCount, traceFile.toAbsolutePath());
            return;
        }

        Process proc = new ProcessBuilder(verifierBin.toString(), traceFile.toAbsolutePath().toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "Lean 4 stream-verifier rejected canonical stream:\n" + output);
        assertTrue(Strings.CS.contains(output, "OK: Verified " + streamCount + " canonical stream(s)"),
            "Unexpected stream-verifier output: " + output);
    }

    private static Path findRepoRoot() {
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.exists(cur.resolve("formal/lakefile.toml"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        return Path.of("").toAbsolutePath();
    }
}
