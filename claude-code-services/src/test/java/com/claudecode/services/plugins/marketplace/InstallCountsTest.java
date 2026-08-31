package com.claudecode.services.plugins.marketplace;

import com.sun.net.httpserver.HttpServer;
import com.claudecode.services.http.ServiceHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;


class InstallCountsTest {

    private static final Instant NOW = Instant.parse("2026-07-13T12:00:00Z");

    @TempDir
    Path tmp;

    private HttpServer server;
    private URI statsUrl;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> responseBody = new AtomicReference<>("{\"plugins\": []}");

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/stats/plugin-installs.json", exchange -> {
            hits.incrementAndGet();
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        statsUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
            + "/stats/plugin-installs.json");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private InstallCounts installCounts() {
        return new InstallCounts(new PluginDirectories(tmp), ServiceHttpClient.noRedirects(),
            Clock.fixed(NOW, ZoneOffset.UTC), statsUrl);
    }

    private Path cacheFile() {
        return tmp.resolve("install-counts-cache.json");
    }

/** Byte-format sample of the shared two-space-indented JSON cache. */
    private void writeOfficialCliCache(String fetchedAt) throws Exception {
        Files.writeString(cacheFile(), """
            {
              "version": 1,
              "fetchedAt": "%s",
              "counts": [
                {
                  "plugin": "frontend-design@claude-plugins-official",
                  "unique_installs": 617078
                },
                {
                  "plugin": "superpowers@claude-plugins-official",
                  "unique_installs": 532114
                }
              ]
            }""".formatted(fetchedAt));
    }

    // ── cache reads ──────────────────────────────────────────────────────────

    @Test
    void freshOfficialCliCache_isReadWithoutNetwork() throws Exception {
        writeOfficialCliCache("2026-07-13T00:00:00.000Z"); // 12h old
        Map<String, Long> counts = installCounts().get();
        assertEquals(617078L, counts.get("frontend-design@claude-plugins-official"));
        assertEquals(532114L, counts.get("superpowers@claude-plugins-official"));
        assertEquals(0, hits.get(), "fresh cache must not hit the network");
    }

    @Test
    void cacheJustInsideTtl_isStillUsed() throws Exception {
        writeOfficialCliCache("2026-07-12T13:00:00.000Z"); // 23h old
        assertEquals(2, installCounts().get().size());
        assertEquals(0, hits.get());
    }

    @Test
    void staleCache_refetchesAndRoundTripsRewrittenCache() throws Exception {
        writeOfficialCliCache("2026-07-12T11:00:00.000Z"); // 25h old → stale
        responseBody.set("""
            {"plugins": [{"plugin": "x@m", "unique_installs": 1500}]}""");
        Map<String, Long> counts = installCounts().get();
        assertEquals(Map.of("x@m", 1500L), counts);
        assertEquals(1, hits.get());
        // Round-trip closure: a second instance must read the rewritten cache
        // back verbatim without touching the network.
        status.set(500);
        assertEquals(Map.of("x@m", 1500L), installCounts().get());
        assertEquals(1, hits.get(), "rewritten cache must satisfy the second read");
    }

    @Test
    void rewrittenCache_matchesOfficialCliByteFormat() throws Exception {
        responseBody.set("""
            {"plugins": [{"plugin": "x@m", "unique_installs": 1500}]}""");
        installCounts().get();
        assertEquals("""
            {
              "version": 1,
              "fetchedAt": "2026-07-13T12:00:00.000Z",
              "counts": [
                {
                  "plugin": "x@m",
                  "unique_installs": 1500
                }
              ]
            }""", Files.readString(cacheFile()));
    }

    @Test
    void rewrittenCache_hasOwnerOnlyPermissions() throws Exception {
        installCounts().get();
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(cacheFile());
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms);
    }

    // ── cache invalidation ───────────────────────────────────────────────────

    @Test
    void wrongCacheVersion_triggersRefetch() throws Exception {
        Files.writeString(cacheFile(), """
            {"version": 2, "fetchedAt": "2026-07-13T11:00:00.000Z",
             "counts": [{"plugin": "a@m", "unique_installs": 1}]}""");
        responseBody.set("""
            {"plugins": [{"plugin": "b@m", "unique_installs": 2}]}""");
        assertEquals(Map.of("b@m", 2L), installCounts().get());
        assertEquals(1, hits.get());
    }

    @Test
    void malformedCacheJson_triggersRefetch() throws Exception {
        Files.writeString(cacheFile(), "not json{{");
        responseBody.set("""
            {"plugins": [{"plugin": "b@m", "unique_installs": 2}]}""");
        assertEquals(Map.of("b@m", 2L), installCounts().get());
        assertEquals(1, hits.get());
    }

    @Test
    void malformedCacheEntry_invalidatesWholeCache() throws Exception {
        Files.writeString(cacheFile(), """
            {"version": 1, "fetchedAt": "2026-07-13T11:00:00.000Z",
             "counts": [{"plugin": "a@m"}]}""");
        responseBody.set("""
            {"plugins": [{"plugin": "b@m", "unique_installs": 2}]}""");
        assertEquals(Map.of("b@m", 2L), installCounts().get());
        assertEquals(1, hits.get());
    }

    @Test
    void invalidFetchedAt_triggersRefetch() throws Exception {
        Files.writeString(cacheFile(), """
            {"version": 1, "fetchedAt": "not a date",
             "counts": [{"plugin": "a@m", "unique_installs": 1}]}""");
        responseBody.set("""
            {"plugins": [{"plugin": "b@m", "unique_installs": 2}]}""");
        assertEquals(Map.of("b@m", 2L), installCounts().get());
    }

    // ── graceful degradation ─────────────────────────────────────────────────

    @Test
    void noCacheAndHttpError_returnsNull() {
        status.set(500);
        assertNull(installCounts().get());
    }

    @Test
    void noCacheAndInvalidResponseShape_returnsNull() {
        responseBody.set("{\"nope\": true}");
        assertNull(installCounts().get());
    }

    @Test
    void noCacheAndUnreachableServer_returnsNull() {
        server.stop(0);
        assertNull(installCounts().get());
    }

    @Test
    void fetchFailure_doesNotWriteCacheFile() {
        status.set(500);
        installCounts().get();
        assertFalse(Files.exists(cacheFile()));
    }

    // ── formatInstallCount ───────────────────────────────────────────────────

    @Test
    void formatInstallCount_matchesTsFormatting() {
        assertEquals("0", InstallCounts.formatInstallCount(0));
        assertEquals("42", InstallCounts.formatInstallCount(42));
        assertEquals("999", InstallCounts.formatInstallCount(999));
        assertEquals("1K", InstallCounts.formatInstallCount(1000));
        assertEquals("1.2K", InstallCounts.formatInstallCount(1234));
        assertEquals("36.2K", InstallCounts.formatInstallCount(36200));
        assertEquals("617.1K", InstallCounts.formatInstallCount(617078));
        assertEquals("1000K", InstallCounts.formatInstallCount(999999));
        assertEquals("1M", InstallCounts.formatInstallCount(1_000_000));
        assertEquals("1.2M", InstallCounts.formatInstallCount(1_200_000));
    }
}
