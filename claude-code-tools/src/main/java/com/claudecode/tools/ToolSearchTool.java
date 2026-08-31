package com.claudecode.tools;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.RawBlocksOutput;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.ToolResultContentForm;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolReferenceBlock;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.nio.file.Files;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;

/**
 * ToolSearchTool — fetches full schema definitions for deferred tools so they can be called.
 */
@BuiltInTool(
    name = ToolSearchTool.NAME,
    readOnly = true,
    concurrencySafe = true
)
public class ToolSearchTool extends AnnotatedTool<JsonNode, ToolSearchTool.Output> {


    public static final String NAME = "ToolSearch";

    private static final String NO_MATCHES_TEXT = "No matching deferred tools found";
    private static final int DEFAULT_MAX_RESULTS = 5;

    private final ToolRegistry toolRegistry;
    private final Supplier<List<String>> pendingMcpServers;


    public record Output(
        List<String> matches,
        String query,
        int totalDeferredTools,
        List<String> pendingMcpServers,
        RawBlocksOutput modelContent
    ) {
        public Output {
            matches = matches == null ? List.of() : List.copyOf(matches);
            query = query == null ? "" : query;
            pendingMcpServers = pendingMcpServers == null
                ? List.of() : List.copyOf(pendingMcpServers);
            if (modelContent == null) {
                modelContent = new RawBlocksOutput(List.of());
            }
        }


        public Map<String, Object> asToolUseResult() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("matches", matches);
            data.put("query", query);
            data.put("total_deferred_tools", totalDeferredTools);
            if (!pendingMcpServers.isEmpty()) {
                data.put("pending_mcp_servers", pendingMcpServers);
            }
            // Keep insertion order stable for JSONL/wire snapshots while still
            // exposing an immutable payload to callers.
            return Collections.unmodifiableMap(data);
        }
    }

    public ToolSearchTool(ToolRegistry toolRegistry) {
        this(toolRegistry, List::of);
    }

    /**
     * Production constructor. The supplier is deliberately narrow so the
     * search tool can report only the model-visible pending MCP names without
     * depending on the MCP provider's implementation or blocking connection
     * startup.
     */
    public ToolSearchTool(ToolRegistry toolRegistry, Supplier<List<String>> pendingMcpServers) {
        this.toolRegistry = toolRegistry;
        this.pendingMcpServers = pendingMcpServers == null ? List::of : pendingMcpServers;
    }

    @Override
    public String description() {
        return prompt(null);
    }

    @Override
    public String prompt(ToolExecutionContext context) {
        return ToolTexts.prompt("ToolSearch", deferredToolLocationVariant());
    }

    private static String deferredToolLocationVariant() {
        if (Strings.CI.equals("ant", SubprocessEnvironment.get("USER_TYPE"))) {
            return "system-reminder";
        }
        try {
            if (Files.isRegularFile(ClaudePaths.GLOBAL_JSON)) {
                JsonNode features = JsonUtils.readJson(ClaudePaths.GLOBAL_JSON)
                    .path("cachedGrowthBookFeatures");
                if (features.path("tengu_glacier_2xr").asBoolean(false)) {
                    return "system-reminder";
                }
            }
        } catch (Exception _) {
            // Preserve the external default if a stale/unreadable cache is
            // encountered.
        }
        return "available-deferred-tools";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = createObjectSchema();
        ObjectNode props = (ObjectNode) schema.get("properties");

        ObjectNode querySchema = mapper().createObjectNode();
        querySchema.put("type", "string");
        querySchema.put("description",
            "Query to find deferred tools. Use \"select:<tool_name>\" for direct selection, or keywords to search.");
        props.set("query", querySchema);

        ObjectNode maxResultsSchema = mapper().createObjectNode();

        // slice semantics (fractional values truncate toward zero) aligned.
        maxResultsSchema.put("type", "number");
        maxResultsSchema.put("default", DEFAULT_MAX_RESULTS);
        maxResultsSchema.put("description", "Maximum number of results to return (default: 5)");
        props.set("max_results", maxResultsSchema);




        ArrayNode required = schema.putArray("required");
        required.add("query");

        return schema;
    }


    @Override
    public boolean isEnabled() {
        return ToolSearchGate.isEnabled();
    }

/** ToolSearch is an internal read-only discovery step. */
    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        return PermissionDecision.allow();
    }


    static boolean isDeferredTool(Tool<?, ?> t) {
        if (t.alwaysLoad()) return false;
        if (NAME.equals(t.name())) return false;

        // MCPTool/McpAuthTool provide that flag; a name that merely happens to
        // use the mcp__ prefix is not sufficient to change deferral semantics.
        if (t.isMcp()) return true;
        return t.shouldDefer();
    }

    @Override
    public Output call(JsonNode input, ToolExecutionContext context) {
        String query = input.has("query") ? input.get("query").asText("") : "";
        int maxResults = DEFAULT_MAX_RESULTS;
        if (input.has("max_results") && input.get("max_results").isNumber()) {
            double raw = input.get("max_results").asDouble(DEFAULT_MAX_RESULTS);
            if (Double.isFinite(raw)) {
                maxResults = (int) raw;
            }
        }

        List<Tool<?, ?>> allTools = new ArrayList<>(toolRegistry.getAll());
        List<Tool<?, ?>> deferredTools = allTools.stream()
            .filter(ToolSearchTool::isDeferredTool)
            .toList();



        // the full tool set is still returned (already-loaded → harmless no-op).
        Matcher selectMatcher = Pattern.compile("^select:(.+)$", Pattern.CASE_INSENSITIVE).matcher(query);
        if (selectMatcher.matches()) {
            List<String> requested = Stream.of(selectMatcher.group(1).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
            Set<String> found = new LinkedHashSet<>();
            for (String name : requested) {
                findByName(deferredTools, name).or(() -> findByName(allTools, name))
                    .ifPresent(t -> found.add(t.name()));
            }
            return found.isEmpty()
                ? output(List.of(), query, deferredTools.size(), safePendingMcpServers())
                : output(List.copyOf(found), query, deferredTools.size(), List.of());
        }

        List<String> matches = searchToolsWithKeywords(query, deferredTools, allTools, maxResults);
        if (matches.isEmpty()) {
            return output(List.of(), query, deferredTools.size(), safePendingMcpServers());
        }
        return output(matches, query, deferredTools.size(), List.of());
    }

    private static Output output(List<String> matches, String query, int totalDeferredTools,
                                 List<String> pendingServers) {
        List<String> safeMatches = matches == null ? List.of() : List.copyOf(matches);
        List<String> safePending = pendingServers == null ? List.of() : List.copyOf(pendingServers);
        RawBlocksOutput blocks = safeMatches.isEmpty()
            ? noMatches(safePending)
            : toolReferences(safeMatches);
        return new Output(safeMatches, query, totalDeferredTools, safePending, blocks);
    }

    @Override
    public ToolResult mapResult(Object rawResult, JsonNode input, ToolExecutionContext context) {
        if (!(rawResult instanceof Output result)) {
            return null;
        }
        return new ToolResult(result.modelContent().blocks(), false)
            .withContentForm(ToolResultContentForm.BLOCKS)
            .withToolUseResult(result.asToolUseResult());
    }

    private static Optional<Tool<?, ?>> findByName(List<Tool<?, ?>> tools, String name) {


        // uses exact string equality, while the separate bare-name fast path is
        // intentionally case-insensitive.
        return tools.stream().filter(t -> t.name().equals(name) || t.aliases().contains(name)).findFirst();
    }

    private static RawBlocksOutput noMatches(List<String> pendingServers) {

        // the user-facing sentence; do not sort or deduplicate this snapshot.
        String suffix = pendingServers == null ? "" : pendingServers.stream()
            .map(s -> s == null ? "" : s)
            .collect(Collectors.joining(", "));
        String text = suffix.isEmpty()
            ? NO_MATCHES_TEXT
            : NO_MATCHES_TEXT + ". Some MCP servers are still connecting: " + suffix
                + ". Their tools will become available shortly — try searching again.";
        return new RawBlocksOutput(List.of(new TextBlock(text)));
    }

    private List<String> safePendingMcpServers() {
        try {
            List<String> pending = pendingMcpServers.get();
            return pending == null ? List.of() : pending;
        } catch (RuntimeException _) {
            // A status snapshot is advisory; a failing MCP status provider must
            // never turn a normal no-match result into a tool execution failure.
            return List.of();
        }
    }

    private static RawBlocksOutput toolReferences(Collection<String> names) {
        List<ContentBlock> blocks = names.stream()
            .map(name -> (ContentBlock) new ToolReferenceBlock(name))
            .toList();
        return new RawBlocksOutput(blocks);
    }

    /**
     * Keyword search over deferred tool names/descriptions.
     */
    private static List<String> searchToolsWithKeywords(String query, List<Tool<?, ?>> deferredTools,
                                                          List<Tool<?, ?>> allTools, int maxResults) {
        String queryLower = query.toLowerCase(Locale.ROOT).trim();

        // Fast path: bare tool name instead of select: prefix (common from
        // subagents/post-compaction) — checks deferred first, falls back to the
        // full tool set (harmless no-op if already discovered).
        Optional<Tool<?, ?>> exact = deferredTools.stream()
            .filter(t -> t.name().equalsIgnoreCase(queryLower)).findFirst()
            .or(() -> allTools.stream().filter(t -> t.name().equalsIgnoreCase(queryLower)).findFirst());
        if (exact.isPresent()) {
            return List.of(exact.get().name());
        }

        // mcp__ prefix — server-name search.
        if (Strings.CS.startsWith(queryLower, "mcp__") && queryLower.length() > 5) {
            List<String> prefixMatches = deferredTools.stream()
                .map(Tool::name)
                .filter(n -> Strings.CI.startsWith(n, queryLower))
                .toList();
            prefixMatches = javascriptSlice(prefixMatches, maxResults);
            if (!prefixMatches.isEmpty()) {
                return prefixMatches;
            }
        }

        List<String> queryTerms = Stream.of(queryLower.split("\\s+"))
            .filter(t -> !t.isEmpty()).toList();
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<String> requiredTerms = new ArrayList<>();
        List<String> optionalTerms = new ArrayList<>();
        for (String term : queryTerms) {
            if (Strings.CS.startsWith(term, "+") && term.length() > 1) {
                requiredTerms.add(term.substring(1));
            } else {
                optionalTerms.add(term);
            }
        }
        List<String> scoringTerms = new ArrayList<>();
        if (!requiredTerms.isEmpty()) {
            scoringTerms.addAll(requiredTerms);
            scoringTerms.addAll(optionalTerms);
        } else {
            scoringTerms.addAll(queryTerms);
        }

        List<Tool<?, ?>> candidates = deferredTools;
        if (!requiredTerms.isEmpty()) {
            candidates = deferredTools.stream()
                .filter(t -> {
                    ParsedName parsed = parseToolName(t.name());
                    String descLower = t.prompt(null).toLowerCase(Locale.ROOT);
                    String hintLower = t.searchHint().toLowerCase(Locale.ROOT);
                    return requiredTerms.stream().allMatch(term ->
                        parsed.parts().contains(term)
                            || parsed.parts().stream().anyMatch(p -> Strings.CS.contains(p, term))
                            || wordBoundaryPattern(term).matcher(hintLower).find()
                            || wordBoundaryPattern(term).matcher(descLower).find());
                })
                .toList();
        }

        record Scored(String name, int score) {}
        List<Scored> scored = candidates.stream().map(t -> {
            ParsedName parsed = parseToolName(t.name());
            String descLower = t.prompt(null).toLowerCase(Locale.ROOT);
            String hintLower = t.searchHint().toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : scoringTerms) {
                if (parsed.parts().contains(term)) {
                    score += parsed.isMcp() ? 12 : 10;
                } else if (parsed.parts().stream().anyMatch(p -> Strings.CS.contains(p, term))) {
                    score += parsed.isMcp() ? 6 : 5;
                }
                if (Strings.CS.contains(parsed.full(), term) && score == 0) {
                    score += 3;
                }
                if (wordBoundaryPattern(term).matcher(hintLower).find()) {
                    score += 4;
                }
                if (wordBoundaryPattern(term).matcher(descLower).find()) {
                    score += 2;
                }
            }
            return new Scored(t.name(), score);
        }).toList();

        List<String> ranked = scored.stream()
            .filter(s -> s.score() > 0)
            .sorted((a, b) -> Integer.compare(b.score(), a.score()))
            .map(Scored::name)
            .toList();
        return javascriptSlice(ranked, maxResults);
    }


    private static List<String> javascriptSlice(List<String> values, int end) {
        if (values == null || values.isEmpty() || end == 0) return List.of();
        int exclusive = end < 0 ? Math.max(0, values.size() + end) : Math.min(values.size(), end);
        return exclusive <= 0 ? List.of() : List.copyOf(values.subList(0, exclusive));
    }

    private static Pattern wordBoundaryPattern(String term) {
        return Pattern.compile("\\b" + Pattern.quote(term) + "\\b");
    }

/**
     * Parsed, lowercased name parts for search matching.
     */
    private record ParsedName(List<String> parts, String full, boolean isMcp) {}

    private static ParsedName parseToolName(String name) {
        if (Strings.CS.startsWith(name, "mcp__")) {
            String withoutPrefix = name.substring("mcp__".length()).toLowerCase(Locale.ROOT);
            List<String> parts = Stream.of(withoutPrefix.split("__"))
                .flatMap(p -> Stream.of(p.split("_")))
                .filter(p -> !p.isEmpty())
                .toList();
            String full = withoutPrefix.replace("__", " ").replace("_", " ");
            return new ParsedName(parts, full, true);
        }
        String spaced = name.replaceAll("([a-z])([A-Z])", "$1 $2").replace("_", " ").toLowerCase(Locale.ROOT);
        List<String> parts = Stream.of(spaced.split("\\s+")).filter(p -> !p.isEmpty()).toList();
        return new ParsedName(parts, String.join(" ", parts), false);
    }
}
