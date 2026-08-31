package com.claudecode.commands.context;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData.AgentEntry;
import com.claudecode.commands.context.ContextData.Category;
import com.claudecode.commands.context.ContextData.ContextColor;
import com.claudecode.commands.context.ContextData.McpToolEntry;
import com.claudecode.commands.context.ContextData.MemoryFileEntry;
import com.claudecode.commands.context.ContextData.MessageBreakdown;
import com.claudecode.commands.context.ContextData.SkillEntry;
import com.claudecode.commands.context.ContextData.SkillInfo;
import com.claudecode.commands.context.ContextData.SlashCommandInfo;
import com.claudecode.commands.context.ContextData.ToolIo;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.engine.StreamingClient.StreamRequest.RequestMessage;
import com.claudecode.core.engine.StreamingClient.StreamRequest.ToolDef;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.model.ModelContextWindows;
import com.claudecode.core.agent.BuiltInAgentDefinitions.AgentDefinition;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Collects context-window usage into a {@link ContextData} snapshot for the {@code /context}
 * command.
 */
public final class ContextUsageAnalyzer {

    public enum MemoryScope { USER, PROJECT, LOCAL }

    public record MemoryDocument(Path path, MemoryScope scope, String content) { }

    /** Neutral skill listing data supplied by the outer tools adapter. */
    public record SkillDescriptor(String name, String description, Source source) {
        public enum Source { MANAGED, USER, PROJECT, BUILTIN, BUNDLED, MCP, PLUGIN }
    }

    static final long MANUAL_COMPACT_BUFFER_TOKENS = 3_000L;

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    /** Exact API token counter injected by the CLI composition root. */
    @FunctionalInterface
    public interface TokenCounter {
        Long count(String model, List<RequestMessage> messages, List<ToolDef> tools);
    }

    @FunctionalInterface
    public interface FallbackTokenCounter {
        Long count(String model, List<RequestMessage> messages, List<ToolDef> tools);
    }

    /**
     * Data sources, all injected so tests can run the analyzer hermetically.
     */
    public record Sources(
        Supplier<List<ToolDef>> toolDefinitions,
        Supplier<String> baseSystemPrompt,
        Supplier<List<String>> systemPromptParts,
        Supplier<List<MemoryDocument>> memoryFiles,
        Supplier<List<SkillDescriptor>> skills,
        Function<String, List<AgentDefinition>> activeAgents,
        Supplier<MessageCompactor> compactor,
        BooleanSupplier autoCompactEnabled,
        TokenCounter tokenCounter,
        FallbackTokenCounter fallbackTokenCounter,
        String workingDirectory,
        Function<String, Long> contextWindowResolver
    ) {
        public Sources(
                Supplier<List<ToolDef>> toolDefinitions,
                Supplier<String> baseSystemPrompt,
                Supplier<List<MemoryDocument>> memoryFiles,
                Supplier<List<SkillDescriptor>> skills,
                Function<String, List<AgentDefinition>> activeAgents,
                Supplier<MessageCompactor> compactor,
                BooleanSupplier autoCompactEnabled,
                TokenCounter tokenCounter,
                String workingDirectory) {
            this(toolDefinitions, baseSystemPrompt, null, memoryFiles, skills,
                activeAgents, compactor, autoCompactEnabled, tokenCounter, null,
                workingDirectory, null);
        }

        /** Compatibility constructor with a custom context-window resolver. */
        public Sources(
                Supplier<List<ToolDef>> toolDefinitions,
                Supplier<String> baseSystemPrompt,
                Supplier<List<MemoryDocument>> memoryFiles,
                Supplier<List<SkillDescriptor>> skills,
                Function<String, List<AgentDefinition>> activeAgents,
                Supplier<MessageCompactor> compactor,
                BooleanSupplier autoCompactEnabled,
                TokenCounter tokenCounter,
                String workingDirectory,
                Function<String, Long> contextWindowResolver) {
            this(toolDefinitions, baseSystemPrompt, null, memoryFiles, skills,
                activeAgents, compactor, autoCompactEnabled, tokenCounter, null,
                workingDirectory, contextWindowResolver);
        }

        /** Compatibility constructor for non-networked renderers/tests. */
        public Sources(
                Supplier<List<ToolDef>> toolDefinitions,
                Supplier<String> baseSystemPrompt,
                Supplier<List<MemoryDocument>> memoryFiles,
                Supplier<List<SkillDescriptor>> skills,
                Function<String, List<AgentDefinition>> activeAgents,
                Supplier<MessageCompactor> compactor,
                BooleanSupplier autoCompactEnabled,
                String workingDirectory) {
            this(toolDefinitions, baseSystemPrompt, null, memoryFiles, skills,
                activeAgents, compactor, autoCompactEnabled, null, null,
                workingDirectory, null);
        }
    }

    private final Sources sources;
    private final TokenEstimator estimator = TokenEstimator.getInstance();

    public ContextUsageAnalyzer(Sources sources) {
        this.sources = sources;
    }

    public ContextData analyze(List<Message> messages, String model) {
        long contextWindow = resolvedContextWindow(model);

        // Same pre-API transforms as the query path: compact boundary, then

        List<Message> apiView = MessageConstants.getMessagesAfterCompactBoundary(
            messages != null ? messages : List.of());
        List<Message> compacted = microcompact(apiView);

        List<String> promptParts = listSafe(sources.systemPromptParts());
        long systemPromptTokens = promptParts.isEmpty()
            ? countTextOrEstimate(valueSafe(sources.baseSystemPrompt()), model)
            : promptParts.stream().mapToLong(part -> countTextOrEstimate(part, model)).sum();

        // Tools — one pass over the registry definitions, split builtin/MCP

        List<ToolDef> builtInTools = new ArrayList<>();
        List<ToolDef> mcpTools = new ArrayList<>();
        List<McpToolEntry> mcpDetails = new ArrayList<>();
        for (ToolDef def : listSafe(sources.toolDefinitions())) {
            if (def.name() != null && Strings.CS.startsWith(def.name(), "mcp__")) {
                mcpTools.add(def);
            } else {
                builtInTools.add(def);
            }
        }
        List<ToolDef> eagerBuiltIns = builtInTools.stream().filter(t -> !t.deferLoading()).toList();
        List<ToolDef> deferredBuiltIns = builtInTools.stream().filter(ToolDef::deferLoading).toList();
        long builtInToolTokens = countToolsOrEstimate(eagerBuiltIns, model, false)
            + countToolsOrEstimate(deferredBuiltIns, model, false);
        long mcpToolTokens = countToolsOrEstimate(mcpTools, model, true);
        if (mcpTools.isEmpty() && sources.tokenCounter() != null) {

            // primary endpoint and its `count` fallback even though the result
            // contributes zero MCP tokens.
            countExact(model, List.of(), List.of());
        }
        long roughMcpTotal = mcpTools.stream().mapToLong(this::estimateToolDef).sum();
        for (ToolDef def : mcpTools) {
            long rough = estimateToolDef(def);
            long tokens = roughMcpTotal > 0
                ? Math.round((double) rough / roughMcpTotal * mcpToolTokens) : 0;
            mcpDetails.add(new McpToolEntry(def.name(), serverName(def.name()), tokens));
        }

        // Memory files.
        long claudeMdTokens = 0;
        List<MemoryFileEntry> memoryDetails = new ArrayList<>();
        for (MemoryDocument file : listSafe(sources.memoryFiles())) {
            long tokens = countTextOrEstimate(file.content(), model);
            claudeMdTokens += tokens;
            memoryDetails.add(new MemoryFileEntry(
                file.path() != null ? file.path().toString() : "",
                memoryTypeDisplay(file), tokens));
        }


        long agentTokens = 0;
        List<AgentEntry> agentDetails = new ArrayList<>();
        for (AgentDefinition agent : agentsSafe()) {
            if (agent.source() == AgentSource.BUILT_IN) continue;
            long tokens = countTextOrEstimate(
                agent.agentType() + " " + (agent.whenToUse() != null ? agent.whenToUse() : ""),
                model);
            agentTokens += tokens;
            agentDetails.add(new AgentEntry(agent.agentType(), agentSourceDisplay(agent.source()), tokens));
        }


        // the Skill tool prompt, including the leading dash and colon.
        long skillTokens = 0;
        List<SkillEntry> skillDetails = new ArrayList<>();
        for (SkillDescriptor skill : listSafe(sources.skills())) {
            long tokens = estimator.estimateTokenCount(
                "- " + skill.name() + (StringUtils.isNotBlank(skill.description())
                    ? ": " + skill.description() : ""));
            skillTokens += tokens;
            skillDetails.add(new SkillEntry(skill.name(), skillSourceDisplay(skill.source()), tokens));
        }
        SkillInfo skillInfo = skillTokens > 0
            ? new SkillInfo(skillDetails.size(), skillTokens, List.copyOf(skillDetails))
            : null;


        ToolDef skillTool = builtInTools.stream()
            .filter(t -> Strings.CS.equals("Skill", t.name())).findFirst().orElse(null);
        long slashCommandTokens = skillTool != null
            ? countToolsOrEstimate(List.of(skillTool), model, false) : 0;
        if (skillTool != null) {
            countToolsOrEstimate(List.of(skillTool), model, false);
        }
        SlashCommandInfo slashCommands = slashCommandTokens > 0
            ? new SlashCommandInfo(skillDetails.size(), skillDetails.size(), slashCommandTokens)
            : null;

        TokenEstimator.UsageSnapshot apiSnapshot = lastApiUsageSnapshot(apiView);
        Usage apiUsage = apiSnapshot != null ? apiSnapshot.usage() : null;
        MessageBreakdown breakdown = buildMessageBreakdown(
            compacted, model, apiUsage != null);


        List<Category> cats = new ArrayList<>();
        addCategory(cats, "System prompt", systemPromptTokens, ContextColor.PROMPT_BORDER);
        // Skills are shown as their own category; the SkillTool schema tokens

        // systemToolsTokens = builtInToolTokens - skillFrontmatterTokens).
        addCategory(cats, "System tools", builtInToolTokens - skillTokens, ContextColor.INACTIVE);
        addCategory(cats, "MCP tools", mcpToolTokens, ContextColor.CYAN);
        addCategory(cats, "Custom agents", agentTokens, ContextColor.PERMISSION);
        addCategory(cats, "Memory files", claudeMdTokens, ContextColor.CLAUDE);
        addCategory(cats, "Skills", skillTokens, ContextColor.WARNING);
        addCategory(cats, "Messages", breakdown.totalTokens(),
            ContextColor.PURPLE);

        long actualUsage = cats.stream().mapToLong(Category::tokens).sum();

        boolean autoCompact = sources.autoCompactEnabled() == null
            || sources.autoCompactEnabled().getAsBoolean();
        MessageCompactor compactor = compactorSafe();
        Long autoCompactThreshold = autoCompact
            ? autoCompactThreshold(compactor, model) : null;
        String autoCompactSource = autoCompactSource(compactor, model);
        long reservedTokens;
        if (autoCompact) {
            reservedTokens = contextWindow - autoCompactThreshold;
            cats.add(new Category(ContextData.AUTOCOMPACT_BUFFER, reservedTokens, ContextColor.INACTIVE));
        } else {
            reservedTokens = MANUAL_COMPACT_BUFFER_TOKENS;
            cats.add(new Category(ContextData.MANUAL_COMPACT_BUFFER, reservedTokens, ContextColor.INACTIVE));
        }

        long freeTokens = Math.max(0, contextWindow - actualUsage - reservedTokens);
        cats.add(new Category(ContextData.FREE_SPACE, freeTokens, ContextColor.PROMPT_BORDER));


        // pre-microcompact view).
        long totalTokens = apiUsage != null
            ? TokenEstimator.contextInputTokens(apiUsage,
                reportingModelOrFallback(apiSnapshot, model))
            : actualUsage;
        int percentage = contextWindow > 0
            ? (int) Math.round(totalTokens * 100.0 / contextWindow) : 0;

        return new ContextData(
            List.copyOf(cats),
            totalTokens,
            contextWindow,
            percentage,
            model,
            List.copyOf(memoryDetails),
            List.copyOf(mcpDetails),
            List.copyOf(agentDetails),
            slashCommands,
            skillInfo,
            autoCompactThreshold,
            autoCompactSource,
            autoCompact,
            breakdown,
            apiUsage);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Context-window size for {@code model}.
     */
    public static long contextWindowFor(String model) {
        return ModelContextWindows.defaultContextWindow(model);
    }

    private long resolvedContextWindow(String model) {
        try {
            Long configured = sources.contextWindowResolver() != null
                ? sources.contextWindowResolver().apply(model) : null;
            return configured != null && configured > 0 ? configured : contextWindowFor(model);
        } catch (RuntimeException _) {
            return contextWindowFor(model);
        }
    }

    static String serverName(String mcpToolName) {
        String[] parts = mcpToolName.split("__");
        return parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "unknown";
    }

    private List<Message> microcompact(List<Message> apiView) {
        try {
            MessageCompactor compactor = compactorSafe();
            if (compactor != null) {
                return compactor.microcompactMessages(apiView).messages();
            }
        } catch (Exception _) {
            // Fall back to the un-compacted view.
        }
        return apiView;
    }

    private MessageCompactor compactorSafe() {
        try {
            return sources.compactor() != null ? sources.compactor().get() : null;
        } catch (Exception _) {
            return null;
        }
    }

    private static long autoCompactThreshold(MessageCompactor compactor, String model) {
        return compactor != null
            ? compactor.getAutoCompactThreshold(model)
            : Math.max(0L, contextWindowFor(model) - 33_000L);
    }

    private static String autoCompactSource(MessageCompactor compactor, String model) {
        if (compactor != null) return compactor.getAutoCompactSource(model);
        if (model == null) return "auto";
        String normalized = model.toLowerCase(Locale.ROOT)
            .replace('_', '-').replace('.', '-');
        return Strings.CS.equals(normalized, "claude-sonnet-4-6")
            || Strings.CS.startsWith(normalized, "claude-sonnet-4-6-")
            || Strings.CS.equals(normalized, "claude-opus-4-6")
            || Strings.CS.startsWith(normalized, "claude-opus-4-6-")
            || ModelContextWindows.isGpt56(model)
            ? "model-default" : "auto";
    }

    private MessageBreakdown buildMessageBreakdown(
            List<Message> compacted, String model, boolean hasApiUsage) {
        long toolCallTokens = 0;
        long toolResultTokens = 0;
        long assistantMessageTokens = 0;
        long userMessageTokens = 0;
        Map<String, String> toolUseIdToName = new HashMap<>();
        Map<String, long[]> byTool = new LinkedHashMap<>();

        for (Message msg : compacted) {
            if (msg instanceof AssistantMessage am && am.message() != null
                    && am.message().content() != null) {
                for (ContentBlock block : am.message().content()) {
                    if (block instanceof ToolUseBlock tub && tub.id() != null) {
                        toolUseIdToName.put(tub.id(), tub.name() != null ? tub.name() : "unknown");
                    }
                }
            }
        }

        for (Message msg : compacted) {
            if (msg instanceof AssistantMessage am && am.message() != null
                    && am.message().content() != null) {
                for (ContentBlock block : am.message().content()) {
                    long tokens = estimateBlock(block);
                    if (block instanceof ToolUseBlock tub) {
                        toolCallTokens += tokens;
                        String name = tub.name() != null ? tub.name() : "unknown";
                        byTool.computeIfAbsent(name, _ -> new long[2])[0] += tokens;
                    } else {
                        assistantMessageTokens += tokens;
                    }
                }
            } else if (msg instanceof UserMessage um && um.message() != null) {
                MessageContent content = um.message();
                if (content.isText()) {
                    userMessageTokens += estimator.estimateTokenCount(content.text());
                } else if (content.blocks() != null) {
                    for (ContentBlock block : content.blocks()) {
                        long tokens = estimateBlock(block);
                        if (block instanceof ToolResultBlock trb) {
                            toolResultTokens += tokens;
                            String name = toolUseIdToName.getOrDefault(trb.toolUseId(), "unknown");
                            byTool.computeIfAbsent(name, _ -> new long[2])[1] += tokens;
                        } else {
                            userMessageTokens += tokens;
                        }
                    }
                }
            }
        }

        List<ToolIo> toolCallsByType = byTool.entrySet().stream()
            .map(e -> new ToolIo(e.getKey(), e.getValue()[0], e.getValue()[1]))
            .sorted((a, b) -> Long.compare(
                b.callTokens() + b.resultTokens(), a.callTokens() + a.resultTokens()))
            .toList();

        long totalTokens;
        if (hasApiUsage) {
            totalTokens = 0;
        } else if (sources.tokenCounter() != null) {
            totalTokens = countExact(model, requestMessages(compacted), List.of());
        } else {
            totalTokens = estimator.estimateTokenCount(compacted);
        }
        long attributed = toolCallTokens + toolResultTokens
            + assistantMessageTokens + userMessageTokens;
        return new MessageBreakdown(totalTokens, toolCallTokens, toolResultTokens,
            assistantMessageTokens, userMessageTokens, 0,
            Math.max(0, totalTokens - attributed), toolCallsByType);
    }

    private long countTextOrEstimate(String text, String model) {
        String value = text != null ? text : "";
        if (value.isEmpty()) return 0;
        if (sources.tokenCounter() == null) {
            return estimator.estimateTokenCount(value);
        }
        return countExact(model, List.of(new RequestMessage("user", value)), List.of());
    }

    private long countToolsOrEstimate(List<ToolDef> tools, String model, boolean subtractOverhead) {
        if (tools.isEmpty()) return 0;
        if (sources.tokenCounter() == null) {
            return tools.stream().mapToLong(this::estimateToolDef).sum();
        }
        long counted = countExact(model, List.of(), tools);
        return subtractOverhead ? Math.max(0, counted - 500) : counted;
    }

    private long countExact(String model, List<RequestMessage> messages, List<ToolDef> tools) {
        try {
            Long value = sources.tokenCounter().count(model, messages, tools);
            return value != null ? Math.max(0, value) : 0;
        } catch (Exception _) {
            if (sources.fallbackTokenCounter() == null) return 0;
            try {
                Long value = sources.fallbackTokenCounter().count(model, messages, tools);
                return value != null ? Math.max(0, value) : 0;
            } catch (Exception _) {
                return 0;
            }
        }
    }

    private static List<RequestMessage> requestMessages(List<Message> messages) {
        List<RequestMessage> out = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant && assistant.message() != null) {
                out.add(new RequestMessage("assistant", assistant.message().content()));
            } else if (message instanceof UserMessage user && user.message() != null) {
                Object content = user.message().isText()
                    ? user.message().text() : user.message().blocks();
                out.add(new RequestMessage("user", content));
            }
        }
        return out;
    }


    public static Usage lastApiUsage(List<Message> messages) {
        TokenEstimator.UsageSnapshot snapshot = lastApiUsageSnapshot(messages);
        return snapshot != null ? snapshot.usage() : null;
    }

    /** Latest real API usage plus the model that reported it. */
    public static TokenEstimator.UsageSnapshot lastApiUsageSnapshot(List<Message> messages) {
        return TokenEstimator.latestUsageSnapshot(messages);
    }

    private static String reportingModelOrFallback(
            TokenEstimator.UsageSnapshot snapshot, String fallback) {
        return snapshot != null && snapshot.model() != null && !StringUtils.isBlank(snapshot.model())
            ? snapshot.model() : fallback;
    }

    private static void addCategory(List<Category> cats, String name, long tokens, ContextColor color) {
        if (tokens > 0) {
            cats.add(new Category(name, tokens, color));
        }
    }

    private long estimateToolDef(ToolDef def) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("name", def.name());
            node.put("description", def.description() != null ? def.description() : "");
            node.set("input_schema", MAPPER.valueToTree(def.inputSchema()));
            return estimator.estimateTokenCount(MAPPER.writeValueAsString(node));
        } catch (Exception _) {
            return 0;
        }
    }

    private long estimateBlock(ContentBlock block) {
        try {
            return estimator.estimateTokenCount(MAPPER.writeValueAsString(block));
        } catch (Exception _) {
            return 0;
        }
    }

    private String valueSafe(Supplier<String> supplier) {
        try {
            return supplier != null ? supplier.get() : null;
        } catch (Exception _) {
            return null;
        }
    }

    private <T> List<T> listSafe(Supplier<List<T>> supplier) {
        try {
            List<T> value = supplier != null ? supplier.get() : null;
            return value != null ? value : List.of();
        } catch (Exception _) {
            return List.of();
        }
    }

    private List<AgentDefinition> agentsSafe() {
        try {
            if (sources.activeAgents() == null) return List.of();
            List<AgentDefinition> agents = sources.activeAgents().apply(sources.workingDirectory());
            return agents != null ? agents : List.of();
        } catch (Exception _) {
            return List.of();
        }
    }

    private static String memoryTypeDisplay(MemoryDocument file) {
        if (file.scope() == null) return "Project";
        return switch (file.scope()) {
            case USER -> "User";
            case PROJECT -> "Project";
            case LOCAL -> "Local";
        };
    }

    private static String agentSourceDisplay(AgentSource source) {
        return switch (source) {
            case MANAGED -> "Managed";
            case USER -> "User";
            case PROJECT -> "Project";
            case FLAG_SETTINGS -> "CLI";
            case BUILT_IN -> "Built-in";
            case PLUGIN -> "Plugin";
        };
    }

    private static String skillSourceDisplay(SkillDescriptor.Source source) {
        if (source == null) return "Project";
        return switch (source) {
            case USER -> "User";
            case PROJECT -> "Project";
            case MANAGED -> "Managed";
            case BUILTIN, BUNDLED -> "Built-in";
            case MCP, PLUGIN -> "Plugin";
        };
    }
}
