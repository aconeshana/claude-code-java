package com.claudecode.runtime.query;

import com.claudecode.runtime.session.PreparedSessionResume;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.session.SessionResumeRequest;
import com.claudecode.core.engine.ToolResultBudget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionLifecycleTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return Collections.emptyIterator();
        }

        @Override
        public String getModel() {
            return "test-model";
        }
    };

    private static DefaultQuerySession engine(String sessionId) {
        DefaultQuerySession engine = new DefaultQuerySession(
            QuerySessionSpec.builder().llmClient(NOOP_CLIENT).build());
        engine.switchToSession(sessionId);
        return engine;
    }

    @Test
    void prepareLoadsTranscriptAndSnapshotsOutgoingSession() throws Exception {
        DefaultQuerySession engine = engine("outgoing");
        List<String> events = new ArrayList<>();
        List<Message> messages = List.of(
            new UserMessage("u1", MessageContent.ofText("hello")));
        List<ToolResultBudget.Replacement> replacements = List.of(
            new ToolResultBudget.Replacement("tool-1", "preview"));
        SessionLifecycle lifecycle = new SessionLifecycle(
            engine,
            path -> {
                events.add("read:" + path.getFileName());
                return new SessionLifecycle.TranscriptSnapshot(messages, replacements);
            },
            new RecordingPorts(events, engine));

        PreparedSessionResume prepared = lifecycle.prepare(
            new SessionResumeRequest("incoming", Path.of("incoming.jsonl"), "/other"),
            "/current");

        assertEquals(List.of("read:incoming.jsonl", "capture-cost:incoming",
            "save-cost:outgoing"), events);
        assertEquals("outgoing", prepared.outgoingSessionId());
        assertEquals(messages, prepared.messages());
        assertEquals(replacements, prepared.contentReplacements());
        assertTrue(prepared.crossProject());
    }

    @Test
    void activateOwnsTheOrderedHeadlessSessionSwitch() throws Exception {
        DefaultQuerySession engine = engine("outgoing");
        List<String> events = new ArrayList<>();
        RecordingPorts ports = new RecordingPorts(events, engine);
        SessionLifecycle lifecycle = new SessionLifecycle(
            engine,
            _ -> new SessionLifecycle.TranscriptSnapshot(
                List.of(new UserMessage("u1", MessageContent.ofText("hello"))),
                List.of(new ToolResultBudget.Replacement("tool-1", "preview"))),
            ports);
        PreparedSessionResume prepared = lifecycle.prepare(
            new SessionResumeRequest("incoming", Path.of("incoming.jsonl"), "/project"),
            "/project");
        events.clear();

        lifecycle.activate(prepared, messages -> {
            events.add("view-loaded:" + messages.size());
            assertEquals("incoming", engine.getSessionId());
        });

        assertEquals(List.of(
            "before-switch:outgoing",
            "restore-cost:incoming",
            "restore-budget:incoming:1:1",
            "load-engine:1",
            "view-loaded:1",
            "after-switch:incoming.jsonl:/project"), events);
        assertEquals("incoming", engine.getSessionId());
    }

    @Test
    void sameSessionDoesNotCopyPlanAndSameProjectIsNotCrossProject() throws Exception {
        DefaultQuerySession engine = engine("same");
        List<String> events = new ArrayList<>();
        SessionLifecycle lifecycle = new SessionLifecycle(
            engine, _ -> new SessionLifecycle.TranscriptSnapshot(List.of(), List.of()),
            new RecordingPorts(events, engine));

        PreparedSessionResume prepared = lifecycle.prepare(
            new SessionResumeRequest("same", Path.of("same.jsonl"), ""), "/project");
        lifecycle.activate(prepared, _ -> {});

        assertFalse(prepared.crossProject());
    }

    @Test
    void identityOnlySwitchSavesOutgoingAndRestoresIncomingCost() {
        DefaultQuerySession engine = engine("outgoing");
        List<String> events = new ArrayList<>();
        SessionLifecycle lifecycle = new SessionLifecycle(
            engine, _ -> new SessionLifecycle.TranscriptSnapshot(List.of(), List.of()),
            new RecordingPorts(events, engine));

        lifecycle.switchIdentity("incoming");

        assertEquals(List.of("capture-cost:incoming", "save-cost:outgoing",
            "restore-cost:incoming"), events);
        assertEquals("incoming", engine.getSessionId());
    }

    private static final class RecordingPorts implements SessionLifecycle.Ports {
        private final List<String> events;
        private final DefaultQuerySession engine;

        private RecordingPorts(List<String> events, DefaultQuerySession engine) {
            this.events = events;
            this.engine = engine;
        }

        @Override
        public void captureCost(String sessionId) {
            events.add("capture-cost:" + sessionId);
        }

        @Override
        public void saveCost(String sessionId) {
            events.add("save-cost:" + sessionId);
        }

        @Override
        public void beforeSwitch() {
            events.add("before-switch:" + engine.getSessionId());
        }

        @Override
        public void restoreCost(String sessionId) {
            events.add("restore-cost:" + sessionId);
        }

        @Override
        public void restoreToolResultBudget(
                String sessionId,
                List<Message> messages,
                List<ToolResultBudget.Replacement> replacements) {
            events.add("restore-budget:" + sessionId + ":" + messages.size()
                + ":" + replacements.size());
        }

        @Override
        public void loadEngineMessages(List<Message> messages) {
            events.add("load-engine:" + messages.size());
            engine.loadMessages(messages);
        }

        @Override
        public void afterSwitch(Path sessionFile, List<Message> messages, String cwd) {
            events.add("after-switch:" + sessionFile.getFileName() + ":" + cwd);
        }
    }
}
