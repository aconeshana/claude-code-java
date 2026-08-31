package com.claudecode.ui.lanterna.suggest;

import org.apache.commons.lang3.Strings;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.impl.session.ClearCommand;
import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.commands.impl.integration.PluginMarkdownCommand;
import com.claudecode.commands.impl.session.ResumeCommand;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.runtime.mcp.McpPromptPort;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.Skill.SkillSource;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression guard: typing an alias (e.g.
 */
class SuggestionControllerTest {

    private static SuggestionController controllerWithClear() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ClearCommand());
        return new SuggestionController(null, null, registry, null, null, null, 80);
    }

    @Test
    void aliasQuery_surfacesOwningCommand() {
        List<SuggestionPanel.Suggestion> results = controllerWithClear().buildCommandSuggestions("new");

        assertTrue(results.stream().anyMatch(s -> Strings.CS.equals(s.primary(), "/clear")),
            "typing alias \"new\" must surface /clear, mirroring TS aliasKey matching");
    }

    @Test
    void aliasMatch_isAnnotatedInDescription() {
        List<SuggestionPanel.Suggestion> results = controllerWithClear().buildCommandSuggestions("new");

        SuggestionPanel.Suggestion clear = results.stream()
            .filter(s -> Strings.CS.equals(s.primary(), "/clear"))
            .findFirst().orElseThrow();
        assertTrue(Strings.CS.contains(clear.description(), "(new)"),
            "alias match should be annotated, mirroring TS \"/clear (new)\" display");
    }

    @Test
    void directNameMatch_hasNoAliasAnnotation() {
        List<SuggestionPanel.Suggestion> results = controllerWithClear().buildCommandSuggestions("clear");

        SuggestionPanel.Suggestion clear = results.stream()
            .filter(s -> Strings.CS.equals(s.primary(), "/clear"))
            .findFirst().orElseThrow();
        assertFalse(Strings.CS.contains(clear.description(), "("),
            "matching the primary name directly shouldn't get an alias annotation");
    }

    @Test
    void prefixNameBeatsOtherCommandsAliasPrefix() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ClearCommand());   // aliases: reset, new
        registry.register(new ResumeCommand());  // name: resume
        SuggestionController controller = new SuggestionController(null, null, registry, null, null, null, 80);

        // "res" prefix-matches /resume's own name AND /clear's "reset" alias.
        // /resume must rank first — a command's direct name-prefix match

        // regardless of which matched string is shorter.
        List<SuggestionPanel.Suggestion> results = controller.buildCommandSuggestions("res");

        assertFalse(results.isEmpty());
        assertEquals("/resume", results.getFirst().primary(),
            "/resume (name-prefix) must rank above /clear (alias-prefix \"reset\"); got: " + results);
    }

    @Test
    void commandSuggestionsAreNotDiscardedAfterTheEighthMatch() {
        CommandRegistry registry = new CommandRegistry();
        for (int i = 0; i < 12; i++) {
            String name = "test-command-" + i;
            registry.register(new Command() {
                @Override public CommandMetadata metadata() {
                    return new CommandMetadata(name, "Test command", List.of());
                }
                @Override public CommandResult execute(CommandContext context, String args) {
                    return CommandResult.forQuery(args);
                }
            });
        }
        SuggestionController controller = new SuggestionController(
            null, null, registry, null, null, null, 80);

        assertEquals(12, controller.buildCommandSuggestions("").size());
        assertEquals(12, controller.buildCommandSuggestions("test-command").size());
    }

    @Test
    void pluginPromptUsesStaticHintOnlyForFirstSpaceThenProgressesArgumentNames() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new PluginMarkdownCommand(PluginCommandDefinition
            .builder("plugin:deploy", "deploy", "plugin")
            .description("Deploy").argumentHint("[legacy hint]")
            .argNames(List.of("environment", "region"))
            .hasUserSpecifiedDescription(true).build()));
        CapturingInputPanel input = new CapturingInputPanel();
        SuggestionController controller = controller(registry, input);

        controller.onQueryChange("/plugin:deploy ", "/plugin:deploy ".length());
        assertEquals("[legacy hint]", input.argumentHint,
            "static argumentHint has priority on the first trailing space");

        controller.onQueryChange("/plugin:deploy production ",
            "/plugin:deploy production ".length());
        assertEquals("[region]", input.argumentHint,
            "after one typed argument, prompt commands show only remaining argNames");

        controller.onQueryChange("/plugin:deploy production us-west ",
            "/plugin:deploy production us-west ".length());
        assertNull(input.argumentHint, "the hint clears after every named argument is filled");
    }

    @Test
    void mcpPromptUsesProgressiveArgumentNamesWithoutSynthesizingAStaticHint() {
            McpPromptPort.Definition info = new McpPromptPort.Definition(
                "mcp__srv__run", "srv", "run", "Run prompt", List.of(
                new McpPromptPort.Argument("query", true),
                new McpPromptPort.Argument("limit", false)));
            Command command = new McpPromptCommand(info, McpPromptPort.none());
            CommandRegistry registry = new CommandRegistry();
            registry.register(command);
            CapturingInputPanel input = new CapturingInputPanel();
            SuggestionController controller = controller(registry, input);

            String first = "/" + command.name() + " ";
            controller.onQueryChange(first, first.length());
            assertEquals("[query] [limit]", input.argumentHint);

            String second = "/" + command.name() + " search-text ";
            controller.onQueryChange(second, second.length());
            assertEquals("[limit]", input.argumentHint);
    }

    @Test
    void slashKeystrokeNeverWaitsForSkillMetadataIo() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new ClearCommand());
        CapturingInputPanel input = new CapturingInputPanel();
        CountDownLatch supplierStarted = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        SuggestionController controller = new SuggestionController(
            immediateGui(), input, registry, null, null, null, 80, () -> {
                supplierStarted.countDown();
                try {
                    releaseSupplier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return List.of(new Skill("slow-skill", "slow", List.of(), "body",
                    null, SkillSource.USER, null, null, null, null));
            });

        long startedAt = System.nanoTime();
        controller.onQueryChange("/", 1);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue(elapsedMs < 100,
            "GUI-thread slash handling waited " + elapsedMs + "ms for metadata");
        assertTrue(supplierStarted.await(1, TimeUnit.SECONDS));
        assertTrue(input.suggestions.stream()
            .anyMatch(s -> Strings.CS.equals(s.primary(), "/clear")),
            "built-ins remain immediately available while skills load");
        releaseSupplier.countDown();
    }

    @Test
    void bundledSkillCommandProjectionAppearsOnlyOnce() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("loop", "Run repeatedly", List.of("proactive"));
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.forQuery("loop " + args);
            }
        });
        CapturingInputPanel input = new CapturingInputPanel();
        SuggestionController controller = new SuggestionController(
            immediateGui(), input, registry, null, null, null, 80,
            () -> List.of(new Skill("loop", "Run repeatedly", List.of(), "body",
                null, SkillSource.BUNDLED, null, null, null,
                Map.of("commandProjection", true))));

        controller.onQueryChange("/loop", 5);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline
                && input.suggestions.stream()
                    .filter(item -> Strings.CS.equals("/loop", item.primary())).count() < 2) {
            Thread.sleep(10);
        }

        assertEquals(1, input.suggestions.stream()
            .filter(item -> Strings.CS.equals("/loop", item.primary())).count());
        controller.close();
    }

    @Test
    void projectedLoopUsesMenuDescriptionWithoutChangingCommandDescription() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("loop", "SDK command description", List.of());
            }
            @Override public String menuDescription() {
                return "Repeat a prompt or command on an interval (e.g. /loop 5m /foo)";
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.forQuery(args);
            }
        });
        SuggestionController controller = new SuggestionController(
            null, null, registry, null, null, null, 80);

        SuggestionPanel.Suggestion loop = controller.buildCommandSuggestions("loop")
            .stream().filter(item -> Strings.CS.equals("/loop", item.primary()))
            .findFirst().orElseThrow();

        assertEquals("Repeat a prompt or command on an interval (e.g. /loop 5m /foo)",
            loop.description());
        assertEquals("SDK command description", registry.find("loop").orElseThrow().description());
    }

    @Test
    void firstFileQueryPublishesMatchesAfterColdCacheRefresh() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        WindowBasedTextGUI gui = immediateGui();
        FileSuggestionService files = new FileSuggestionService(gui, input);
        SuggestionController controller = new SuggestionController(
            gui, input, registry, null, files, null, 80);

        controller.onQueryChange("@settings", 9);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline
                && input.suggestions.stream().noneMatch(item ->
                    Strings.CS.endsWith(item.primary(), "settings.gradle.kts"))) {
            Thread.sleep(10);
        }
        assertTrue(input.suggestions.stream().anyMatch(item ->
            Strings.CS.endsWith(item.primary(), "settings.gradle.kts")),
            "the first @ query must publish matches after its cold file index finishes");
        controller.close();
    }

    @Test
    void clearingAtQueryPreventsLateSuggestionsFromReopening() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        WindowBasedTextGUI gui = immediateGui();
        FileSuggestionService files = new FileSuggestionService(gui, input);
        SuggestionController controller = new SuggestionController(
            gui, input, registry, null, files, null, 80);

        controller.onQueryChange("@pom", 4);
        controller.onQueryChange("", 0);
        Thread.sleep(250);

        assertTrue(input.suggestions.isEmpty(),
            "a completed stale file search must not reopen the dropdown after backspace");
        controller.close();
    }

    @Test
    void ordinaryTypingDoesNotInvalidateTheFileSuggestionGeneration() {
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        FileSuggestionService files = new FileSuggestionService(immediateGui(), input);
        SuggestionController controller = new SuggestionController(
            immediateGui(), input, registry, null, files, null, 80);
        long before = files.currentGen();

        controller.onQueryChange("ordinary text", "ordinary text".length());

        assertEquals(before, files.currentGen(),
            "plain input must not pay the atomic cancellation path reserved for active @ queries");
        controller.close();
    }

    @Test
    void ordinaryTypingDoesNotMutateAnAlreadyIdleSuggestionPanel() {
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        SuggestionController controller = controller(registry, input);

        controller.onQueryChange("ordinary text", "ordinary text".length());

        assertEquals(0, input.hideCalls,
            "the plain-input hot path must return before touching already-hidden UI state");
        controller.close();
    }

    @Test
    void registryRevisionRebuildsSlashIndexWithoutWaitingForTheTtl() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        SuggestionController controller = controller(registry, input);

        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata("late-command", "Late command", List.of());
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return CommandResult.forQuery(args);
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline
                && controller.buildCommandSuggestions("late").isEmpty()) {
            Thread.sleep(10);
        }
        assertEquals("/late-command",
            controller.buildCommandSuggestions("late").getFirst().primary());
        controller.close();
    }

    @Test
    void registryRevisionUpdatesCommandIndexWhileMetadataWorkerIsBlocked() throws Exception {
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        CountDownLatch metadataStarted = new CountDownLatch(1);
        CountDownLatch releaseMetadata = new CountDownLatch(1);
        SuggestionController controller = new SuggestionController(
            immediateGui(), input, registry, null, null, null, 80, () -> {
                metadataStarted.countDown();
                try {
                    releaseMetadata.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                }
                return List.of();
            });

        try {
            assertTrue(metadataStarted.await(1, TimeUnit.SECONDS));
            registry.register(new Command() {
                @Override public CommandMetadata metadata() {
                    return new CommandMetadata(
                        "plugin:late-command", "Late plugin command", List.of());
                }
                @Override public CommandResult execute(CommandContext context, String args) {
                    return CommandResult.forQuery(args);
                }
            });

            assertEquals("/plugin:late-command",
                controller.buildCommandSuggestions("plugin:late").getFirst().primary(),
                "a published registry revision must not wait behind stale Skill metadata I/O");
        } finally {
            releaseMetadata.countDown();
            controller.close();
        }
    }

    @Test
    void bashPathTokenShowsDirectorySuggestions(@TempDir Path tempDir) throws Exception {
        Path downloads = Files.createDirectory(tempDir.resolve("Downloads"));
        CommandRegistry registry = new CommandRegistry();
        CapturingInputPanel input = new CapturingInputPanel();
        WindowBasedTextGUI gui = immediateGui();
        FileSuggestionService files = new FileSuggestionService(gui, input);
        SuggestionController controller = new SuggestionController(
            gui, input, registry, null, files, new DirectorySuggestionService(), 80);
        String query = "imgcat " + tempDir.resolve("Down");
        input.setRestoredText("!" + query);

        controller.onQueryChange(query, query.length());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline
                && input.suggestions.stream().noneMatch(item ->
                    Strings.CS.equals(downloads + "/", item.primary()))) {
            Thread.sleep(10);
        }
        assertTrue(input.suggestions.stream().anyMatch(item ->
                Strings.CS.equals(downloads + "/", item.primary())),
            "bash mode should complete the token after the final space without requiring @");
        controller.close();
    }

    private static SuggestionController controller(
            CommandRegistry registry, CapturingInputPanel input) {
        return new SuggestionController(immediateGui(), input, registry,
            null, null, null, 80);
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
            WindowBasedTextGUI.class.getClassLoader(),
            new Class<?>[]{WindowBasedTextGUI.class},
            (_, method, _) -> {
                if (Strings.CS.equals(method.getName(), "getGUIThread")) return thread;
                Class<?> type = method.getReturnType();
                if (!type.isPrimitive()) return null;
                if (type == boolean.class) return false;
                if (type == char.class) return '\0';
                return 0;
            });
    }

    private static final class CapturingInputPanel extends InputPanel {
        private String argumentHint;
        private volatile List<SuggestionPanel.Suggestion> suggestions = List.of();
        private int hideCalls;

        @Override public boolean isSuppressingSuggestions() { return false; }
        @Override public void hideSuggestions() { hideCalls++; suggestions = List.of(); }
        @Override public void setArgumentHint(String hint) { argumentHint = hint; }
        @Override public void showSuggestions(List<SuggestionPanel.Suggestion> items, int width) {
            suggestions = List.copyOf(items);
        }
        @Override public void showBashPathSuggestions(
                List<SuggestionPanel.Suggestion> items, int width) {
            suggestions = List.copyOf(items);
        }
        @Override public void showSuggestions(List<SuggestionPanel.Suggestion> items, int width, int commandColumnWidth) {
            suggestions = List.copyOf(items);
        }
    }
}
