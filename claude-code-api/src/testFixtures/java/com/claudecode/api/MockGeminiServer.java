/*
 * Reusable Google Gemini (/v1beta & Code Assist) mock server inspired by yetone/magpie's
 * Gemini test suite (internal/provider/gemini_api_test.go & internal/gateway/gemini_*_test.go).
 */
package com.claudecode.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Reusable loopback HTTP+SSE mock server for the Google Gemini {@code /v1beta} protocol
 * ({@code :generateContent}, {@code :streamGenerateContent?alt=sse}, JSON-array
 * {@code :streamGenerateContent}, {@code :countTokens}, and {@code GET /models}).
 *
 * <p>By default, enforces real Google AI Studio / Vertex AI wire invariants taken from
 * {@code yetone/magpie}'s Gemini test suites (no top-level {@code model}/{@code stream} fields,
 * history must open with a {@code user} turn, {@code gemini-3+}/{@code gemini-4+} models must use
 * {@code thinkingLevel} instead of a positive {@code thinkingBudget}, and tool schemas must not
 * contain boolean schema nodes or {@code x-*} extension keywords).
 */
public final class MockGeminiServer implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> ALLOWED_OPENAPI_SCHEMA_KEYS = Set.of(
        "type", "format", "title", "description", "nullable", "enum",
        "maxItems", "minItems", "properties", "required", "minProperties",
        "maxProperties", "minLength", "maxLength", "pattern", "example",
        "anyOf", "propertyOrdering", "default", "items", "minimum", "maximum"
    );

    /** Captured inbound HTTP call against this mock Gemini server. */
    public record CapturedRequest(
            String method,
            String path,
            String rawQuery,
            String requestUri,
            String model,
            String action,
            String googApiKey,
            String authorization,
            Map<String, String> headers,
            JsonNode body) {

        public JsonNode generationConfig() {
            return body != null ? body.path("generationConfig") : MAPPER.missingNode();
        }

        public JsonNode thinkingConfig() {
            return generationConfig().path("thinkingConfig");
        }

        public JsonNode contents() {
            return body != null ? body.path("contents") : MAPPER.missingNode();
        }

        public JsonNode tools() {
            return body != null ? body.path("tools") : MAPPER.missingNode();
        }
    }

    /** Listed Gemini model metadata returned by {@code GET {prefix}/models}. */
    public record ListedModel(
            String id,
            String displayName,
            List<String> supportedGenerationMethods,
            int inputTokenLimit,
            int outputTokenLimit) {

        public static ListedModel generateModel(String id, String displayName, int context, int maxOut) {
            return new ListedModel(
                id, displayName, List.of("generateContent", "countTokens"), context, maxOut);
        }
    }

    /** Scripted response for a single {@code :generateContent} or {@code :streamGenerateContent} call. */
    public sealed interface Reply {

        /** Structured candidate chunks automatically rendered as unary JSON, SSE, or JSON array. */
        record CandidateStream(List<ObjectNode> chunks) implements Reply {}

        /** Raw SSE wire payload (useful for testing truncated streams or Code Assist envelopes). */
        record RawSse(String rawSse, int unaryStatusIfCut, String unaryCutMessage) implements Reply {}

        /** Upstream Google API error JSON response. */
        record ErrorReply(int statusCode, String status, String message) implements Reply {}

        static Reply text(String... textChunks) {
            return thoughtAndText(null, null, 7, 3, textChunks);
        }

        static Reply thoughtAndText(
                String thought,
                String signature,
                int promptTokens,
                int candidatesTokens,
                String... textChunks) {
            List<ObjectNode> chunks = new ArrayList<>();
            if (StringUtils.isNotEmpty(thought)) {
                ObjectNode c = candidateChunk(null);
                ObjectNode p = partsArray(c).addObject();
                p.put("text", thought);
                p.put("thought", true);
                if (StringUtils.isNotEmpty(signature) && (textChunks == null || textChunks.length == 0)) {
                    p.put("thoughtSignature", signature);
                }
                chunks.add(c);
            }
            String[] effective = (textChunks == null || textChunks.length == 0)
                ? new String[] {""} : textChunks;
            for (int i = 0; i < effective.length; i++) {
                boolean last = (i == effective.length - 1);
                ObjectNode c = candidateChunk(last ? "STOP" : null);
                ObjectNode p = partsArray(c).addObject();
                p.put("text", effective[i]);
                if (last && StringUtils.isNotEmpty(signature)) {
                    p.put("thoughtSignature", signature);
                }
                if (last) {
                    putUsage(c, promptTokens, candidatesTokens, StringUtils.isNotEmpty(thought) ? 5 : 0);
                }
                chunks.add(c);
            }
            return new CandidateStream(List.copyOf(chunks));
        }

        /** OpenCode Zen / Gemini 3 shape: text chunk followed by empty-text STOP chunk carrying thoughtSignature. */
        static Reply openCodeTrailingSignature(
                String text,
                String trailingSignature,
                int promptTokens,
                int candidatesTokens,
                int thoughtsTokens) {
            ObjectNode first = candidateChunk(null);
            partsArray(first).addObject().put("text", text);
            first.putObject("usageMetadata");

            ObjectNode second = candidateChunk("STOP");
            ObjectNode p2 = partsArray(second).addObject();
            p2.put("text", "");
            p2.put("thoughtSignature", trailingSignature);
            putUsage(second, promptTokens, candidatesTokens, thoughtsTokens);
            return new CandidateStream(List.of(first, second));
        }

        static Reply toolCall(
                String thought,
                String signature,
                String callId,
                String functionName,
                JsonNode args) {
            List<ObjectNode> chunks = new ArrayList<>();
            if (StringUtils.isNotEmpty(thought)) {
                ObjectNode tc = candidateChunk(null);
                ObjectNode tp = partsArray(tc).addObject();
                tp.put("text", thought);
                tp.put("thought", true);
                chunks.add(tc);
            }
            ObjectNode fcChunk = candidateChunk("STOP");
            ObjectNode part = partsArray(fcChunk).addObject();
            ObjectNode fc = part.putObject("functionCall");
            if (StringUtils.isNotEmpty(callId)) fc.put("id", callId);
            fc.put("name", functionName);
            fc.set("args", args != null ? args.deepCopy() : MAPPER.createObjectNode());
            if (StringUtils.isNotEmpty(signature)) {
                part.put("thoughtSignature", signature);
            }
            putUsage(fcChunk, 12, 6, StringUtils.isNotEmpty(thought) ? 4 : 0);
            chunks.add(fcChunk);
            return new CandidateStream(List.copyOf(chunks));
        }

        static Reply malformedFunctionCall(String finishReason, String finishMessage) {
            ObjectNode c = candidateChunk(finishReason);
            JsonNode cand = c.path("candidates").get(0);
            if (cand instanceof ObjectNode candObj) {
                candObj.put("finishMessage", finishMessage);
            }
            putUsage(c, 10, 4, 0);
            return new CandidateStream(List.of(c));
        }

        /** Gemini 3 image generation reply containing a draft thought image + caption + final signed image (#620). */
        static Reply imageWithDraftThought(
                String draftPngBase64, String caption, String finalPngBase64, String signature) {
            ObjectNode c = candidateChunk("STOP");
            ArrayNode parts = partsArray(c);
            if (StringUtils.isNotEmpty(draftPngBase64)) {
                ObjectNode draft = parts.addObject();
                draft.put("thought", true);
                ObjectNode inl = draft.putObject("inlineData");
                inl.put("mimeType", "image/png");
                inl.put("data", draftPngBase64);
            }
            if (StringUtils.isNotEmpty(caption)) {
                parts.addObject().put("text", caption);
            }
            ObjectNode img = parts.addObject();
            ObjectNode inl = img.putObject("inlineData");
            inl.put("mimeType", "image/png");
            inl.put("data", finalPngBase64);
            if (StringUtils.isNotEmpty(signature)) {
                img.put("thoughtSignature", signature);
            }
            putUsage(c, 5, 1567, 0);
            return new CandidateStream(List.of(c));
        }

        /** Wraps candidate chunks inside {@code {"response": ...}} like Gemini Code Assist / Antigravity. */
        static Reply codeAssistWrapped(ObjectNode... innerChunks) {
            StringBuilder sse = new StringBuilder();
            for (ObjectNode inner : innerChunks) {
                ObjectNode outer = MAPPER.createObjectNode();
                outer.set("response", inner);
                sse.append("data: ").append(outer).append("\n\n");
            }
            return new RawSse(sse.toString(), 200, null);
        }

        static Reply error(int statusCode, String status, String message) {
            return new ErrorReply(statusCode, status, message);
        }

        private static ObjectNode candidateChunk(String finishReason) {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("responseId", "gem_mock_" + UUID.randomUUID().toString().substring(0, 8));
            ObjectNode cand = root.putArray("candidates").addObject();
            ObjectNode content = cand.putObject("content");
            content.put("role", "model");
            content.putArray("parts");
            if (StringUtils.isNotEmpty(finishReason)) {
                cand.put("finishReason", finishReason);
            }
            return root;
        }

        private static ArrayNode partsArray(ObjectNode chunk) {
            return (ArrayNode) chunk.path("candidates").get(0).path("content").path("parts");
        }

        private static void putUsage(ObjectNode chunk, int prompt, int candidates, int thoughts) {
            ObjectNode usage = chunk.putObject("usageMetadata");
            usage.put("promptTokenCount", prompt);
            usage.put("candidatesTokenCount", candidates);
            if (thoughts > 0) usage.put("thoughtsTokenCount", thoughts);
            usage.put("totalTokenCount", prompt + candidates + thoughts);
        }
    }

    private final HttpServer server;
    private final String pathPrefix;
    private final String expectedApiKey;
    private final boolean strictWireValidation;
    private final List<List<ListedModel>> modelPages;
    private final ConcurrentLinkedDeque<Reply> scriptedReplies = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<CapturedRequest> capturedRequests = new CopyOnWriteArrayList<>();
    private volatile Function<CapturedRequest, Reply> dynamicHandler;
    private volatile long fixedTokenCount = 42L;

    private MockGeminiServer(Builder builder) throws IOException {
        this.pathPrefix = normalizePrefix(builder.pathPrefix);
        this.expectedApiKey = builder.expectedApiKey;
        this.strictWireValidation = builder.strictWireValidation;
        this.modelPages = List.copyOf(builder.modelPages);
        this.dynamicHandler = builder.dynamicHandler;
        this.server = HttpServer.create(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    public static MockGeminiServer start() throws IOException {
        return builder().start();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Bare host URL without version path (e.g. {@code http://127.0.0.1:54321}). */
    public String hostUrl() {
        return "http://127.0.0.1:" + port();
    }

    /** Configured Gemini base URL including prefix (e.g. {@code http://127.0.0.1:54321/v1beta}). */
    public String baseUrl() {
        return hostUrl() + pathPrefix;
    }

    /** Creates a retry-free {@link GeminiClient} pointing directly at this mock server. */
    public GeminiClient newClient(String apiKey, String defaultModel) {
        return new GeminiClient(
            new ApiConfig.OpenAiConfig(apiKey, defaultModel, baseUrl()),
            new OkHttpClient());
    }

    public MockGeminiServer enqueue(Reply reply) {
        scriptedReplies.addLast(Objects.requireNonNull(reply, "reply"));
        return this;
    }

    public MockGeminiServer enqueueText(String... chunks) {
        return enqueue(Reply.text(chunks));
    }

    public MockGeminiServer onGenerate(Function<CapturedRequest, Reply> handler) {
        this.dynamicHandler = handler;
        return this;
    }

    public MockGeminiServer setTokenCount(long count) {
        this.fixedTokenCount = count;
        return this;
    }

    public List<CapturedRequest> requests() {
        return Collections.unmodifiableList(capturedRequests);
    }

    public CapturedRequest lastRequest() {
        if (capturedRequests.isEmpty()) {
            throw new IllegalStateException("No requests received by MockGeminiServer");
        }
        return capturedRequests.getLast();
    }

    public void clearRequests() {
        capturedRequests.clear();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) {
        try {
            handleSafely(exchange);
        } catch (Throwable failure) {
            try {
                if (exchange.getResponseCode() == -1) {
                    writeGoogleError(exchange, 400, "MOCK_SERVER_BUG", failure.toString());
                }
            } catch (IOException _) {
                // Client already disconnected.
            }
        } finally {
            exchange.close();
        }
    }

    private void handleSafely(HttpExchange exchange) throws IOException {
        {
            byte[] rawBody = exchange.getRequestBody().readAllBytes();
            JsonNode parsedBody = null;
            if (rawBody.length > 0) {
                try {
                    parsedBody = MAPPER.readTree(rawBody);
                } catch (IOException _) {
                    writeGoogleError(exchange, 400, "INVALID_ARGUMENT", "Malformed JSON request body");
                    return;
                }
            }
            String path = exchange.getRequestURI().getPath();
            String rawQuery = StringUtils.defaultString(exchange.getRequestURI().getRawQuery());
            String requestUri = rawQuery.isEmpty() ? path : path + "?" + rawQuery;
            String googKey = exchange.getRequestHeaders().getFirst("x-goog-api-key");
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            Map<String, String> flatHeaders = new LinkedHashMap<>();
            exchange.getRequestHeaders().forEach((k, v) -> {
                if (v != null && !v.isEmpty()) flatHeaders.put(k, v.getFirst());
            });

            String modelsRoot = pathPrefix + "/models";
            String model = "";
            String action = "";
            if (Strings.CS.startsWith(path, modelsRoot + "/")) {
                String tail = path.substring((modelsRoot + "/").length());
                int colon = tail.lastIndexOf(':');
                if (colon > 0) {
                    model = URLDecoder.decode(tail.substring(0, colon), StandardCharsets.UTF_8);
                    action = tail.substring(colon + 1);
                } else {
                    model = URLDecoder.decode(tail, StandardCharsets.UTF_8);
                    action = "get";
                }
            }

            CapturedRequest captured = new CapturedRequest(
                exchange.getRequestMethod(), path, rawQuery, requestUri,
                model, action, googKey, auth, Map.copyOf(flatHeaders), parsedBody);
            capturedRequests.add(captured);

            if (!checkAuth(exchange, rawQuery, googKey, auth)) {
                return;
            }

            if (Strings.CS.equals("GET", exchange.getRequestMethod()) && Strings.CS.equals(modelsRoot, path)) {
                handleListModels(exchange, rawQuery);
                return;
            }

            if (!Strings.CS.equals("POST", exchange.getRequestMethod())
                    || !Strings.CS.startsWith(path, modelsRoot + "/")
                    || StringUtils.isEmpty(action)) {
                writeGoogleError(exchange, 404, "NOT_FOUND", "Unknown Gemini route: " + path);
                return;
            }

            if (Strings.CS.equals("countTokens", action)) {
                ObjectNode tok = MAPPER.createObjectNode();
                tok.put("totalTokens", fixedTokenCount);
                writeJson(exchange, 200, tok);
                return;
            }

            if (!Strings.CS.equals("generateContent", action)
                    && !Strings.CS.equals("streamGenerateContent", action)) {
                writeGoogleError(exchange, 404, "NOT_FOUND", "Unsupported Gemini action: " + action);
                return;
            }

            if (strictWireValidation) {
                String fault = validateGoogleGenerateRequest(model, parsedBody);
                if (fault != null) {
                    writeGoogleError(exchange, 400, "INVALID_ARGUMENT", fault);
                    return;
                }
            }

            Reply reply = scriptedReplies.pollFirst();
            if (reply == null && dynamicHandler != null) {
                reply = dynamicHandler.apply(captured);
            }
            if (reply == null) {
                reply = Reply.text("mock-gemini-ok");
            }

            boolean streaming = Strings.CS.equals("streamGenerateContent", action);
            boolean sse = streaming && queryParamEquals(rawQuery, "alt", "sse");
            writeReply(exchange, model, reply, streaming, sse);
        }
    }

    private boolean checkAuth(
            HttpExchange exchange, String rawQuery, String googKey, String auth) throws IOException {
        if (StringUtils.isBlank(expectedApiKey)) return true;
        String queryKey = queryParam(rawQuery, "key");
        String bearer = (auth != null && Strings.CI.startsWith(auth, "Bearer "))
            ? auth.substring("Bearer ".length()).trim() : "";
        if (Strings.CS.equals(expectedApiKey, googKey)
                || Strings.CS.equals(expectedApiKey, queryKey)
                || Strings.CS.equals(expectedApiKey, bearer)) {
            return true;
        }
        writeGoogleError(exchange, 401, "UNAUTHENTICATED", "API key not valid. Please pass a valid API key.");
        return false;
    }

    private void handleListModels(HttpExchange exchange, String rawQuery) throws IOException {
        List<List<ListedModel>> pages = modelPages.isEmpty()
            ? List.of(List.of(
                ListedModel.generateModel("gemini-2.5-pro", "Gemini 2.5 Pro", 1_048_576, 65_536),
                ListedModel.generateModel("gemini-3-flash", "Gemini 3 Flash", 1_048_576, 65_536)))
            : modelPages;
        String token = queryParam(rawQuery, "pageToken");
        int pageIndex = 0;
        if (Strings.CS.startsWith(token, "p")) {
            try {
                pageIndex = Integer.parseInt(token.substring(1)) - 1;
            } catch (NumberFormatException _) {
                pageIndex = 0;
            }
        }
        if (pageIndex < 0 || pageIndex >= pages.size()) {
            pageIndex = pages.size() - 1;
        }
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode arr = out.putArray("models");
        for (ListedModel m : pages.get(pageIndex)) {
            ObjectNode item = arr.addObject();
            item.put("name", Strings.CS.startsWith(m.id(), "models/") ? m.id() : "models/" + m.id());
            if (StringUtils.isNotBlank(m.displayName())) item.put("displayName", m.displayName());
            ArrayNode methods = item.putArray("supportedGenerationMethods");
            m.supportedGenerationMethods().forEach(methods::add);
            if (m.inputTokenLimit() > 0) item.put("inputTokenLimit", m.inputTokenLimit());
            if (m.outputTokenLimit() > 0) item.put("outputTokenLimit", m.outputTokenLimit());
        }
        if (pageIndex + 1 < pages.size()) {
            out.put("nextPageToken", "p" + (pageIndex + 2));
        }
        writeJson(exchange, 200, out);
    }

    private static String validateGoogleGenerateRequest(String model, JsonNode body) {
        if (body == null || !body.isObject()) {
            return "Request body must be a JSON object";
        }
        if (body.has("model") || body.has("stream")) {
            return "Unknown top-level field 'model' or 'stream' in GenerateContentRequest";
        }
        JsonNode contents = body.get("contents");
        if (contents == null || !contents.isArray() || contents.isEmpty()) {
            return "contents is required and must be non-empty";
        }
        String firstRole = contents.get(0).path("role").asText("user");
        if (Strings.CS.equals("model", firstRole)) {
            return "Please ensure that function call turn comes immediately after a user turn or after a function response turn.";
        }
        for (JsonNode turn : contents) {
            String role = turn.path("role").asText("user");
            if (Strings.CS.equals("assistant", role) || Strings.CS.equals("system", role)) {
                return "Invalid content role '" + role + "'; Gemini accepts only 'user' and 'model'";
            }
        }
        // Gemini 3 / 4 thinkingBudget vs thinkingLevel rule (#2083)
        JsonNode tc = body.path("generationConfig").path("thinkingConfig");
        if (tc.isObject() && tc.hasNonNull("thinkingBudget") && tc.get("thinkingBudget").asInt(0) > 0) {
            if (GeminiClient.geminiGeneration(model) >= 3) {
                return "Thinking budget is not supported for this model. Use thinking level instead.";
            }
        }
        // Validate tool declaration schemas (#2083 & gemini_bool_schema_test.go)
        JsonNode tools = body.get("tools");
        if (tools != null && tools.isArray()) {
            for (JsonNode toolObj : tools) {
                for (JsonNode decl : toolObj.path("functionDeclarations")) {
                    if (decl.has("parameters")) {
                        List<String> faults = findOpenApiSchemaFaults(decl.get("parameters"), "parameters");
                        if (!faults.isEmpty()) {
                            return "Invalid Gemini Schema at " + faults;
                        }
                    }
                    if (decl.has("parametersJsonSchema")) {
                        String jsonSchemaFault = findJsonSchemaExtensionFault(
                            decl.get("parametersJsonSchema"), "parametersJsonSchema");
                        if (jsonSchemaFault != null) {
                            return jsonSchemaFault;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static List<String> findOpenApiSchemaFaults(JsonNode schema, String path) {
        if (schema == null || !schema.isObject()) {
            return List.of(path + "=" + schema);
        }
        List<String> faults = new ArrayList<>();
        schema.properties().forEach(entry -> {
            String key = entry.getKey();
            JsonNode val = entry.getValue();
            if (!ALLOWED_OPENAPI_SCHEMA_KEYS.contains(key)) {
                faults.add(path + "." + key);
            } else if (Strings.CS.equals("items", key)) {
                faults.addAll(findOpenApiSchemaFaults(val, path + ".items"));
            } else if (Strings.CS.equals("properties", key) && val.isObject()) {
                val.properties().forEach(prop ->
                    faults.addAll(findOpenApiSchemaFaults(prop.getValue(),
                        path + ".properties[" + prop.getKey() + "]")));
            } else if (Strings.CS.equals("anyOf", key) && val.isArray()) {
                for (int i = 0; i < val.size(); i++) {
                    faults.addAll(findOpenApiSchemaFaults(val.get(i), path + ".anyOf[" + i + "]"));
                }
            }
        });
        return faults;
    }

    private static String findJsonSchemaExtensionFault(JsonNode node, String path) {
        if (node == null || node.isNull()) return null;
        if (node.isBoolean()) {
            return "Boolean schema not supported by Gemini at " + path + "=" + node;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String f = findJsonSchemaExtensionFault(node.get(i), path + "[" + i + "]");
                if (f != null) return f;
            }
            return null;
        }
        if (!node.isObject()) return null;
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            String k = e.getKey();
            if (Strings.CS.equals("$schema", k) || Strings.CS.startsWith(k, "x-")) {
                return "Unsupported keyword '" + k + "' at " + path;
            }
            if ((Strings.CS.equals("additionalProperties", k)
                    || Strings.CS.equals("unevaluatedProperties", k)
                    || Strings.CS.equals("unevaluatedItems", k))
                    && e.getValue().isBoolean()) {
                continue;
            }
            if ((Strings.CS.equals("properties", k)
                    || Strings.CS.equals("$defs", k)
                    || Strings.CS.equals("definitions", k))
                    && e.getValue().isObject()) {
                for (Map.Entry<String, JsonNode> prop : e.getValue().properties()) {
                    // Property/$defs names (including x-region) are identifiers; validate their schema values.
                    String f = findJsonSchemaExtensionFault(
                        prop.getValue(), path + "." + k + "[" + prop.getKey() + "]");
                    if (f != null) return f;
                }
            } else {
                String f = findJsonSchemaExtensionFault(e.getValue(), path + "." + k);
                if (f != null) return f;
            }
        }
        return null;
    }

    private static void writeReply(
            HttpExchange exchange, String model, Reply reply, boolean streaming, boolean sse)
            throws IOException {
        switch (reply) {
            case Reply.ErrorReply(int code, String status, String msg) ->
                writeGoogleError(exchange, code, status, msg);
            case Reply.RawSse(String rawSse, int unaryCode, String unaryCutMsg) -> {
                if (!streaming && unaryCode >= 400) {
                    writeGoogleError(exchange, unaryCode, "INTERNAL",
                        StringUtils.defaultIfBlank(unaryCutMsg, "Stream cut short"));
                    return;
                }
                byte[] bytes = rawSse.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type",
                    sse ? "text/event-stream; charset=utf-8" : "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, streaming ? 0 : bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                    out.flush();
                }
            }
            case Reply.CandidateStream(List<ObjectNode> chunks) -> {
                List<ObjectNode> stamped = chunks.stream().map(c -> {
                    ObjectNode copy = c.deepCopy();
                    if (!copy.hasNonNull("modelVersion") && StringUtils.isNotBlank(model)) {
                        copy.put("modelVersion", model);
                    }
                    return copy;
                }).toList();

                if (!streaming) {
                    writeJson(exchange, 200, mergeChunksForUnary(stamped, model));
                } else if (sse) {
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(200, 0);
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (ObjectNode chunk : stamped) {
                            byte[] frame = ("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8);
                            out.write(frame);
                            out.flush();
                        }
                    }
                } else {
                    ArrayNode arr = MAPPER.createArrayNode();
                    stamped.forEach(arr::add);
                    writeJson(exchange, 200, arr);
                }
            }
        }
    }

    private static ObjectNode mergeChunksForUnary(List<ObjectNode> chunks, String model) {
        if (chunks.size() == 1) return chunks.getFirst();
        ObjectNode merged = MAPPER.createObjectNode();
        merged.put("responseId", chunks.isEmpty()
            ? "gem_empty" : chunks.getFirst().path("responseId").asText("gem_merged"));
        if (StringUtils.isNotBlank(model)) merged.put("modelVersion", model);
        ObjectNode cand = merged.putArray("candidates").addObject();
        ObjectNode content = cand.putObject("content");
        content.put("role", "model");
        ArrayNode parts = content.putArray("parts");
        String finishReason = "STOP";
        JsonNode lastUsage = null;
        for (ObjectNode chunk : chunks) {
            JsonNode c0 = chunk.path("candidates").path(0);
            for (JsonNode p : c0.path("content").path("parts")) {
                parts.add(p.deepCopy());
            }
            if (c0.hasNonNull("finishReason")) {
                finishReason = c0.path("finishReason").asText("STOP");
            }
            if (chunk.has("usageMetadata")) {
                lastUsage = chunk.get("usageMetadata");
            }
        }
        cand.put("finishReason", finishReason);
        if (lastUsage != null) {
            merged.set("usageMetadata", lastUsage.deepCopy());
        }
        return merged;
    }

    private static void writeGoogleError(
            HttpExchange exchange, int code, String status, String message) throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode err = root.putObject("error");
        err.put("code", code);
        err.put("message", message);
        err.put("status", status);
        writeJson(exchange, code, root);
    }

    private static void writeJson(HttpExchange exchange, int status, JsonNode node) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(node);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
            out.flush();
        }
    }

    private static String normalizePrefix(String prefix) {
        String p = StringUtils.defaultIfBlank(prefix, "/v1beta").trim();
        if (!Strings.CS.startsWith(p, "/")) p = "/" + p;
        while (p.length() > 1 && Strings.CS.endsWith(p, "/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static boolean queryParamEquals(String rawQuery, String name, String expected) {
        return Strings.CI.equals(expected, queryParam(rawQuery, name));
    }

    private static String queryParam(String rawQuery, String name) {
        if (StringUtils.isEmpty(rawQuery)) return "";
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (Strings.CS.equals(name, k)) {
                return eq >= 0
                    ? URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }

    /** Builder for configuring {@link MockGeminiServer}. */
    public static final class Builder {
        private String pathPrefix = "/v1beta";
        private String expectedApiKey;
        private boolean strictWireValidation = true;
        private final List<List<ListedModel>> modelPages = new ArrayList<>();
        private Function<CapturedRequest, Reply> dynamicHandler;

        public Builder pathPrefix(String value) {
            this.pathPrefix = value;
            return this;
        }

        public Builder expectedApiKey(String value) {
            this.expectedApiKey = value;
            return this;
        }

        public Builder strictWireValidation(boolean value) {
            this.strictWireValidation = value;
            return this;
        }

        public Builder addModelPage(List<ListedModel> models) {
            this.modelPages.add(List.copyOf(models));
            return this;
        }

        public Builder onGenerate(Function<CapturedRequest, Reply> handler) {
            this.dynamicHandler = handler;
            return this;
        }

        public MockGeminiServer start() throws IOException {
            return new MockGeminiServer(this);
        }
    }
}
