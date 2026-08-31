package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.pokemon.PokemonRoller;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PokemonHatchDialogTest {

    @Test
    void defaultsToKeepingCurrentPokemon() {
        AtomicBoolean hatched = new AtomicBoolean();
        var request = new CommandContext.PokemonHatchRequest(
            PokemonRoller.defaultPikachu(),
            () -> { hatched.set(true); return CommandResult.of("hatched"); },
            () -> CommandResult.of("kept"));
        AtomicReference<CommandResult> result = new AtomicReference<>();
        PokemonHatchDialog dialog = new PokemonHatchDialog();

        dialog.show(request, result::set);
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        assertFalse(hatched.get());
        assertEquals("kept", result.get().output());
        assertFalse(dialog.isActive());
    }

    @Test
    void explicitUpSelectionConfirmsHatchAndEscapeCancels() {
        AtomicBoolean hatched = new AtomicBoolean();
        var request = new CommandContext.PokemonHatchRequest(
            PokemonRoller.defaultPikachu(),
            () -> { hatched.set(true); return CommandResult.of("hatched"); },
            () -> CommandResult.of("kept"));
        AtomicReference<CommandResult> result = new AtomicReference<>();
        PokemonHatchDialog dialog = new PokemonHatchDialog();

        dialog.show(request, result::set);
        dialog.handleKey(new KeyStroke(KeyType.ARROW_UP), new AtomicBoolean(true));
        dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertTrue(hatched.get());
        assertEquals("hatched", result.get().output());

        hatched.set(false);
        result.set(null);
        dialog.show(request, result::set);
        dialog.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertFalse(hatched.get());
        assertEquals("kept", result.get().output());
    }

    @Test
    void configuredDialogDoesNotBlockKeyHandlerWhileHatchPersists() throws Exception {
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        var request = new CommandContext.PokemonHatchRequest(
            PokemonRoller.defaultPikachu(),
            () -> {
                actionStarted.countDown();
                try {
                    releaseAction.await();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return CommandResult.of("hatched");
            },
            () -> CommandResult.of("kept"));
        AtomicReference<CommandResult> result = new AtomicReference<>();
        ConcurrentLinkedQueue<Runnable> guiTasks = new ConcurrentLinkedQueue<>();
        PokemonHatchDialog dialog = new PokemonHatchDialog();
        dialog.setGuiInvoker(guiTasks::add);
        dialog.show(request, result::set);
        dialog.handleKey(new KeyStroke(KeyType.ARROW_UP), new AtomicBoolean(true));

        assertTimeout(Duration.ofMillis(200), () ->
            dialog.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true)));
        assertTrue(actionStarted.await(2, TimeUnit.SECONDS));
        assertTrue(dialog.isActive(), "dialog remains active while persistence is in flight");

        releaseAction.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (guiTasks.isEmpty() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(guiTasks.isEmpty(), "completion should be posted to the GUI thread");
        guiTasks.remove().run();

        assertFalse(dialog.isActive());
        assertEquals("hatched", result.get().output());
    }
}
