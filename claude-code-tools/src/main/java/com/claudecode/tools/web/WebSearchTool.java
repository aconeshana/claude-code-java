package com.claudecode.tools.web;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.message.WebSearchToolResultBlock;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.http.HttpCalls;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolHttpClient;
import com.claudecode.tools.ToolCallResult;

/**
 * WebSearchTool — web search integration with Anthropic's server-side {@code web_search_20250305}
 * tool.
 */
@BuiltInTool(
    name = "WebSearch",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class WebSearchTool extends AnnotatedTool<JsonNode, String> {

    private static final JsonNode SCHEMA = buildSchema();
    private static final Pattern MONTH_YEAR_LINE = Pattern.compile(
        "(?m)(The current month is )[^.]+(?=\\. You MUST use this year)");
    /** Extracts a complete query from streamed server-tool input JSON. */
    private static final Pattern STREAMED_QUERY = Pattern.compile(
        "\\\"query\\\"\\s*:\\s*\\\"((?:[^\\\"\\\\]|\\\\.)*)\\\"");
    private static final DateTimeFormatter MONTH_YEAR_FORMAT =
        DateTimeFormatter.ofPattern("MMMM uuuu", Locale.US);

    private final StreamingClient llmClient;
    private final OkHttpClient httpClient;
    private static final class InvocationCapture {
        private SearchInvocation invocation;
    }

    private record SearchInvocation(String query, List<Object> results,
                                    double durationSeconds) {}

    public WebSearchTool() {
        this(null, ToolHttpClient.standard());
    }


    public WebSearchTool(StreamingClient llmClient) {
        this(llmClient, ToolHttpClient.standard());
    }

    WebSearchTool(StreamingClient llmClient, OkHttpClient httpClient) {
        this.llmClient = llmClient;
        this.httpClient = httpClient;
    }

    @Override
    public String description() {
        String template = ToolTexts.description("WebSearch");
        return renderDescription(template, Instant.now(), ZoneId.systemDefault(),
            SubprocessEnvironment.get("CLAUDE_CODE_OVERRIDE_DATE"));
    }


    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        String query = input == null ? "" : input.path("query").asText("");
        return StringUtils.isBlank(query) ? description() : "Claude wants to search the web for: " + query;
    }

    @Override
    public String searchHint() {
        return "search the web for current information";
    }


    @Override
    public String prompt(ToolExecutionContext context) {
        String monthYear = localMonthYear(Instant.now(), ZoneId.systemDefault(),
            SubprocessEnvironment.get("CLAUDE_CODE_OVERRIDE_DATE"));
        return ToolTexts.render(ToolTexts.prompt("WebSearch", "template"),
            Map.of("MONTH_YEAR", monthYear));
    }


    @Override
    public boolean isEnabled() {
        if (llmClient == null) return true;
        String provider = llmClient.provider();
        if (Strings.CS.equals("firstParty", provider)
                || Strings.CS.equals("foundry", provider)) return true;
        if (!Strings.CS.equals("vertex", provider)) return false;
        String model = llmClient.getModel();
        return model != null && (Strings.CS.contains(model, "claude-opus-5")
            || Strings.CS.contains(model, "claude-opus-4")
            || Strings.CS.contains(model, "claude-sonnet-5")
            || Strings.CS.contains(model, "claude-sonnet-4")
            || Strings.CS.contains(model, "claude-haiku-4"));
    }

    static String renderDescription(String template, Instant now, ZoneId zone,
                                    String overrideDate) {
        String monthYear = localMonthYear(now, zone, overrideDate);
        return MONTH_YEAR_LINE.matcher(template)
            .replaceFirst(match -> match.group(1) + monthYear);
    }

/** matches {@code new Date(override).toLocaleString('en-US',...)} for ISO dates. */
    static String localMonthYear(Instant now, ZoneId zone, String overrideDate) {
        ZonedDateTime date = now.atZone(zone);
        if (StringUtils.isNotBlank(overrideDate)) {
            try {
                // ECMAScript parses a bare YYYY-MM-DD at UTC midnight before
                // formatting it in the local zone; preserve that edge behavior.
                date = LocalDate.parse(overrideDate).atStartOfDay(ZoneOffset.UTC)
                    .toInstant().atZone(zone);
            } catch (RuntimeException _) {
                try {
                    date = Instant.parse(overrideDate).atZone(zone);
                } catch (RuntimeException _) {
                    return "Invalid Date";
                }
            }
        }
        return MONTH_YEAR_FORMAT.format(date);
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return callWithResult(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        InvocationCapture capture = new InvocationCapture();
        Instant started = Instant.now();
        if (context != null) {
            String initialQuery = input == null ? "" : input.path("query").asText("");
            context.reportProgress(ToolExecutionContext.ProgressUpdate.webSearch(
                "query_update", initialQuery, null, "Searching: " + initialQuery));
        }
        if (input == null) {
            return mapped("Error: Missing query",
                new SearchInvocation("", List.of("Error: Missing query"), 0.0));
        }
        String query = input.has("query") ? input.get("query").asText("") : "";
        if (StringUtils.isBlank(query)) {
            return mapped("Error: Missing query",
                new SearchInvocation(query, List.of("Error: Missing query"), 0.0));
        }


// z.string.min(2), but the Java tool contract carries only metadata,

// schema-level validation that would otherwise block the call).
        if (query.length() < 2) {
            return mapped("Error: Query must be at least 2 characters",
                new SearchInvocation(query, List.of("Error: Query must be at least 2 characters"), 0.0));
        }

        List<String> denyDomains    = extractDeniedDomains(input);
        List<String> allowedDomains = extractAllowedDomains(input);

        if (!allowedDomains.isEmpty() && !denyDomains.isEmpty()) {
            String error = "Error: Cannot specify both allowed_domains and blocked_domains in the same request";
            return mapped(error, new SearchInvocation(query, List.of(error), 0.0));
        }
        String source = extractSource(input);

        String output = executeDirectSearch(
            query, source, denyDomains, allowedDomains, context, capture);
        if (capture.invocation == null) {

            // shape: the model receives the text while downstream observers
            // retain query/results/duration metadata.
            capture.invocation = new SearchInvocation(query, List.of(output),
                Duration.between(started, Instant.now()).toNanos() / 1_000_000_000.0);
        }
        return mapped(output, capture.invocation);
    }


    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        PermissionUpdate suggestion = new PermissionUpdate.AddRules(
            List.of(new PermissionUpdate.RuleValue(name(), null)),
            PermissionUpdate.Behavior.ALLOW,
            PermissionUpdate.Destination.LOCAL_SETTINGS);
        return new PermissionDecision.Ask(
            null, null,
            "WebSearchTool requires permission.",
            null, null, List.of(suggestion));
    }

    private String executeDirectSearch(String query, String source, List<String> denyDomains,
                                       List<String> allowedDomains, ToolExecutionContext context,
                                       InvocationCapture capture) {
        try {
            if (Strings.CI.equals("anthropic", source) || source.isEmpty()) {
                return searchWithAnthropicApi(query, denyDomains, allowedDomains, context, capture);
            }
            return searchWithBraveApi(query, denyDomains);
        } catch (Exception e) {
            return "Error: search failed: " + e.getMessage();
        }
    }

    private String searchWithAnthropicApi(String query, List<String> denyDomains, List<String> allowedDomains,
                                          ToolExecutionContext context, InvocationCapture capture) {
        if (llmClient == null) {
            return "Web search not configured. Query was: " + query + ". " +
                   "Configure ANTHROPIC_API_KEY or use a search provider.";
        }
        return searchWithAnthropicWebSearch(query, denyDomains, allowedDomains, context, capture);
    }


    private String searchWithAnthropicWebSearch(String query, List<String> denyDomains, List<String> allowedDomains,
                                                 ToolExecutionContext context, InvocationCapture capture) {
        Instant started = Instant.now();
        StreamingClient.StreamRequest.ToolDef webSearchTool = StreamingClient.StreamRequest.ToolDef.serverTool(
            "web_search_20250305", "web_search", 8,
            allowedDomains == null || allowedDomains.isEmpty() ? null : allowedDomains,
            denyDomains == null || denyDomains.isEmpty() ? null : denyDomains);

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            llmClient.getModel(),
            4096,
            "You are an assistant for performing a web search tool use",
            List.of(new StreamingClient.StreamRequest.RequestMessage(
                "user", "Perform a web search for the query: " + query)),
            true,
            List.of(webSearchTool));

        // Keyed by content_block index so the final text/result ordering
        // matches the order Anthropic streamed them in.
        Map<Integer, StringBuilder> textBlocks = new TreeMap<>();
        Map<Integer, WebSearchToolResultBlock> searchResults = new TreeMap<>();
        Map<String, StringBuilder> serverToolInputs = new HashMap<>();
        Map<Integer, String> serverToolIdsByIndex = new HashMap<>();
        Map<String, String> toolUseQueries = new HashMap<>();

        try {
            Iterator<StreamingClient.StreamingEvent> events = llmClient.createStream(request);
            while (events.hasNext()) {
                if (context != null && context.abortController() != null
                        && context.abortController().isAborted()) {
                    break;
                }
                StreamingClient.StreamingEvent event = events.next();
                if (event instanceof StreamingClient.StreamingEvent.ContentBlockStartEvent start) {
                    if (Strings.CS.equals("text", start.type())) {
                        textBlocks.put(start.index(), new StringBuilder());
                    } else if (Strings.CS.equals("server_tool_use", start.type())
                            && start.id() != null) {

                        // so the UI can show query_update before the result arrives.
                        serverToolInputs.put(start.id(), new StringBuilder());
                        serverToolIdsByIndex.put(start.index(), start.id());
                    } else if (Strings.CS.equals("web_search_tool_result", start.type())
                            && start.block() instanceof WebSearchToolResultBlock result) {
                        searchResults.put(start.index(), result);
                        String actualQuery = toolUseQueries.getOrDefault(result.toolUseId(), query);
                        if (context != null) {
                            int count = result.content() == null ? 0 : result.content().size();
                            context.reportProgress(ToolExecutionContext.ProgressUpdate.webSearch(
                                "search_results_received", actualQuery, (long) count,
                                "Found " + count + " results for \"" + actualQuery + "\""));
                        }
                    }
                } else if (event instanceof StreamingClient.StreamingEvent.ContentBlockDeltaEvent delta) {
                    if (Strings.CS.equals("text_delta", delta.deltaType())) {
                        StringBuilder sb = textBlocks.get(delta.index());
                        if (sb != null) sb.append(delta.deltaText());
                    } else if (Strings.CS.equals("input_json_delta", delta.deltaType())) {
                        // ContentBlockDeltaEvent carries the content-block index;
                        // use the exact start-event index rather than map iteration
                        // order (multiple server tools can be interleaved).
                        String serverId = serverToolIdsByIndex.get(delta.index());
                        if (serverId != null) {
                            StringBuilder json = serverToolInputs.get(serverId);
                            json.append(delta.deltaText() == null ? "" : delta.deltaText());
                            Matcher matcher = STREAMED_QUERY.matcher(json);
                            if (matcher.find()) {
                                String actualQuery = unescapeJsonString(matcher.group(1));
                                if (!StringUtils.isBlank(actualQuery)
                                        && !actualQuery.equals(toolUseQueries.get(serverId))) {
                                    toolUseQueries.put(serverId, actualQuery);
                                    if (context != null) {
                                        context.reportProgress(ToolExecutionContext.ProgressUpdate.webSearch(
                                            "query_update", actualQuery, null,
                                            "Searching: " + actualQuery));
                                    }
                                }
                            }
                        }
                    }
                } else if (event instanceof StreamingClient.StreamingEvent.ErrorEvent(var exception, _)) {
                    return "Web search failed: " + exception.getMessage();
                }
            }
        } catch (Exception e) {
            return "Web search failed: " + e.getMessage();
        }

        List<Object> results = new ArrayList<>();
        TreeSet<Integer> indices = new TreeSet<>();
        indices.addAll(textBlocks.keySet());
        indices.addAll(searchResults.keySet());
        for (int index : indices) {
            WebSearchToolResultBlock result = searchResults.get(index);
            if (result != null) {
                if (result.errorCode() != null) {
                    results.add("Web search error: " + result.errorCode());
                } else {
                    ObjectNode item = mapper().createObjectNode();
                    item.put("tool_use_id", result.toolUseId());
                    ArrayNode content = item.putArray("content");
                    if (result.content() != null) {
                        for (WebSearchToolResultBlock.Hit hit : result.content()) {
                            ObjectNode link = content.addObject();
                            link.put("title", hit.title());
                            link.put("url", hit.url());
                        }
                    }
                    results.add(item);
                }
            }
            StringBuilder text = textBlocks.get(index);
            if (text != null && !StringUtils.isBlank(text.toString())) {
                results.add(text.toString().trim());
            }
        }
        capture.invocation = new SearchInvocation(query, List.copyOf(results),
            Duration.between(started, Instant.now()).toNanos() / 1_000_000_000.0);
        return formatAnthropicSearchResults(query, textBlocks, searchResults);
    }

    private static String unescapeJsonString(String value) {
        try {
            return mapper().readTree("\"" + value + "\"").asText();
        } catch (Exception _) {
            return value.replace("\\\"", "\"").replace("\\\\", "\\");
        }
    }


    private ToolCallResult<String> mapped(String text, SearchInvocation invocation) {
        ObjectNode data = mapper().createObjectNode();
        data.put("query", invocation.query());
        ArrayNode results = data.putArray("results");
        for (Object result : invocation.results()) {
            results.add(mapper().valueToTree(result));
        }
        data.put("durationSeconds", invocation.durationSeconds());
        return new ToolCallResult<>(text,
            ToolResult.success(text).withToolUseResult(data));
    }


    String formatAnthropicSearchResults(String query, Map<Integer, StringBuilder> textBlocks,
                                        Map<Integer, WebSearchToolResultBlock> searchResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for query: \"").append(query).append("\"\n\n");
        TreeSet<Integer> indices = new TreeSet<>();
        indices.addAll(textBlocks.keySet());
        indices.addAll(searchResults.keySet());

        for (int index : indices) {
            WebSearchToolResultBlock result = searchResults.get(index);
            if (result != null) {
                if (result.errorCode() != null) {
                    sb.append("Web search error: ").append(result.errorCode()).append("\n\n");
                } else if (result.content() != null && !result.content().isEmpty()) {
                    ArrayNode links = mapper().createArrayNode();
                    for (WebSearchToolResultBlock.Hit hit : result.content()) {
                        ObjectNode link = mapper().createObjectNode();
                        link.put("title", hit.title());
                        link.put("url", hit.url());
                        links.add(link);
                    }
                    sb.append("Links: ").append(links.toString()).append("\n\n");
                } else {

                    sb.append("No links found.\n\n");
                }
                continue;
            }
            StringBuilder text = textBlocks.get(index);
            if (text != null && !StringUtils.isBlank(text.toString())) {
                sb.append(text.toString().trim()).append("\n\n");
            }
        }

        sb.append(SOURCES_REMINDER);
        return sb.toString().trim();
    }

    private String searchWithBraveApi(String query, List<String> denyDomains) {
        String apiKey = SubprocessEnvironment.get("BRAVE_API_KEY");
        if (StringUtils.isBlank(apiKey)) {
            return "Web search not configured. Query was: " + query + ". " +
                   "Configure BRAVE_API_KEY environment variable or use a search provider.";
        }
        return searchWithBrave(query, apiKey, denyDomains);
    }

    private String searchWithBrave(String query, String apiKey, List<String> denyDomains) {
        try {
            String encodedQuery = URLEncoder.encode(query, UTF_8);
            String endpoint = "https://api.search.brave.com/res/v1/web/search?q=" + encodedQuery + "&count=10";

            Request request = new Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("X-Subscription-Token", apiKey)
                .build();

            try (Response response = HttpCalls.execute(
                    httpClient, request, Duration.ofSeconds(30))) {
                String body = response.body().string();
                if (response.code() == 200) {
                    JsonNode respJson = mapper().readTree(body);
                    return formatBraveResults(respJson, denyDomains);
                }
                return "Web search failed with status " + response.code() + ": " + body;
            }
        } catch (Exception e) {
            return "Web search failed: " + e.getMessage();
        }
    }


    private static final String SOURCES_REMINDER =
        """


            REMINDER: You MUST include the sources above in your response to the \
            user using markdown hyperlinks.""";

    private String formatBraveResults(JsonNode response, List<String> denyDomains) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results:\n\n");
        boolean hasResults = false;
        if (response.has("web") && response.get("web").has("results")) {
            for (JsonNode result : response.get("web").get("results")) {
                String title   = result.has("title")       ? result.get("title").asText()       : "";
                String url     = result.has("url")         ? result.get("url").asText()         : "";
                String snippet = result.has("description") ? result.get("description").asText() : "";
                if (!StringUtils.isBlank(title) && !StringUtils.isBlank(url)) {
                    String domain = extractDomain(url);
                    if (denyDomains != null && denyDomains.contains(domain)) continue;
                    sb.append("- [").append(title).append("](").append(url).append(")");
                    if (!StringUtils.isBlank(snippet)) sb.append("\n  > ").append(snippet);
                    sb.append("\n");
                    hasResults = true;
                }
            }
        }
        if (!hasResults) sb.append("No results found.");
        sb.append(SOURCES_REMINDER);
        return sb.toString().trim();
    }

    private List<String> extractDeniedDomains(JsonNode input) {
        List<String> domains = new ArrayList<>();

        String[] keys = {"blocked_domains", "deny_domains"};
        for (String key : keys) {
            if (input.has(key) && input.get(key).isArray()) {
                for (JsonNode node : input.get(key)) {
                    domains.add(node.asText());
                }
                break;
            }
        }
        return domains;
    }


    private List<String> extractAllowedDomains(JsonNode input) {
        List<String> domains = new ArrayList<>();
        if (input.has("allowed_domains") && input.get("allowed_domains").isArray()) {
            for (JsonNode node : input.get("allowed_domains")) {
                domains.add(node.asText());
            }
        }
        return domains;
    }

    private String extractSource(JsonNode input) {
        if (input.has("source") && !input.get("source").isNull()) {
            return input.get("source").asText("");
        }
        return "";
    }

    /** There is no per-domain permission rule: WebSearch has no URL input. */
    private String extractDomain(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host != null) {
                if (Strings.CS.startsWith(host, "www.")) {
                    host = host.substring(4);
                }
                return host;
            }
        } catch (Exception _) {
        }
        return "";
    }



    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return input == null ? "" : input.path("query").asText("");
    }



    private static JsonNode buildSchema() {

        // { query(required, min 2), allowed_domains(array), blocked_domains(array) },


        // the model-facing contract (extractSource() below still honors it
        // as an internal default, just not settable via the tool_use input
        // anymore).
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("query")
            .put("type", "string")
            .put("minLength", 2)
            .put("description", "The search query to use");

        ObjectNode allowed = properties.putObject("allowed_domains");
        allowed.put("type", "array");
        allowed.putObject("items").put("type", "string");
        allowed.put("description", "Only include search results from these domains");

        ObjectNode blocked = properties.putObject("blocked_domains");
        blocked.put("type", "array");
        blocked.putObject("items").put("type", "string");
        blocked.put("description", "Never include search results from these domains");

        schema.putArray("required").add("query");

        // validation error rather than silently passed to the server tool.
        schema.put("additionalProperties", false);
        return schema;
    }
}
