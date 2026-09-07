package com.claudecode.services.agent;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageFactory;
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

    private static Message awaySummaryMessage() {
        return MessageFactoryBridge.awaySummary();
    }

    /** Bridges to the core MessageFactory without widening its test surface. */
    private static final class MessageFactoryBridge {
        static Message awaySummary() {
            return MessageFactory.createAwaySummaryMessage("previous recap");
        }
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
    void generateAwaySummary_sendsConversationPlusPromptAndTrims() {
        StubLlmClient client = new StubLlmClient("  The user is refactoring the parser.  ");
        AwaySummaryService svc = new AwaySummaryService(client);

        String result = svc.generateAwaySummary(sampleMessages(5));

        assertEquals("The user is refactoring the parser.", result);
        // One query; its last message is the fixed 236 FlT prompt (JXn appends
        // it after the conversation, it is not the first message).
        assertEquals(1, client.prompts.size());
        assertTrue(Strings.CS.contains(client.prompts.getFirst(), "stepped away"));
        assertTrue(Strings.CS.contains(client.prompts.getFirst(), "under 40 words"));
        assertEquals(6, client.requests.getFirst().messages().size(),
            "5 conversation messages + 1 recap prompt");
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
    void generateAwaySummary_capsAt400CharsOnWordBoundary() {
        String longRecap = ("word ".repeat(120)) + "tail";
        StubLlmClient client = new StubLlmClient(longRecap);
        AwaySummaryService svc = new AwaySummaryService(client);

        String result = svc.generateAwaySummary(sampleMessages(4));

        assertTrue(result.length() <= AwaySummaryService.RECAP_CHAR_CAP + 1,
            "cap 400 plus ellipsis");
        assertTrue(Strings.CS.endsWith(result, "…"));
        assertTrue(Strings.CS.contains(result, " "),
            "long recaps cut at a word boundary, not mid-word");
    }

    @Test
    void capRecapText_shortTextUnchanged() {
        assertEquals("short", AwaySummaryService.capRecapText("short"));
        assertEquals("x".repeat(400), AwaySummaryService.capRecapText("x".repeat(400)));
    }

    @Test
    void capRecapText_longTextCutsAtWordBoundary() {
        String text = "word ".repeat(150);
        String capped = AwaySummaryService.capRecapText(text);
        assertTrue(capped.length() <= 401);
        assertTrue(Strings.CS.endsWith(capped, "…"));
        assertFalse(Strings.CS.endsWith(capped.substring(0, capped.length() - 1), " "),
            "no trailing space before the ellipsis");
    }

    @Test
    void shouldRecap_requiresThreeUserMessages() {
        AwaySummaryService svc = new AwaySummaryService(new StubLlmClient("recap"));
        assertFalse(svc.shouldRecap(sampleMessages(2)), "236 Ze0=3 minimum user turns");
        assertTrue(svc.shouldRecap(sampleMessages(3)));
    }

    @Test
    void shouldRecap_afterRecapRequiresTwoNewUserMessages() {
        AwaySummaryService svc = new AwaySummaryService(new StubLlmClient("recap"));
        List<Message> msgs = new ArrayList<>(sampleMessages(3));
        msgs.add(awaySummaryMessage());
        assertFalse(svc.shouldRecap(msgs), "236 Qe0=2 new user turns after a recap");

        msgs.add(new UserMessage("u-new-1", MessageContent.ofText("next 1")));
        assertFalse(svc.shouldRecap(msgs), "still one short");

        msgs.add(new UserMessage("u-new-2", MessageContent.ofText("next 2")));
        assertTrue(svc.shouldRecap(msgs), "two new user turns unlock the next recap");
    }

    @Test
    void lastMessageIsAwaySummary_matches236Hqg() {
        AwaySummaryService svc = new AwaySummaryService(new StubLlmClient("recap"));
        List<Message> msgs = new ArrayList<>(sampleMessages(3));
        assertFalse(svc.lastMessageIsAwaySummary(msgs));

        msgs.add(awaySummaryMessage());
        assertTrue(svc.lastMessageIsAwaySummary(msgs), "last message is a recap");

        msgs.add(new UserMessage("u-after", MessageContent.ofText("after recap")));
        assertFalse(svc.lastMessageIsAwaySummary(msgs), "a later message resets hQg");
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
    void maybePublish_gatesFail_doesNotCallLlm() throws IOException {
        setFlag("awaySummaryEnabled", true);
        StubLlmClient client = new StubLlmClient("recap");
        AwaySummaryService svc = new AwaySummaryService(client);

        AtomicReference<String> published = new AtomicReference<>();
        // Fewer than 3 user messages → et0 gate → no LLM call.
        svc.maybePublishAwaySummary(sampleMessages(2), published::set);

        assertNull(published.get());
        assertTrue(client.prompts.isEmpty());
    }

    @Test
    void withDisableHint_firstThreeOnly() {
        assertEquals("recap (disable recaps in /config)",
            AwaySummaryService.withDisableHint("recap", 0));
        assertEquals("recap (disable recaps in /config)",
            AwaySummaryService.withDisableHint("recap", 2));
        assertEquals("recap", AwaySummaryService.withDisableHint("recap", 3));
    }
}