package com.claudecode.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionMirrorBufferTest {
    @Test
    void batchesByDefaultAndFlushesOnCompletion() throws Exception {
        RecordingStore store = new RecordingStore();
        SessionMirrorBuffer buffer = new SessionMirrorBuffer(store, false, _ -> { });
        SessionStoreKey key = new SessionStoreKey("project", "session");

        buffer.append(key, List.of(row(1)));
        buffer.append(key, List.of(row(2)));
        assertEquals(0, store.appends.size());

        buffer.flush();
        assertEquals(List.of(1, 2), store.appends.getFirst().stream()
            .map(value -> value.path("n").asInt()).toList());
    }

    @Test
    void eagerModeWritesEveryFrameImmediately() throws Exception {
        RecordingStore store = new RecordingStore();
        SessionMirrorBuffer buffer = new SessionMirrorBuffer(store, true, _ -> { });
        SessionStoreKey key = new SessionStoreKey("project", "session");

        buffer.append(key, List.of(row(1)));
        buffer.append(key, List.of(row(2)));

        assertEquals(2, store.appends.size());
    }

    private static JsonNode row(int value) throws Exception {
        return JsonUtils.getMapper().readTree("{\"n\":" + value + "}");
    }

    private static final class RecordingStore implements SessionStore {
        private final List<List<JsonNode>> appends = new ArrayList<>();
        @Override public List<JsonNode> load(SessionStoreKey key) { return List.of(); }
        @Override public void append(SessionStoreKey key, List<JsonNode> entries) {
            appends.add(List.copyOf(entries));
        }
    }
}
