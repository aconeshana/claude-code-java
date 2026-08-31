package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.config.VersionInfo;

import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsEvent;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageOrigin;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AutoModeReminderAttachment;
import com.claudecode.core.message.FileContentAttachment;
import com.claudecode.core.message.ImageFileAttachment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.error.ErrorUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * JSONL-based session storage for messages.
 */
public class SessionStorage {

    private static final Logger log = LoggerFactory.getLogger(SessionStorage.class);
    static final String APP_VERSION = VersionInfo.version();
    static final String USER_TYPE = Optional.ofNullable(System.getenv("USER_TYPE")).orElse("external");
    private static final String SYNTHETIC_MODEL = "<synthetic>";
    private static final String RECOVERY_CONTINUATION = "Continue from where you left off.";
    private static final int TOMBSTONE_TAIL_BYTES = 64 * 1024;
    private static final long MAX_TOMBSTONE_REWRITE_BYTES = 50L * 1024 * 1024;

    private final ObjectMapper mapper;
    private volatile BiConsumer<Path, ObjectNode> appendListener = (_, _) -> { };

    public SessionStorage() {
        this(JsonUtils.getMapper());
    }

    public SessionStorage(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Receives a lossless copy of every successfully appended JSONL row. */
    public void setAppendListener(BiConsumer<Path, ObjectNode> listener) {
        appendListener = listener != null ? listener : (_, _) -> { };
    }

    /**
     * Writes the sibling used by local-agent resume.
     */
    public void writeAgentMetadata(Path transcriptFile, AgentMetadata metadata) {
        Path metadataFile = agentMetadataPath(transcriptFile);
        ObjectNode json = mapper.createObjectNode();
        json.put("agentType", metadata.agentType());
        if (metadata.worktreePath() != null) json.put("worktreePath", metadata.worktreePath());
        if (metadata.description() != null) json.put("description", metadata.description());
        if (metadata.stoppedByUser()) json.put("stoppedByUser", true);
        if (metadata.spawnDepth() != null) json.put("spawnDepth", metadata.spawnDepth());
        if (metadata.subagentMaxDepth() != null) {
            json.put("subagentMaxDepth", metadata.subagentMaxDepth());
        }
        try {
            Files.createDirectories(metadataFile.getParent());
            Files.writeString(metadataFile, mapper.writeValueAsString(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write agent metadata: " + metadataFile, e);
        }
    }

    /**
     * Reads the local-agent resume sidecar.
     */
    public Optional<AgentMetadata> readAgentMetadata(Path transcriptFile) {
        Path metadataFile = agentMetadataPath(transcriptFile);
        String raw;
        try {
            raw = Files.readString(metadataFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (isFsInaccessible(e)) return Optional.empty();
            throw new UncheckedIOException("Failed to read agent metadata: " + metadataFile, e);
        }
        try {
            JsonNode json = mapper.readTree(raw);
            if (json == null || json.isNull()) return Optional.empty();
            return Optional.of(new AgentMetadata(
                json.path("agentType").asText(null),
                json.path("worktreePath").asText(null),
                json.path("description").asText(null),
                json.path("stoppedByUser").asBoolean(false),
                integralOrNull(json.get("spawnDepth")),
                integralOrNull(json.get("subagentMaxDepth"))));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid agent metadata: " + metadataFile, e);
        }
    }

    private static Integer integralOrNull(JsonNode node) {
        return node != null && node.isIntegralNumber() ? node.asInt() : null;
    }

    private static Path agentMetadataPath(Path transcriptFile) {
        String name = transcriptFile.getFileName().toString();
        String metadataName = Strings.CS.endsWith(name, ".jsonl")
            ? name.substring(0, name.length() - ".jsonl".length()) + ".meta.json"
            : name;
        return transcriptFile.resolveSibling(metadataName);
    }

    private static boolean isFsInaccessible(IOException error) {
        if (error instanceof NoSuchFileException
                || error instanceof AccessDeniedException
                || error instanceof NotDirectoryException
                || error instanceof FileSystemLoopException) {
            return true;
        }
        if (error instanceof FileSystemException fileSystemError) {
            String reason = fileSystemError.getReason();
            return reason != null && (Strings.CI.contains(reason, "permission denied")
                || Strings.CI.contains(reason, "operation not permitted"));
        }
        return false;
    }

    // ── Write path ──────────────────────────────────────────────────────────

    /**
     * Appends a message with session-level metadata to the session JSONL file.
     */
    public void appendMessage(Path sessionFile, Message message,
                              String sessionId, String cwd,
                              boolean isSidechain, String gitBranch) {
        appendMessage(sessionFile, message, sessionId, cwd, isSidechain, null, gitBranch);
    }

    /**
     * Like {@link #appendMessage(Path, Message, String, String, boolean, String)} but also stamps
     * {@code agentId} — the sub-agent invocation this message belongs to.
     */
    public void appendMessage(Path sessionFile, Message message,
                              String sessionId, String cwd,
                              boolean isSidechain, String agentId, String gitBranch) {
        try {
            writeNode(sessionFile, stampMessage(
                message, sessionId, cwd, isSidechain, agentId, gitBranch,
                null, null, "sdk", null, TeamInfo.EMPTY));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append message to " + sessionFile, e);
        }
    }

    /**
     * Like {@link #appendMessage(Path, Message, String, String, boolean, String, String)} but stamps an
     * explicit {@code parentUuid}.
     */
    public void appendMessageWithParent(Path sessionFile, Message message,
                                         String sessionId, String cwd,
                                         boolean isSidechain, String agentId, String gitBranch,
                                         String logicalParentUuid, String parentUuid) {
        appendMessageWithParent(sessionFile, message, sessionId, cwd, isSidechain,
            agentId, gitBranch, null, logicalParentUuid, parentUuid);
    }


    public void appendMessageWithParent(Path sessionFile, Message message,
                                         String sessionId, String cwd,
                                         boolean isSidechain, String agentId, String gitBranch,
                                         String slug, String logicalParentUuid,
                                         String parentUuid) {
        appendMessageWithParent(sessionFile, message, sessionId, cwd, isSidechain,
            agentId, gitBranch, slug, logicalParentUuid, parentUuid, null);
    }


    public void appendMessageWithParent(Path sessionFile, Message message,
                                         String sessionId, String cwd,
                                         boolean isSidechain, String agentId, String gitBranch,
                                         String slug, String logicalParentUuid,
                                         String parentUuid, String promptId) {
        appendMessageWithParent(sessionFile, message, sessionId, cwd, isSidechain,
            agentId, gitBranch, slug, logicalParentUuid, parentUuid, promptId, "sdk");
    }


    public void appendMessageWithParent(Path sessionFile, Message message,
                                         String sessionId, String cwd,
                                         boolean isSidechain, String agentId, String gitBranch,
                                         String slug, String logicalParentUuid,
                                         String parentUuid, String promptId,
                                         String promptSource) {
        appendMessageWithParent(sessionFile, message, sessionId, cwd, isSidechain,
            agentId, gitBranch, slug, logicalParentUuid, parentUuid, promptId,
            promptSource, null);
    }


    public void appendMessageWithParent(Path sessionFile, Message message,
                                         String sessionId, String cwd,
                                         boolean isSidechain, String agentId, String gitBranch,
                                         String slug, String logicalParentUuid,
                                         String parentUuid, String promptId,
                                         String promptSource, String attributionAgent) {
        appendMessageWithParent(sessionFile, message, sessionId, cwd, isSidechain,
            agentId, gitBranch, slug, logicalParentUuid, parentUuid, promptId,
            promptSource, attributionAgent, TeamInfo.EMPTY);
    }


    public void appendMessageWithParent(Path sessionFile, Message message,
                                         String sessionId, String cwd,
                                         boolean isSidechain, String agentId, String gitBranch,
                                         String slug, String logicalParentUuid,
                                         String parentUuid, String promptId,
                                         String promptSource, String attributionAgent,
                                         TeamInfo teamInfo) {
        try {
            ObjectNode node = stampMessage(
                message, sessionId, cwd, isSidechain, agentId, gitBranch,
                slug, promptId, promptSource, attributionAgent, teamInfo);

            node.put("parentUuid", parentUuid);
            // logicalParentUuid is written only on compact boundaries (null here means

            if (logicalParentUuid != null) {
                node.put("logicalParentUuid", logicalParentUuid);
            }
            writeNode(sessionFile, node);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append message to " + sessionFile, e);
        }
    }

/**
     * Builds an {@link ObjectNode} from {@code message} with all session-level metadata stamped — the
     * shared stamping logic used by both {@link #appendMessage} and {@link #appendMessageWithParent}.
     */
    private ObjectNode stampMessage(Message message, String sessionId, String cwd,
                                     boolean isSidechain, String agentId, String gitBranch,
                                     String slug, String promptId, String promptSource,
                                     String attributionAgent, TeamInfo teamInfo) {
        ObjectNode node = mapper.valueToTree(message);
        if (message instanceof UserMessage user) {
            // Native Claude Code stores the API-style envelope inside
            // transcript user rows, not Java's MessageContent record shape.
            ObjectNode envelope = mapper.createObjectNode();
            envelope.put("role", "user");
            if (user.message() == null) {
                envelope.putNull("content");
            } else if (user.message().isText()) {
                envelope.put("content", user.message().text());
            } else {
                // Reuse the already-serialized MessageContent.blocks node. A
                // fresh valueToTree(List) loses the declared ContentBlock
                // element type and therefore omits Jackson's polymorphic
                // `type` discriminator from tool_result/image blocks.
                envelope.set("content", node.path("message").path("blocks").deepCopy());
                normalizeSingleTextToolResults(
                    envelope.path("content"), user.toolUseResult(),
                    user.message().blocks().stream()
                        .filter(ToolResultBlock.class::isInstance)
                        .map(ToolResultBlock.class::cast)
                        .anyMatch(ToolResultBlock::preserveContentBlocks));
            }
            node.set("message", envelope);
            node.put("promptId",
                StringUtils.isNotBlank(promptId)
                    ? promptId : UUID.randomUUID().toString());
            node.put("promptSource",
                StringUtils.isNotBlank(promptSource) ? promptSource : "sdk");
            if (isSidechain && (StringUtils.isBlank(promptSource))) {
                node.remove("promptSource");
            }
            if (isSidechain) {
                node.remove("permissionMode");
            }
            if (isRecoveryContinuation(user)) {

                // unlike a submitted SDK prompt it has no promptSource field.
                node.remove("promptSource");
            }
            if (isPromptCommandMetadata(user)) {
                node.remove("promptSource");
            }
            if (isSyntheticInterruption(user)) {

                node.remove("promptSource");
                node.remove("origin");
            }
            if (user.isCompactSummary()) {
                if (user.summarizeMetadata() == null) {
                    node.put("isVisibleInTranscriptOnly", true);
                } else {
                    node.remove("isVisibleInTranscriptOnly");
                }
                node.remove("promptSource");
                node.remove("origin");
            } else if (isLocalCommandTranscriptMessage(user)) {
                node.remove("promptSource");
                node.remove("origin");

                node.remove("sourceToolAssistantUUID");
            }
            if (!user.isMeta()) node.remove("isMeta");
            if (!user.isCompactSummary()) node.remove("isCompactSummary");
            if (isToolResultMessage(user)) {
                node.remove("promptSource");
                node.remove("origin");
            } else if (user.sourceToolUseID() != null) {

                // tool. They participate in the current turn but were not submitted
                // by the human, so they do not inherit that turn's prompt provenance.
                node.remove("promptSource");
                node.remove("origin");
            } else if (user.isMeta()) {
                // createUserMessage({isMeta:true}) messages injected by tools
                // (PDF pages/full documents/image metadata) are internal
                // conversation material, not independently submitted SDK prompts.
                node.remove("promptSource");
                node.remove("origin");
            } else if (!isLocalCommandTranscriptMessage(user)
                    && user.origin() == MessageOrigin.USER
                    && Strings.CS.equals("typed", promptSource)) {
                ObjectNode origin = mapper.createObjectNode();
                origin.put("kind", "human");
                node.set("origin", origin);
            } else if (user.origin() == MessageOrigin.TASK_NOTIFICATION) {
                ObjectNode origin = mapper.createObjectNode();
                origin.put("kind", "task-notification");
                node.set("origin", origin);
            } else if (user.origin() == MessageOrigin.AUTO_CONTINUATION) {
                ObjectNode origin = mapper.createObjectNode();
                origin.put("kind", "auto-continuation");
                node.set("origin", origin);
            } else if (user.origin() == null || user.origin() == MessageOrigin.USER) {
                node.remove("origin");
            }
        } else if (message instanceof AssistantMessage assistant) {
            AssistantContent response = assistant.message();
            boolean synthetic = response != null && SYNTHETIC_MODEL.equals(response.model());
            ObjectNode serialized = node.path("message") instanceof ObjectNode object
                ? object : mapper.createObjectNode();
            ObjectNode envelope = mapper.createObjectNode();
            if (response != null && response.id() != null) envelope.put("id", response.id());
            else envelope.putNull("id");
            if (synthetic) envelope.putNull("container");
            envelope.put("type", "message");
            envelope.put("role", "assistant");
            if (response != null && response.model() != null) envelope.put("model", response.model());
            else envelope.putNull("model");
            envelope.set("content", serialized.path("content").deepCopy());
            if (response != null && response.stopReason() != null) {
                envelope.put("stop_reason", response.stopReason());
            } else {
                envelope.putNull("stop_reason");
            }
            if (response != null && response.stopSequence() != null) {
                envelope.put("stop_sequence", response.stopSequence());
            } else {
                envelope.putNull("stop_sequence");
            }
            envelope.set("usage", synthetic
                ? syntheticUsage(response.usage())
                : officialUsage(response != null ? response.usage() : null));
            envelope.putNull("stop_details");
            if (synthetic) envelope.putNull("context_management");
            node.set("message", envelope);
            if (!assistant.isApiErrorMessage() && !synthetic) node.remove("isApiErrorMessage");
            if (isSidechain && attributionAgent != null && !StringUtils.isBlank(attributionAgent)) {
                node.put("attributionAgent", attributionAgent);
            }
        } else if (message instanceof SystemMessage system
                && (Strings.CS.equals("compact_boundary", system.subtype())
                    || Strings.CS.equals("turn_duration", system.subtype())
                    || Strings.CS.equals("informational", system.subtype())
                    || Strings.CS.equals("model_refusal_fallback", system.subtype())
                    || Strings.CS.equals("model_refusal_no_fallback", system.subtype()))) {
            node.put("isMeta", false);
        } else if (message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof AutoModeReminderAttachment
                && node.path("attachment") instanceof ObjectNode serializedAttachment) {

            // reminderType still selects full vs sparse prompt text before the
            // transcript is written, but it is not part of the JSONL contract.
            serializedAttachment.remove("reminderType");
        } else if (message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof FileContentAttachment fileContent) {
            normalizeFileAttachment(node, fileContent, cwd);
        } else if (message instanceof AttachmentMessage attachment
                && attachment.payload() instanceof ImageFileAttachment imageFile) {
            normalizeImageFileAttachment(node, imageFile);
        }
        node.put("cwd", cwd != null ? cwd : System.getProperty("user.dir"));
        node.put("userType", USER_TYPE);
        node.put("entrypoint", entrypoint());
        node.put("sessionId", sessionId);
        node.put("timestamp", message.timestamp().orElseGet(Instant::now).toString());
        node.put("version", APP_VERSION);
        node.put("isSidechain", isSidechain);
        if (agentId != null) {
            node.put("agentId", agentId);
        }
        if (gitBranch != null) {
            node.put("gitBranch", gitBranch);
        }
        if (slug != null) {
            node.put("slug", slug);
        }
        if (teamInfo != null && StringUtils.isNotBlank(teamInfo.teamName())) {
            node.put("teamName", teamInfo.teamName());
        }
        if (teamInfo != null && StringUtils.isNotBlank(teamInfo.agentName())) {
            node.put("agentName", teamInfo.agentName());
        }
        return node;
    }

    private static void normalizeImageFileAttachment(ObjectNode node,
                                                      ImageFileAttachment attachment) {
        ObjectNode serialized = JsonUtils.getMapper().createObjectNode();
        serialized.put("type", "file");
        serialized.put("filename", attachment.filename());
        ObjectNode content = serialized.putObject("content");
        content.put("type", "image");
        ObjectNode file = content.putObject("file");
        file.put("base64", attachment.base64());
        file.put("type", attachment.mediaType());
        file.put("originalSize", attachment.originalSize());
        if (attachment.dimensions() != null) {
            ObjectNode dimensions = file.putObject("dimensions");
            if (attachment.dimensions().originalWidth() != null) {
                dimensions.put("originalWidth", attachment.dimensions().originalWidth());
            }
            if (attachment.dimensions().originalHeight() != null) {
                dimensions.put("originalHeight", attachment.dimensions().originalHeight());
            }
            if (attachment.dimensions().displayWidth() != null) {
                dimensions.put("displayWidth", attachment.dimensions().displayWidth());
            }
            if (attachment.dimensions().displayHeight() != null) {
                dimensions.put("displayHeight", attachment.dimensions().displayHeight());
            }
        }
        serialized.put("displayPath", attachment.displayPath());
        node.set("attachment", serialized);
    }

    private static void normalizeFileAttachment(ObjectNode node,
                                                FileContentAttachment attachment,
                                                String cwd) {
        Path file = Path.of(attachment.filename()).normalize();
        String rawContent;
        try {
            rawContent = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException _) {
            rawContent = attachment.content();
        }
        int lineCount = rawContent.split("\\n", -1).length;
        ObjectNode serialized = JsonUtils.getMapper().createObjectNode();
        serialized.put("type", "file");
        serialized.put("filename", attachment.filename());
        ObjectNode content = serialized.putObject("content");
        content.put("type", "text");
        ObjectNode fileNode = content.putObject("file");
        fileNode.put("filePath", attachment.filename());
        fileNode.put("content", rawContent);
        fileNode.put("numLines", lineCount);
        fileNode.put("startLine", 1);
        fileNode.put("totalLines", lineCount);
        Path cwdPath = StringUtils.isBlank(cwd) ? null : Path.of(cwd).normalize();
        String displayPath = cwdPath != null && file.startsWith(cwdPath)
            ? cwdPath.relativize(file).toString() : file.getFileName().toString();
        serialized.put("displayPath", displayPath);
        node.set("attachment", serialized);
    }

    private static boolean isToolResultMessage(UserMessage user) {
        if (user.toolUseResult() != null) return true;
        return user.message() != null && user.message().blocks() != null
            && user.message().blocks().stream().anyMatch(
                ToolResultBlock.class::isInstance);
    }

    private static boolean isRecoveryContinuation(UserMessage user) {
        if (!user.isMeta() || user.message() == null || user.message().blocks() == null
                || user.message().blocks().size() != 1) {
            return false;
        }
        return user.message().blocks().getFirst() instanceof TextBlock(String text1)
            && RECOVERY_CONTINUATION.equals(text1);
    }

    private static boolean isSyntheticInterruption(UserMessage user) {
        if (user.message() == null || user.message().blocks() == null
            || user.message().blocks().size() != 1) {
            return false;
        }
        if (!(user.message().blocks().getFirst() instanceof TextBlock(String text1))) {
            return false;
        }
        return Strings.CS.equals(MessageConstants.INTERRUPT_MESSAGE, text1)
            || Strings.CS.equals(MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE, text1);
    }


    private static void normalizeSingleTextToolResults(JsonNode content,
                                                        Object toolUseResult,
                                                        boolean preserveContentBlocks) {
        if (content == null || !content.isArray()) return;
        if (preserveContentBlocks) return;
// MCPTool.mapToolResultToToolResultBlockParam receives the protocol's content-block array
// directly.
        if (isContentBlockArrayToolUseResult(toolUseResult)) return;
        for (JsonNode block : content) {
            if (!(block instanceof ObjectNode object)
                    || !Strings.CS.equals("tool_result", object.path("type").asText())) {
                continue;
            }
            JsonNode nested = object.path("content");
            if (nested.isArray() && nested.size() == 1
                    && Strings.CS.equals("text", nested.get(0).path("type").asText())
                    && nested.get(0).has("text")) {
                object.put("content", nested.get(0).path("text").asText());
            }
        }
    }

    /** MCP protocol result arrays contain typed content blocks; structured tool
     * outputs such as ListMcpResourcesTool are ordinary JSON arrays and the
     * established tool mapping serializes those to a scalar JSON string. */
    private static boolean isContentBlockArrayToolUseResult(Object toolUseResult) {
        JsonNode node = toolUseResult instanceof JsonNode json
            ? json : JsonUtils.getMapper().valueToTree(toolUseResult);
        if (node == null || !node.isArray() || node.isEmpty()) return false;
        for (JsonNode item : node) {
            if (!item.isObject() || !item.hasNonNull("type")) return false;
        }
        return true;
    }

    private static boolean isLocalCommandTranscriptMessage(UserMessage user) {
        if (user.message() == null || !user.message().isText()) return false;
        String text = user.message().text();
        return Strings.CS.startsWith(text, "<local-command-caveat>")
            || Strings.CS.startsWith(text, "<command-name>")
            || Strings.CS.startsWith(text, "<local-command-stdout>");
    }

    private static boolean isPromptCommandMetadata(UserMessage user) {
        return user.message() != null && user.message().isText()
            && Strings.CS.startsWith(user.message().text(), "<command-message>");
    }


    private ObjectNode officialUsage(Usage usage) {
        ObjectNode result = mapper.valueToTree(usage != null ? usage : Usage.EMPTY);
        if (!result.hasNonNull("service_tier")) result.put("service_tier", "standard");
        ObjectNode cacheCreation;
        if (result.path("cache_creation") instanceof ObjectNode existing) {
            cacheCreation = existing;
        } else {
            cacheCreation = mapper.createObjectNode();
            result.set("cache_creation", cacheCreation);
        }
        if (!cacheCreation.has("ephemeral_1h_input_tokens")) {
            cacheCreation.put("ephemeral_1h_input_tokens", 0);
        }
        if (!cacheCreation.has("ephemeral_5m_input_tokens")) {
            cacheCreation.put("ephemeral_5m_input_tokens", 0);
        }
        if (!result.hasNonNull("inference_geo")) result.put("inference_geo", "");
        if (!result.path("iterations").isArray()) result.putArray("iterations");
        if (!result.hasNonNull("speed")) result.put("speed", "standard");
        return result;
    }

    private ObjectNode syntheticUsage(Usage usage) {
        Usage value = usage != null ? usage : new Usage(
            0, 0, 0, 0, Usage.ServerToolUse.ZERO, null,
            Usage.CacheCreation.ZERO, null, null, null);
        ObjectNode result = mapper.createObjectNode();
        result.put("input_tokens", value.inputTokens());
        result.put("output_tokens", value.outputTokens());
        result.put("cache_creation_input_tokens", value.cacheCreationInputTokens());
        result.put("cache_read_input_tokens", value.cacheReadInputTokens());
        Usage.ServerToolUse tools = value.serverToolUse() != null
            ? value.serverToolUse() : Usage.ServerToolUse.ZERO;
        ObjectNode serverToolUse = result.putObject("server_tool_use");
        serverToolUse.put("web_search_requests", tools.webSearchRequests());
        serverToolUse.put("web_fetch_requests", tools.webFetchRequests());
        result.putNull("service_tier");
        Usage.CacheCreation cache = value.cacheCreation() != null
            ? value.cacheCreation() : Usage.CacheCreation.ZERO;
        ObjectNode cacheCreation = result.putObject("cache_creation");
        cacheCreation.put("ephemeral_1h_input_tokens", cache.ephemeral1hInputTokens());
        cacheCreation.put("ephemeral_5m_input_tokens", cache.ephemeral5mInputTokens());
        result.putNull("inference_geo");
        result.putNull("iterations");
        result.putNull("speed");
        return result;
    }

    private static String entrypoint() {
        String explicit = System.getenv("CLAUDE_CODE_ENTRYPOINT");
        if (StringUtils.isNotBlank(explicit)) return explicit;
        String runtime = System.getProperty("claude.code.entrypoint");
        return StringUtils.isNotBlank(runtime) ? runtime : "cli";
    }

    /** Returns the last persisted session slug, if this transcript has one. */
    public Optional<String> readSessionSlug(Path sessionFile) {
        if (sessionFile == null || !Files.exists(sessionFile)) return Optional.empty();
        String slug = null;
        try {
            for (JsonNode line : JsonUtils.readJsonLines(sessionFile)) {
                if (line.hasNonNull("slug") && !StringUtils.isBlank(line.path("slug").asText())) {
                    slug = line.path("slug").asText();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read session slug from {} [failureType={}]",
                sessionFile, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
        }
        return Optional.ofNullable(slug);
    }

    /**
     * Backward-compatible overload: appends with default cwd and isSidechain=false.
     */
    public void appendMessage(Path sessionFile, Message message) {
        appendMessage(sessionFile, message,
            /* sessionId */ sessionFile.getFileName().toString().replace(".jsonl", ""),
            /* cwd       */ System.getProperty("user.dir"),
            /* isSidechain */ false,
            /* gitBranch */ null);
    }

    /**
     * Appends a sentinel JSONL entry (non-Message) such as {@code {type:"custom-title",
     * customTitle:..., sessionId:...}} or {@code {type:"agent-name", agentName:..., sessionId:...}}.
     */
    public void appendCustomEntry(Path sessionFile, ObjectNode entry) {
        try {
            writeNode(sessionFile, entry);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append entry to " + sessionFile, e);
        }
    }

    public void appendMode(Path sessionFile, String sessionId, String mode) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "mode");
        entry.put("mode", mode);
        entry.put("sessionId", sessionId);
        appendCustomEntry(sessionFile, entry);
    }

    /**
     * Appends a {@code file-history-snapshot} entry.
     */
    public void insertFileHistorySnapshot(Path sessionFile, String messageId, JsonNode snapshotJson, boolean isSnapshotUpdate) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "file-history-snapshot");
        entry.put("messageId", messageId);
        entry.put("isSnapshotUpdate", isSnapshotUpdate);
        entry.set("snapshot", snapshotJson);
        appendCustomEntry(sessionFile, entry);
    }

    /** One {@code file-history-snapshot} JSONL entry, as read back for resume. */
    public record FileHistorySnapshotEntry(String messageId, JsonNode snapshotJson, boolean isSnapshotUpdate) {}


    public List<FileHistorySnapshotEntry> scanFileHistorySnapshots(Path sessionFile) {
        if (!Files.exists(sessionFile)) return List.of();
        List<FileHistorySnapshotEntry> entries = new ArrayList<>();
        try {
            for (JsonNode node : JsonUtils.readJsonLines(sessionFile)) {
                if (!Strings.CS.equals("file-history-snapshot", node.path("type").asText())) continue;
                String messageId = node.hasNonNull("messageId") ? node.get("messageId").asText() : null;
                JsonNode snapshot = node.get("snapshot");
                boolean isUpdate = node.path("isSnapshotUpdate").asBoolean(false);
                if (messageId != null && snapshot != null) {
                    entries.add(new FileHistorySnapshotEntry(messageId, snapshot, isUpdate));
                }
            }
        } catch (IOException e) {
            log.warn("scanFileHistorySnapshots failed for {} [failureType={}]",
                sessionFile, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
            return List.of();
        }
        return entries;
    }

    /**
     * One {@code worktree-state} JSONL entry, as read back on {@code /resume}.
     */
    public record WorktreeStateEntry(String sessionId, JsonNode worktreeSessionJson) {}

    /**
     * Appends a {@code worktree-state} entry.
     */
    public void appendWorktreeState(Path sessionFile, String sessionId, ObjectNode worktreeSessionJson) {
        ObjectNode entry = mapper.createObjectNode();
        entry.put("type", "worktree-state");
        entry.put("sessionId", sessionId);
        if (worktreeSessionJson != null) {
            entry.set("worktreeSession", worktreeSessionJson);
        } else {
            entry.putNull("worktreeSession");
        }
        appendCustomEntry(sessionFile, entry);
    }

    /**
     * Reads the LAST {@code worktree-state} entry in {@code sessionFile} (append-only
     * log, last-seen-wins — same trade-off as {@link #scanFileHistorySnapshots}).
     * Returns {@code null} if the file has no such entry at all.
     */
    public WorktreeStateEntry scanWorktreeState(Path sessionFile) {
        if (!Files.exists(sessionFile)) return null;
        WorktreeStateEntry last = null;
        try {
            for (JsonNode node : JsonUtils.readJsonLines(sessionFile)) {
                if (!Strings.CS.equals("worktree-state", node.path("type").asText())) continue;
                String sid = node.hasNonNull("sessionId") ? node.get("sessionId").asText() : null;
                JsonNode ws = node.get("worktreeSession");
                last = new WorktreeStateEntry(sid, (ws == null || ws.isNull()) ? null : ws);
            }
        } catch (IOException e) {
            log.warn("scanWorktreeState failed for {} [failureType={}]",
                sessionFile, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
            return null;
        }
        return last;
    }

    private void writeNode(Path sessionFile, ObjectNode node) throws IOException {
        String json = mapper.writeValueAsString(node);
        Path parent = sessionFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            trySetPosix(parent, DIR_PERMS);
        }
        boolean isNew = !Files.exists(sessionFile);
        Files.writeString(sessionFile, json + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try {
            appendListener.accept(sessionFile, node.deepCopy());
        } catch (RuntimeException e) {
            log.debug("Transcript append listener failed for {}: {}", sessionFile, e.getMessage());
        }
        if (isNew) {

            trySetPosix(sessionFile, FILE_PERMS);
        }
    }


    private static final Set<PosixFilePermission> FILE_PERMS = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);


    private static final Set<PosixFilePermission> DIR_PERMS = EnumSet.of(
        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private static void trySetPosix(Path path, Set<PosixFilePermission> perms) {
        try {
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException _) {
            // Windows / non-POSIX FS — fall through with default ACLs.
        }
    }

    // ── Metadata snapshot scan (for /resume) ─────────────────────────────

    /**
     * Snapshot of session-scoped metadata written into the JSONL by the
     * previous run — everything {@code /resume} needs to reapply so the
     * resumed shell looks and behaves like the one that was closed.
     */
    public record MetadataSnapshot(
        Optional<String> mode,
        Optional<String> permissionMode,
        Optional<String> customTitle,
        Optional<String> agentName,
        Optional<String> agentColor,
        Optional<String> tag,
        Optional<String> lastPrompt,
        Optional<String> leafUuid) {
        public static MetadataSnapshot empty() {
            return new MetadataSnapshot(
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty());
        }
    }

    /**
     * One pass over the file scooping the latest metadata entry of each type.
     * Entries near the tail win — the writer appends every mutation and never
     * rewrites, so last-seen is authoritative.
     */
    public MetadataSnapshot scanMetadata(Path sessionFile) {
        if (!Files.exists(sessionFile)) return MetadataSnapshot.empty();
        String mode = null, permissionMode = null, customTitle = null;
        String agentName = null, agentColor = null, tag = null;
        String lastPrompt = null, leafUuid = null;
        try {
            for (JsonNode node : JsonUtils.readJsonLines(sessionFile)) {
                String type = node.hasNonNull("type") ? node.get("type").asText() : null;
                if (type == null) continue;
                String rowPermissionMode = textField(node, "permissionMode");
                if (rowPermissionMode != null) {
                    // Headless SDK transcripts carry the mode on each user
                    // message rather than in a permission-mode metadata row.
                    permissionMode = rowPermissionMode;
                }
                switch (type) {
                    case "mode"           -> mode           = textField(node, "mode");
                    case "permission-mode" -> permissionMode = textField(node, "permissionMode");
                    case "custom-title"   -> customTitle    = textField(node, "customTitle");
                    case "agent-name"     -> agentName      = textField(node, "agentName");
                    case "agent-color"    -> agentColor     = textField(node, "agentColor");
                    case "tag"            -> tag            = textField(node, "tag");
                    case "last-prompt"    -> {
                        String prompt = textField(node, "lastPrompt");
                        if (prompt != null) lastPrompt = prompt;
                        String candidate = textField(node, "leafUuid");
                        if (candidate != null) leafUuid = candidate;
                    }
                    default -> {
                        // Not a metadata entry consumed by this snapshot.
                    }
                }
            }
        } catch (IOException e) {
            log.warn("scanMetadata failed for {} [failureType={}]",
                sessionFile, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
            return MetadataSnapshot.empty();
        }
        return new MetadataSnapshot(
            Optional.ofNullable(mode),
            Optional.ofNullable(permissionMode),
            Optional.ofNullable(customTitle),
            Optional.ofNullable(agentName),
            Optional.ofNullable(agentColor),
            Optional.ofNullable(tag),
            Optional.ofNullable(lastPrompt),
            Optional.ofNullable(leafUuid));
    }

    /** Reads only Java HUD metric rows; malformed or future-schema rows are ignored. */
    public List<SessionMetricsEvent> readSessionMetrics(Path sessionFile) {
        if (sessionFile == null) return List.of();
        Path metricsFile = SessionMetricsFiles.sidecar(sessionFile);
        Path source = Files.isRegularFile(metricsFile) ? metricsFile : sessionFile;
        if (!Files.isRegularFile(source)) return List.of();
        List<SessionMetricsEvent> events = new ArrayList<>();
        try {
            for (JsonNode node : JsonUtils.readJsonLines(source)) {
                if (!Strings.CS.equals(SessionMetricsEvent.TRANSCRIPT_TYPE,
                        node.path("type").asText())) continue;
                try {
                    events.add(new SessionMetricsEvent(
                        node.path("schemaVersion").asInt(),
                        node.path("seq").asLong(),
                        node.path("time").asLong(),
                        node.path("sessionId").asText(),
                        SessionMetricsEvent.Kind.fromWireName(node.path("event").asText()),
                        node.hasNonNull("turnId") ? node.path("turnId").asText() : null,
                        node.path("turn").asLong(), node.path("step").asLong(),
                        node.hasNonNull("callId") ? node.path("callId").asText() : null,
                        node.path("uncachedInputTokens").asLong(),
                        node.path("outputTokens").asLong(),
                        node.path("cacheWriteTokens").asLong(),
                        node.path("cacheReadTokens").asLong(),
                        node.path("synthetic").asBoolean(false)));
                } catch (RuntimeException _) {
                    // Preserve the transcript reader's malformed-row tolerance.
                }
            }
        } catch (IOException e) {
            log.debug("readSessionMetrics failed for {}: {}", source, e.getMessage());
        }
        return List.copyOf(events);
    }

    /** Main submitted user UUIDs that require one complete metrics turn. */
    public List<String> readMetricTurnIds(Path sessionFile) {
        if (sessionFile == null || !Files.exists(sessionFile)) return List.of();
        List<String> turnIds = new ArrayList<>();
        try {
            for (JsonNode node : JsonUtils.readJsonLines(sessionFile)) {
                if (Strings.CS.equals("user", node.path("type").asText())
                        && node.hasNonNull("promptSource")
                        && node.hasNonNull("uuid")) {
                    turnIds.add(node.path("uuid").asText());
                }
            }
        } catch (IOException e) {
            log.debug("readMetricTurnIds failed for {}: {}", sessionFile, e.getMessage());
        }
        return List.copyOf(turnIds);
    }

    private static String textField(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    // ── Assistant usage aggregate (for cost restore) ─────────────────────

    /**
     * Sum {@code assistant.message.usage} across every assistant row in the file.
     */
    public Usage sumAssistantUsage(Path sessionFile) {
        if (!Files.exists(sessionFile)) return Usage.EMPTY;
        long input = 0, output = 0, cacheCreate = 0, cacheRead = 0;
        try {
            for (JsonNode root : JsonUtils.readJsonLines(sessionFile)) {
                if (!Strings.CS.equals("assistant", root.path("type").asText())) continue;
                JsonNode usage = root.path("message").path("usage");
                if (usage.isMissingNode() || usage.isNull()) continue;
                input       += usage.path("input_tokens").asLong(0);
                output      += usage.path("output_tokens").asLong(0);
                cacheCreate += usage.path("cache_creation_input_tokens").asLong(0);
                cacheRead   += usage.path("cache_read_input_tokens").asLong(0);
            }
        } catch (IOException e) {
            log.warn("sumAssistantUsage failed for {} [failureType={}]",
                sessionFile, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
            return Usage.EMPTY;
        }
        return new Usage(input, output, cacheCreate, cacheRead);
    }

    // ── Read path ────────────────────────────────────────────────────────────

    /**
     * Reads all lines from the session file, deserializing each as a Message.
     * Extra metadata fields (cwd, sessionId, isSidechain, etc.) are ignored
     * via Jackson's {@code FAIL_ON_UNKNOWN_PROPERTIES = false} setting.
     * Malformed lines are skipped and logged as warnings.
     *
     * @return list of successfully deserialized messages; empty list if file doesn't exist
     */
    public List<Message> readMessages(Path sessionFile) {
        if (!Files.exists(sessionFile)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(sessionFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read session file: " + sessionFile, e);
        }

        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            // Skip metadata-only entries (type: custom-title, ai-title, last-prompt, etc.)
            if (isMetadataEntry(line)) continue;
            try {
                Message msg = readTranscriptMessage(line);
                messages.add(msg);
            } catch (JsonProcessingException e) {
                log.warn("Skipping malformed line {} in {} [failureType={}]",
                    i + 1, sessionFile, e.getClass().getName(),
                    ErrorUtils.redactedForLogging(e));
            }
        }
        return messages;
    }


    public TranscriptLoader.LoadedTranscript loadTranscriptFromFile(Path sessionFile) {
        return new TranscriptLoader(mapper).loadTranscriptFromFile(sessionFile);
    }


    public Optional<TranscriptLoader.AgentTranscript> getAgentTranscript(
            Path agentFile, String agentId) {
        return new TranscriptLoader(mapper).getAgentTranscript(agentFile, agentId);
    }

    /**
     * Removes one persisted transcript message by its envelope {@code uuid}.
     */
    public void removeTranscriptMessage(Path sessionFile, String targetUuid) {
        if (sessionFile == null || StringUtils.isBlank(targetUuid)
                || !Files.isRegularFile(sessionFile)) return;
        try {
            long size;
            try (FileChannel channel = FileChannel.open(sessionFile,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                size = channel.size();
                if (size == 0) return;
                int length = Math.toIntExact(Math.min(size, TOMBSTONE_TAIL_BYTES));
                long start = size - length;
                byte[] tail = new byte[length];
                ByteBuffer buffer = ByteBuffer.wrap(tail);
                channel.position(start);
                while (buffer.hasRemaining()) {
                    int read = channel.read(buffer);
                    if (read < 0) break;
                    if (read == 0) Thread.onSpinWait();
                }
                byte[] needle = ("\"uuid\":\"" + targetUuid + "\"")
                    .getBytes(StandardCharsets.US_ASCII);
                int match = lastIndexOf(tail, needle);
                if (match >= 0) {
                    int previousNewline = lastIndexOf(tail, (byte) '\n', match);
                    if (previousNewline >= 0 || start == 0) {
                        int lineStart = previousNewline + 1;
                        int nextNewline = indexOf(tail, (byte) '\n', match + needle.length);
                        int lineEnd = nextNewline >= 0 ? nextNewline + 1 : tail.length;
                        int afterLength = tail.length - lineEnd;
                        channel.position(start + lineStart);
                        if (afterLength > 0) {
                            ByteBuffer after = ByteBuffer.wrap(tail, lineEnd, afterLength);
                            while (after.hasRemaining()) {
                                int written = channel.write(after);
                                if (written == 0) Thread.onSpinWait();
                            }
                        }
                        channel.truncate(size - (lineEnd - lineStart));
                        return;
                    }
                }
            }
            if (size > MAX_TOMBSTONE_REWRITE_BYTES) {
                log.debug("Skipping tombstone rewrite outside the tail for {} ({} bytes)",
                    sessionFile, size);
                return;
            }
            rewriteWithoutUuid(sessionFile, targetUuid);
        } catch (IOException failure) {
            // Transcript cleanup is best-effort and must never break the model retry.
            log.debug("Failed to remove transcript message {} from {}: {}",
                targetUuid, sessionFile, failure.getMessage());
        }
    }

    private void rewriteWithoutUuid(Path sessionFile, String targetUuid) throws IOException {
        Path parent = sessionFile.toAbsolutePath().normalize().getParent();
        if (parent == null) return;
        Path replacement = Files.createTempFile(parent, ".transcript-tombstone-", ".jsonl");
        boolean removed = false;
        try {
            try (BufferedReader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8);
                 BufferedWriter writer = Files.newBufferedWriter(replacement, StandardCharsets.UTF_8,
                     StandardOpenOption.TRUNCATE_EXISTING)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (rowHasUuid(line, targetUuid)) {
                        removed = true;
                        continue;
                    }
                    writer.write(line);
                    writer.newLine();
                }
            }
            if (!removed) return;
            try {
                Files.setPosixFilePermissions(replacement,
                    Files.getPosixFilePermissions(sessionFile));
            } catch (UnsupportedOperationException _) {
                // Non-POSIX filesystems retain their native ACL behavior.
            }
            try {
                Files.move(replacement, sessionFile,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(replacement, sessionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(replacement);
        }
    }

    private boolean rowHasUuid(String line, String targetUuid) {
        try {
            JsonNode parsed = mapper.readTree(line);
            return parsed != null && parsed.isObject()
                && Strings.CS.equals(targetUuid, parsed.path("uuid").asText(null));
        } catch (Exception _) {
            return false;
        }
    }

    private static int lastIndexOf(byte[] haystack, byte[] needle) {
        for (int i = haystack.length - needle.length; i >= 0; i--) {
            int j = 0;
            while (j < needle.length && haystack[i + j] == needle[j]) j++;
            if (j == needle.length) return i;
        }
        return -1;
    }

    private static int lastIndexOf(byte[] bytes, byte value, int before) {
        for (int i = Math.min(before - 1, bytes.length - 1); i >= 0; i--) {
            if (bytes[i] == value) return i;
        }
        return -1;
    }

    private static int indexOf(byte[] bytes, byte value, int from) {
        for (int i = Math.max(0, from); i < bytes.length; i++) {
            if (bytes[i] == value) return i;
        }
        return -1;
    }

    /**
     * Reads message-level tool-result replacement decisions from a transcript.
     * These metadata rows are intentionally kept separate from {@link
     * #readMessages(Path)} so ordinary message deserialization never sees the
     * non-Message {@code content-replacement} entry. Malformed rows are
     * ignored, matching the session message reader's fail-open recovery.
     */
    public List<ToolResultBudget.Replacement> readContentReplacements(Path sessionFile) {
        if (sessionFile == null || !Files.exists(sessionFile)) return List.of();
        List<ToolResultBudget.Replacement> result = new ArrayList<>();
        try {
            for (String raw : Files.readAllLines(sessionFile, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                JsonNode root;
                try {
                    root = mapper.readTree(line);
                } catch (Exception _) {
                    continue;
                }
                if (!(root instanceof ObjectNode entry)
                        || !Strings.CS.equals("content-replacement", entry.path("type").asText())
                        || !entry.path("replacements").isArray()) {
                    continue;
                }
                for (JsonNode item : entry.path("replacements")) {
                    if (!item.isObject()
                            || !Strings.CS.equals("tool-result", item.path("kind").asText())) {
                        continue;
                    }
                    String id = item.path("toolUseId").asText("");
                    JsonNode replacement = item.get("replacement");
                    if (!StringUtils.isBlank(id) && replacement != null && replacement.isTextual()) {
                        result.add(new ToolResultBudget.Replacement(id, replacement.textValue()));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read content-replacement metadata from {} [failureType={}]",
                sessionFile, e.getClass().getName(), ErrorUtils.redactedForLogging(e));
        }
        return List.copyOf(result);
    }

    


    private Message readTranscriptMessage(String line) throws JsonProcessingException {
        if (!Strings.CS.contains(line, "\"origin\":{")) {
            return mapper.readValue(line, Message.class);
        }
        JsonNode parsed = mapper.readTree(line);
        if (!(parsed instanceof ObjectNode root)
                || !Strings.CS.equals("user", root.path("type").asText())
                || !root.path("origin").isObject()) {
            return mapper.treeToValue(parsed, Message.class);
        }
        String kind = root.path("origin").path("kind").asText("");
        MessageOrigin normalized = switch (kind) {
            case "task-notification" -> MessageOrigin.TASK_NOTIFICATION;
            case "auto-continuation" -> MessageOrigin.AUTO_CONTINUATION;
            case "hook" -> MessageOrigin.HOOK;
            case "system" -> MessageOrigin.SYSTEM;
            case "tool_result", "tool-result" -> MessageOrigin.TOOL_RESULT;
            case "compact_summary", "compact-summary" -> MessageOrigin.COMPACT_SUMMARY;
            default -> MessageOrigin.USER;
        };
        root.put("origin", normalized.name());
        return mapper.treeToValue(root, Message.class);
    }

    /**
     * Returns {@code true} for metadata-only JSONL entries that should not be deserialized as {@link
     * Message}.
     */
    static boolean isMetadataEntry(String line) {
// Fast string check before JSON parse.
        return Strings.CS.contains(line, "\"type\":\"custom-title\"")
            || Strings.CS.contains(line, "\"type\":\"ai-title\"")
            || Strings.CS.contains(line, "\"type\":\"last-prompt\"")
            || Strings.CS.contains(line, "\"type\":\"summary\"")
            || Strings.CS.contains(line, "\"type\":\"tag\"")
            || Strings.CS.contains(line, "\"type\":\"task-summary\"")
            || Strings.CS.contains(line, "\"type\":\"agent-name\"")
            || Strings.CS.contains(line, "\"type\":\"agent-color\"")
            || Strings.CS.contains(line, "\"type\":\"agent-setting\"")
            || Strings.CS.contains(line, "\"type\":\"pr-link\"")
            || Strings.CS.contains(line, "\"type\":\"file-history-snapshot\"")
            || Strings.CS.contains(line, "\"type\":\"attribution-snapshot\"")
            || Strings.CS.contains(line, "\"type\":\"speculation-accept\"")
            || Strings.CS.contains(line, "\"type\":\"mode\"")
            || Strings.CS.contains(line, "\"type\":\"permission-mode\"")
            || Strings.CS.contains(line, "\"type\":\"worktree-state\"")
            || Strings.CS.contains(line, "\"type\":\"content-replacement\"")
            || Strings.CS.contains(line, "\"type\":\"marble-origami-commit\"")
            || Strings.CS.contains(line, "\"type\":\"marble-origami-snapshot\"")
            || Strings.CS.contains(line, "\"type\":\"queue-operation\"")
            || Strings.CS.contains(line, "\"type\":\"parent-session\"")
            || Strings.CS.contains(line, "\"type\":\"java-session-metrics\"");
    }
}
