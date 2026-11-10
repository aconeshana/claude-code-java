package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class MirrorHubRemoteTest {

    private final SessionHostRegistry registry = new SessionHostRegistry(
        new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by this test");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        });

    @Test
    void remoteEventIsJournaledWithSessionIdAndUnchangedPayload() {
        MirrorHub hub = new MirrorHub(registry);
        ObjectNode payload = JsonUtils.getMapper().createObjectNode()
            .put("name", "Bash").put("result", "ok");

        hub.publishRemote("remote-1", "tool.completed", payload);

        List<MirrorHub.MirrorFrame> replay = hub.replayAfter(0);
        assertThat(replay).hasSize(1);
        MirrorHub.MirrorFrame frame = replay.getFirst();
        assertThat(frame.event()).isEqualTo("tool.completed");
        assertThat(frame.data()).contains("\"session_id\":\"remote-1\"");
        assertThat(frame.data()).contains("\"result\":\"ok\"");
        assertThat(payload.has("session_id")).isFalse();
    }
}
