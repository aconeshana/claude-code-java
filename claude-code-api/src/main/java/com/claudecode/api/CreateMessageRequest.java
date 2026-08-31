package com.claudecode.api;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.http.CancellationRegistrar;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Request to create a message via the Anthropic Messages API.
 * Supports prompt caching and extended thinking mode.
 *
 * <ul>
 *   <li>Messages API body fields and the
 *       internal-only cache, query-source, cancellation, model capability,
 *       and adaptive-vs-budget thinking rules.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateMessageRequest(
        @JsonProperty("model") String model,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonProperty("system") String systemPrompt,
        @JsonProperty("messages") List<RequestMessage> messages,
        @JsonProperty("tools") List<ToolDefinition> tools,
        @JsonProperty("metadata") JsonNode metadata,
        @JsonProperty("stop_sequences") List<String> stopSequences,
        @JsonProperty("stream") boolean stream,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("top_k") Integer topK,
        @JsonProperty("thinking") ThinkingConfig thinking,
        /**
         * Optional reasoning effort level — {@code "low" | "medium" | "high" | "max"}.
         */
        @JsonProperty("effort") String effort,
        @JsonProperty("tool_choice") ToolChoice toolChoice,
        /**
         * Nested effort config required by newer models (adaptive-thinking generation, e.g.
         */
        @JsonProperty("output_config") OutputConfig outputConfig,
/** Fast Mode request tier; the established wire sends the literal {@code "fast"}. */
        @JsonProperty("speed") String speed,
        /**
         * Native context-management edits (currently just thinking-block pruning).
         */
        @JsonProperty("context_management") ContextManagementConfig contextManagement,
        /**
         * Internal prompt-cache policy.
         */
        @JsonIgnore boolean skipCacheWrite,
        /**
         * Internal prompt-cache gate.
         */
        @JsonIgnore boolean promptCachingEnabled,
        /** Internal TTL selected for automatically generated cache breakpoints. */
        @JsonIgnore PromptCacheTtl promptCacheTtl,
        /** Internal retry classification; never serialized on the API wire. */
        @JsonIgnore String querySource,
        /** Internal cancellation bridge; never serialized on the API wire. */
        @JsonIgnore CancellationRegistrar cancellationRegistrar,
        /** Internal SDK sub-agent attribution marker; never serialized. */
        @JsonIgnore boolean subagent
) {

    private static final Pattern RELEASE_DATE_SUFFIX = Pattern.compile("-\\d{8}$");
    private static final Pattern TWO_PART_MODEL_VERSION =
        Pattern.compile("-(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern ONE_PART_MODEL_VERSION = Pattern.compile("-(\\d{1,2})$");

    /** Internal separator for a cacheable system prefix followed by an uncached dynamic suffix. */
    public static final String UNCACHED_SYSTEM_SUFFIX_BOUNDARY =
        "<CLAUDE_CODE_UNCACHED_SYSTEM_SUFFIX_2_1_197>";

    /**
     * A message in the request messages array.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RequestMessage(
            @JsonProperty("role") String role,
            @JsonProperty("content") Object content
    ) {}

    /**
     * A tool definition for the API request.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolDefinition(
            @JsonProperty("name") String name,
            @JsonProperty("description") String description,
            @JsonProperty("input_schema") JsonNode inputSchema,
            @JsonProperty("cache_control") CacheControl cacheControl,
            @JsonProperty("type") String type,
            @JsonProperty("max_uses") Integer maxUses,
            @JsonProperty("allowed_domains") List<String> allowedDomains,
            @JsonProperty("blocked_domains") List<String> blockedDomains,
            @JsonProperty("defer_loading") Boolean deferLoading,
            @JsonProperty("strict") Boolean strict,
            @JsonProperty("eager_input_streaming") Boolean eagerInputStreaming
    ) {
        /** Source-compatible constructor before eager input streaming metadata. */
        public ToolDefinition(String name, String description, JsonNode inputSchema,
                              CacheControl cacheControl, String type, Integer maxUses,
                              List<String> allowedDomains, List<String> blockedDomains,
                              Boolean deferLoading, Boolean strict) {
            this(name, description, inputSchema, cacheControl, type, maxUses,
                allowedDomains, blockedDomains, deferLoading, strict, null);
        }

        /** Convenience constructor without cache control (client-executed tool). */
        public ToolDefinition(String name, String description, JsonNode inputSchema) {
            this(name, description, inputSchema, null, null, null, null, null, null, null, null);
        }

        /** Convenience constructor with cache control (client-executed tool). */
        public ToolDefinition(String name, String description, JsonNode inputSchema, CacheControl cacheControl) {
            this(name, description, inputSchema, cacheControl, null, null, null, null, null, null, null);
        }

        /** Convenience constructor for a client-executed tool with an explicit defer-loading flag. */
        public ToolDefinition(String name, String description, JsonNode inputSchema, Boolean deferLoading) {
            this(name, description, inputSchema, null, null, null, null, null, deferLoading, null, null);
        }

        /** Source-compatible constructor before strict tool metadata was added. */
        public ToolDefinition(String name, String description, JsonNode inputSchema,
                              CacheControl cacheControl, String type, Integer maxUses,
                              List<String> allowedDomains, List<String> blockedDomains,
                              Boolean deferLoading) {
            this(name, description, inputSchema, cacheControl, type, maxUses,
                allowedDomains, blockedDomains, deferLoading, null);
        }

        /** Anthropic server-side tool (e.g. {@code web_search_20250305}) — no input_schema. */
        public static ToolDefinition serverTool(String type, String name, Integer maxUses,
                                                 List<String> allowedDomains, List<String> blockedDomains) {
            return new ToolDefinition(name, null, null, null, type, maxUses,
                allowedDomains, blockedDomains, null, null, null);
        }
    }

    /**
     * Cache control for prompt caching support.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CacheControl(
            @JsonProperty("type") String type,
            @JsonProperty("ttl") String ttl
    ) {



        public CacheControl(String type) {
            this(type, null);
        }

        /** Creates an ephemeral cache control. */
        public static CacheControl ephemeral() {
            return ephemeral(PromptCacheTtl.FIVE_MINUTES);
        }

        /** Creates an ephemeral cache control using the selected request TTL. */
        public static CacheControl ephemeral(PromptCacheTtl ttl) {
            return new CacheControl("ephemeral",
                ttl == PromptCacheTtl.ONE_HOUR ? "1h" : null);
        }
    }

    /** TTL for automatically generated prompt-cache breakpoints. */
    public enum PromptCacheTtl {
        FIVE_MINUTES,
        ONE_HOUR
    }

    /**
     * Tool choice for forced tool usage.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolChoice(
            @JsonProperty("type") String type,
            @JsonProperty("name") String name
    ) {
        /** Forces the model to call a specific tool by name. */
        public static ToolChoice forTool(String toolName) {
            return new ToolChoice("tool", toolName);
        }
    }

    /**
     * Nested effort config for newer (adaptive-thinking) models. See
     * {@link #outputConfig}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutputConfig(
            @JsonProperty("effort") String effort,
            @JsonProperty("format") JsonNode format,
            @JsonProperty("task_budget") Object taskBudget
    ) {
        /** Backwards-compatible effort-only shape used by main-loop requests. */
        public OutputConfig(String effort) {
            this(effort, null, null);
        }

        /** Backwards-compatible effort/format shape used by structured output. */
        public OutputConfig(String effort, JsonNode format) {
            this(effort, format, null);
        }
    }

    /**
     * Wrapper for {@code context_management}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextManagementConfig(
            @JsonProperty("edits") List<ContextEditStrategy> edits
    ) {}

    /**
     * One context-management edit strategy.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ContextEditStrategy(
            @JsonProperty("type") String type,
            @JsonProperty("keep") Keep keep
    ) {
        /** The {@code clear_thinking_20251015} edit, keeping every thinking turn. */
        public static ContextEditStrategy clearThinkingKeepAll() {
            return new ContextEditStrategy("clear_thinking_20251015", new Keep.KeepAll());
        }

        /**
         * The {@code clear_thinking_20251015} edit, keeping only the most recent thinking turn.
         */
        public static ContextEditStrategy clearThinkingKeepLastTurn() {
            return new ContextEditStrategy(
                "clear_thinking_20251015", new Keep.ThinkingTurnsKeep("thinking_turns", 1));
        }


        @JsonSerialize(using = Keep.Serializer.class)
        @JsonDeserialize(using = Keep.Deserializer.class)
        public sealed interface Keep permits Keep.KeepAll, Keep.ThinkingTurnsKeep {

            /** The bare string {@code "all"} shape of {@link #keep}. */
            record KeepAll() implements Keep {}

            /** The {@code {type: "thinking_turns", value: N}} shape of {@link #keep}. */
            record ThinkingTurnsKeep(
                    @JsonProperty("type") String type,
                    @JsonProperty("value") int value
            ) implements Keep {}

            final class Serializer extends JsonSerializer<Keep> {
                @Override
                public void serialize(Keep value, JsonGenerator gen, SerializerProvider serializers)
                        throws IOException {
                    switch (value) {
                        case KeepAll _ -> gen.writeString("all");
                        case ThinkingTurnsKeep t -> {
                            gen.writeStartObject();
                            gen.writeStringField("type", t.type());
                            gen.writeNumberField("value", t.value());
                            gen.writeEndObject();
                        }
                    }
                }
            }

            final class Deserializer extends StdDeserializer<Keep> {
                Deserializer() {
                    super(Keep.class);
                }

                @Override
                public Keep deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
                    JsonNode node = p.getCodec().readTree(p);
                    if (node.isTextual()) {
                        return new KeepAll();
                    }
                    return new ThinkingTurnsKeep(node.path("type").asText(), node.path("value").asInt());
                }
            }
        }
    }

    /**
     * Thinking/extended thinking configuration.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ThinkingConfig(
            @JsonProperty("type") String type,
            @JsonProperty("budget_tokens") Integer budgetTokens
    ) {
        /** Creates an enabled thinking config with a token budget. */
        public static ThinkingConfig enabled(int budgetTokens) {
            return new ThinkingConfig("enabled", budgetTokens);
        }

        /** Creates a disabled thinking config. */
        public static ThinkingConfig disabled() {
            return new ThinkingConfig("disabled", null);
        }

        /**
         * Creates an adaptive thinking config (no token budget — the model
         * decides). Required instead of {@link #enabled} for newer models;
         * see {@link #supportsAdaptiveThinking(String)}.
         */
        public static ThinkingConfig adaptive() {
            return new ThinkingConfig("adaptive", null);
        }
    }


    public static boolean supportsAdaptiveThinking(String model) {
        if (model == null) return false;
        String m = model.toLowerCase(Locale.ROOT);
        // Strip a trailing release-date suffix (e.g. the "-20250514" in
        // "claude-sonnet-4-20250514") so it isn't mistaken for a version
        // component by the patterns below.
        m = RELEASE_DATE_SUFFIX.matcher(m).replaceFirst("");
        Matcher twoPart = TWO_PART_MODEL_VERSION.matcher(m);
        if (twoPart.find()) {
            int major = Integer.parseInt(twoPart.group(1));
            int minor = Integer.parseInt(twoPart.group(2));
            return major > 4 || (major == 4 && minor >= 6);
        }
        Matcher onePart = ONE_PART_MODEL_VERSION.matcher(m);
        if (onePart.find()) {
            int major = Integer.parseInt(onePart.group(1));
            return major >= 5;
        }

        // infer provider=thirdParty merely from a custom base URL: Anthropic's
        // first-party Messages protocol is still in use.
        return !Strings.CS.contains(m, "opus") && !Strings.CS.contains(m, "sonnet") && !Strings.CS.contains(m, "haiku");
    }


    public static boolean supportsThinking(String model) {
        if (StringUtils.isBlank(model)) return false;
        return !Strings.CI.contains(model, "claude-3-");
    }


    public static boolean supportsStructuredOutputs(String model) {
        if (StringUtils.isBlank(model)) return false;
        String canonical = model.toLowerCase(Locale.ROOT);
        return Strings.CS.contains(canonical, "claude-opus-5")
            || Strings.CS.contains(canonical, "claude-sonnet-4-6")
            ||Strings.CS.contains( canonical, "claude-sonnet-4-5")
            ||Strings.CS.contains( canonical, "claude-opus-4-1")
            ||Strings.CS.contains( canonical, "claude-opus-4-5")
            ||Strings.CS.contains( canonical, "claude-opus-4-6")
            ||Strings.CS.contains( canonical, "claude-haiku-4-5");
    }

    /** Returns whether a tool may emit the strict field on the current wire. */
    public static boolean strictToolEnabled(String model, boolean requested) {
        return requested
            && FeatureGate.isEnabled(FeatureGate.Flag.STRICT_TOOLS)
            && supportsStructuredOutputs(model);
    }

    /**
     * Whether {@code model} is eligible for the {@code clear_thinking_20251015} context-management edit
     * (auto-pruning older assistant turns' thinking blocks to save context, once thinking is on).
     */
    public static boolean supportsContextManagement(String model) {
        if (model == null) return false;
        return !Strings.CI.contains(model, "claude-3-");
    }

    /**
     * Builder for CreateMessageRequest.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model;
        private int maxTokens = 4096;
        private String systemPrompt;
        private List<RequestMessage> messages = List.of();
        private List<ToolDefinition> tools;
        private JsonNode metadata;
        private List<String> stopSequences;
        private boolean stream = true;
        private Double temperature;
        private Double topP;
        private Integer topK;
        private ThinkingConfig thinking;
        private String effort;
        private ToolChoice toolChoice;
        private OutputConfig outputConfig;
        private String speed;
        private ContextManagementConfig contextManagement;
        private boolean skipCacheWrite;
        private boolean promptCachingEnabled = true;
        private PromptCacheTtl promptCacheTtl = PromptCacheTtl.FIVE_MINUTES;
        private String querySource;
        private CancellationRegistrar cancellationRegistrar = CancellationRegistrar.NONE;
        private boolean subagent;

        public Builder model(String model) { this.model = model; return this; }
        public Builder maxTokens(int maxTokens) { this.maxTokens = maxTokens; return this; }
        public Builder systemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; return this; }
        public Builder messages(List<RequestMessage> messages) { this.messages = messages; return this; }
        public Builder tools(List<ToolDefinition> tools) { this.tools = tools; return this; }
        public Builder metadata(JsonNode metadata) { this.metadata = metadata; return this; }
        public Builder stopSequences(List<String> stopSequences) { this.stopSequences = stopSequences; return this; }
        public Builder stream(boolean stream) { this.stream = stream; return this; }
        public Builder temperature(Double temperature) { this.temperature = temperature; return this; }
        public Builder topP(Double topP) { this.topP = topP; return this; }
        public Builder topK(Integer topK) { this.topK = topK; return this; }
        public Builder thinking(ThinkingConfig thinking) { this.thinking = thinking; return this; }
        public Builder effort(String effort) { this.effort = effort; return this; }
        public Builder toolChoice(ToolChoice toolChoice) { this.toolChoice = toolChoice; return this; }
        public Builder outputConfig(OutputConfig outputConfig) { this.outputConfig = outputConfig; return this; }
        public Builder speed(String speed) { this.speed = speed; return this; }
        public Builder contextManagement(ContextManagementConfig contextManagement) { this.contextManagement = contextManagement; return this; }
        public Builder skipCacheWrite(boolean skipCacheWrite) { this.skipCacheWrite = skipCacheWrite; return this; }
        public Builder promptCachingEnabled(boolean promptCachingEnabled) { this.promptCachingEnabled = promptCachingEnabled; return this; }
        public Builder promptCacheTtl(PromptCacheTtl promptCacheTtl) {
            this.promptCacheTtl = promptCacheTtl != null ? promptCacheTtl : PromptCacheTtl.FIVE_MINUTES;
            return this;
        }
        public Builder querySource(String querySource) { this.querySource = querySource; return this; }
        public Builder cancellationRegistrar(CancellationRegistrar cancellationRegistrar) {
            this.cancellationRegistrar = cancellationRegistrar != null
                ? cancellationRegistrar : CancellationRegistrar.NONE;
            return this;
        }
        public Builder subagent(boolean subagent) { this.subagent = subagent; return this; }

        public CreateMessageRequest build() {
            return new CreateMessageRequest(
                    model, maxTokens, systemPrompt, messages, tools,
                    metadata, stopSequences, stream, temperature, topP, topK,
                    thinking, effort, toolChoice, outputConfig, speed, contextManagement, skipCacheWrite,
                    promptCachingEnabled, promptCacheTtl, querySource, cancellationRegistrar, subagent
            );
        }
    }
}
