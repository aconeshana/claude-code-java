package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStoreMaterializerTest {
    @TempDir Path temp;

    @Test
    void materializesMainAndSafeSubagentAndRejectsEscapes() throws Exception {
        JsonNode row = JsonUtils.getMapper().readTree("{\"type\":\"user\"}");
        SessionStore store = new SessionStore() {
            @Override public List<JsonNode> load(SessionStoreKey key) {
                return List.of(row);
            }
            @Override public void append(SessionStoreKey key, List<JsonNode> entries) { }
            @Override public List<String> listSubkeys(SessionStoreKey key) {
                return List.of("subagents/agent-safe", "../escape", "/absolute");
            }
        };
        QueryOptions options = QueryOptions.builder().cwd(temp).resume("session-1")
            .sessionStore(store).build();

        Path config = SessionStoreMaterializer.prepare(options);

        Path project = config.resolve("projects").resolve(
            SessionStoreProjectKey.fromDirectory(temp.toString()));
        assertTrue(Files.isRegularFile(project.resolve("session-1.jsonl")));
        assertTrue(Files.isRegularFile(project.resolve(
            "session-1/subagents/agent-safe.jsonl")));
        assertFalse(Files.exists(config.resolve("escape.jsonl")));
        assertEquals(config.toString(), options.env.get("CLAUDE_CONFIG_DIR"));
    }

    @Test
    void continueRequiresSessionEnumeration() {
        SessionStore store = new SessionStore() {
            @Override public List<JsonNode> load(SessionStoreKey key) { return List.of(); }
            @Override public void append(SessionStoreKey key, List<JsonNode> entries) { }
        };
        QueryOptions options = QueryOptions.builder().cwd(temp)
            .continueConversation(true).sessionStore(store).build();
        assertThrows(UnsupportedOperationException.class,
            () -> SessionStoreMaterializer.prepare(options));
    }

    @Test
    void mirrorKeyPreservesSubagentIdentity() {
        SessionStore store = new SessionStore() {
            @Override public List<JsonNode> load(SessionStoreKey key) { return List.of(); }
            @Override public void append(SessionStoreKey key, List<JsonNode> entries) { }
        };
        QueryOptions options = QueryOptions.builder().cwd(temp).sessionStore(store).build();
        SessionStoreKey key = SessionStoreMaterializer.keyForFrame(
            "/cfg/projects/proj/session-1/subagents/agent-a.jsonl", options);
        assertEquals("session-1", key.sessionId());
        assertEquals("subagents/agent-a", key.subpath());
    }
}
