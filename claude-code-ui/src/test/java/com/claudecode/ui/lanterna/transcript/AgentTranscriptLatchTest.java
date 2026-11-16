package com.claudecode.ui.lanterna.transcript;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTranscriptLatchTest {

    @Test
    void anUnviewedAgentNeedsARead() {
        assertTrue(new AgentTranscriptLatch().needsRead("agent-1"));
    }

    @Test
    void theMainTranscriptNeverNeedsARead() {
        // No viewed agent means the rows come from the in-memory message history.
        assertFalse(new AgentTranscriptLatch().needsRead(null));
    }

    @Test
    void repaintingTheSameAgentSkipsTheDisk() {
        AgentTranscriptLatch latch = new AgentTranscriptLatch();
        latch.markRead("agent-1");

        // This is the fix: a viewer repaints on every spinner tick, and re-parsing a large agent
        // JSONL each time is what made the main/sub switch feel slow.
        assertFalse(latch.needsRead("agent-1"));
    }

    @Test
    void switchingToAnotherAgentNeedsItsOwnRead() {
        AgentTranscriptLatch latch = new AgentTranscriptLatch();
        latch.markRead("agent-1");

        latch.viewChangedTo("agent-2");

        assertTrue(latch.needsRead("agent-2"));
    }

    @Test
    void returningToAnAgentAfterLeavingTheViewReReadsIt() {
        AgentTranscriptLatch latch = new AgentTranscriptLatch();
        latch.markRead("agent-1");
        latch.viewChangedTo(null);

        // Leaving the view drops the latch, matching the released client resetting diskLoaded on
        // exit: an agent that kept running while unwatched must be re-seeded, or the view would
        // render a stale transcript.
        latch.viewChangedTo("agent-1");

        assertTrue(latch.needsRead("agent-1"));
    }

    @Test
    void stayingOnTheSameViewKeepsTheLatch() {
        AgentTranscriptLatch latch = new AgentTranscriptLatch();
        latch.markRead("agent-1");

        latch.viewChangedTo("agent-1");

        assertFalse(latch.needsRead("agent-1"),
            "a listener re-sync on the same view must not force a re-read");
    }
}