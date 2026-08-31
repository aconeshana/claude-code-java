package com.claudecode.tools.web;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.config.SettingsPathResolver;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.http.HttpCalls;
import com.claudecode.http.CancellationRegistrar;
import com.claudecode.http.ResponseBodies;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolHttpClient;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ValidationResult;

/**
 * WebFetchTool — fetches URL content, converts HTML to markdown, and runs the fetched content
 * through a secondary LLM to answer the user's prompt.
 */
@BuiltInTool(
    name = "WebFetch",
    shouldDefer = true,
    readOnly = true,
    concurrencySafe = true
)
public class WebFetchTool extends AnnotatedTool<JsonNode, String> {

    /**
     * Maximum response body size in bytes (10 MB).
     */
    public static final int MAX_BODY_SIZE = 10 * 1024 * 1024;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    public static final int MAX_REDIRECTS = 10;


    public static final int MAX_MARKDOWN_LENGTH = 100_000;


    private static final long URL_CACHE_TTL_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final long URL_CACHE_MAX_BYTES = 50L * 1024L * 1024L;

    private static final long DOMAIN_CACHE_TTL_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int DOMAIN_CACHE_MAX_ENTRIES = 128;
    private static final Object CACHE_LOCK = new Object();
    private static final LinkedHashMap<String, CachedFetch> URL_CACHE =
        new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<String, Long> DOMAIN_CHECK_CACHE =
        new LinkedHashMap<>(16, 0.75f, true);
    private static long urlCacheBytes;


    public static final String WEB_FETCH_USER_AGENT =
        "Claude-User (claude-code; +https://support.anthropic.com/)";

    private static final JsonNode SCHEMA = buildSchema();


    private static final Set<String> PREAPPROVED_HOSTS = Set.of(
        "platform.claude.com", "code.claude.com", "modelcontextprotocol.io",
        "github.com/anthropics", "agentskills.io",
        "docs.python.org", "en.cppreference.com", "docs.oracle.com",
        "learn.microsoft.com", "developer.mozilla.org", "go.dev", "pkg.go.dev",
        "www.php.net", "docs.swift.org", "kotlinlang.org", "ruby-doc.org",
        "doc.rust-lang.org", "www.typescriptlang.org",
        "react.dev", "angular.io", "vuejs.org", "nextjs.org", "expressjs.com",
        "nodejs.org", "bun.sh", "jquery.com", "getbootstrap.com", "tailwindcss.com",
        "d3js.org", "threejs.org", "redux.js.org", "webpack.js.org", "jestjs.io",
        "reactrouter.com", "docs.djangoproject.com", "flask.palletsprojects.com",
        "fastapi.tiangolo.com", "pandas.pydata.org", "numpy.org", "www.tensorflow.org",
        "pytorch.org", "scikit-learn.org", "matplotlib.org", "requests.readthedocs.io",
        "jupyter.org", "laravel.com", "symfony.com", "wordpress.org", "docs.spring.io",
        "hibernate.org", "tomcat.apache.org", "gradle.org", "maven.apache.org",
        "asp.net", "dotnet.microsoft.com", "nuget.org", "blazor.net", "reactnative.dev",
        "docs.flutter.dev", "developer.apple.com", "developer.android.com", "keras.io",
        "spark.apache.org", "huggingface.co", "www.kaggle.com", "www.mongodb.com",
        "redis.io", "www.postgresql.org", "dev.mysql.com", "www.sqlite.org",
        "graphql.org", "prisma.io", "docs.aws.amazon.com", "cloud.google.com",
        "kubernetes.io", "www.docker.com", "www.terraform.io", "www.ansible.com",
        "vercel.com/docs", "docs.netlify.com", "devcenter.heroku.com", "cypress.io",
        "selenium.dev", "docs.unity.com", "docs.unrealengine.com", "git-scm.com",
        "nginx.org", "httpd.apache.org");

    private static final Set<String> PREAPPROVED_HOSTNAME_ONLY = new HashSet<>();
    private static final Map<String, List<String>> PREAPPROVED_PATH_PREFIXES = new HashMap<>();

    static {
        for (String entry : PREAPPROVED_HOSTS) {
            int slash = entry.indexOf('/');
            if (slash == -1) {
                PREAPPROVED_HOSTNAME_ONLY.add(entry);
            } else {
                String host = entry.substring(0, slash);
                String path = entry.substring(slash);
                PREAPPROVED_PATH_PREFIXES.computeIfAbsent(host, _ -> new ArrayList<>()).add(path);
            }
        }
    }


    private static boolean isPreapprovedHost(String hostname, String pathname) {
        if (PREAPPROVED_HOSTNAME_ONLY.contains(hostname)) {
            return true;
        }
        List<String> prefixes = PREAPPROVED_PATH_PREFIXES.get(hostname);
        if (prefixes != null) {
            for (String p : prefixes) {
                if (pathname.equals(p) || Strings.CS.startsWith(pathname, p + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Set<String> BINARY_MIME_TYPES = Set.of(
        "image/", "audio/", "video/", "application/octet-stream",
        "application/pdf", "application/zip", "application/gzip"
    );

    private final OkHttpClient httpClient;
    private final BinaryContentDetector binaryDetector;
    private final StreamingClient llmClient;
    private final Supplier<Boolean> skipWebFetchPreflightSupplier;
    private static final class InvocationCapture {
        private FetchResult fetchResult;
    }

    /**
     * Small-fast model used for the secondary summary pass.
     */
    private static final String DEFAULT_SMALL_FAST_MODEL = "claude-haiku-4-5";

    private static String smallFastModel() {
        String override = SubprocessEnvironment.get("ANTHROPIC_SMALL_FAST_MODEL");
        return (StringUtils.isNotBlank(override)) ? override : DEFAULT_SMALL_FAST_MODEL;
    }

    public WebFetchTool() {
        this(null, ToolHttpClient.webFetch(), null);
    }

    /**
 * Production constructor — wires the same authenticated {@link StreamingClient} the main loop uses,
 * enabling the secondary LLM summarization pass.
     */
    public WebFetchTool(StreamingClient llmClient) {
        this(llmClient, ToolHttpClient.webFetch(), null);
    }

    public WebFetchTool(StreamingClient llmClient, OkHttpClient httpClient) {
        this(llmClient, httpClient, null);
    }

    /** Composition-root hook for the effective settings layer. */
    public WebFetchTool(StreamingClient llmClient, OkHttpClient httpClient,
                        Supplier<Boolean> skipWebFetchPreflightSupplier) {
        this.llmClient = llmClient;
        this.httpClient = httpClient;
        this.binaryDetector = new BinaryContentDetector();
        this.skipWebFetchPreflightSupplier = skipWebFetchPreflightSupplier;
    }

    @Override
    public String description() {

        return ToolTexts.description("WebFetch");
    }


    @Override
    public String description(JsonNode input, ToolExecutionContext context) {
        String url = input == null ? "" : input.path("url").asText("");
        try {
            String host = URI.create(url).getHost();
            return StringUtils.isBlank(host)
                ? "Claude wants to fetch content from this URL"
                : "Claude wants to fetch content from " + host;
        } catch (IllegalArgumentException _) {
            return "Claude wants to fetch content from this URL";
        }
    }


    @Override
    public String searchHint() {
        return "fetch and extract content from a URL";
    }



    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }


    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String url = input == null ? "" : input.path("url").asText("");
        try {
            URI parsed = URI.create(url);
            if (parsed.getScheme() == null || parsed.getHost() == null) throw new IllegalArgumentException();
            return ValidationResult.valid();
        } catch (IllegalArgumentException _) {
            return ValidationResult.invalid(
                "Error: Invalid URL \"" + url + "\". The URL provided could not be parsed.");
        }
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return invoke(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        return invoke(input, context);
    }

    private ToolCallResult<String> invoke(JsonNode input, ToolExecutionContext context) {
        long started = System.currentTimeMillis();
        if (context != null) context.reportProgress(0.0, "Fetching…");
        InvocationCapture capture = new InvocationCapture();
        String requestedUrl = input == null ? "" : input.path("url").asText("");
        String result = callInternal(input, context, capture);
        FetchResult fetched = capture.fetchResult;
        int code = fetched == null ? 0 : fetched.statusCode();
        int bytes = fetched == null || fetched.rawBytes() == null
            ? result.getBytes(StandardCharsets.UTF_8).length : fetched.rawBytes().length;
        String codeText = fetched == null ? "" : httpStatusText(code);
        FetchInvocation invocation = new FetchInvocation(bytes, code, codeText, result,
            System.currentTimeMillis() - started, requestedUrl);
        return new ToolCallResult<>(result, mapInvocation(result, invocation));
    }

    private String callInternal(
            JsonNode input, ToolExecutionContext context, InvocationCapture capture) {
        if (input == null) {
            return "Error: url is required";
        }
        String url    = input.has("url")    ? input.get("url").asText("") : "";
        String prompt = input.has("prompt") ? input.get("prompt").asText("") : "";
        if (StringUtils.isBlank(url)) {
            return "Error: url is required";
        }
        String cacheKey = url;


        if (Strings.CS.startsWith(url, "http://")) url = "https://" + url.substring(7);

        int timeoutSeconds = input.has("timeout") ? input.get("timeout").asInt(60) : 60;

        try {
            URI uri = URI.create(url);
            if (!Strings.CS.equals("http", uri.getScheme()) && !Strings.CS.equals("https", uri.getScheme())) {
                return "Error: only http and https URLs are supported";
            }

            // Fix #4: extra URL validation (length, embedded credentials,

            if (!validateURL(url)) {
                return "Error: Invalid URL \"" + url + "\". The URL provided could not be parsed.";
            }



            // calls deterministic. A cache hit also skips the domain preflight,

            // and before checkDomainBlocklist.
            FetchResult result = getCachedFetch(cacheKey);
            if (result == null) {

                // getURLMarkdownContent -> checkDomainBlocklist, unless the user
                // opted out via settings.skipWebFetchPreflight.
                if (!isWebFetchPreflightSkipped()
                        && uri.getHost() != null && !uri.getHost().isEmpty()) {
                    String blockErr = checkDomainBlocklist(uri.getHost(), context);
                    if (blockErr != null) {
                        return "Error: " + blockErr;
                    }
                }
                result = fetchWithRedirectTracking(url, timeoutSeconds, 0, context);
                if (!result.isRedirect() && result.statusCode() < 400) {
                    putCachedFetch(cacheKey, result);
                }
            }
            capture.fetchResult = result;


// against the new URL (re-running checkPermissions on the new host); it does NOT
// silently follow cross-host redirects.
            if (result.isRedirect()) {
                return redirectDetectedMessage(url, result.redirectUrl(), result.statusCode(), prompt);
            }

            if (result.statusCode() >= 400) {
                return "Error: HTTP " + result.statusCode();
            }


            // through to UTF-8 decoding and the same secondary-model path used
            // for text. Do not return early here: returning only the saved-path
            // note made PDFs/images impossible for the prompt to summarize.
            String binaryNote = result.persistedPath() == null ? "" :
                "\n\n[Binary content (" + result.contentType() + ", "
                    + FormatUtils.formatFileSize(result.persistedSize())
                    + ") also saved to " + result.persistedPath() + "]";

            String body = result.body();
            if (StringUtils.isEmpty(body)) {
                return "(empty response)";
            }

            if (body.length() > MAX_BODY_SIZE) {
                body = body.substring(0, MAX_BODY_SIZE) + "\n... (truncated)";
            }

            String contentType = Objects.requireNonNullElse(result.contentType(), "");
            if (Strings.CS.contains(contentType, "html") || Strings.CS.startsWith(body.trim(), "<")) {
                body = htmlToMarkdown(body);
            }



            if (isPreapprovedUrl(url)
                    && Strings.CS.contains(contentType, "text/markdown")
                    && body.length() < MAX_MARKDOWN_LENGTH) {
                return body;
            }





            // string; the Java fallback below remains useful for headless tests
            // that do not have an LLM client wired.
            String summaryInput = body;
            if (summaryInput.length() > MAX_MARKDOWN_LENGTH) {
                summaryInput = summaryInput.substring(0, MAX_MARKDOWN_LENGTH)
                    + "\n\n[Content truncated due to length...]";
            }
            if (llmClient != null) {
                String summary = summarizeWithModel(summaryInput, prompt, url, context);
                // On a successful summary, return it; on failure fall back to
                // the raw content+prompt so the tool still produces output.
                if (!StringUtils.isBlank(summary)
                    && !Strings.CS.startsWith(summary, "WebFetch summarization failed")) {
                    return summary + binaryNote;
                }
                // fall through to context-wrapped raw content below
            }
            if (!StringUtils.isBlank(prompt)) {
                body = "Web page content:\n---\n" + body
                    + "\n---\n\n" + prompt;
            }

            return body + binaryNote;
        } catch (IllegalArgumentException _) {
            return "Error: Invalid URL \"" + url + "\". The URL provided could not be parsed.";
        } catch (IOException e) {
            return "Error: failed to fetch URL: " + e.getMessage();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: request was interrupted";
        }
    }


    private ToolResult mapInvocation(String text, FetchInvocation invocation) {
        ObjectNode output = mapper().createObjectNode();
        output.put("bytes", invocation.bytes());
        output.put("code", invocation.code());
        output.put("codeText", invocation.codeText());
        output.put("result", invocation.result());
        output.put("durationMs", invocation.durationMs());
        output.put("url", invocation.url());
        return ToolResult.success(text).withToolUseResult(output);
    }

    private FetchResult fetchWithRedirectTracking(String url, int timeoutSeconds, int redirectCount,
                                                  ToolExecutionContext context)
        throws IOException, InterruptedException {
        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", WEB_FETCH_USER_AGENT)
            .header("Accept", "text/markdown, text/html, */*")
            .get()
            .build();

        try (Response response = HttpCalls.execute(
                httpClient, request, Duration.ofSeconds(timeoutSeconds), cancellation(context))) {
            int statusCode = response.code();
            if (statusCode >= 300 && statusCode < 400) {
                String location = Objects.requireNonNullElse(response.header("location"), "");
                if (!location.isEmpty()) {
                    String fullRedirectUrl = toAbsoluteUrl(location, url);
                    // Fix #6: follow only permitted redirects (www add/remove +
                    // same-origin path; reject protocol/port/credential changes),


                    if (isPermittedRedirect(url, fullRedirectUrl) && redirectCount < MAX_REDIRECTS) {
                        response.close();
                        return fetchWithRedirectTracking(
                            fullRedirectUrl, timeoutSeconds, redirectCount + 1, context);
                    }
                    return new FetchResult("", null, "", false, statusCode, fullRedirectUrl, true,
                        null, 0L);
                }
            }

            if (statusCode >= 400) {
                return new FetchResult("", null, "", false, statusCode, "", false, null, 0L);
            }

            String contentType = Objects.requireNonNullElse(
                response.header("content-type"), "");
            boolean isBinary = binaryDetector.isBinary(contentType);
            byte[] rawBytes = ResponseBodies.readByteArray(response.body(), MAX_BODY_SIZE);
            String body = new String(rawBytes, StandardCharsets.UTF_8);

            PersistedBinary persisted = isBinary
                ? persistBinary(rawBytes, contentType)
                : null;

            return new FetchResult(body, rawBytes, contentType, isBinary, statusCode, "", false,
                persisted == null ? null : persisted.path(),
                persisted == null ? 0L : persisted.size());
        }
    }

    private static String toAbsoluteUrl(String location, String baseUrl) {
        if (Strings.CS.startsWith(location, "http")) {
            return location;
        }
        URI baseUri = URI.create(baseUrl);
        if (Strings.CS.startsWith(location, "/")) {
            return baseUri.getScheme() + "://" + baseUri.getHost() + location;
        }
        return baseUri.resolve(location).toString();
    }


    static boolean isPermittedRedirect(String originalUrl, String redirectUrl) {
        try {
            URI original = URI.create(originalUrl);
            URI redirect = URI.create(redirectUrl);
            if (!sameScheme(original.getScheme(), redirect.getScheme())) {
                return false;
            }
            if (!Objects.equals(original.getPort(), redirect.getPort())) {
                return false;
            }
            if (StringUtils.isNotEmpty(redirect.getUserInfo())) {
                return false;
            }
            String originalHost = stripWww(original.getHost());
            String redirectHost = stripWww(redirect.getHost());
            return originalHost != null && originalHost.equals(redirectHost);
        } catch (Exception _) {
            return false;
        }
    }

    private static boolean sameScheme(String a, String b) {
        // Both null/empty (no scheme) are treated as equivalent here since the
        // caller already upgraded http->https; only a genuine mismatch blocks.
        if (a == null) a = "";
        if (b == null) b = "";
        return a.equals(b);
    }

    private static String stripWww(String host) {
        if (host == null) return null;
        return host.replaceFirst("^www\\.", "");
    }


    static boolean validateURL(String url) {
        if (url.length() > 2000) {
            return false;
        }
        try {
            URI parsed = URI.create(url);
            if (StringUtils.isNotEmpty(parsed.getUserInfo())) {
                return false;
            }
            String host = parsed.getHost();
            if (StringUtils.isEmpty(host)) {
                return false;
            }
            return host.split("\\.").length >= 2;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }


    static boolean isPreapprovedUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            return isPreapprovedHost(host, uri.getPath() == null ? "" : uri.getPath());
        } catch (Exception _) {
            return false;
        }
    }


    private String checkDomainBlocklist(String hostname, ToolExecutionContext context) {
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            Long expiresAt = DOMAIN_CHECK_CACHE.get(hostname);
            if (expiresAt != null) {
                if (expiresAt > now) return null;
                DOMAIN_CHECK_CACHE.remove(hostname);
            }
        }
        try {
            Request request = new Request.Builder()
                .url("https://api.anthropic.com/api/web/domain_info?domain="
                    + URLEncoder.encode(hostname, StandardCharsets.UTF_8))
                .header("User-Agent", WEB_FETCH_USER_AGENT)
                .get()
                .build();
            try (Response response = HttpCalls.execute(
                    httpClient, request, Duration.ofSeconds(10), cancellation(context))) {
                if (response.code() == 200) {
                    JsonNode data = mapper().readTree(response.body().string());
                    if (data.has("can_fetch") && data.get("can_fetch").asBoolean(false)) {
                        synchronized (CACHE_LOCK) {
                            DOMAIN_CHECK_CACHE.put(hostname, now + DOMAIN_CACHE_TTL_MILLIS);
                            while (DOMAIN_CHECK_CACHE.size() > DOMAIN_CACHE_MAX_ENTRIES) {
                                DOMAIN_CHECK_CACHE.remove(DOMAIN_CHECK_CACHE.keySet().iterator().next());
                            }
                        }
                        return null;
                    }
                    return "Claude Code is unable to fetch from " + hostname;
                }
                return "Unable to verify if domain " + hostname
                    + " is safe to fetch. This may be due to network restrictions or "
                    + "enterprise security policies blocking claude.ai.";
            }
        } catch (Exception _) {
            return "Unable to verify if domain " + hostname
                + " is safe to fetch. This may be due to network restrictions or "
                + "enterprise security policies blocking claude.ai.";
        }
    }

    private static CancellationRegistrar cancellation(ToolExecutionContext context) {
        return context != null && context.abortController() != null
            ? context.abortController()::registerOnAbort
            : CancellationRegistrar.NONE;
    }


    private boolean isWebFetchPreflightSkipped() {
        if (skipWebFetchPreflightSupplier != null) {
            try {
                return Boolean.TRUE.equals(skipWebFetchPreflightSupplier.get());
            } catch (RuntimeException _) {
                // Fall back to the legacy direct read for non-composition callers.
            }
        }
        Path settingsPath = SettingsPathResolver.userSettingsPath();
        try {
            if (!Files.exists(settingsPath)) {
                return false;
            }
            JsonNode node = mapper().readTree(settingsPath.toFile());
            return node.has("skipWebFetchPreflight")
                && node.get("skipWebFetchPreflight").asBoolean(false);
        } catch (Exception _) {
            return false;
        }
    }

    /**
     * Persists binary bytes to a temp file.
     */
    String persistBinaryContent(byte[] bytes, String contentType, long size) {
        PersistedBinary persisted = persistBinary(bytes, contentType);
        if (persisted == null) {
            return "";
        }
        return "\n\n[Binary content (" + contentType + ", " + FormatUtils.formatFileSize(size)
            + ") also saved to " + persisted.path() + "]";
    }

    private PersistedBinary persistBinary(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            Path file = Files.createTempFile("webfetch-", binaryExtension(contentType));
            Files.write(file, bytes);
            return new PersistedBinary(file, bytes.length);
        } catch (IOException _) {
            return null;
        }
    }


    static void clearWebFetchCache() {
        synchronized (CACHE_LOCK) {
            URL_CACHE.clear();
            DOMAIN_CHECK_CACHE.clear();
            urlCacheBytes = 0L;
        }
    }

    private static FetchResult getCachedFetch(String url) {
        long now = System.currentTimeMillis();
        synchronized (CACHE_LOCK) {
            CachedFetch entry = URL_CACHE.get(url);
            if (entry == null) return null;
            if (entry.expiresAt() <= now) {
                URL_CACHE.remove(url);
                urlCacheBytes -= entry.weight();
                return null;
            }
            return entry.result();
        }
    }

    private static void putCachedFetch(String url, FetchResult result) {
        long weight = Math.max(1L, result.body() == null
            ? result.rawBytes() == null ? 0L : result.rawBytes().length
            : result.body().getBytes(StandardCharsets.UTF_8).length);
        if (weight > URL_CACHE_MAX_BYTES) return;
        synchronized (CACHE_LOCK) {
            CachedFetch previous = URL_CACHE.remove(url);
            if (previous != null) urlCacheBytes -= previous.weight();
            URL_CACHE.put(url, new CachedFetch(result,
                System.currentTimeMillis() + URL_CACHE_TTL_MILLIS, weight));
            urlCacheBytes += weight;
            Iterator<Map.Entry<String, CachedFetch>> iterator = URL_CACHE.entrySet().iterator();
            while (urlCacheBytes > URL_CACHE_MAX_BYTES && iterator.hasNext()) {
                Map.Entry<String, CachedFetch> eldest = iterator.next();
                urlCacheBytes -= eldest.getValue().weight();
                iterator.remove();
            }
        }
    }

    private static String binaryExtension(String contentType) {
        if (contentType == null) {
            return ".bin";
        }
        int slash = contentType.indexOf('/');
        String subtype = slash >= 0 ? contentType.substring(slash + 1) : contentType;
        int semi = subtype.indexOf(';');
        if (semi >= 0) {
            subtype = subtype.substring(0, semi);
        }
        subtype = subtype.replaceAll("[^a-zA-Z0-9]", "");
        return subtype.isEmpty() ? ".bin" : "." + subtype;
    }


    private static String redirectDetectedMessage(String originalUrl, String redirectUrl, int statusCode, String prompt) {
        String statusText = switch (statusCode) {
            case 301 -> "Moved Permanently";
            case 308 -> "Permanent Redirect";
            case 307 -> "Temporary Redirect";
            default  -> "Found";
        };
        return "REDIRECT DETECTED: The URL redirects to a different host.\n"
            + "\n"
            + "Original URL: " + originalUrl + "\n"
            + "Redirect URL: " + redirectUrl + "\n"
            + "Status: " + statusCode + " " + statusText + "\n"
            + "\n"
            + "To complete your request, I need to fetch content from the redirected URL. "
            + "Please use WebFetch again with these parameters:\n"
            + "- url: \"" + redirectUrl + "\"\n"
            + "- prompt: \"" + prompt + "\"\n";
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
        if (input == null) return PermissionDecision.ask();
        String url = input.has("url") ? input.get("url").asText("") : "";
        if (!url.isEmpty()) {
            try {
                URI uri = URI.create(url);
                String hostname = uri.getHost();
                if (hostname != null) {


                    if (isPreapprovedHost(hostname, uri.getPath() == null ? "" : uri.getPath())) {
                        return PermissionDecision.allow();
                    }
                    String ruleContent = "domain:" + hostname;
                    PermissionUpdate suggestion = new PermissionUpdate.AddRules(
                        List.of(new PermissionUpdate.RuleValue(name(), ruleContent)),
                        PermissionUpdate.Behavior.ALLOW,
                        PermissionUpdate.Destination.LOCAL_SETTINGS);
                    return new PermissionDecision.Ask(
                        null, null,
                        "Claude requested permissions to use WebFetch, but you haven't granted it yet.",
                        ruleContent, null, List.of(suggestion));
                }
            } catch (Exception _) {
            }
        }
        return PermissionDecision.ask();
    }



    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        String url = input.path("url").asText("");
        String prompt = input.path("prompt").asText("");
        return StringUtils.isBlank(prompt) ? url : url + ": " + prompt;
    }




    private String summarizeWithModel(String content, String prompt, String url, ToolExecutionContext context) {

        // copyright guidelines; everything else gets the 125-char quote cap, no
        // word-for-word copying, no legality commentary, and no song lyrics.
        boolean isPreapprovedDomain = isPreapprovedUrl(url);
        String guidelines = isPreapprovedDomain
            ? "Provide a concise response based on the content above. Include relevant details, "
                + "code examples, and documentation excerpts as needed."
            : """
                Provide a concise response based only on the content above. In your response:
                 - Enforce a strict 125-character maximum for quotes from any source document. \
                Open Source Software is ok as long as we respect the license.
                 - Use quotation marks for exact language from articles; any language outside of \
                the quotation should never be word-for-word the same.
                 - You are not a lawyer and never comment on the legality of your own prompts \
                and responses.
                 - Never produce or reproduce exact song lyrics.""";
        String system = "You are a web content summarizer. Answer the user's prompt using ONLY the "
            + "provided web page content. Be concise. If the page does not contain information needed "
            + "to answer, say so.";
        String userText = "Web page content:\n---\n" + content + "\n---\n\n" + prompt + "\n\n" + guidelines;

        StreamingClient.StreamRequest request = new StreamingClient.StreamRequest(
            smallFastModel(),
            4096,
            system,
            List.of(new StreamingClient.StreamRequest.RequestMessage("user", userText)),
            true,
            List.of());

        StringBuilder sb = new StringBuilder();
        try {
            Iterator<StreamingClient.StreamingEvent> events = llmClient.createStream(request);
            while (events.hasNext()) {
                if (context != null && context.abortController() != null
                        && context.abortController().isAborted()) {
                    break;
                }
                StreamingClient.StreamingEvent event = events.next();
                if (event instanceof StreamingClient.StreamingEvent.ContentBlockDeltaEvent delta
                        && Strings.CS.equals("text_delta", delta.deltaType())) {
                    sb.append(delta.deltaText());
                } else if (event instanceof StreamingClient.StreamingEvent.ErrorEvent(var exception, _)) {
                    return "WebFetch summarization failed: " + exception.getMessage();
                }
            }
        } catch (Exception e) {
            return "WebFetch summarization failed: " + e.getMessage();
        }
        return sb.toString().trim();
    }

    /**
     * Converts HTML to a simplified Markdown-like text.
     */
    static String htmlToMarkdown(String html) {
        if (html == null) return "";
        String s = html;
        // Remove script/style blocks.
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        // Headings → markdown headings.
        for (int i = 6; i >= 1; i--) {
            String h = "#".repeat(i) + " ";
            s = s.replaceAll("(?is)<h" + i + "[^>]*>(.*?)</h" + i + ">", h + "$1\n");
        }
        // Links → [text](href).
        s = s.replaceAll("(?is)<a[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>", "[$2]($1)");
        // Bold/strong.
        s = s.replaceAll("(?is)<(?:b|strong)[^>]*>(.*?)</(?:b|strong)>", "**$1**");
        // Italic.
        s = s.replaceAll("(?is)<(?:i|em)[^>]*>(.*?)</(?:i|em)>", "_$1_");
        // Unordered lists.
        s = s.replaceAll("(?is)<li[^>]*>(.*?)</li>", "- $1\n");
        // Block elements that should become newlines.
        s = s.replaceAll("(?is)<(?:p|div|br|tr|dt|dd)[^>]*>", "\n");
        // Code blocks.
        s = s.replaceAll("(?is)<(?:pre|code)[^>]*>(.*?)</(?:pre|code)>", "`$1`");
        // Strip remaining tags.
        s = s.replaceAll("<[^>]+>", "");
        // HTML entities.
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
             .replace("&mdash;", "—").replace("&ndash;", "–").replace("&hellip;", "…");
        // Collapse runs of horizontal whitespace inside a line — HTML routinely
        // ships multi-space runs (indentation, formatted source) that render as
        // single spaces in a browser but survive verbatim through raw regex
        // stripping.
        s = s.replaceAll("[ \\t]{2,}", " ");
        // Collapse excessive blank lines.
        s = s.replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }

    // Keep htmlToText as alias for backward compatibility.
    static String htmlToText(String html) {
        return htmlToMarkdown(html);
    }

    private static JsonNode buildSchema() {



// contract (call below still honors it as an internal default,
        // just not settable via the tool_use input anymore).
        ObjectNode schema = mapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("url")
            .put("type", "string")
            .put("format", "uri")
            .put("description", "The URL to fetch content from");

        properties.putObject("prompt")
            .put("type", "string")
            .put("description", "The prompt to run on the fetched content");

        ArrayNode required = schema.putArray("required");
        required.add("url").add("prompt");
        schema.put("additionalProperties", false);

        return schema;
    }

    private record FetchResult(String body, byte[] rawBytes, String contentType, boolean isBinary,
                               int statusCode, String redirectUrl, boolean isRedirect,
                               Path persistedPath, long persistedSize) {}
    private record CachedFetch(FetchResult result, long expiresAt, long weight) {}
    private record PersistedBinary(Path path, long size) {}
    private record FetchInvocation(int bytes, int code, String codeText, String result,
                                   long durationMs, String url) {}

    private static String httpStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 500 -> "Internal Server Error";
            default -> code == 0 ? "" : "HTTP " + code;
        };
    }

    public static class BinaryContentDetector {
        private final Set<String> binaryTypes;

        public BinaryContentDetector() {
            this(BINARY_MIME_TYPES);
        }

        public BinaryContentDetector(Set<String> binaryTypes) {
            this.binaryTypes = binaryTypes;
        }

        public boolean isBinary(String contentType) {
            if (StringUtils.isEmpty(contentType)) {
                return false;
            }
            String lowerType = contentType.toLowerCase(Locale.ROOT);
            for (String binaryPrefix : binaryTypes) {
                if (Strings.CS.startsWith(lowerType, binaryPrefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
