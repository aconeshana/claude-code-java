package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code GET /api/file-suggestions} over loopback HTTP: the composer's
 * {@code @} completion surface.
 *
 * <p>The properties under test are the wire contract the webui menu depends
 * on: rows arrive as {@code {path, directory}}, a directory row keeps its
 * trailing slash (that is what makes descending work by plain typing), the
 * query reaches the port decoded (a path with a space must survive the query
 * string), and the route is authenticated like every other {@code /api}
 * surface.
 */
class GatewayFileSuggestionsInteropTest {

    private static final String TOKEN = "f".repeat(48);

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession>
                    activate(SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by these tests");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    private final OkHttpClient client = new OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .readTimeout(Duration.ofSeconds(30)).build();
    private GatewayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    @Timeout(20)
    void servesRowsAsPathAndDirectory() throws Exception {
        startServer(new GatewayFileSuggestionsPort() {
            @Override public List<FileCandidate> list(String query) {
                return List.of(new FileCandidate("src/", true),
                    new FileCandidate("README.md", false));
            }
        });

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/file-suggestions?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
            JsonNode files = JsonUtils.getMapper()
                .readTree(response.body().string()).path("files");
            assertThat(files).hasSize(2);
            assertThat(files.get(0).path("path").asText()).isEqualTo("src/");
            assertThat(files.get(0).path("directory").asBoolean()).isTrue();
            assertThat(files.get(1).path("path").asText()).isEqualTo("README.md");
            assertThat(files.get(1).path("directory").asBoolean()).isFalse();
        }
    }

    /**
     * The query carries a path the user typed, and a path may contain a space.
     * The value must arrive decoded — an undecoded {@code %20} would make the
     * endpoint search for a directory literally named {@code my%20file}.
     */
    @Test
    @Timeout(20)
    void decodesTheQueryBeforeAskingThePort() throws Exception {
        StringBuilder seen = new StringBuilder();
        startServer(new GatewayFileSuggestionsPort() {
            @Override public List<FileCandidate> list(String query) {
                seen.append(query);
                return List.of();
            }
        });

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/file-suggestions?q=src/my%20file&token=" + TOKEN)).build())
                .execute()) {
            assertThat(response.code()).isEqualTo(200);
        }
        assertThat(seen.toString()).isEqualTo("src/my file");
    }

    /** An absent query is the blank one (the workspace root listing). */
    @Test
    @Timeout(20)
    void treatsAnAbsentQueryAsBlank() throws Exception {
        StringBuilder seen = new StringBuilder("sentinel");
        startServer(new GatewayFileSuggestionsPort() {
            @Override public List<FileCandidate> list(String query) {
                seen.setLength(0);
                seen.append(query);
                return List.of();
            }
        });

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/file-suggestions?token=" + TOKEN)).build()).execute()) {
            assertThat(response.code()).isEqualTo(200);
        }
        assertThat(seen.toString()).isEmpty();
    }

    /** The route sits behind the same bearer check as every other /api face. */
    @Test
    @Timeout(20)
    void requiresTheLaunchToken() throws Exception {
        startServer(new GatewayFileSuggestionsPort() {});

        try (Response response = client.newCall(new Request.Builder()
                .url(url("/api/file-suggestions")).build()).execute()) {
            assertThat(response.code()).isEqualTo(401);
        }
    }

    private void startServer(GatewayFileSuggestionsPort files) throws IOException {
        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .headless(new GatewayHeadlessSessions() {})
            .fileSuggestions(files)
            .build();
        server.start();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }
}