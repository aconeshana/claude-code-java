package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.process.ProcessUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.plugins.runtime.PluginModelProviderSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supervises plugin-declared {@code modelProviders} loopback HTTP sidecars, discovers their
 * models, and exposes reverse-proxy targets for their optional embedded management UIs.
 *
 * <p>When {@code publishRegistry} is {@code true} (inside the standalone {@code claude web}
 * daemon), active sidecar ports and tokens are published to
 * {@code $CLAUDE_CONFIG_DIR/plugin-providers.json} (mode {@code 0600}) so independent TUI
 * processes can reuse already-running sidecars without spawning duplicates.
 */
@Explanation("Manages plugin model-provider sidecars with loopback port/token isolation and optional daemon sharing")
final class CliPluginProviderManager implements CliPluginProviderView, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CliPluginProviderManager.class);
    private static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(10);
    private static final long HEALTH_POLL_MS = 150L;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(5);
    private static final long MODELS_CACHE_TTL_MS = 3_000L;
    private static final int MAX_STDERR_LINES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build();

    private static final class ManagedProvider {
        final PluginModelProviderSpec spec;
        final int port;
        final String token;
        final Process process;
        final boolean external;
        final Deque<String> stderrTail = new ArrayDeque<>(MAX_STDERR_LINES);
        volatile String status = "starting";
        volatile String lastError = "";
        volatile List<CliCompositeModelCatalog.ProvidedModel> cachedModels = List.of();
        volatile long cachedAtMs = 0L;

        ManagedProvider(PluginModelProviderSpec spec, int port, String token, Process process, boolean external) {
            this.spec = spec;
            this.port = port;
            this.token = token;
            this.process = process;
            this.external = external;
        }

        String baseOrigin() {
            return "http://127.0.0.1:" + port;
        }

        void appendStderr(String line) {
            if (StringUtils.isBlank(line)) return;
            synchronized (stderrTail) {
                if (stderrTail.size() >= MAX_STDERR_LINES) {
                    stderrTail.removeFirst();
                }
                stderrTail.addLast(line.trim());
            }
        }

        String stderrSummary() {
            synchronized (stderrTail) {
                if (stderrTail.isEmpty()) return "";
                return String.join(" | ", stderrTail);
            }
        }
    }

    private final boolean publishRegistry;
    private final Path registryPath;
    private final Map<String, ManagedProvider> providers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    CliPluginProviderManager(boolean publishRegistry) {
        this(publishRegistry, ClaudePaths.currentClaudeHome().resolve("plugin-providers.json"));
    }

    CliPluginProviderManager(boolean publishRegistry, Path registryPath) {
        this.publishRegistry = publishRegistry;
        this.registryPath = Objects.requireNonNull(registryPath, "registryPath");
    }

    /** Reconciles running sidecars against the latest enabled plugin snapshot. */
    synchronized void sync(List<PluginModelProviderSpec> specs) {
        if (closed.get()) return;
        List<PluginModelProviderSpec> next = specs == null ? List.of() : List.copyOf(specs);
        stopStaleProviders(next);
        startMissingProviders(next);
        if (publishRegistry) {
            writePublishedRegistry();
        }
    }

    private void stopStaleProviders(List<PluginModelProviderSpec> next) {
        Map<String, PluginModelProviderSpec> nextByKey = new LinkedHashMap<>();
        for (PluginModelProviderSpec spec : next) {
            nextByKey.put(spec.key(), spec);
        }
        List<String> toRemove = new ArrayList<>();
        for (Map.Entry<String, ManagedProvider> entry : providers.entrySet()) {
            PluginModelProviderSpec updated = nextByKey.get(entry.getKey());
            if (updated == null || !updated.equals(entry.getValue().spec)) {
                toRemove.add(entry.getKey());
            }
        }
        for (String key : toRemove) {
            ManagedProvider removed = providers.remove(key);
            if (removed != null) {
                stopProvider(removed);
            }
        }
    }

    private void startMissingProviders(List<PluginModelProviderSpec> next) {
        Map<String, PublishedEntry> published = publishRegistry ? Map.of() : readPublishedRegistry();
        for (PluginModelProviderSpec spec : next) {
            ManagedProvider existing = providers.get(spec.key());
            if (existing != null && isHealthyOrStarting(existing)) {
                continue;
            }
            if (existing != null) {
                stopProvider(existing);
                providers.remove(spec.key());
            }
            ManagedProvider reused = tryReusePublished(spec, published.get(spec.key()));
            if (reused != null) {
                providers.put(spec.key(), reused);
                continue;
            }
            ManagedProvider started = spawnProvider(spec);
            if (started != null) {
                providers.put(spec.key(), started);
            }
        }
    }

    /** Clears the model list cache so the next {@link #listModels()} hits every running sidecar. */
    @Override
    public void invalidateModelsCache() {
        for (ManagedProvider mp : providers.values()) {
            mp.cachedAtMs = 0L;
        }
    }

    /** Lists all models currently offered by running plugin sidecars. */
    @Override
    public List<CliCompositeModelCatalog.ProvidedModel> listModels() {
        if (closed.get() || providers.isEmpty()) {
            return List.of();
        }
        List<CliCompositeModelCatalog.ProvidedModel> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ManagedProvider mp : providers.values()) {
            if (!Strings.CS.equals("running", mp.status)) {
                continue;
            }
            if (now - mp.cachedAtMs < MODELS_CACHE_TTL_MS && mp.cachedAtMs > 0L) {
                out.addAll(mp.cachedModels);
                continue;
            }
            List<CliCompositeModelCatalog.ProvidedModel> fetched = fetchModels(mp);
            mp.cachedModels = fetched;
            mp.cachedAtMs = now;
            out.addAll(fetched);
        }
        return List.copyOf(out);
    }

    /** Returns the reverse-proxy target for {@code pluginId}'s management UI, if declared. */
    @Override
    public Optional<UiTarget> uiTarget(String pluginId) {
        if (StringUtils.isBlank(pluginId)) {
            return Optional.empty();
        }
        for (ManagedProvider mp : providers.values()) {
            if (mp.spec.ui() == null) continue;
            if (pluginId.equals(mp.spec.pluginId()) || pluginId.equals(mp.spec.pluginName())) {
                return Optional.of(toUiTarget(mp));
            }
        }
        return Optional.empty();
    }

    /** Returns UI panel metadata for all enabled plugins that declare an embedded UI. */
    @Override
    public List<UiTarget> uiPanels() {
        List<UiTarget> out = new ArrayList<>();
        for (ManagedProvider mp : providers.values()) {
            if (mp.spec.ui() != null) {
                out.add(toUiTarget(mp));
            }
        }
        return List.copyOf(out);
    }

    private static UiTarget toUiTarget(ManagedProvider mp) {
        PluginModelProviderSpec.UiSpec ui = mp.spec.ui();
        return new UiTarget(
            mp.spec.pluginId(),
            mp.spec.providerId(),
            StringUtils.defaultIfBlank(ui.title(), mp.spec.pluginName()),
            ui.description(),
            mp.baseOrigin(),
            ui.path(),
            mp.token,
            mp.status,
            mp.lastError,
            ui.settingsTab());
    }

    private ManagedProvider spawnProvider(PluginModelProviderSpec spec) {
        int port;
        try {
            port = allocateLoopbackPort();
        } catch (IOException e) {
            log.warn("Failed to allocate loopback port for plugin provider {}: {}", spec.key(), e.getMessage());
            ManagedProvider failed = new ManagedProvider(spec, 0, "", null, false);
            failed.status = "error";
            failed.lastError = "Failed to allocate port: " + e.getMessage();
            return failed;
        }
        String token = randomToken();
        List<String> cmd = new ArrayList<>();
        cmd.add(spec.command());
        cmd.addAll(spec.args());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (spec.workDir() != null && Files.isDirectory(spec.workDir())) {
            pb.directory(spec.workDir().toFile());
        }
        Map<String, String> env = pb.environment();
        env.putAll(spec.env());
        env.put("POCO_PROVIDER_HOST", "127.0.0.1");
        env.put("POCO_PROVIDER_PORT", String.valueOf(port));
        env.put("POCO_PROVIDER_TOKEN", token);
        if (spec.dataDir() != null) {
            try {
                Files.createDirectories(spec.dataDir());
            } catch (IOException _) {
                // Best effort; sidecar may create it lazily.
            }
            env.put("POCO_PROVIDER_DATA_DIR", spec.dataDir().toAbsolutePath().toString());
        }
        String encodedPluginId = URLEncoder.encode(spec.pluginId(), StandardCharsets.UTF_8);
        env.put("POCO_UI_BASE_PATH", "/api/plugins/" + encodedPluginId + "/ui");
        pb.redirectErrorStream(false);

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            log.warn("Failed to start plugin model provider {} ({}): {}",
                spec.key(), spec.command(), e.getMessage());
            ManagedProvider failed = new ManagedProvider(spec, port, token, null, false);
            failed.status = "error";
            failed.lastError = "Failed to launch '" + spec.command() + "': " + e.getMessage();
            return failed;
        }

        ManagedProvider mp = new ManagedProvider(spec, port, token, process, false);
        drainStreamAsync(process.getInputStream(), "provider-stdout-" + spec.providerId(), null);
        drainStreamAsync(process.getErrorStream(), "provider-stderr-" + spec.providerId(), mp::appendStderr);

        // Perform health handshake asynchronously so gateway startup is never blocked by a slow sidecar.
        Thread.ofVirtual().name("provider-health-" + spec.providerId()).start(() -> waitForReady(mp));
        return mp;
    }

    private void waitForReady(ManagedProvider mp) {
        Process process = mp.process;
        if (process == null) return;
        long deadlineNanos = System.nanoTime() + HEALTH_TIMEOUT.toNanos();
        while (!closed.get()) {
            if (!process.isAlive()) {
                recordEarlyExit(mp, process);
                return;
            }
            if (probeHealth(mp.baseOrigin() + mp.spec.healthPath(), mp.token)) {
                mp.status = "running";
                mp.lastError = "";
                mp.cachedAtMs = 0L;
                log.info("Plugin model provider {} ready on 127.0.0.1:{}", mp.spec.key(), mp.port);
                if (publishRegistry) {
                    writePublishedRegistry();
                }
                return;
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0L) {
                break;
            }
            try {
                if (process.waitFor(Math.min(HEALTH_POLL_MS, remainingMs), TimeUnit.MILLISECONDS)) {
                    if (!closed.get()) {
                        recordEarlyExit(mp, process);
                    }
                    return;
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (!closed.get() && !Strings.CS.equals("running", mp.status)) {
            mp.status = "error";
            String err = mp.stderrSummary();
            mp.lastError = StringUtils.isNotBlank(err)
                ? "Health check timed out: " + err
                : "Health check timed out after " + HEALTH_TIMEOUT.toSeconds() + "s";
            log.warn("Plugin provider {} health check timed out: {}", mp.spec.key(), mp.lastError);
        }
    }

    private static void recordEarlyExit(ManagedProvider mp, Process process) {
        mp.status = "error";
        String err = mp.stderrSummary();
        mp.lastError = StringUtils.isNotBlank(err)
            ? "Exited with code " + process.exitValue() + ": " + err
            : "Exited with code " + process.exitValue();
        log.warn("Plugin provider {} exited during startup: {}", mp.spec.key(), mp.lastError);
    }

    private static boolean probeHealth(String url, String token) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(2))
                .GET();
            if (StringUtils.isNotBlank(token)) {
                req.header("Authorization", "Bearer " + token);
            }
            HttpResponse<Void> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.discarding());
            return res.statusCode() >= 200 && res.statusCode() < 300;
        } catch (Exception _) {
            return false;
        }
    }

    private List<CliCompositeModelCatalog.ProvidedModel> fetchModels(ManagedProvider mp) {
        if (!mp.external && (mp.process == null || !mp.process.isAlive())) {
            mp.status = "error";
            mp.lastError = "Sidecar process is not running";
            return List.of();
        }
        String url = mp.baseOrigin() + mp.spec.modelsPath();
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(HTTP_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
            if (StringUtils.isNotBlank(mp.token)) {
                req.header("Authorization", "Bearer " + mp.token);
            }
            HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                log.debug("Plugin provider {} /v1/models returned HTTP {}", mp.spec.key(), res.statusCode());
                return mp.cachedModels;
            }
            JsonNode root = JsonUtils.getMapper().readTree(res.body());
            JsonNode data = root.has("data") && root.get("data").isArray()
                ? root.get("data")
                : root.isArray() ? root : null;
            if (data == null) {
                return List.of();
            }
            String baseUrl = mp.baseOrigin() + "/v1";
            List<CliCompositeModelCatalog.ProvidedModel> out = new ArrayList<>();
            for (JsonNode item : data) {
                String rawId = item.path("id").asText("").trim();
                if (rawId.isEmpty()) continue;
                String modelName = applyPrefix(rawId, mp.spec);
                String protoRaw = item.path("protocol").asText(mp.spec.protocol()).trim().toLowerCase(Locale.ROOT);
                ModelApiProtocol protocol;
                try {
                    protocol = ModelApiProtocol.fromConfigValue(protoRaw);
                } catch (IllegalArgumentException _) {
                    protocol = ModelApiProtocol.OPENAI_CHAT;
                }
                Long contextWindow = null;
                if (item.hasNonNull("context_window") && item.get("context_window").isNumber()) {
                    long cw = item.get("context_window").asLong();
                    if (cw >= 40_000L) {
                        contextWindow = cw;
                    }
                }
                Boolean multimodal = item.has("multimodal") && item.get("multimodal").isBoolean()
                    ? item.get("multimodal").asBoolean() : null;
                try {
                    CustomModelConfig cfg = new CustomModelConfig(
                        modelName, protocol, baseUrl, mp.token, Map.of(), contextWindow, multimodal);
                    out.add(new CliCompositeModelCatalog.ProvidedModel(mp.spec.pluginId(), mp.spec.providerId(), cfg));
                } catch (IllegalArgumentException e) {
                    log.debug("Skipping invalid plugin model {} from {}: {}", modelName, mp.spec.key(), e.getMessage());
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            log.debug("Failed to fetch models from plugin provider {}: {}", mp.spec.key(), e.getMessage());
            return mp.cachedModels;
        }
    }

    private static String applyPrefix(String rawId, PluginModelProviderSpec spec) {
        String prefix = spec.modelPrefix();
        if (prefix == null) {
            // If model id already contains a namespace slash (e.g. "trae-cn/glm-5"), keep as-is.
            return Strings.CS.contains(rawId, "/") ? rawId : spec.providerId() + "/" + rawId;
        }
        if (prefix.isEmpty()) {
            return rawId;
        }
        return Strings.CS.startsWith(rawId, prefix) ? rawId : prefix + rawId;
    }

    private static boolean isHealthyOrStarting(ManagedProvider mp) {
        if (Strings.CS.equals("starting", mp.status)) return true;
        if (!Strings.CS.equals("running", mp.status)) return false;
        if (mp.external) {
            return probeHealth(mp.baseOrigin() + mp.spec.healthPath(), mp.token);
        }
        return mp.process != null && mp.process.isAlive();
    }

    private static void stopProvider(ManagedProvider mp) {
        if (mp.external || mp.process == null) return;
        try {
            mp.process.descendants().forEach(ProcessHandle::destroy);
            mp.process.destroy();
            if (!mp.process.waitFor(2, TimeUnit.SECONDS)) {
                mp.process.descendants().forEach(ProcessHandle::destroyForcibly);
                mp.process.destroyForcibly();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            mp.process.destroyForcibly();
        } catch (RuntimeException _) {
            mp.process.destroyForcibly();
        }
    }

    private static int allocateLoopbackPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String randomToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return "poco-prov-" + HEX.formatHex(buf);
    }

    private static void drainStreamAsync(
            InputStream stream, String threadName, Consumer<String> onLine) {
        Thread.ofVirtual().name(threadName).start(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (onLine != null) onLine.accept(line);
                }
            } catch (IOException _) {
                // Stream closed on process exit.
            }
        });
    }

    // ── Daemon sharing registry ($CLAUDE_CONFIG_DIR/plugin-providers.json) ──

    private record PublishedEntry(String key, long ownerPid, int port, String token) {}

    private ManagedProvider tryReusePublished(PluginModelProviderSpec spec, PublishedEntry entry) {
        if (entry == null || entry.port() <= 0 || StringUtils.isBlank(entry.token())) {
            return null;
        }
        if (entry.ownerPid() > 0 && ProcessUtils.isProcessRunning(entry.ownerPid())) {
            String url = "http://127.0.0.1:" + entry.port() + spec.healthPath();
            if (probeHealth(url, entry.token())) {
                ManagedProvider reused = new ManagedProvider(spec, entry.port(), entry.token(), null, true);
                reused.status = "running";
                log.debug("Reusing daemon-hosted plugin provider {} on 127.0.0.1:{}", spec.key(), entry.port());
                return reused;
            }
        }
        return null;
    }

    private Map<String, PublishedEntry> readPublishedRegistry() {
        if (!Files.isRegularFile(registryPath)) {
            return Map.of();
        }
        try {
            JsonNode root = JsonUtils.readJson(registryPath);
            JsonNode arr = root == null ? null : root.get("providers");
            if (arr == null || !arr.isArray()) {
                return Map.of();
            }
            Map<String, PublishedEntry> out = new LinkedHashMap<>();
            for (JsonNode n : arr) {
                String key = n.path("key").asText("");
                long pid = n.path("owner_pid").asLong(0);
                int port = n.path("port").asInt(0);
                String token = n.path("token").asText("");
                if (!key.isEmpty() && port > 0 && !token.isEmpty()) {
                    out.put(key, new PublishedEntry(key, pid, port, token));
                }
            }
            return out;
        } catch (IOException | RuntimeException _) {
            return Map.of();
        }
    }

    private synchronized void writePublishedRegistry() {
        try {
            Path parent = registryPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            ObjectNode root = JsonUtils.getMapper().createObjectNode();
            ArrayNode arr = root.putArray("providers");
            long selfPid = ProcessHandle.current().pid();
            for (ManagedProvider mp : providers.values()) {
                if (mp.external || mp.port <= 0 || StringUtils.isBlank(mp.token)) continue;
                if (!Strings.CS.equals("running", mp.status)) continue;
                ObjectNode item = arr.addObject();
                item.put("key", mp.spec.key());
                item.put("plugin_id", mp.spec.pluginId());
                item.put("provider_id", mp.spec.providerId());
                item.put("owner_pid", selfPid);
                item.put("port", mp.port);
                item.put("token", mp.token);
            }
            String json = JsonUtils.getMapper().writeValueAsString(root);
            FileUtils.atomicReplace(registryPath, tmp -> {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                try {
                    Files.setPosixFilePermissions(tmp, Set.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException _) {
                    // Non-POSIX filesystem (Windows).
                }
            });
        } catch (IOException e) {
            log.debug("Failed to write plugin-providers.json: {}", e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ManagedProvider mp : providers.values()) {
            stopProvider(mp);
        }
        providers.clear();
        if (publishRegistry) {
            try {
                Files.deleteIfExists(registryPath);
            } catch (IOException _) {
                // Best effort cleanup.
            }
        }
    }
}
