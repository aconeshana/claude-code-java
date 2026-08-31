package com.claudecode.session;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionStorageAppendListenerTest {
    @TempDir Path temp;

    @Test
    void listenerReceivesTheExactSuccessfullyPersistedRow() {
        SessionStorage storage = new SessionStorage();
        AtomicReference<ObjectNode> mirrored = new AtomicReference<>();
        storage.setAppendListener((_, row) -> mirrored.set(row));
        ObjectNode metadata = JsonUtils.getMapper().createObjectNode();
        metadata.put("type", "custom-title").put("customTitle", "SDK title");

        storage.appendCustomEntry(temp.resolve("session.jsonl"), metadata);

        assertEquals(metadata, mirrored.get());
    }
}
