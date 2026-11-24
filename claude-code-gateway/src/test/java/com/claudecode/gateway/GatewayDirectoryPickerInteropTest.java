package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Interop tests for {@code GET /api/directories} and {@code POST /api/directories},
 * backing the vendored upstream {@code DirectoryBrowser} (browse + new folder).
 */
class GatewayDirectoryPickerInteropTest {

    private static final String TOKEN = "d".repeat(48);
    private static final MediaType JSON = MediaType.get("application/json");

    @TempDir
    Path tempRoot;

    private GatewayServer server;
    private final OkHttpClient client = new OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void startServer() throws IOException {
        SessionHostRegistry registry = new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override
                public CompletionStage<SessionHostSession> activate(SessionOpenRequest request) {
                    throw new UnsupportedOperationException("not used");
                }

                @Override
                public List<SessionHostInfo> list() {
                    return List.of();
                }
            });
        server = GatewayServer.builder(new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
            .build();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    @Test
    void listsSubdirectoriesWithHiddenFlagsAndBreadcrumbs() throws IOException {
        Files.createDirectory(tempRoot.resolve("beta"));
        Files.createDirectory(tempRoot.resolve("alpha"));
        Files.createDirectory(tempRoot.resolve(".secret"));
        Files.writeString(tempRoot.resolve("plain-file.txt"), "ignore files");

        Request req = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/directories?path="
                + java.net.URLEncoder.encode(tempRoot.toString(), java.nio.charset.StandardCharsets.UTF_8))
            .header("Authorization", "Bearer " + TOKEN)
            .get()
            .build();
        try (Response resp = client.newCall(req).execute()) {
            assertThat(resp.code()).isEqualTo(200);
            JsonNode body = JsonUtils.getMapper().readTree(resp.body().string());
            assertThat(body.path("path").asText()).isEqualTo(tempRoot.toAbsolutePath().normalize().toString());
            assertThat(body.path("home").asText()).isNotBlank();
            assertThat(body.path("truncated").asBoolean()).isFalse();
            assertThat(body.path("crumbs").isArray()).isTrue();
            assertThat(body.path("crumbs").size()).isGreaterThanOrEqualTo(2);

            JsonNode entries = body.path("entries");
            assertThat(entries).hasSize(3);
            assertThat(entries.get(0).path("name").asText()).isEqualTo(".secret");
            assertThat(entries.get(0).path("hidden").asBoolean()).isTrue();
            assertThat(entries.get(1).path("name").asText()).isEqualTo("alpha");
            assertThat(entries.get(1).path("hidden").asBoolean()).isFalse();
            assertThat(entries.get(2).path("name").asText()).isEqualTo("beta");
        }
    }

    @Test
    void createsNewProjectFolderAndRejectsDuplicateOrTraversal() throws IOException {
        String createBody = JsonUtils.getMapper().createObjectNode()
            .put("path", tempRoot.toString())
            .put("name", "my-new-project")
            .toString();

        Request createReq = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/directories")
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(createBody, JSON))
            .build();
        try (Response resp = client.newCall(createReq).execute()) {
            assertThat(resp.code()).isEqualTo(201);
            JsonNode body = JsonUtils.getMapper().readTree(resp.body().string());
            Path expected = tempRoot.resolve("my-new-project").toAbsolutePath().normalize();
            assertThat(body.path("path").asText()).isEqualTo(expected.toString());
            assertThat(Files.isDirectory(expected)).isTrue();
        }

        // Duplicate folder returns 409 Conflict.
        try (Response dup = client.newCall(createReq).execute()) {
            assertThat(dup.code()).isEqualTo(409);
        }

        // Path traversal name is rejected with 400.
        String badBody = JsonUtils.getMapper().createObjectNode()
            .put("path", tempRoot.toString())
            .put("name", "../escape")
            .toString();
        Request badReq = new Request.Builder()
            .url("http://127.0.0.1:" + server.port() + "/api/directories")
            .header("Authorization", "Bearer " + TOKEN)
            .post(RequestBody.create(badBody, JSON))
            .build();
        try (Response bad = client.newCall(badReq).execute()) {
            assertThat(bad.code()).isEqualTo(400);
        }
    }
}
