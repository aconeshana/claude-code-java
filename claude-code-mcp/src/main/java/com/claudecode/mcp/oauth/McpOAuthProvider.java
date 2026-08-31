package com.claudecode.mcp.oauth;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claudecode.mcp.McpException;
import com.claudecode.mcp.McpServerConfig;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Front door for MCP OAuth.
 */
public final class McpOAuthProvider {

    private static final Logger LOG = LoggerFactory.getLogger(McpOAuthProvider.class);

    /** How long we wait for the user to complete the browser flow. */
    static final Duration CALLBACK_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Preemptive refresh window: refresh a token when it has less than this left.
     */
    static final long REFRESH_LEEWAY_MS = 60_000;

    private final SecureStorage storage;
    private final McpOAuthDiscovery discovery;
    private final DynamicClientRegistration dcr;
    private final OAuthTokenClient tokens;
    private final BrowserLauncher browser;
    /** Reused for pasted callback submissions across the OAuth provider lifetime. */
    private final OkHttpClient callbackClient;

    public McpOAuthProvider() {
        this(SecureStorageFactory.getInstance(),
            new McpOAuthDiscovery(),
            new DynamicClientRegistration(),
            new OAuthTokenClient(),
            McpOAuthProvider::defaultOpenBrowser);
    }

    McpOAuthProvider(
        SecureStorage storage,
        McpOAuthDiscovery discovery,
        DynamicClientRegistration dcr,
        OAuthTokenClient tokens,
        BrowserLauncher browser
    ) {
        this(storage, discovery, dcr, tokens, browser, OAuthHttpClient.shared());
    }

    McpOAuthProvider(
        SecureStorage storage,
        McpOAuthDiscovery discovery,
        DynamicClientRegistration dcr,
        OAuthTokenClient tokens,
        BrowserLauncher browser,
        OkHttpClient callbackClient
    ) {
        this.storage = storage;
        this.discovery = discovery;
        this.dcr = dcr;
        this.tokens = tokens;
        this.browser = browser;
        this.callbackClient = callbackClient;
    }

    /**
     * Returns a valid bearer token for the given remote server, or empty when
     * none is stored (or all refresh paths have failed). Never blocks on user
     * interaction — the caller is responsible for triggering
     * {@link #authenticate} on empty results.
     *
     * <p>Auto-refreshes when the token is within {@link #REFRESH_LEEWAY_MS}
     * of expiry and a refresh_token is present.
     */
    public Optional<String> tokenFor(McpServerConfig serverConfig) {
        if (!isRemote(serverConfig)) return Optional.empty();
        String key = ServerKey.forConfig(serverConfig);
        Optional<SecureStorageData.McpOAuthEntry> entry = storage.read()
            .flatMap(d -> Optional.ofNullable(d.mcpOAuth().get(key)));
        if (entry.isEmpty()) return Optional.empty();

        SecureStorageData.McpOAuthEntry e = entry.get();
        if (StringUtils.isBlank(e.accessToken())) return Optional.empty();
        if (e.expiresAt() == 0 || e.expiresAt() - System.currentTimeMillis() > REFRESH_LEEWAY_MS) {
            return Optional.of(e.accessToken());
        }
        // Preemptive refresh — only if a refresh_token is available.
        if (e.refreshToken() == null || e.tokenEndpoint() == null) {
            LOG.debug("[oauth] {}: token expiring but no refresh_token stored; returning as-is",
                serverConfig.name());
            return Optional.of(e.accessToken());
        }
        try {
            OAuthTokenClient.TokenResponse fresh = tokens.refresh(
                e.tokenEndpoint(), e.clientId(), e.clientSecret(), e.refreshToken());
            SecureStorageData.McpOAuthEntry replaced = new SecureStorageData.McpOAuthEntry(
                e.serverName(), e.serverUrl(), e.clientId(), e.clientSecret(),
                fresh.accessToken(),
                fresh.refreshToken() != null ? fresh.refreshToken() : e.refreshToken(),
                fresh.expiresAt(),
                e.tokenEndpoint(),
                fresh.scope() != null ? fresh.scope() : e.scope());
            persistToken(key, replaced);
            return Optional.of(fresh.accessToken());
        } catch (McpException ex) {
            LOG.warn("[oauth] Refresh failed for {}: {} — user must re-authenticate",
                serverConfig.name(), ex.getMessage());
            // Purge the stored token so the transport surfaces auth-required
            // without letting the stale one drift in cache.
            invalidateToken(key);
            return Optional.empty();
        }
    }

    /**
     * Interactive OAuth flow: discovery → DCR/config → open browser → wait
     * for callback → exchange code → persist tokens.
     *
     * @throws McpException if any step fails.
     */
    public AuthResult authenticate(McpServerConfig serverConfig) {
        try {
            return startAuthentication(serverConfig, true).completion().join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof McpException mcp) throw mcp;
            throw error;
        }
    }

    /** Starts the SDK-owned-browser variant and returns before authorization completes. */
    public PendingAuth startAuthentication(McpServerConfig serverConfig, boolean openBrowser) {
        if (!isRemote(serverConfig)) {
            throw new McpException("Cannot authenticate stdio server " + serverConfig.name());
        }
        String key = ServerKey.forConfig(serverConfig);
        OAuthMetadata metadata = discovery.discover(serverConfig.url(), null);
        if (metadata.authorizationEndpoint() == null || metadata.tokenEndpoint() == null) {
            throw new McpException("Discovered metadata for " + serverConfig.name()
                + " is missing authorization_endpoint or token_endpoint — cannot authenticate");
        }

        List<String> scopes = List.of();

        // Generate PKCE + state up front so the callback server can be seeded
        // with the same state we bake into the authorize URL — no rebind dance.
        PkcePair pkce = PkcePair.generate();
        String state = generateState();

        LoopbackCallbackServer callback = new LoopbackCallbackServer(state);
        try {
            String redirectUri = callback.redirectUri();

            // Resolve client — stored ▸ config ▸ DCR — now that we know the
            // redirect_uri (port).
            DynamicClientRegistration.DcrResult client = dcr.resolveClient(
                storage, key, serverConfig.name(), metadata, redirectUri, scopes,
                /* configClientId */ null, /* configClientSecret */ null)
                .orElseThrow(() -> new McpException(
                    "No OAuth client available for " + serverConfig.name()
                        + " — no stored credentials, no oauth.clientId in config, and the authorization server does not support Dynamic Client Registration"));

            String authorizeUrl = buildAuthorizeUrl(
                metadata.authorizationEndpoint(),
                client.clientId(), redirectUri, scopes, pkce.challenge(), state,
                serverConfig.url());

            if (openBrowser) {
                browser.open(authorizeUrl);
                LOG.info("[oauth] {}: opened browser at {}", serverConfig.name(), authorizeUrl);
            }
            CompletableFuture<AuthResult> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    String code = callback.awaitCode(CALLBACK_TIMEOUT);
                    OAuthTokenClient.TokenResponse resp = tokens.exchangeCode(
                        metadata.tokenEndpoint(), client.clientId(), client.clientSecret(),
                        code, pkce.verifier(), redirectUri, serverConfig.url());
                    SecureStorageData.McpOAuthEntry stored = new SecureStorageData.McpOAuthEntry(
                        serverConfig.name(), serverConfig.url(), client.clientId(),
                        client.clientSecret(), resp.accessToken(), resp.refreshToken(),
                        resp.expiresAt(), metadata.tokenEndpoint(), resp.scope());
                    persistToken(key, stored);
                    return new AuthResult(client.clientId(), resp.accessToken(),
                        resp.expiresAt(), authorizeUrl);
                } finally {
                    callback.close();
                }
            }, command -> Thread.ofVirtual().name("mcp-oauth-" + serverConfig.name()).start(command));
            return new PendingAuth(authorizeUrl, redirectUri, state, completion,
                callback, callbackClient);
        } catch (RuntimeException error) {
            callback.close();
            throw error;
        }
    }

    public static final class PendingAuth {
        private final String authUrl;
        private final String redirectUri;
        private final String state;
        private final CompletableFuture<AuthResult> completion;
        private final LoopbackCallbackServer callback;
        private final OkHttpClient callbackClient;

        PendingAuth(String authUrl, String redirectUri, String state,
                    CompletableFuture<AuthResult> completion,
                    LoopbackCallbackServer callback,
                    OkHttpClient callbackClient) {
            this.authUrl = authUrl;
            this.redirectUri = redirectUri;
            this.state = state;
            this.completion = completion;
            this.callback = callback;
            this.callbackClient = callbackClient;
        }
        public String authUrl() { return authUrl; }
        public String state() { return state; }
        public int callbackPort() { return callback.port(); }
        public CompletableFuture<AuthResult> completion() { return completion; }

        /** Submits a pasted redirect URL only to this flow's loopback endpoint. */
        public void submitCallbackUrl(String callbackUrl) {
            try {
                URI expected = URI.create(redirectUri);
                URI actual = URI.create(callbackUrl);
                if (!expected.getScheme().equalsIgnoreCase(actual.getScheme())
                        || !expected.getHost().equalsIgnoreCase(actual.getHost())
                        || expected.getPort() != actual.getPort()
                        || !expected.getPath().equals(actual.getPath())) {
                    throw new McpException("Invalid callback URL for active OAuth flow");
                }

                callbackClient.connectionPool().evictAll();
                Request request = new Request.Builder()
                    .url(actual.toString())
                    .header("Connection", "close")
                    .get()
                    .build();
                try (Response response = callbackClient.newCall(request).execute()) {
                    // The loopback server owns state/code validation; only the
                    // callback delivery status is useful to the client.
                    LOG.debug("Forwarded MCP OAuth callback to loopback endpoint: status={}",
                        response.code());
                }
            } catch (McpException error) {
                throw error;
            } catch (Exception error) {
                throw new McpException("OAuth callback submission failed: " + error.getMessage(), error);
            }
        }

        public void cancel() {
            callback.close();
            completion.cancel(true);
        }
    }

    /**
     * Assembles the authorize URL from pre-generated PKCE + state. Same
     * shape as {@link AuthorizeUrlBuilder#build} but takes the values in
     * (instead of generating fresh ones) so authorize URL and callback
     * server state stay in sync.
     */
    private static String buildAuthorizeUrl(
        String authorizationEndpoint,
        String clientId,
        String redirectUri,
        List<String> scopes,
        String codeChallenge,
        String state,
        String resource
    ) {
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code");
        params.put("client_id", clientId);
        params.put("code_challenge", codeChallenge);
        params.put("code_challenge_method", "S256");
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        if (scopes != null && !scopes.isEmpty()) {
            params.put("scope", String.join(" ", scopes));
        }
        if (StringUtils.isNotBlank(resource)) {
            params.put("resource", resource);
        }
        StringBuilder sb = new StringBuilder(authorizationEndpoint);
        sb.append(Strings.CS.contains(authorizationEndpoint, "?") ? '&' : '?');
        boolean first = true;
        for (var e : params.entrySet()) {
            if (!first) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(e.getValue(), UTF_8));
            first = false;
        }
        return sb.toString();
    }

    private static String generateState() {
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** Wipes stored token + client credentials for the server. */
    public void clearAuth(McpServerConfig serverConfig) {
        String key = ServerKey.forConfig(serverConfig);
        SecureStorageData data = storage.read().orElseGet(SecureStorageData::empty);
        data.mcpOAuth().remove(key);
        data.mcpOAuthClientConfig().remove(key);
        storage.update(data);
        LOG.info("[oauth] Cleared credentials for {}", serverConfig.name());
    }

    /** Whether the server has any stored token — used by the dialog to render the auth-state row. */
    public boolean hasStoredToken(McpServerConfig serverConfig) {
        String key = ServerKey.forConfig(serverConfig);
        return storage.read()
            .map(d -> d.mcpOAuth().get(key))
            .map(e -> StringUtils.isNotBlank(e.accessToken()))
            .orElse(false);
    }

    // ── internals ────────────────────────────────────────────────────────

    private void persistToken(String key, SecureStorageData.McpOAuthEntry entry) {
        SecureStorageData data = storage.read().orElseGet(SecureStorageData::empty);
        data.mcpOAuth().put(key, entry);
        storage.update(data);
    }

    private void invalidateToken(String key) {
        SecureStorageData data = storage.read().orElseGet(SecureStorageData::empty);
        if (data.mcpOAuth().remove(key) != null) {
            storage.update(data);
        }
    }

    static boolean isRemote(McpServerConfig config) {
        String type = config.transportType();
        return Strings.CS.equals("http", type) || Strings.CS.equals("sse", type);
    }

    private static void defaultOpenBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception e) {
            LOG.debug("java.awt.Desktop failed to open browser: {}", e.getMessage());
        }
        // Fallback: xdg-open (Linux) or `open` (macOS) — the Java Desktop
        // integration is fussy in headless / SSH contexts.
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String cmd = Strings.CS.contains(os, "mac") ? "open" : "xdg-open";
            new ProcessBuilder(cmd, url).redirectErrorStream(true).start();
        } catch (Exception _) {
            LOG.warn("Could not open browser for {} — user must open manually", url);
        }
    }

    /** Test seam so unit tests can capture URL instead of spawning a browser. */
    @FunctionalInterface
    interface BrowserLauncher {
        void open(String url);
    }

    /**
     * Result of a successful {@link #authenticate}. The dialog uses these
     * fields to render the "authenticated · expires in Nm" line.
     */
    public record AuthResult(
        String clientId,
        String accessToken,
        long   expiresAt,
        String authorizeUrl
    ) {}
}
