package com.claudecode.services.agent;

import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AgentSummaryServiceTest {

    private String savedUserDir;

    @AfterEach
    void restoreUserDir() {
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        }
    }

    private static List<Message> sampleTranscript(int n) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(new UserMessage("uuid-" + i, MessageContent.ofText("step " + i)));
        }
        return msgs;
    }

    private void enableFlag(String flag) throws IOException {
        savedUserDir = System.getProperty("user.dir");
        Path cwd = Files.createTempDirectory("cc-agent-settings");
        Path settings = cwd.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"" + flag + "\": true}");
        System.setProperty("user.dir", cwd.toString());
    }

    @Test
    void startSummarization_disabled_returnsNoOpRunnable() {
        StubLlmClient client = new StubLlmClient("Reading runAgent.ts");
        AgentSummaryService svc = new AgentSummaryService(client);
        assertFalse(svc.isEnabled());

        AtomicReference<String> lastSummary = new AtomicReference<>();
        Runnable stopper = svc.startSummarization(
            "task-1", () -> sampleTranscript(5),
            (id, text) -> lastSummary.set(id + ":" + text));

        assertNotNull(stopper);
        // Disabled: nothing is scheduled, the LLM is never touched.
        stopper.run();
        assertNull(lastSummary.get());
        assertTrue(client.prompts.isEmpty());
    }

    @Test
    void startSummarization_disabled_onSummaryNeverFires() throws Exception {
        StubLlmClient client = new StubLlmClient("Running tests");
        AgentSummaryService svc = new AgentSummaryService(client);

        List<String> received = new ArrayList<>();
        Runnable stopper = svc.startSummarization(
            "task-2", () -> sampleTranscript(10), (_, text) -> received.add(text));
        stopper.run();

        assertTrue(received.isEmpty());
    }

    @Test
    void startSummarization_enabled_returnsRunnable() throws IOException {
        // Verifies the enabled branch builds a working scheduler; the 30s tick
        // is not awaited (too slow for a unit test). No synchronous LLM call
        // should occur merely from starting.
        enableFlag("agentProgressSummariesEnabled");
        StubLlmClient client = new StubLlmClient("Fixing null check in validate.ts");
        AgentSummaryService svc = new AgentSummaryService(client);
        assertTrue(svc.isEnabled());

        List<String> received = new ArrayList<>();
        Runnable stopper = svc.startSummarization(
            "task-3", () -> sampleTranscript(10), (_, text) -> received.add(text));

        assertNotNull(stopper);
        assertTrue(received.isEmpty());
        assertTrue(client.prompts.isEmpty());
        stopper.run(); // must not throw
    }

    @Test
    void startSummarization_nullTaskId_isHandled() throws IOException {
        enableFlag("agentProgressSummariesEnabled");
        StubLlmClient client = new StubLlmClient("Indexing files");
        AgentSummaryService svc = new AgentSummaryService(client);

        Runnable stopper = svc.startSummarization(
            null, () -> sampleTranscript(5), (_, _) -> {});
        assertNotNull(stopper);
        stopper.run();
    }
}
