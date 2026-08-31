package com.claudecode.services.plugins.marketplace;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketplaceHttpTest {

    @TempDir
    Path tempDir;

    @Test
    void urlDownloadUsesInjectedOkHttpHeadersAndTenSecondDeadline() {
        AtomicReference<String> userAgent = new AtomicReference<>();
        AtomicReference<String> customHeader = new AtomicReference<>();
        AtomicLong timeoutNanos = new AtomicLong();
        OkHttpClient client = new OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(chain -> {
                userAgent.set(chain.request().header("User-Agent"));
                customHeader.set(chain.request().header("X-Test"));
                timeoutNanos.set(chain.call().timeout().timeoutNanos());
                String manifest = "{\"name\":\"remote\",\"owner\":{\"name\":\"Tester\"},"
                    + "\"plugins\":[]}";
                return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("fixture")
                    .body(ResponseBody.create(manifest, MediaType.get("application/json")))
                    .build();
            })
            .build();
        PluginSettingsStore settings = new PluginSettingsStore(
            tempDir.resolve("user.json"), tempDir.resolve("project.json"),
            tempDir.resolve("local.json"), tempDir.resolve("policy.json"));
        MarketplaceManager manager = new MarketplaceManager(
            tempDir.resolve("plugins"), FakeGitExecutor.alwaysFailing(), client, settings);
        Path cache = tempDir.resolve("cache/marketplace.json");

        manager.cacheFromUrl("https://example.com/marketplace.json", cache,
            Map.of("User-Agent", "must-be-overridden", "X-Test", "configured"), _ -> {});

        assertEquals("Claude-Code-Plugin-Manager", userAgent.get());
        assertEquals("configured", customHeader.get());
        assertEquals(Duration.ofSeconds(10).toNanos(), timeoutNanos.get());
        assertEquals("remote", manager.readCachedMarketplace(cache).name());
    }
}
