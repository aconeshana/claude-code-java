package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.sun.net.httpserver.HttpExchange;

/**
 * {@code GET /api/sessions/{sessionId}/messages}: the authoritative message
 * snapshot of one session.
 *
 * <p>Snapshot-first: a reconnecting web client pulls this once and then
 * follows SSE increments — the HTTP equivalent of the alignment model's
 * message-snapshot replay. Each assistant message projects its content[]
 * with typed {@code tool_call} entries: a tool_use block and the matching
 * later tool_result block pair up by tool use id into the entry's
 * status/result/ready fields, the same pairing the TUI dispatcher performs
 * when rendering a turn.
 */
@Explanation("Snapshot replay endpoint of the alignment model over HTTP")
final class GatewayMessagesSnapshotHandler {

    /** Bounded excerpt per tool result, matching the mirror frame projection. */
    private static final int MAX_RESULT_CHARS = 20_000;
    private static final String TRUNCATION_MARKER = "\n…[truncated]";

    /** A paired tool result plus the structured side-channel it carried. */
    private record ToolOutcome(ToolResultBlock result, Object toolUseResult) {}

    private final GatewayHeadlessSessions headless;
    private final GatewaySessionMessagesPort messages;

    GatewayMessagesSnapshotHandler(
            GatewayHeadlessSessions headless, GatewaySessionMessagesPort messages) {
        this.headless = Objects.requireNonNull(headless, "headless");
        this.messages = messages == null ? new GatewaySessionMessagesPort() {} : messages;
    }

    /** Handles one snapshot exchange; {@code sessionId} from the URL path. */
    void handle(HttpExchange exchange, String sessionId) throws IOException {
        List<Message> rows;
        String openHeadlessId = headless.isOpen(sessionId) ? sessionId : null;
        try {
            rows = messages.messages(sessionId, openHeadlessId).orElse(null);
        } catch (RuntimeException _) {
            rows = null;
        }
        if (rows == null) {
            respondJson(exchange, 404, errorBody("not_found",
                "unknown session: " + sessionId));
            return;
        }
        respondJson(exchange, 200, snapshot(sessionId, rows));
    }

    /** Projects the message list into the snapshot shape. */
    private static ObjectNode snapshot(String sessionId, List<Message> rows) {
        // Tool results pair with their tool_use entries by id; collect them
        // in one pass so each assistant message can look them up.
        Map<String, ToolOutcome> resultsByUseId = new HashMap<>();
        for (Message row : rows) {
            if (!(row instanceof UserMessage user) || user.message() == null) continue;
            List<ContentBlock> blocks = user.message().blocks();
            if (blocks == null) continue;
            for (ContentBlock block : blocks) {
                if (block instanceof ToolResultBlock result && result.toolUseId() != null) {
                    resultsByUseId.put(result.toolUseId(),
                        new ToolOutcome(result, user.toolUseResult()));
                }
            }
        }

        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        body.put("session_id", sessionId);
        ArrayNode messagesNode = body.putArray("messages");
        // Turn numbering mirrors the metrics tracker's turn opener: each
        // human-prompted user row (not a tool result, meta, or compact
        // summary row) opens turn N+1; assistant rows inherit the open turn.
        // Rows before the first opener sit outside any turn (pre-turn
        // listings) and carry no turn fields.
        long openTurn = 0;
        for (Message row : rows) {
            if (row instanceof AssistantMessage assistant) {
                messagesNode.add(assistantEntry(assistant, resultsByUseId, openTurn));
            } else if (row instanceof UserMessage user) {
                if (opensTurn(user)) openTurn += 1;
                ObjectNode entry = userEntry(user);
                if (entry != null) messagesNode.add(entry);
            }
            // system/progress/attachment rows are display bookkeeping; the
            // snapshot serves the conversation, not the render log.
        }
        return body;
    }

    /**
     * Whether this user row opens a new turn: the message-level view of the
     * metrics tracker's opener rule. Tool-result rows, injected meta rows,
     * and compact summaries participate in an already-open turn; the
     * transcript-level {@code promptSource} stamp encodes the same split but
     * is not carried on the {@code Message} rows this handler serves.
     */
    private static boolean opensTurn(UserMessage user) {
        if (user.isMeta() || user.isCompactSummary() || user.toolUseResult() != null) {
            return false;
        }
        if (user.message() == null || user.message().blocks() == null) return true;
        return user.message().blocks().stream()
            .noneMatch(ToolResultBlock.class::isInstance);
    }

    /** One assistant message with its typed content[] entries. */
    private static ObjectNode assistantEntry(
            AssistantMessage message, Map<String, ToolOutcome> resultsByUseId, long turn) {
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        if (message.uuid() != null) entry.put("id", message.uuid());
        if (message.requestId() != null) entry.put("request_id", message.requestId());
        entry.put("complete", true);
        // Epoch ms for the turn-tail clock label — the same fact the TUI
        // transcript row stamps. Absent on synthetic rows (no durable time).
        message.timestamp().map(Instant::toEpochMilli)
            .ifPresent(time -> entry.put("time", time));
        if (turn > 0) {
            entry.put("turn", turn);
            // The step's provider-reported buckets, raw integers — the same
            // contract the turn.completed frame's delta serves.
            AssistantContent envelope = message.message();
            Usage usage = envelope == null ? null : envelope.usage();
            if (usage != null) {
                entry.set("turn_usage", turnUsageBody(usage, envelope.model()));
            }
        }
        ArrayNode content = entry.putArray("content");
        AssistantContent envelope = message.message();
        if (envelope == null || envelope.content() == null) return entry;
        for (ContentBlock block : envelope.content()) {
            switch (block) {
                case TextBlock text -> content.add(objectOf(
                    "type", "text", "text", text.text()));
                case ThinkingBlock thinking -> content.add(objectOf(
                    "type", "thinking", "thinking", thinking.thinking()));
                case ToolUseBlock tool -> content.add(toolCallEntry(tool, resultsByUseId));
                default -> { /* Rich blocks stay in the local renderer. */ }
            }
        }
        return entry;
    }

    /**
     * One assistant step's reported token buckets, snake_case on the wire.
     * The model id rides along as the usage dialog's model-route row (absent
     * on rows whose envelope carried none).
     */
    private static ObjectNode turnUsageBody(Usage usage, String model) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        if (model != null) node.put("model", model);
        node.put("uncached_input_tokens", usage.inputTokens());
        node.put("output_tokens", usage.outputTokens());
        node.put("cache_write_tokens", usage.cacheCreationInputTokens());
        node.put("cache_read_tokens", usage.cacheReadInputTokens());
        node.put("total_tokens", usage.inputTokens() + usage.outputTokens()
            + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens());
        return node;
    }

    /**
     * One user message's textual and image content, when it carries any.
     *
     * <p>Mirrors the TUI's default (non-transcript) visibility rule
     * ({@code MessageConstants.shouldShowUserMessage}): {@code isMeta} rows
     * and transcript-only rows never reach the ordinary view, so they never
     * reach this snapshot either. A compact-summary row IS shown, but never
     * with its raw injected continuation prompt — the TUI collapses it to a
     * title line by default (full text is a Ctrl+O-only reveal, a control
     * webui has no counterpart for), so this projects the same collapsed
     * title instead of the multi-thousand-token summary body.
     */
    private static ObjectNode userEntry(UserMessage message) {
        if (message.isMeta() || Boolean.TRUE.equals(message.isVisibleInTranscriptOnly())) {
            return null;
        }
        if (message.isCompactSummary()) return compactSummaryEntry(message);
        MessageContent content = message.message();
        if (content == null) return null;
        String text = content.text();
        ArrayNode images = JsonUtils.getMapper().createArrayNode();
        if (text == null && content.blocks() != null) {
            StringBuilder body = new StringBuilder();
            for (ContentBlock block : content.blocks()) {
                if (block instanceof TextBlock(String text1)) body.append(text1);
                else if (block instanceof ImageBlock image) addImage(images, image);
            }
            text = body.isEmpty() ? null : body.toString();
        } else if (content.blocks() != null) {
            for (ContentBlock block : content.blocks()) {
                if (block instanceof ImageBlock image) addImage(images, image);
            }
        }
        if (StringUtils.isBlank(text) && images.isEmpty()) return null;
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        if (message.uuid() != null) entry.put("id", message.uuid());
        entry.put("role", "user");
        entry.put("complete", true);
        // Epoch ms for the user row's leading clock label (upstream places
        // the clock before the copy/branch icons on user rows).
        message.timestamp().map(Instant::toEpochMilli)
            .ifPresent(time -> entry.put("time", time));
        if (StringUtils.isNotBlank(text)) entry.put("text", text);
        if (!images.isEmpty()) entry.set("images", images);
        return entry;
    }

    /**
     * The collapsed placeholder for a compact-summary row — the wire
     * counterpart of {@code UserMessageRenderer.renderCompactSummary}'s
     * default-view title (+ the partial-summary detail lines when
     * {@code summarizeMetadata} is present). Never carries the raw
     * continuation-prompt body.
     */
    private static ObjectNode compactSummaryEntry(UserMessage message) {
        SummarizeMetadata metadata = message.summarizeMetadata();
        StringBuilder text = new StringBuilder(
            metadata == null ? "Compact summary" : "Summarized conversation");
        if (metadata != null) {
            String position = Strings.CS.equals("up_to", metadata.direction())
                ? "up to this point" : "from this point";
            text.append("\nSummarized ").append(metadata.messagesSummarized())
                .append(" messages ").append(position);
            if (StringUtils.isNotBlank(metadata.userContext())) {
                text.append("\nContext: \u201c").append(metadata.userContext()).append('\u201d');
            }
        }
        ObjectNode entry = JsonUtils.getMapper().createObjectNode();
        if (message.uuid() != null) entry.put("id", message.uuid());
        entry.put("role", "user");
        entry.put("complete", true);
        message.timestamp().map(Instant::toEpochMilli)
            .ifPresent(time -> entry.put("time", time));
        entry.put("text", text.toString());
        return entry;
    }

    /**
     * Appends one {@code {media_type, data}} entry from an {@link ImageBlock}'s
     * base64 source — the same shape the webui submits images in, so the wire
     * is symmetric in both directions.
     */
    private static void addImage(ArrayNode images, ImageBlock image) {
        JsonNode source = image.source();
        if (source == null) return;
        String data = source.path("data").asText(null);
        if (data == null) return;
        ObjectNode node = images.addObject();
        String mediaType = source.path("media_type").asText(null);
        if (mediaType != null) node.put("media_type", mediaType);
        node.put("data", data);
    }

    /**
     * One typed tool_call entry: the tool_use block paired with its later
     * tool_result block. Without the result the call is still pending.
     */
    private static ObjectNode toolCallEntry(
            ToolUseBlock tool, Map<String, ToolOutcome> resultsByUseId) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("type", "tool_call");
        ObjectNode call = node.putObject("tool");
        call.put("tool_use_id", tool.id());
        call.put("name", tool.name());
        if (tool.input() != null) call.set("args", tool.input());
        ToolOutcome outcome = tool.id() == null ? null : resultsByUseId.get(tool.id());
        if (outcome == null) {
            call.put("status", "pending");
            call.put("ready", false);
            return node;
        }
        call.put("status", outcome.result().isError() ? "failed" : "executed");
        call.put("ready", true);
        call.set("result", projectResult(tool.name(), outcome.result(), outcome.toolUseResult()));
        return node;
    }

    /** The result projection shared with the mirror frame shape. */
    private static ObjectNode projectResult(
            String toolName, ToolResultBlock result, Object toolUseResult) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("type", resultType(toolName));
        String text = resultText(result);
        if (text == null) {
            node.putNull("data");
        } else if (text.length() > MAX_RESULT_CHARS) {
            node.put("data", text.substring(0, MAX_RESULT_CHARS) + TRUNCATION_MARKER);
        } else {
            node.put("data", text);
        }
        if (result.isError()) {
            node.put("errorMessage", text);
            node.put("errorCode", "tool_error");
        }
        List<String> locations = toolLocations(toolName, toolUseResult);
        if (!locations.isEmpty()) {
            ArrayNode array = node.putArray("locations");
            locations.forEach(array::add);
        }
        return node;
    }

    /**
     * The file path(s) a Write/Edit/NotebookEdit tool produced, or empty for
     * every other tool. Mirrors {@code MirrorHub}'s helper of the same name —
     * both files independently project the same {@code ToolResult} wire
     * shape and neither shares a common projection module.
     */
    private static List<String> toolLocations(String toolName, Object toolUseResult) {
        String field = switch (toolName) {
            case "Write", "Edit" -> "filePath";
            case "NotebookEdit" -> "notebook_path";
            case null, default -> null;
        };
        if (field == null || toolUseResult == null) return List.of();
        JsonNode payload;
        try {
            payload = JsonUtils.getMapper().valueToTree(toolUseResult);
        } catch (RuntimeException _) {
            return List.of();
        }
        JsonNode path = payload.path(field);
        return path.isTextual() && StringUtils.isNotBlank(path.asText())
            ? List.of(path.asText()) : List.of();
    }

    /** Concatenates the result's textual content blocks, or null when none. */
    private static String resultText(ToolResultBlock result) {
        if (result.content() == null || result.content().isEmpty()) return null;
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : result.content()) {
            if (block instanceof TextBlock(String text1)) body.append(text1);
        }
        return body.isEmpty() ? null : body.toString();
    }

    /** The typed result discriminator for the tool family, when known. */
    private static String resultType(String toolName) {
        if (toolName == null) return "tool_result";
        return switch (toolName) {
            case "Bash" -> "execute_command_tool_result";
            case "Read" -> "read_file_tool_result";
            case "Write" -> "write_to_file_tool_result";
            case "Edit", "NotebookEdit" -> "replace_in_file_tool_result";
            case "Task", "Agent" -> "task_tool_result";
            default -> "tool_result";
        };
    }

    private static ObjectNode objectOf(String... pairs) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return node;
    }

    private static ObjectNode errorBody(String type, String message) {
        ObjectNode body = JsonUtils.getMapper().createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("type", type);
        error.put("message", message);
        return body;
    }

    private static void respondJson(HttpExchange exchange, int status, ObjectNode body)
            throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
