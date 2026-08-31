package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.text.FormatUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.http.HttpCalls;
import com.claudecode.services.http.ServiceHttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Plugin install-counts data layer: fetches per-plugin unique-install counts from the official
 * Claude plugins statistics repository and caches them on disk at (24h TTL).
 */
public final class InstallCounts {


    static final String INSTALL_COUNTS_URL =
        "https://raw.githubusercontent.com/anthropics/claude-plugins-official"
            + "/refs/heads/stats/stats/plugin-installs.json";

    private static final int CACHE_VERSION = 1;
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);
    private final Path cachePath;
    private final OkHttpClient http;
    private final Clock clock;
    private final URI url;

    public InstallCounts(PluginDirectories directories, OkHttpClient http) {
        this(directories, http, Clock.systemUTC(), URI.create(INSTALL_COUNTS_URL));
    }

    public static InstallCounts standard(PluginDirectories directories) {
        return new InstallCounts(directories, ServiceHttpClient.pluginStatistics());
    }

    /** Test seam: fixed clock + local stats endpoint. */
    InstallCounts(PluginDirectories directories, OkHttpClient http, Clock clock, URI url) {
        this.cachePath = directories.installCountsCacheFile();
        this.http = http;
        this.clock = clock;
        this.url = url;
    }

    private record CountEntry(String plugin, long uniqueInstalls) {}

    /**
     * Plugin install counts keyed by plugin ID ({@code name@marketplace}), in popularity order.
     */
    public Map<String, Long> get() {
        Map<String, Long> cached = loadCache();
        if (cached != null) {
            return cached;
        }
        try {
            List<CountEntry> counts = fetch();
            saveCache(counts);
            Map<String, Long> map = new LinkedHashMap<>();
            for (CountEntry entry : counts) {
                map.put(entry.plugin(), entry.uniqueInstalls());
            }
            return map;
        } catch (Exception _) {
            return null;
        }
    }


    public static String formatInstallCount(long count) {
        if (count < 1_000) {
            return String.valueOf(count);
        }
        if (count < 1_000_000) {
            return withSuffix(count / 1_000.0, "K");
        }
        return withSuffix(count / 1_000_000.0, "M");
    }

    private static String withSuffix(double value, String suffix) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return Strings.CS.endsWith(formatted, ".0")
            ? formatted.substring(0, formatted.length() - 2) + suffix
            : formatted + suffix;
    }

    // ── cache ─────────────────────────────────────────────────────────────────


    private Map<String, Long> loadCache() {
        String content;
        try {
            content = Files.readString(cachePath);
        } catch (NoSuchFileException _) {
            return null;
        } catch (IOException _) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = JsonUtils.getMapper().readTree(content);
        } catch (Exception _) {
            return null;
        }
        if (parsed == null || !parsed.isObject() || !parsed.has("version")
                || !parsed.has("fetchedAt") || !parsed.has("counts")) {
            return null;
        }
        JsonNode version = parsed.get("version");
        if (!version.isIntegralNumber() || version.longValue() != CACHE_VERSION) {
            return null;
        }
        JsonNode fetchedAtNode = parsed.get("fetchedAt");
        JsonNode counts = parsed.get("counts");
        if (!fetchedAtNode.isTextual() || !counts.isArray()) {
            return null;
        }
        Instant fetchedAt = parseInstant(fetchedAtNode.asText());
        if (fetchedAt == null) {
            return null;
        }
        Map<String, Long> map = new LinkedHashMap<>();
        for (JsonNode entry : counts) {
            if (!entry.isObject() || !entry.path("plugin").isTextual()
                    || !entry.path("unique_installs").isNumber()) {
                return null;
            }
            map.put(entry.get("plugin").asText(), entry.get("unique_installs").asLong());
        }
        if (Duration.between(fetchedAt, clock.instant()).compareTo(CACHE_TTL) > 0) {
            return null; // stale (>24h old)
        }
        return map;
    }


    private static Instant parseInstant(String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException _) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException _) {
                return null;
            }
        }
    }


    private void saveCache(List<CountEntry> counts) {
        try {
            FileUtils.atomicReplace(cachePath, tempPath -> {
                Files.writeString(tempPath, serializeCache(counts));
                FileUtils.trySetOwnerOnlyPermissions(tempPath);
            });
        } catch (Exception _) {

        }
    }

/** Emits the shared, two-space-indented JSON cache format. */
    private String serializeCache(List<CountEntry> counts) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": ").append(CACHE_VERSION)
            .append(",\n  \"fetchedAt\": ").append(JsonUtils.toJson(FormatUtils.formatInstantIso(clock.instant())))
            .append(",\n  \"counts\": [");
        for (int i = 0; i < counts.size(); i++) {
            CountEntry entry = counts.get(i);
            sb.append(i == 0 ? "\n" : ",\n")
                .append("    {\n      \"plugin\": ").append(JsonUtils.toJson(entry.plugin()))
                .append(",\n      \"unique_installs\": ").append(entry.uniqueInstalls())
                .append("\n    }");
        }
        sb.append(counts.isEmpty() ? "]" : "\n  ]").append("\n}");
        return sb.toString();
    }

    // ── fetch ─────────────────────────────────────────────────────────────────


    private List<CountEntry> fetch() throws IOException, InterruptedException {
        Request request = new Request.Builder()
            .url(url.toString())
            .get()
            .build();
        try (Response response = HttpCalls.execute(http, request, FETCH_TIMEOUT)) {
            if (response.code() < 200 || response.code() >= 300) {
                throw new IOException("Install counts request failed with status "
                    + response.code());
            }
            JsonNode root = JsonUtils.getMapper().readTree(response.body().string());
            JsonNode plugins = root == null ? null : root.get("plugins");
            if (plugins == null || !plugins.isArray()) {
                throw new IOException("Invalid response format from install counts API");
            }
            List<CountEntry> out = new ArrayList<>();
            for (JsonNode node : plugins) {
                if (node.isObject() && node.path("plugin").isTextual()
                        && node.path("unique_installs").isNumber()) {
                    out.add(new CountEntry(
                        node.get("plugin").asText(), node.get("unique_installs").asLong()));
                }
            }
            return out;
        }
    }
}
