package com.claudecode.ui.lanterna.slash;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.metadata.CommandMetadataEncoder;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.transcript.LanternaMessageDispatcher;
import com.claudecode.ui.lanterna.transcript.MessageHistory;
import com.claudecode.ui.lanterna.transcript.MessagePanel;
import com.claudecode.tools.skills.Skill;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class SlashCommandDispatcherThreadingTest {

    @Test
    void nativeModelPickerMountsInTheCurrentGuiEvent() {
        AtomicBoolean opened = new AtomicBoolean();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ModelCommand());
        CommandContext context = CommandContext.builder(
                "claude-sonnet-4-20250514", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0,
                System.getProperty("user.dir"), false)
            .modelDialogLauncher(() -> opened.set(true))
            .build();
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            new NoopHost(), refs, registry, context);

        dispatcher.dispatch("/model");

        assertTrue(opened.get(),
            "the in-memory picker should not pay a virtual-thread plus invokeLater round trip");
    }

    @Test
    void busyTurnQueuesNonImmediateModelPickerInsteadOfOpeningIt() {
        AtomicBoolean opened = new AtomicBoolean();
        AtomicReference<QueuedCommand> queued = new AtomicReference<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ModelCommand() {
            @Override public boolean isImmediate() { return false; }
        });
        CommandContext context = CommandContext.builder(
                "claude-sonnet-4-20250514", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0,
                System.getProperty("user.dir"), false)
            .modelDialogLauncher(() -> opened.set(true))
            .build();
        NoopHost host = new NoopHost() {
            @Override public boolean isTurnInFlight() { return true; }
            @Override public void renderAndQueue(QueuedCommand cmd, String displayText) {
                queued.set(cmd);
            }
        };
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            host, refs, registry, context);

        dispatcher.dispatch("/model");

        assertFalse(opened.get(), "197 queues typed /model when it is not immediate");
        assertEquals("/model", queued.get().text());
    }

    @Test
    void busyTurnStillOpensImmediateModelPicker() {
        AtomicBoolean opened = new AtomicBoolean();
        AtomicReference<QueuedCommand> queued = new AtomicReference<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ModelCommand() {
            @Override public boolean isImmediate() { return true; }
        });
        CommandContext context = CommandContext.builder(
                "claude-sonnet-4-20250514", List::of, () -> {}, _ -> {},
                () -> Usage.EMPTY, _ -> 0.0,
                System.getProperty("user.dir"), false)
            .modelDialogLauncher(() -> opened.set(true))
            .build();
        NoopHost host = new NoopHost() {
            @Override public boolean isTurnInFlight() { return true; }
            @Override public void renderAndQueue(QueuedCommand cmd, String displayText) {
                queued.set(cmd);
            }
        };
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);

        new SlashCommandDispatcher(host, refs, registry, context).dispatch("/model");

        assertTrue(opened.get());
        assertNull(queued.get());
    }

    @Test
    void ordinaryCommandExecutionNeverRunsOnCallingGuiThread() throws Exception {
        long callerThread = Thread.currentThread().threadId();
        AtomicReference<Long> executionThread = new AtomicReference<>();
        CountDownLatch executed = new CountDownLatch(1);
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("ordinary", "test");
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                executionThread.set(Thread.currentThread().threadId());
                executed.countDown();
                return CommandResult.skip();
            }
        });
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            new NoopHost(), refs, registry, CommandContext.minimal());

        dispatcher.dispatch("/ordinary");

        assertTrue(executed.await(1, TimeUnit.SECONDS));
        assertNotEquals(callerThread, executionThread.get());
    }

    @Test
    void fastCommandUsesTheHostFastModePort() {
        AtomicInteger toggles = new AtomicInteger();
        NoopHost host = new NoopHost() {
            @Override public void toggleFastMode() { toggles.incrementAndGet(); }
        };
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            host, refs, new CommandRegistry(), CommandContext.minimal());

        dispatcher.dispatch("/fast");

        assertEquals(1, toggles.get());
    }

    @Test
    void unknownCommandSkillDiscoveryNeverRunsOnCallingGuiThread() throws Exception {
        long callerThread = Thread.currentThread().threadId();
        AtomicReference<Long> discoveryThread = new AtomicReference<>();
        CountDownLatch discovered = new CountDownLatch(1);
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            new NoopHost(), refs, new CommandRegistry(), CommandContext.minimal(), () -> {
                discoveryThread.set(Thread.currentThread().threadId());
                discovered.countDown();
                return List.of(new Skill("missing-skill", "test", List.of(),
                    "expanded body", null, Skill.SkillSource.BUNDLED, null, null, null, null));
            });

        dispatcher.dispatch("/missing-skill");

        assertTrue(discovered.await(1, TimeUnit.SECONDS));
        assertNotEquals(callerThread, discoveryThread.get());
    }

    @Test
    void sessionHostCompactUsesNativeLongRunningCommandAndPreservesInstructions() throws Exception {
        AtomicReference<String> receivedArgs = new AtomicReference<>();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("compact", "test");
            }
            @Override public boolean isLongRunning() { return true; }
            @Override public CommandResult execute(CommandContext context, String args) {
                receivedArgs.set(args);
                return CommandResult.skip();
            }
        });
        TrackingHost host = new TrackingHost();
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            host, refs, registry, CommandContext.minimal());

        dispatcher.dispatchSessionHostCompact("keep architecture decisions")
            .toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals("keep architecture decisions", receivedArgs.get());
        assertEquals(1, host.started.get());
        assertEquals(1, host.finished.get());
    }

    /**
     * The host gets the typed input (for the processing placeholder) and a cancel
     * hook bound to the command itself, so Esc reaches the running execution.
     */
    @Test
    void typedLongRunningCommandHandsTheHostItsInputAndCancelHook() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("recap", "test");
            }
            @Override public boolean isLongRunning() { return true; }
            @Override public boolean cancelInFlight() {
                cancelled.set(true);
                return true;
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.skip();
            }
        });
        TrackingHost host = new TrackingHost();
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            host, refs, registry, CommandContext.minimal());

        dispatcher.dispatch("/recap");

        assertTrue(host.finishedLatch.await(1, TimeUnit.SECONDS));
        assertEquals("/recap", host.startedInput.get());
        assertTrue(host.startedCancel.get().getAsBoolean());
        assertTrue(cancelled.get());
    }

    /**
     * 236 force-ends the guard on Esc and the late {@code onQuery} still runs the command's
     * prompt, against the aborted controller: it becomes an already-cancelled turn.
     */
    @Test
    void interruptedLongRunningCommandSubmitsItsPromptAsAnAbortedTurn() throws Exception {
        TrackingHost host = new TrackingHost();
        CountDownLatch release = new CountDownLatch(1);
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("slow-prompt", "test");
            }
            @Override public boolean isLongRunning() { return true; }
            @Override public CommandResult execute(CommandContext context, String args) {
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return CommandResult.forQuery("summarize");
            }
        });
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            host, refs, registry, CommandContext.minimal());

        dispatcher.dispatch("/slow-prompt");
        host.interrupted = true;
        release.countDown();

        assertTrue(host.finishedLatch.await(1, TimeUnit.SECONDS));
        assertEquals(0, host.queries.get(), "no ordinary turn");
        assertEquals("/slow-prompt", host.interruptedPrompt.get());
    }

    /**
     * 236's {@code run} finds the guard taken by a turn the cancel drained: the command's visible
     * messages are resurrected into the queue; the command does not run again.
     */
    @Test
    void interruptedPromptBehindARunningTurnResurrectsItsBreadcrumb() throws Exception {
        TrackingHost host = new TrackingHost();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("slow-prompt", "test");
            }
            @Override public boolean isLongRunning() { return true; }
            @Override public CommandResult execute(CommandContext context, String args) {
                runs.incrementAndGet();
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return CommandResult.forPrompt(PromptInvocation.builder(
                        List.of(new TextBlock("prompt body")))
                    .precedingUserMessages(List.of(MessageContent.ofText("<command-message>x")))
                    .build());
            }
        });
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            host, refs, registry, CommandContext.minimal());

        dispatcher.dispatch("/slow-prompt");
        host.interrupted = true;
        host.turnInFlight = true;
        release.countDown();

        assertTrue(host.finishedLatch.await(1, TimeUnit.SECONDS));
        assertEquals(1, runs.get());
        assertNull(host.interruptedPrompt.get());
        assertEquals(List.of("<command-message>x"),
            host.queued.stream().map(QueuedCommand::text).toList());
        assertEquals("prompt", host.queued.getFirst().mode());
    }

    /**
     * A force-ended local command whose result lands while a drained turn runs: 236's
     * {@code run} re-enqueues the non-meta user messages instead of appending them.
     */
    @Test
    void forceEndedLocalResultBehindARunningTurnIsResurrected() throws Exception {
        TrackingHost host = new TrackingHost();
        dispatchSlowLocal(host, true, false);

        assertEquals(List.of(CommandMetadataEncoder.encodeCommandInputTags("slow-local", "")),
            host.queued.stream().map(QueuedCommand::text).toList(),
            "a local command re-enqueues only its tagged input, which never re-runs it");
    }

    @Test
    void forceEndedLocalJsxResultBehindARunningTurnCarriesItsOutput() throws Exception {
        TrackingHost host = new TrackingHost();
        dispatchSlowLocal(host, true, true);

        assertEquals(List.of(CommandMetadataEncoder.encodeCommandInputTags("slow-local", ""),
                "<local-command-stdout>done</local-command-stdout>"),
            host.queued.stream().map(QueuedCommand::text).toList());
    }

    @Test
    void forceEndedLocalResultOnAnIdleSessionRendersInPlace() throws Exception {
        TrackingHost host = new TrackingHost();
        dispatchSlowLocal(host, false, false);

        assertTrue(host.queued.isEmpty());
    }

    private static void dispatchSlowLocal(TrackingHost host, boolean turnInFlight,
                                          boolean localJsx) throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("slow-local", "test");
            }
            @Override public boolean isLongRunning() { return true; }
            @Override public boolean isLocalJsx() { return localJsx; }
            @Override public CommandResult execute(CommandContext context, String args) {
                try {
                    release.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return CommandResult.localJsx("done");
            }
        });
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), new InputPanel(),
            new MessageHistory(), null, null, null);
        new SlashCommandDispatcher(host, refs, registry, CommandContext.minimal())
            .dispatch("/slow-local");
        host.interrupted = true;
        host.turnInFlight = turnInFlight;
        release.countDown();

        assertTrue(host.finishedLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    void commandPermissionTransitionSynchronizesPromptMode() throws Exception {
        PermissionGate gate = new PermissionGate();
        gate.setMode(PermissionMode.DEFAULT);
        CommandRegistry registry = new CommandRegistry();
        CountDownLatch executed = new CountDownLatch(1);
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("enter-plan", "test");
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                gate.setMode(PermissionMode.PLAN);
                executed.countDown();
                return CommandResult.of("Enabled plan mode");
            }
        });
        InputPanel input = new InputPanel("default");
        ReplRefs refs = new ReplRefs(immediateGui(), new MessagePanel(), input,
            new MessageHistory(), new LanternaMessageDispatcher(),
            null, gate);
        SlashCommandDispatcher dispatcher = new SlashCommandDispatcher(
            new NoopHost(), refs, registry, CommandContext.minimal());

        dispatcher.dispatch("/enter-plan");

        assertTrue(executed.await(1, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!Strings.CS.equals("plan", input.getPermissionMode()) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals("plan", input.getPermissionMode());
    }

    private static WindowBasedTextGUI immediateGui() {
        TextGUIThread thread = new TextGUIThread() {
            @Override public void invokeLater(Runnable runnable) { runnable.run(); }
            @Override public boolean processEventsAndUpdate() throws IOException { return false; }
            @Override public void invokeAndWait(Runnable runnable) { runnable.run(); }
            @Override public void setExceptionHandler(ExceptionHandler exceptionHandler) {}
            @Override public Thread getThread() { return Thread.currentThread(); }
        };
        return (WindowBasedTextGUI) Proxy.newProxyInstance(
            WindowBasedTextGUI.class.getClassLoader(), new Class<?>[]{WindowBasedTextGUI.class},
            (_, method, _) -> Strings.CS.equals("getGUIThread", method.getName()) ? thread
                : method.getReturnType().isPrimitive() ? primitiveDefault(method.getReturnType()) : null);
    }

    private static Object primitiveDefault(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }

    private static class NoopHost implements SlashHost {
        @Override public void handleQuery(String input) {}
        @Override public void executeQuery(String displayText, String queryContent,
                                           Map<Integer, PastedContent> pasted) {}
        @Override public void executePrompt(String displayText,
                                            PromptInvocation invocation,
                                            Map<Integer, PastedContent> pasted) {}
        @Override public void renderAndQueue(QueuedCommand cmd, String displayText) {}
        @Override public void showSessionPicker() {}
        @Override public void toggleFastMode() {}
        @Override public void stop() {}
        @Override public boolean isTurnInFlight() { return false; }
    }

    private static final class TrackingHost extends NoopHost {
        private final AtomicInteger started =
            new AtomicInteger();
        private final AtomicInteger finished =
            new AtomicInteger();

        private volatile boolean interrupted;
        private volatile boolean turnInFlight;
        private final List<QueuedCommand> queued = new CopyOnWriteArrayList<>();
        private final AtomicInteger queries = new AtomicInteger();
        private final AtomicReference<String> interruptedPrompt = new AtomicReference<>();

        @Override public LongRunningCommand longRunningCommandStarted(String input,
                                                                     BooleanSupplier cancel) {
            startedInput.set(input);
            startedCancel.set(cancel);
            started.incrementAndGet();
            return new LongRunningCommand() {
                @Override public boolean interrupted() { return interrupted; }
                @Override public void finished() {
                    finished.incrementAndGet();
                    finishedLatch.countDown();
                }
            };
        }
        @Override public void executeQuery(String displayText, String queryContent,
                                           Map<Integer, PastedContent> pasted) {
            queries.incrementAndGet();
        }
        @Override public void executePrompt(String displayText, PromptInvocation invocation,
                                            Map<Integer, PastedContent> pasted) {
            queries.incrementAndGet();
        }
        @Override public boolean isTurnInFlight() { return turnInFlight; }
        @Override public void renderAndQueue(QueuedCommand cmd, String displayText) {
            queued.add(cmd);
        }
        @Override public void executeInterruptedPrompt(String displayText,
                                                       PromptInvocation invocation,
                                                       Map<Integer, PastedContent> pasted) {
            interruptedPrompt.set(displayText);
        }
        private final AtomicReference<String> startedInput = new AtomicReference<>();
        private final AtomicReference<BooleanSupplier> startedCancel = new AtomicReference<>();
        private final CountDownLatch finishedLatch = new CountDownLatch(1);
    }
}
