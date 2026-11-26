package com.claudecode.app;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

/**
 * Runtime reflection probe suite.
 *
 * <p>Exercises the production Jackson {@code ObjectMapper} ({@code JsonUtils.getMapper()})
 * over the core on-disk and wire models (plugins, custom models, transcripts, web workers,
 * tasks, and caches). Types are loaded by name from the runtime classpath so
 * {@code claude-code-app} preserves its strict compile-time module boundary.
 *
 * <p>When run under GraalVM's {@code -agentlib:native-image-agent} via
 * {@code :claude-code-app:verifyNativeAgentMetadata}, every reflective call made by Jackson
 * — including newly introduced calls after a Jackson upgrade such as
 * {@code PluginScope.wire()} — is recorded and verified against the native-image
 * reachability metadata.
 */
class NativeReflectionProbeTest {

    private static ObjectMapper productionMapper() throws Exception {
        Class<?> jsonUtils = Class.forName("com.claudecode.core.serialization.JsonUtils");
        Method getMapper = jsonUtils.getMethod("getMapper");
        return (ObjectMapper) getMapper.invoke(null);
    }

    private static Object roundTrip(ObjectMapper mapper, String className, String json) throws Exception {
        Class<?> target = Class.forName(className);
        Object deserialized = mapper.readValue(json, target);
        assertNotNull(deserialized, () -> "Deserialized null for " + className);
        String serialized = mapper.writeValueAsString(deserialized);
        assertFalse(StringUtils.isBlank(serialized), () -> "Serialized blank JSON for " + className);
        return deserialized;
    }

    @Test
    void exercisesInstalledPluginsAndPluginScopeEnumDeserialization() throws Exception {
        ObjectMapper mapper = productionMapper();
        roundTrip(mapper, "com.claudecode.services.plugins.marketplace.InstalledPlugins", """
            {
              "version": 2,
              "plugins": {
                "demo@official": [
                  {
                    "scope": "user",
                    "installPath": "/tmp/demo",
                    "version": "1.0.0",
                    "installedAt": "2026-01-01T00:00:00Z",
                    "lastUpdated": "2026-01-01T00:00:00Z",
                    "gitCommitSha": "abc123"
                  },
                  {
                    "scope": "project",
                    "projectPath": "/tmp/proj",
                    "installPath": "/tmp/demo",
                    "version": "1.0.0"
                  },
                  {
                    "scope": "local",
                    "installPath": "/tmp/demo",
                    "version": "1.0.0"
                  },
                  {
                    "scope": "managed",
                    "installPath": "/tmp/demo",
                    "version": "1.0.0"
                  }
                ]
              }
            }
            """);
    }

    @Test
    void exercisesPluginManifestAndMarketplaceModels() throws Exception {
        ObjectMapper mapper = productionMapper();
        roundTrip(mapper, "com.claudecode.services.plugins.marketplace.KnownMarketplaces", """
            {
              "official": {
                "source": {"source": "github", "repo": "anthropics/claude-plugins-official"},
                "installLocation": "/tmp/market",
                "lastUpdated": "2026-01-01T00:00:00Z"
              }
            }
            """);

        roundTrip(mapper, "com.claudecode.services.plugins.marketplace.PluginManifest", """
            {
              "name": "probe-plugin",
              "version": "1.0.0",
              "description": "probe"
            }
            """);

        roundTrip(mapper, "com.claudecode.services.plugins.marketplace.MarketplaceManifest", """
            {
              "name": "probe-market",
              "owner": {"name": "owner"},
              "plugins": [
                {
                  "name": "demo",
                  "source": "./plugins/demo",
                  "description": "demo plugin"
                }
              ]
            }
            """);
    }

    @Test
    void exercisesCustomModelCatalogAndAllFourProtocols() throws Exception {
        ObjectMapper mapper = productionMapper();
        roundTrip(mapper, "com.claudecode.api.CustomModelJsonStore$CatalogDocument", """
            {
              "version": 1,
              "models": [
                {"modelName": "claude-sonnet", "protocol": "anthropic", "baseUrl": "https://a.example", "apiKey": "k1"},
                {"modelName": "gpt-4o", "protocol": "chat", "baseUrl": "https://b.example", "apiKey": "k2"},
                {"modelName": "o3", "protocol": "responses", "baseUrl": "https://c.example", "apiKey": "k3"},
                {"modelName": "gemini-2.5-pro", "protocol": "gemini", "baseUrl": "https://d.example", "apiKey": "k4"}
              ]
            }
            """);
    }

    @Test
    void exercisesTranscriptMessagesWebWorkerTasksAndCaches() throws Exception {
        ObjectMapper mapper = productionMapper();
        roundTrip(mapper, "com.claudecode.core.message.Message", """
            {
              "type": "user",
              "uuid": "u-1",
              "sessionId": "session-1",
              "timestamp": "2026-01-01T00:00:00Z",
              "cwd": "/tmp",
              "message": {"role": "user", "content": "hello"}
            }
            """);

        roundTrip(mapper, "com.claudecode.core.message.Message", """
            {
              "type": "assistant",
              "uuid": "a-1",
              "parentUuid": "u-1",
              "sessionId": "session-1",
              "timestamp": "2026-01-01T00:00:01Z",
              "message": {
                "model": "claude-sonnet",
                "role": "assistant",
                "content": [{"type": "text", "text": "hi"}],
                "stop_reason": "end_turn"
              }
            }
            """);

        roundTrip(mapper, "com.claudecode.runtime.webworker.WebWorkerRegistration", """
            {
              "pid": 12345,
              "url": "http://127.0.0.1:19999",
              "token": "tok",
              "cwd": "/tmp",
              "version": "0.3.8",
              "startedAt": 1000,
              "lastHeartbeatAt": 1000
            }
            """);

        roundTrip(mapper, "com.claudecode.tools.tasks.TaskState", """
            {
              "tasks": [
                {
                  "id": "t-1",
                  "subject": "subject",
                  "description": "desc",
                  "activeForm": "working",
                  "status": "in_progress",
                  "owner": "owner",
                  "blocks": [],
                  "blockedBy": [],
                  "metadata": {}
                }
              ],
              "nextId": 2
            }
            """);

        roundTrip(mapper, "com.claudecode.session.ProjectIndexSnapshot", """
            {
              "version": 1,
              "projects": {}
            }
            """);
    }
}
