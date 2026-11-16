package com.claudecode.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * A subscriber that has gone away must cost the hub only that subscriber.
 *
 * <p>The fan-out runs on whichever thread published the frame. For a session
 * hosted by another process that thread is the Session Link's reader, so a sink
 * that throws would otherwise end the read loop and close the link — every later
 * forward to that TUI then fails with {@code session-link connection closed}
 * until the pool happens to rediscover the worker, and the TUI's frames stop
 * reaching the webui in the meantime.
 */
class MirrorHubSinkIsolationTest {

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

    @Test
    void aSinkThatThrowsDoesNotStarveTheOtherSinks() {
        MirrorHub hub = hub();
        List<String> delivered = new ArrayList<>();
        // Subscribed first, so it is fanned out to before the healthy one: an
        // unguarded loop would abandon the healthy sink on this frame.
        hub.subscribe(_ -> {
            throw new IllegalStateException("sse lane is gone");
        });
        hub.subscribe(frame -> delivered.add(frame.event()));

        hub.publishRemote("session-a", "turn.started", JsonUtils.getMapper().createObjectNode());

        assertThat(delivered).containsExactly("turn.started");
    }

    @Test
    void aSinkThatThrowsStillLeavesTheFrameJournaledForReplay() {
        MirrorHub hub = hub();
        hub.subscribe(_ -> {
            throw new IllegalStateException("sse lane is gone");
        });

        hub.publishRemote("session-a", "turn.started", JsonUtils.getMapper().createObjectNode());
        long tip = hub.latestId();

        // The reconnecting client resumes from the journal, so the frame the
        // broken lane dropped must still be there.
        assertThat(hub.replayAfter(tip - 1))
            .extracting(MirrorHub.MirrorFrame::event)
            .containsExactly("turn.started");
    }

    @Test
    void aSinkThatThrowsDoesNotStopLaterFramesFromReachingIt() {
        MirrorHub hub = hub();
        List<String> delivered = new ArrayList<>();
        // Throws once, then behaves: the hub must keep offering it frames.
        List<Integer> attempts = new ArrayList<>();
        hub.subscribe(frame -> {
            attempts.add(1);
            if (attempts.size() == 1) throw new IllegalStateException("transient");
            delivered.add(frame.event());
        });

        hub.publishRemote("session-a", "turn.started", JsonUtils.getMapper().createObjectNode());
        hub.publishRemote("session-a", "output.text", JsonUtils.getMapper().createObjectNode());

        assertThat(delivered).containsExactly("output.text");
    }
}
