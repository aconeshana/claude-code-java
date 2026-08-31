package com.claudecode.services.session;

import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.session.SessionStorage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeStateRestorerGoalTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override public String getModel() { return "test-model"; }
    };

    @Test
    void rejectedResumeGateDoesNotRestoreUnfinishedGoal() {
        DefaultQuerySession engine = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .workingDirectory("/tmp")
            .build());
        HookEngine hooks = new HookEngine(HooksSettings.EMPTY, "/tmp");
        hooks.setGoal("stale active goal", 0L);
        ResumeStateRestorer restorer = new ResumeStateRestorer(
            engine, new SessionStorage(), hooks, null, _ -> false);
        List<Message> messages = List.of(new AttachmentMessage(
            "goal", GoalStatusAttachment.pending("unfinished goal", "not done")));

        restorer.restoreGoal(messages, "/tmp");

        assertTrue(hooks.activeGoal().isEmpty());
    }
}
