package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Bounded transcript-match batching for custom SDK session stores.
{@code SessionStore} match persistence.</li></ul>
 */
final class SessionMirrorBuffer {
    private static final int MAX_BATCH_ENTRIES = 32;
    private static final int MAX_BATCH_BYTES = 64 * 1024;

    private final SessionStore store;
    private final boolean eager;
    private final Consumer<Exception> errorSink;
    private final Map<SessionStoreKey, Batch> batches = new LinkedHashMap<>();

    SessionMirrorBuffer(SessionStore store, boolean eager, Consumer<Exception> errorSink) {
        this.store = store;
        this.eager = eager;
        this.errorSink = errorSink;
    }

    synchronized void append(SessionStoreKey key, List<JsonNode> entries) {
        if (store == null || key == null || entries == null || entries.isEmpty()) return;
        Batch batch = batches.computeIfAbsent(key, _ -> new Batch());
        for (JsonNode entry : entries) {
            JsonNode copy = entry.deepCopy();
            batch.entries.add(copy);
            batch.bytes += copy.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
        }
        if (eager || batch.entries.size() >= MAX_BATCH_ENTRIES || batch.bytes >= MAX_BATCH_BYTES) {
            flush(key, batch);
        }
    }

    synchronized void flush() {
        new ArrayList<>(batches.entrySet()).forEach(entry -> flush(entry.getKey(), entry.getValue()));
    }

    private void flush(SessionStoreKey key, Batch batch) {
        if (batch.entries.isEmpty()) return;
        List<JsonNode> entries = List.copyOf(batch.entries);
        batch.entries.clear();
        batch.bytes = 0;
        if (batches.get(key) == batch) batches.remove(key);
        try {
            store.append(key, entries);
        } catch (Exception failure) {
            errorSink.accept(failure);
        }
    }

    private static final class Batch {
        private final List<JsonNode> entries = new ArrayList<>();
        private int bytes;
    }
}
