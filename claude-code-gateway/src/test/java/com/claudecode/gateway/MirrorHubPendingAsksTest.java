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

/**
 * The parked-ask replay set a cursorless subscriber is seeded from: one
 * session's asks never reach another's stream, a resolution clears its entry,
 * and an ask whose host vanished mid-dialog cannot grow the set without bound.
 */
class MirrorHubPendingAsksTest {

    private static MirrorHub hub() {
        return new MirrorHub(new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(
                    SessionOpenRequest request) {
                throw new UnsupportedOperationException("not used by this test");
            }
            @Override public List<SessionHostInfo> list() {
                return List.of();
            }
        }));
    }

    private static ObjectNode ask(String requestId) {
        return JsonUtils.getMapper().createObjectNode().put("request_id", requestId);
    }

    @Test
    void pendingAsksAreNarrowedToTheRequestedSession() {
        MirrorHub hub = hub();
        hub.publishPermissionAsked("session-a", ask("a1"));
        hub.publishPermissionAsked("session-b", ask("b1"));

        assertThat(hub.pendingPermissionFrames("session-a"))
            .extracting(MirrorHub.MirrorFrame::data)
            .singleElement()
            .asString()
            .contains("\"request_id\":\"a1\"");
        assertThat(hub.pendingPermissionFrames("session-b"))
            .extracting(MirrorHub.MirrorFrame::data)
            .singleElement()
            .asString()
            .contains("\"request_id\":\"b1\"");
        assertThat(hub.pendingPermissionFrames("session-b"))
            .extracting(MirrorHub.MirrorFrame::data)
            .singleElement()
            .asString()
            .doesNotContain("a1");
    }

    @Test
    void noFilterStillSeesEverySessionsAsks() {
        MirrorHub hub = hub();
        hub.publishPermissionAsked("session-a", ask("a1"));
        hub.publishPermissionAsked("session-b", ask("b1"));

        assertThat(hub.pendingPermissionFrames(null)).hasSize(2);
    }

    @Test
    void aResolvedAskStopsBeingReplayed() {
        MirrorHub hub = hub();
        hub.publishPermissionAsked("session-a", ask("a1"));
        assertThat(hub.pendingPermissionFrames(null)).hasSize(1);

        hub.publishPermissionResolved("session-a", JsonUtils.getMapper().createObjectNode()
            .put("request_id", "a1").put("resolution", "allowed"));

        assertThat(hub.pendingPermissionFrames(null)).isEmpty();
    }

    @Test
    void aResolutionForAnUnknownIdLeavesTheParkedSetAlone() {
        MirrorHub hub = hub();
        hub.publishPermissionAsked("session-a", ask("a1"));

        hub.publishPermissionResolved("session-a", JsonUtils.getMapper().createObjectNode()
            .put("request_id", ""));

        assertThat(hub.pendingPermissionFrames(null)).hasSize(1);
    }

    @Test
    void unansweredAsksCannotGrowTheParkedSetWithoutBound() {
        MirrorHub hub = hub();
        // Every ask here is never resolved: the shape of a host that crashed
        // mid-dialog, where no interaction.resolved callback ever arrives.
        for (int i = 0; i < 200; i++) {
            hub.publishPermissionAsked("session-a", ask("ask-" + i));
        }

        List<MirrorHub.MirrorFrame> parked = hub.pendingPermissionFrames(null);
        assertThat(parked).hasSizeLessThanOrEqualTo(64);
        // Eviction drops the oldest, so the newest ask is always still replayable.
        assertThat(parked.getLast().data()).contains("ask-199");
    }

    @Test
    void anAskWithoutARequestIdIsPublishedButNotParked() {
        MirrorHub hub = hub();
        hub.publishPermissionAsked("session-a", JsonUtils.getMapper().createObjectNode());

        assertThat(hub.pendingPermissionFrames(null)).isEmpty();
        assertThat(hub.replayAfter(0)).hasSize(1);
    }
}
