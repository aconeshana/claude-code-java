package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClaudeAgentSdkSessionTest {
    private static final String SOURCE = "11111111-1111-4111-8111-111111111111";

    @TempDir Path tempDir;

    @Test
    void sessionStoreReadMutateAndForkUseOneProjectKey() {
        MemoryStore store = new MemoryStore();
        String dir = tempDir.resolve("project").toString();
        String projectKey = SessionStoreProjectKey.fromDirectory(dir);
        store.data.put(new SessionStoreKey(projectKey, SOURCE), new ArrayList<>(List.of(
            entry("user", "u1", null, "hello"),
            entry("assistant", "a1", "u1", "answer"))));

        List<SessionMessage> messages = ClaudeAgentSdk.getSessionMessages(SOURCE,
            new GetSessionMessagesOptions(dir, null, null, false, store)).join();
        ClaudeAgentSdk.renameSession(SOURCE, " Name ",
            new SessionMutationOptions(dir, store)).join();
        ClaudeAgentSdk.tagSession(SOURCE, null,
            new SessionMutationOptions(dir, store)).join();
        ForkSessionResult fork = ClaudeAgentSdk.forkSession(SOURCE,
            new ForkSessionOptions(dir, store, null, null)).join();

        assertEquals(List.of("user", "assistant"), messages.stream().map(SessionMessage::type).toList());
        List<JsonNode> source = store.data.get(new SessionStoreKey(projectKey, SOURCE));
        assertEquals("Name", source.get(2).path("customTitle").asText());
        assertTrue(source.get(2).hasNonNull("uuid"));
        assertEquals("", source.get(3).path("tag").asText());
        assertNotEquals(SOURCE, fork.sessionId());
        assertTrue(store.data.containsKey(new SessionStoreKey(projectKey, fork.sessionId())));
    }

    @Test
    void exitReasonUsesOfficialWireValues() {
        assertEquals("prompt_input_exit", ExitReason.PROMPT_INPUT_EXIT.wireValue());
        assertEquals(ExitReason.BYPASS_PERMISSIONS_DISABLED,
            ExitReason.fromWire("bypass_permissions_disabled"));
    }

    @Test
    void storeInfoAndListingFilterThenPaginate() {
        MemoryStore store = new MemoryStore();
        String dir = tempDir.resolve("project").toString();
        String projectKey = SessionStoreProjectKey.fromDirectory(dir);
        String second = "22222222-2222-4222-8222-222222222222";
        store.data.put(new SessionStoreKey(projectKey, SOURCE), new ArrayList<>(List.of(
            entry("user", "u1", null, "first"), titled("last-prompt", "lastPrompt", "latest"))));
        store.data.put(new SessionStoreKey(projectKey, second), new ArrayList<>());
        store.sessions = List.of(new StoredSession(second, 20), new StoredSession(SOURCE, 10));

        SDKSessionInfo info = ClaudeAgentSdk.getSessionInfo(SOURCE,
            new GetSessionInfoOptions(dir, store)).join().orElseThrow();
        List<SDKSessionInfo> page = ClaudeAgentSdk.listSessions(new ListSessionsOptions(
            dir, 1, 0, false, false, store)).join();

        assertEquals("latest", info.summary());
        assertEquals(List.of(SOURCE), page.stream().map(SDKSessionInfo::sessionId).toList());
    }

    private static JsonNode entry(String type, String uuid, String parent, String content) {
        var node = JsonUtils.getMapper().createObjectNode();
        node.put("type", type).put("uuid", uuid).put("sessionId", SOURCE)
            .put("timestamp", "2025-01-01T00:00:00Z");
        if (parent == null) node.putNull("parentUuid"); else node.put("parentUuid", parent);
        node.set("message", JsonUtils.getMapper().createObjectNode()
            .put("role", type).put("content", content));
        return node;
    }

    private static JsonNode titled(String type, String field, String value) {
        return JsonUtils.getMapper().createObjectNode().put("type", type).put(field, value)
            .put("sessionId", SOURCE).put("timestamp", "2025-01-02T00:00:00Z");
    }

    private static final class MemoryStore implements SessionStore {
        private final Map<SessionStoreKey, List<JsonNode>> data = new LinkedHashMap<>();
        private List<StoredSession> sessions = List.of();

        @Override public List<JsonNode> load(SessionStoreKey key) {
            return List.copyOf(data.getOrDefault(key, List.of()));
        }

        @Override public void append(SessionStoreKey key, List<JsonNode> entries) {
            data.computeIfAbsent(key, _ -> new ArrayList<>()).addAll(entries);
        }

        @Override public List<StoredSession> listSessions(String projectKey) { return sessions; }
    }
}
