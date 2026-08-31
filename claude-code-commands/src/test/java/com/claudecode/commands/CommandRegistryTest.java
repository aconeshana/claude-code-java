package com.claudecode.commands;


import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.commands.metadata.CommandMetadata;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.impl.session.ClearCommand;
import com.claudecode.commands.impl.context.CompactCommand;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.info.CostCommand;
import com.claudecode.commands.impl.session.ExitCommand;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Set;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {

    private CommandRegistry registry;
    private CommandContext context;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
        context = CommandContext.minimal();
    }

    // ---- Registration and Lookup ----

    @Nested
    class RegistrationAndLookup {

        @Test
        void replaceMatchingPublishesOneAtomicRevision() throws Exception {
            registry.register(command("dynamic:old-a"));
            registry.register(command("dynamic:old-b"));
            AtomicInteger notifications = new AtomicInteger();
            AtomicReference<CommandRegistry.Snapshot> published = new AtomicReference<>();
            try (AutoCloseable ignored = registry.subscribe(snapshot -> {
                notifications.incrementAndGet();
                published.set(snapshot);
            })) {
                long before = registry.snapshot().revision();

                registry.replaceMatching(
                    name -> Strings.CS.startsWith(name, "dynamic:"),
                    List.of(command("dynamic:new-a"), command("dynamic:new-b")));

                assertEquals(1, notifications.get());
                assertEquals(before + 1, published.get().revision());
                assertEquals(Set.of("dynamic:new-a", "dynamic:new-b"),
                    published.get().commands().stream().map(Command::name).collect(Collectors.toSet()));
            }
        }

        @Test
        void replaceMatchingNeverExposesAPartialGeneration() throws Exception {
            registry.replaceMatching(_ -> true,
                List.of(command("generation:a"), command("generation:b")));
            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<List<String>> partial = new AtomicReference<>();
            CountDownLatch readerStarted = new CountDownLatch(1);
            Thread reader = Thread.startVirtualThread(() -> {
                readerStarted.countDown();
                while (!stop.get()) {
                    List<String> names = registry.snapshot().commands().stream()
                        .map(Command::name).sorted().toList();
                    if (!names.equals(List.of("generation:a", "generation:b"))
                            && !names.equals(List.of("generation:c", "generation:d"))) {
                        partial.compareAndSet(null, names);
                        return;
                    }
                }
            });
            assertTrue(readerStarted.await(1, TimeUnit.SECONDS));

            for (int i = 0; i < 2_000; i++) {
                List<Command> generation = (i & 1) == 0
                    ? List.of(command("generation:c"), command("generation:d"))
                    : List.of(command("generation:a"), command("generation:b"));
                registry.replaceMatching(
                    name -> Strings.CS.startsWith(name, "generation:"), generation);
            }
            stop.set(true);
            reader.join();

            assertNull(partial.get(), "readers must observe the old or new generation, never a partial set");
        }

        @Test
        void subscribersRunOutsideTheRegistryLock() throws Exception {
            CountDownLatch notificationEntered = new CountDownLatch(1);
            CountDownLatch concurrentReadFinished = new CountDownLatch(1);
            try (AutoCloseable ignored = registry.subscribe(_ -> {
                notificationEntered.countDown();
                Thread.startVirtualThread(() -> {
                    registry.snapshot();
                    concurrentReadFinished.countDown();
                });
                try {
                    assertTrue(concurrentReadFinished.await(1, TimeUnit.SECONDS),
                        "a listener must not hold the registry lock while it invokes clients");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail(e);
                }
            })) {
                CompletableFuture<Void> mutation = CompletableFuture.runAsync(
                    () -> registry.register(command("outside-lock")));
                mutation.get(2, TimeUnit.SECONDS);
                assertTrue(notificationEntered.await(1, TimeUnit.SECONDS));
            }
        }

        @Test
        void bundledLoopIsNotRegisteredAsAnIndependentFactoryCommand() {
            CommandRegistry defaults = CommandFactory.createDefault();

            assertTrue(defaults.find("loop").isEmpty());
            assertTrue(defaults.find("proactive").isEmpty());
        }

        @Test
        void defaultFactoryRegistersBuiltInsAsOneAtomicBatch() {
            CommandRegistry defaults = CommandFactory.createDefault();

            assertFalse(defaults.getAll().isEmpty());
            assertEquals(1, defaults.snapshot().revision());
        }

        @Test
        void registerAndFindByName() {
            registry.register(new ExitCommand());
            Optional<Command> found = registry.find("exit");
            assertTrue(found.isPresent());
            assertEquals("exit", found.get().name());
        }

        @Test
        void findIsCaseInsensitive() {
            registry.register(new ExitCommand());
            assertTrue(registry.find("EXIT").isPresent());
            assertTrue(registry.find("Exit").isPresent());
        }

        @Test
        void findByAlias() {
            registry.register(new ExitCommand());
            Optional<Command> found = registry.find("quit");
            assertTrue(found.isPresent());
            assertEquals("exit", found.get().name());
        }

        @Test
        void findUnknownReturnsEmpty() {
            assertTrue(registry.find("nonexistent").isEmpty());
        }

        @Test
        void findNullReturnsEmpty() {
            assertTrue(registry.find(null).isEmpty());
        }

        @Test
        void findBlankReturnsEmpty() {
            assertTrue(registry.find("  ").isEmpty());
        }

        @Test
        void getAllReturnsRegisteredCommands() {
            registry.register(new ExitCommand());
            registry.register(new ClearCommand());
            List<Command> all = registry.getAll();
            assertEquals(2, all.size());
        }

        @Test
        void getAllReturnsImmutableList() {
            registry.register(new ExitCommand());
            List<Command> all = registry.getAll();
            assertThrows(UnsupportedOperationException.class,
                () -> all.add(new ClearCommand()));
        }
    }

    private static Command command(String name) {
        return new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata(name, name);
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.of(name);
            }
        };
    }

    // ---- Dispatch ----

    @Nested
    class Dispatch {

        @Test
        void dispatchKnownCommand() {
            registry.register(new ExitCommand());
            CommandResult result = registry.dispatch("/exit", context);
            assertTrue(result.shouldExit());

            assertTrue(List.of("Goodbye!", "See ya!", "Bye!", "Catch you later!").contains(result.output()),
                "Expected a valid goodbye message but got: " + result.output());
        }

        @Test
        void dispatchUnknownCommand() {
            CommandResult result = registry.dispatch("/foo", context);
            assertFalse(result.shouldExit());
            assertTrue(Strings.CS.contains(result.output(), "Unknown command"));
        }

        @Test
        void dispatchEmptyInput() {
            CommandResult result = registry.dispatch("", context);
            assertEquals("Empty command.", result.output());
        }

        @Test
        void dispatchNullInput() {
            CommandResult result = registry.dispatch(null, context);
            assertEquals("Empty command.", result.output());
        }

        @Test
        void dispatchNonSlashInput() {
            CommandResult result = registry.dispatch("hello", context);
            assertTrue(Strings.CS.contains(result.output(), "Not a command"));
        }

        @Test
        void dispatchWithArgs() {
            registry.register(new ModelCommand());
            CommandResult result = registry.dispatch("/model claude-opus", context);
            assertTrue(Strings.CS.contains(result.output(), "Set model to claude-opus"));
        }

        @Test
        void dispatchByAlias() {
            registry.register(new ExitCommand());
            CommandResult result = registry.dispatch("/quit", context);
            assertTrue(result.shouldExit());
        }

        @Test
        void dispatchUnavailableCommand() {
            Command unavailable = new Command() {
                @Override public CommandMetadata metadata() {
                    return new CommandMetadata("secret", "Secret");
                }
                @Override public CommandResult execute(CommandContext ctx, String args) {
                    return CommandResult.of("secret!");
                }
                @Override public boolean isAvailable(CommandContext ctx) { return false; }
            };
            registry.register(unavailable);
            CommandResult result = registry.dispatch("/secret", context);
            assertTrue(Strings.CS.contains(result.output(), "not available"));
        }

        @Test
        void nonInteractiveDispatchIgnoresOrdinaryPrompts() {
            assertTrue(registry.dispatchNonInteractive("hello", context).isEmpty());
        }

        @Test
        void nonInteractiveDispatchExecutesEligibleCommandAndPreservesArgs() {
            registry.register(new Command() {
                @Override public CommandMetadata metadata() {
                    return new CommandMetadata("headless", "Headless");
                }
                @Override public boolean supportsNonInteractive() { return true; }
                @Override public CommandResult execute(CommandContext ctx, String args) {
                    return CommandResult.forQuery("expanded:" + args);
                }
            });

            Optional<CommandResult> result =
                registry.dispatchNonInteractive("/headless one two", context);

            assertTrue(result.isPresent());
            assertTrue(result.orElseThrow().shouldQuery());
            assertEquals("expanded:one two", result.orElseThrow().output());
        }

        @Test
        void nonInteractiveDispatchHidesKnownButUnsupportedCommandsAsUnknownSkills() {
            registry.register(new ClearCommand());

            CommandResult result = registry.dispatchNonInteractive("/clear", context).orElseThrow();

            assertEquals("Unknown skill: clear", result.output());
            assertFalse(result.shouldQuery());
        }

        @Test
        void nonInteractiveDispatchHidesUnavailableEligibleCommandsAsUnknownSkills() {
            registry.register(new Command() {
                @Override public CommandMetadata metadata() {
                    return new CommandMetadata("gated", "Gated");
                }
                @Override public boolean supportsNonInteractive() { return true; }
                @Override public boolean isAvailable(CommandContext ctx) { return false; }
                @Override public CommandResult execute(CommandContext ctx, String args) {
                    return CommandResult.of("must not run");
                }
            });

            CommandResult result = registry.dispatchNonInteractive("/gated", context).orElseThrow();

            assertEquals("Unknown skill: gated", result.output());
        }
    }

    // ---- Command Parsing ----

    @Nested
    class Parsing {

        @Test
        void parseSimpleCommand() {
            var parsed = CommandRegistry.parseInput("/help");
            assertEquals("help", parsed.name());
            assertEquals("", parsed.args());
        }

        @Test
        void parseCommandWithArgs() {
            var parsed = CommandRegistry.parseInput("/model claude-opus");
            assertEquals("model", parsed.name());
            assertEquals("claude-opus", parsed.args());
        }

        @Test
        void parseCommandWithMultipleArgs() {
            var parsed = CommandRegistry.parseInput("/model claude-opus --fast");
            assertEquals("model", parsed.name());
            assertEquals("claude-opus --fast", parsed.args());
        }

        @Test
        void parseEmptyInput() {
            var parsed = CommandRegistry.parseInput("");
            assertEquals("", parsed.name());
            assertEquals("", parsed.args());
        }

        @Test
        void parseNullInput() {
            var parsed = CommandRegistry.parseInput(null);
            assertEquals("", parsed.name());
            assertEquals("", parsed.args());
        }

        @Test
        void parseWhitespaceInput() {
            var parsed = CommandRegistry.parseInput("   ");
            assertEquals("", parsed.name());
            assertEquals("", parsed.args());
        }

        @Test
        void parseCommandNameIsCaseInsensitive() {
            var parsed = CommandRegistry.parseInput("/HELP");
            assertEquals("help", parsed.name());
        }

        @Test
        void parseCommandWithLeadingWhitespace() {
            var parsed = CommandRegistry.parseInput("  /help  ");
            assertEquals("help", parsed.name());
        }

        @Test
        void parseCommandWithExtraSpaces() {
            var parsed = CommandRegistry.parseInput("/model   claude-opus");
            assertEquals("model", parsed.name());
            assertEquals("claude-opus", parsed.args());
        }
    }

    // ---- P0 Command Behavior ----

    @Nested
    class P0Commands {

        @Test
        void helpListsCommands() {
            CommandRegistry reg = CommandFactory.createDefault();
            CommandResult result = reg.dispatch("/help", context);
            assertFalse(result.shouldExit());
            assertTrue(Strings.CS.contains(result.output(), "/help"));
            assertTrue(Strings.CS.contains(result.output(), "/exit"));
            assertTrue(Strings.CS.contains(result.output(), "/clear"));
            assertTrue(Strings.CS.contains(result.output(), "/model"));
            assertTrue(Strings.CS.contains(result.output(), "/cost"));
        }

        @Test
        void exitReturnsShouldExit() {
            registry.register(new ExitCommand());
            CommandResult result = registry.dispatch("/exit", context);
            assertTrue(result.shouldExit());

            assertTrue(List.of("Goodbye!", "See ya!", "Bye!", "Catch you later!").contains(result.output()),
                "Expected a valid goodbye message but got: " + result.output());
        }

        @Test
        void clearClearsMessages() {
            List<Object> cleared = new ArrayList<>();
            CommandContext ctx = CommandContext.builder(
                "test-model",
                List::of,
                () -> cleared.add("cleared"),
                _ -> {},
                () -> Usage.EMPTY,
                _ -> 0.0,
                "/tmp",
                false
            ).build();
            registry.register(new ClearCommand());
            CommandResult result = registry.dispatch("/clear", ctx);
            assertFalse(result.shouldExit());

            // /clear never emits a "Conversation cleared" or similar user-facing
            // string; the effect is entirely on the message history (clearMessages
            // callback fires).
            assertEquals("", result.output(),
                "/clear must return empty output — TS clear.ts returns { value: '' }");
            assertEquals(1, cleared.size());
        }

        @Test
        void modelShowsCurrentModel() {
            registry.register(new ModelCommand());
            CommandResult result = registry.dispatch("/model", context);
            // New behavior: renders the display name, not the raw id.
            assertTrue(Strings.CS.startsWith(result.output(), "Current model:"));
        }

        @Test
        void modelChangesModel() {
            AtomicReference<String> modelRef = new AtomicReference<>("old-model");
            CommandContext ctx = CommandContext.builder(
                "old-model",
                List::of,
                () -> {},
                modelRef::set,
                () -> Usage.EMPTY,
                _ -> 0.0,
                "/tmp",
                false
            ).build();
            registry.register(new ModelCommand());
            CommandResult result = registry.dispatch("/model new-model", ctx);
            assertTrue(Strings.CS.contains(result.output(), "Set model to new-model"));
            assertEquals("new-model", modelRef.get());
        }

        @Test
        void costShowsUsage() {
            Usage usage = new Usage(1000, 500, 0, 0);
            CommandContext ctx = CommandContext.builder(
                "test-model",
                List::of,
                () -> {},
                _ -> {},
                () -> usage,
                _ -> 0.018,
                "/tmp",
                false
            ).build();
            registry.register(new CostCommand());
            // /cost now sources from the process-level SessionCostState

            SessionCostState.get().reset();
            SessionCostState.get()
                .recordApiRequest("test-model", usage, 1200);
            CommandResult result = registry.dispatch("/cost", ctx);
            assertTrue(Strings.CS.contains(result.output(), "1.0k input"), result.output());
            assertTrue(Strings.CS.contains(result.output(), "500 output"), result.output());
            assertTrue(Strings.CS.contains(result.output(), "Total cost:"), result.output());
            assertTrue(Strings.CS.contains(result.output(), "Total duration (wall):"), result.output());
        }

        @Test
        void compactWithNoMessages() {
            registry.register(new CompactCommand());
            CommandResult result = registry.dispatch("/compact", context);
            assertTrue(Strings.CS.contains(result.output(), "No messages to compact"));
        }

        @Test
        void configShowsInfo() {
            registry.register(new ConfigCommand());
            CommandResult result = registry.dispatch("/config", context);
            assertTrue(Strings.CS.contains(result.output(), "Configuration"));
            assertTrue(Strings.CS.contains(result.output(), "verbose"));
        }
    }

    // ---- Availability Checks ----

    @Nested
    class AvailabilityChecks {

        @Test
        void defaultRegistryKeepsCoreCommandsAvailable() {
            CommandRegistry reg = CommandFactory.createDefault();
            List<Command> available = reg.getAvailable(context);
            assertFalse(available.isEmpty());
            assertTrue(available.stream().anyMatch(command -> Strings.CS.equals(command.name(), "exit")));
            assertTrue(available.stream().anyMatch(command -> Strings.CS.equals(command.name(), "clear")));
        }

        @Test
        void unavailableCommandFilteredFromGetAvailable() {
            Command unavailable = new Command() {
                @Override public CommandMetadata metadata() {
                    return new CommandMetadata("hidden", "Hidden");
                }
                @Override public CommandResult execute(CommandContext ctx, String args) {
                    return CommandResult.of("hidden");
                }
                @Override public boolean isAvailable(CommandContext ctx) { return false; }
            };
            registry.register(new ExitCommand());
            registry.register(unavailable);
            List<Command> available = registry.getAvailable(context);
            assertEquals(1, available.size());
            assertEquals("exit", available.getFirst().name());
        }

    }

    // ---- Factory ----

    @Nested
    class Factory {

        @Test
        void defaultRegistryHasAllExpectedCommands() {
            CommandRegistry reg = CommandFactory.createDefault();
            List<Command> all = reg.getAll();
            assertTrue(all.size() >= 18, "Expected at least 18 commands, got " + all.size());

            // P0
            assertTrue(reg.find("help").isPresent());
            assertTrue(reg.find("exit").isPresent());
            assertTrue(reg.find("clear").isPresent());
            assertTrue(reg.find("compact").isPresent());
            assertTrue(reg.find("compact-warning").isEmpty(),
                    "TS has no /compact-warning command; warning suppression is internal state");
            assertTrue(reg.find("config").isPresent());
            assertTrue(reg.find("model").isPresent());
            assertTrue(reg.find("cost").isPresent());

            // P1 stubs
            assertTrue(reg.find("commit").isPresent());
            assertTrue(reg.find("diff").isPresent());
            assertTrue(reg.find("review").isPresent());
            assertTrue(reg.find("resume").isPresent());
            assertTrue(reg.find("share").isPresent());
            assertTrue(reg.find("export").isPresent());
            assertTrue(reg.find("memory").isPresent());
            assertTrue(reg.find("doctor").isPresent());
            assertTrue(reg.find("permissions").isPresent());
            assertTrue(reg.find("status").isPresent());
        }

        @Test
        void aliasesWorkInDefaultRegistry() {
            CommandRegistry reg = CommandFactory.createDefault();
            // "quit" is alias for "exit"
            assertTrue(reg.find("quit").isPresent());
            assertEquals("exit", reg.find("quit").get().name());


            assertTrue(reg.find("help").isPresent());
        }

        @Test
        void colorAndThemeDoNotCollide() {
            // Regression: ThemeCommand once wrongly declared aliases=[color,colors]

            // correctly (primary-name lookup wins over aliasMap), but "/colors"
            // silently opened the theme picker instead of failing to resolve.
            CommandRegistry reg = CommandFactory.createDefault();
            assertTrue(reg.find("color").isPresent());
            assertEquals("color", reg.find("color").get().name());
            assertTrue(reg.find("theme").isPresent());
            assertEquals("theme", reg.find("theme").get().name());
            assertTrue(reg.find("colors").isEmpty(),
                "TS has no '/colors' command — Java must not resolve it via a stray alias");
        }
    }
}
