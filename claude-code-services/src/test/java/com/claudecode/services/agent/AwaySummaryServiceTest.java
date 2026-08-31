package com.claudecode.services.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class AwaySummaryServiceTest {

    private String savedUserDir;

    @AfterEach
    void restoreUserDir() {
        if (savedUserDir != null) {
            System.setProperty("user.dir", savedUserDir);
        }
    }

    private static List<Message> sampleMessages(int n) {
        List<Message> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            msgs.add(new UserMessage("uuid-" + i, MessageContent.ofText("message " + i)));
        }
        return msgs;
    }



    private void setFlag(String flag, boolean value) throws IOException {
        savedUserDir = System.getProperty("user.dir");
        Path cwd = Files.createTempDirectory("cc-away-settings");
        Path settings = cwd.resolve(".claude").resolve("settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"" + flag + "\": " + value + "}");
        System.setProperty("user.dir", cwd.toString());
    }

    @Test
    void generateAwaySummary_nullOrEmpty_returnsNull() {
        AwaySummaryService svc = new AwaySummaryService(new StubLlmClient("recap"));
        assertNull(svc.generateAwaySummary(null));
        assertNull(svc.generateAwaySummary(List.of()));
    }

    @Test
    void generateAwaySummary_withMessages_callsLlmAndTrims() {
        StubLlmClient client = new StubLlmClient("  The user is refactoring the parser.  ");
        AwaySummaryService svc = new AwaySummaryService(client);

        String result = svc.generateAwaySummary(sampleMessages(5));

        assertEquals("The user is refactoring the parser.", result);
        // SideQuery wraps the prompt and queries the small-fast model once.
        assertEquals(1, client.prompts.size());
        assertTrue(Strings.CS.contains(client.prompts.getFirst(), "stepped away"));
    }

    @Test
    void generateAwaySummary_llmReturnsNull_returnsNull() {
        StubLlmClient client = new StubLlmClient(null);
        AwaySummaryService svc = new AwaySummaryService(client);
        assertNull(svc.generateAwaySummary(sampleMessages(3)));
    }

    @Test
    void generateAwaySummary_llmReturnsBlank_returnsNull() {
        StubLlmClient client = new StubLlmClient("   ");
        AwaySummaryService svc = new AwaySummaryService(client);
        assertNull(svc.generateAwaySummary(sampleMessages(3)));
    }

    @Test
    void maybePublish_disabled_isNoOp() throws IOException {
        setFlag("awaySummaryEnabled", false);
        StubLlmClient client = new StubLlmClient("recap");
        AwaySummaryService svc = new AwaySummaryService(client);
        assertFalse(svc.isEnabled());

        AtomicReference<String> published = new AtomicReference<>();
        svc.maybePublishAwaySummary(sampleMessages(4), published::set);

        assertNull(published.get());
        assertTrue(client.prompts.isEmpty());
    }

    @Test
    void maybePublish_enabled_publishes() throws IOException {
        setFlag("awaySummaryEnabled", true);
        StubLlmClient client = new StubLlmClient("You were debugging the auth flow.");
        AwaySummaryService svc = new AwaySummaryService(client);
        assertTrue(svc.isEnabled());

        AtomicReference<String> published = new AtomicReference<>();
        svc.maybePublishAwaySummary(sampleMessages(4), published::set);

        assertEquals("You were debugging the auth flow.", published.get());
    }

    @Test
    void startIdleWatcher_disabled_returnsNoOpRunnable() throws IOException {
        setFlag("awaySummaryEnabled", false);
        StubLlmClient client = new StubLlmClient("recap");
        AwaySummaryService svc = new AwaySummaryService(client);

        AtomicReference<String> published = new AtomicReference<>();
        Runnable stopper = svc.startIdleWatcher(() -> sampleMessages(2), published::set);

        assertNotNull(stopper);
        // No scheduled work should touch the LLM or the sink.
        stopper.run();
        assertNull(published.get());
        assertTrue(client.prompts.isEmpty());
    }

    @Test
    void startIdleWatcher_enabled_returnsRunnable() throws IOException {
        // Verifies the enabled branch constructs a working stopper; the actual
        // 5min idle trigger is not exercised (would be too slow for a unit test).
        setFlag("awaySummaryEnabled", true);
        StubLlmClient client = new StubLlmClient("recap");
        AwaySummaryService svc = new AwaySummaryService(client);
        assertTrue(svc.isEnabled());

        Runnable stopper = svc.startIdleWatcher(() -> sampleMessages(2), _ -> {});
        assertNotNull(stopper);
        stopper.run(); // must not throw
    }
}
