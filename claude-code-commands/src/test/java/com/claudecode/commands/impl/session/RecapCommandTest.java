package com.claudecode.commands.impl.session;

import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.recap.RecapPort;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RecapCommandTest {

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage("a-" + text.hashCode(),
            new AssistantContent("m", List.of(new TextBlock(text)), null));
    }

    private static CommandContext.Builder builder(List<Message> messages) {
        return CommandContext.builder(
            "test-model", () -> messages, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0,
            Path.of("/tmp/nonexistent").toString(), false);
    }

    @Test
    void metadata_matchesTsBuiltIn() {
        RecapCommand command = new RecapCommand();
        assertEquals("recap", command.name());
        assertEquals("Generate a one-line session recap now", command.description());
        assertTrue(command.supportsNonInteractive(), "236 recap is supportsNonInteractive");
        assertTrue(command.isLongRunning(), "synthesis makes a model call — must not run on the GUI thread");
    }

    @Test
    void nonePort_degradesToFailedMessage() {
        // The builder substitutes RecapPort.none() for a null value; none()
        // reports FAILED, which 236 collapses onto the generic failure message.
        CommandResult r = new RecapCommand().execute(
            builder(List.of(assistant("hello"))).recap(null).build(), "");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "Couldn't generate a recap"));
    }

    @Test
    void ok_recapTextReturnedLocally() {
        RecapPort port = (_, _) -> RecapPort.Outcome.ok("Implementing /recap; next is the away-summary diff.");
        CommandResult r = new RecapCommand().execute(
            builder(List.of(assistant("hello"))).recap(port).build(), "");
        assertFalse(r.shouldQuery());
        assertEquals("Implementing /recap; next is the away-summary diff.", r.output());
    }

    @Test
    void noTurn_tellsUserToMessageFirst() {
        RecapPort port = (_, _) -> RecapPort.Outcome.noTurn();
        CommandResult r = new RecapCommand().execute(
            builder(List.of()).recap(port).build(), "");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "Nothing to recap yet"));
    }

    @Test
    void failed_degradesToMessage() {
        RecapPort port = (_, _) -> RecapPort.Outcome.failed();
        CommandResult r = new RecapCommand().execute(
            builder(List.of(assistant("hello"))).recap(port).build(), "");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "Couldn't generate a recap"));
    }

    @Test
    void port_receivesConversationMessages() {
        List<Message> messages = List.of(assistant("ignore"), assistant("me"));
        AtomicReference<List<Message>> seen = new AtomicReference<>();
        RecapPort port = (m, _) -> {
            seen.set(m);
            return RecapPort.Outcome.ok("recap");
        };
        new RecapCommand().execute(builder(messages).recap(port).build(), "");
        assertEquals(messages, seen.get());
    }

    @Test
    void cancelInFlight_abortsTheRunningSynthesisAndReportsCancelled() throws Exception {
        RecapCommand command = new RecapCommand();
        assertFalse(command.cancelInFlight(), "nothing to cancel while idle");

        CountDownLatch started = new CountDownLatch(1);
        RecapPort port = (_, abort) -> {
            started.countDown();
            CountDownLatch aborted = new CountDownLatch(1);
            abort.onAbort(aborted::countDown);
            try {
                aborted.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            return abort.isAborted() ? RecapPort.Outcome.aborted() : RecapPort.Outcome.failed();
        };
        CommandContext context = builder(List.of(assistant("hello"))).recap(port).build();
        CompletableFuture<CommandResult> result =
            CompletableFuture.supplyAsync(() -> command.execute(context, ""));

        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(command.cancelInFlight());
        assertEquals("Recap cancelled.", result.get(5, TimeUnit.SECONDS).output());
        assertFalse(command.cancelInFlight(), "the finished execution released its signal");
    }
}