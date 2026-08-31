package com.claudecode.ui.lanterna.features.settings;

import org.apache.commons.lang3.Strings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.commands.Command;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.metadata.CommandMetadata;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.runtime.query.DefaultQuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.ui.lanterna.dialog.ModelPickerDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextGUIThread;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.claudecode.runtime.settings.SettingsManagementPort;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;


class PreferencesFeatureTest {

    private static final StreamingClient NOOP_CLIENT = new StreamingClient() {
        @Override public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return List.<StreamingEvent>of().iterator();
        }
        @Override public String getModel() { return "model"; }
    };

    @Test
    void modelSelectionReusesEffortCommandAndModelApplyContract() {
        CapturingSink sink = new CapturingSink();
        AtomicReference<String> effortArg = new AtomicReference<>();
        AtomicReference<String> modelApply = new AtomicReference<>();
        CommandRegistry registry = registryWith("effort", args -> {
            effortArg.set(args);
            return CommandResult.of("effort=" + args);
        });
        CommandContext context = contextBuilder()
            .modelApplyFromDialog((_, model, effort) -> {
                modelApply.set(model + ":" + effort);
                return CommandResult.of("Set model to " + model);
            })
            .build();
        PreferencesFeature feature = new PreferencesFeature(registry, context, sink);

        feature.handleModelResult(new ModelPickerDialog.ModelPickResult("sonnet", "high"));

        assertEquals("high", effortArg.get());
        assertEquals("sonnet:high", modelApply.get());
        assertEquals(List.of("/model"), sink.breadcrumbs);
        assertTrue(sink.lines.stream().anyMatch(line -> Strings.CS.contains(line, "Set model to sonnet")));
    }

    @Test
    void modelSelectionAutoClearsInheritedEffortWithoutRenderingAutoAsALevel() {
        CapturingSink sink = new CapturingSink();
        AtomicReference<String> effortArg = new AtomicReference<>();
        AtomicReference<String> modelApply = new AtomicReference<>();
        CommandRegistry registry = registryWith("effort", args -> {
            effortArg.set(args);
            return CommandResult.of("effort=" + args);
        });
        CommandContext context = contextBuilder()
            .modelApplyFromDialog((_, model, effort) -> {
                modelApply.set(model + ":" + effort);
                return CommandResult.of("Set model to " + model);
            })
            .build();
        PreferencesFeature feature = new PreferencesFeature(registry, context, sink);

        feature.handleModelResult(new ModelPickerDialog.ModelPickResult("gateway-alias", "auto"));

        assertEquals("auto", effortArg.get());
        assertEquals("gateway-alias:null", modelApply.get());
    }

    @Test
    void dismissedModelAndThemeDoNotEmitCommandBreadcrumbs() {
        CapturingSink sink = new CapturingSink();
        PreferencesFeature feature = new PreferencesFeature(
            new CommandRegistry(), contextBuilder().build(), sink);

        feature.handleModelResult(null);
        feature.handleThemeResult(null);

        assertTrue(sink.breadcrumbs.isEmpty());
        assertEquals(List.of("  Model picker dismissed", "  Theme picker dismissed"), sink.lines);
    }

    @Test
    void effortAndThemeResultsReuseCommandContractsAndRenderTranscriptShape() {
        CapturingSink sink = new CapturingSink();
        AtomicReference<String> effortArg = new AtomicReference<>();
        AtomicReference<String> theme = new AtomicReference<>();
        CommandRegistry registry = registryWith("effort", args -> {
            effortArg.set(args);
            return CommandResult.of("Set effort " + args);
        });
        CommandContext context = contextBuilder()
            .themeApplyFromDialog((_, name) -> {
                theme.set(name);
                return CommandResult.of("Theme set to " + name);
            })
            .build();
        PreferencesFeature feature = new PreferencesFeature(registry, context, sink);

        feature.handleEffortResult("medium");
        feature.handleThemeResult("dark");

        assertEquals("medium", effortArg.get());
        assertEquals("dark", theme.get());
        assertEquals(List.of("/effort", "/theme"), sink.breadcrumbs);
        assertTrue(sink.lines.contains("  ⎿  Set effort medium"));
        assertTrue(sink.lines.contains("  ⎿  Theme set to dark"));
    }

    @Test
    void settingsResultUsesClosedTabAndRoutesModelThroughRegistry() {
        CapturingSink sink = new CapturingSink();
        AtomicReference<String> modelArg = new AtomicReference<>();
        CommandRegistry registry = registryWith("model", args -> {
            modelArg.set(args);
            return CommandResult.of("Set model to " + args);
        });
        PreferencesFeature feature = new PreferencesFeature(
            registry, contextBuilder().build(), sink);
        Map<String, String> pending = new LinkedHashMap<>();
        pending.put("model", "opus");

        feature.handleSettingsResult(SettingsTabContainer.Tab.CONFIG, pending);

        assertEquals("opus", modelArg.get());
        assertEquals(List.of("/config"), sink.breadcrumbs);
        assertEquals(List.of("  ⎿  Set model to opus"), sink.lines);
    }

    @Test
    void released197ImmediateConfigApplyIsNotRepeatedWhenTheDialogCloses() {
        CapturingSink sink = new CapturingSink();
        AtomicInteger modelCalls = new AtomicInteger();
        CommandRegistry registry = registryWith("model", args -> {
            modelCalls.incrementAndGet();
            return CommandResult.of("Set model to " + args);
        });
        PreferencesFeature feature = new PreferencesFeature(
            registry, contextBuilder().build(), sink);

        feature.handleImmediateSetting("model", "opus");

        assertEquals(1, modelCalls.get());
        assertTrue(sink.lines.isEmpty());
        feature.handleSettingsClose(SettingsTabContainer.Tab.CONFIG,
            Map.of("model", "opus"));

        assertEquals(1, modelCalls.get());
        assertEquals(List.of("/config"), sink.breadcrumbs);
        assertEquals(List.of("  ⎿  Set model to opus"), sink.lines);
    }

    @Test
    void immediateConfigApplyInvalidatesThePreparedSettingsSnapshot() throws Exception {
        AtomicReference<String> persisted = new AtomicReference<>("true");
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch reloaded = new CountDownLatch(1);
        SettingsManagementPort fallback = SettingsManagementPort.none();
        SettingsManagementPort.Configuration configuration =
            new SettingsManagementPort.Configuration() {
                @Override public Map<String, String> values(String workingDirectory) {
                    if (loads.incrementAndGet() > 1) reloaded.countDown();
                    return Map.of("leftArrowOpensAgents", persisted.get());
                }
                @Override public void save(String workingDirectory, String key, String value) {
                    persisted.set(value);
                }
                @Override public boolean syntaxHighlightingDisabled() { return false; }
                @Override public void saveSyntaxHighlightingDisabled(boolean disabled) { }
            };
        SettingsManagementPort settings = new SettingsManagementPort() {
            @Override public Configuration configuration() { return configuration; }
            @Override public Preferences preferences() { return fallback.preferences(); }
            @Override public Sandbox sandbox() { return fallback.sandbox(); }
        };
        var query = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .modelPreference("sonnet")
            .build());
        CommandContext context = contextBuilder().settingsManagement(settings).build();
        PreferencesFeature feature = new PreferencesFeature(
            immediateGui(), new InputPanel(), () -> 40, query,
            new CommandRegistry(), context, null, OutputStyleCatalog.builtIns(),
            _ -> {}, new CapturingSink(), null);
        feature.startHotUiPreparation().toCompletableFuture().join();

        feature.handleImmediateSetting("leftArrowOpensAgents", "false");
        feature.openConfig();

        assertEquals("false", persisted.get());
        assertTrue(reloaded.await(2, TimeUnit.SECONDS),
            "reopening Config must reload values changed after warm-up");
        assertEquals(2, loads.get());
    }

    @Test
    void savingCustomModelPersistsAndSelectsIt() {
        CapturingSink sink = new CapturingSink();
        InMemoryCatalog catalog = new InMemoryCatalog();
        AtomicReference<String> modelApply = new AtomicReference<>();
        CommandContext context = contextBuilder()
            .modelApplyFromDialog((_, model, _) -> {
                modelApply.set(model);
                return CommandResult.of("Set model to " + model);
            }).build();
        PreferencesFeature feature = new PreferencesFeature(
            new CommandRegistry(), context, sink, catalog);
        CustomModelConfig model = new CustomModelConfig(
            "gpt-custom", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of("X-Tenant", "demo"));

        feature.handleCustomModelResult(model);

        assertEquals(model, catalog.find("gpt-custom").orElseThrow());
        assertEquals("gpt-custom", modelApply.get());
        assertTrue(sink.lines.stream().noneMatch(line -> Strings.CS.contains(line, "secret")));
    }

    @Test
    void deletingCurrentCustomModelRemovesItAndSwitchesToDefault() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.save(new CustomModelConfig(
            "current-custom", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of()));
        var query = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .modelPreference("current-custom")
            .build());
        AtomicReference<String> applied = new AtomicReference<>("not-called");
        CommandContext context = contextBuilder()
            .modelApplyFromDialog((_, model, _) -> {
                applied.set(model);
                query.setModel(model);
                return CommandResult.of("Set model to default");
            }).build();
        PreferencesFeature feature = new PreferencesFeature(
            immediateGui(), new InputPanel(), () -> 40, query,
            new CommandRegistry(), context, null, OutputStyleCatalog.builtIns(),
            _ -> {}, new CapturingSink(), catalog);

        feature.deleteCustomModel("current-custom").toCompletableFuture().join();

        assertTrue(catalog.find("current-custom").isEmpty());
        assertNull(applied.get());
        assertNull(query.configuration().getConfig().modelPreference());
        assertTrue(feature.prepareModelPicker(null, null).customModelNames().isEmpty());
    }

    @Test
    void deletingNonCurrentCustomModelLeavesTheSessionModelUnchanged() {
        InMemoryCatalog catalog = new InMemoryCatalog();
        catalog.save(new CustomModelConfig(
            "unused-custom", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", "secret", Map.of()));
        var query = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .modelPreference("sonnet")
            .build());
        AtomicReference<String> applied = new AtomicReference<>("not-called");
        CommandContext context = contextBuilder()
            .modelApplyFromDialog((_, model, _) -> {
                applied.set(model);
                return CommandResult.of("unexpected");
            }).build();
        PreferencesFeature feature = new PreferencesFeature(
            immediateGui(), new InputPanel(), () -> 40, query,
            new CommandRegistry(), context, null, OutputStyleCatalog.builtIns(),
            _ -> {}, new CapturingSink(), catalog);

        feature.deleteCustomModel("unused-custom").toCompletableFuture().join();

        assertTrue(catalog.find("unused-custom").isEmpty());
        assertEquals("not-called", applied.get());
        assertEquals("sonnet", query.configuration().getConfig().modelPreference());
    }

    @Test
    void modelPickerMetadataSnapshotDoesNotReReadCustomModelFileOnEveryOpen() {
        CapturingSink sink = new CapturingSink();
        CountingCatalog catalog = new CountingCatalog();
        PreferencesFeature feature = new PreferencesFeature(
            new CommandRegistry(), contextBuilder().build(), sink, catalog);

        feature.refreshModelPickerMetadata();
        feature.prepareModelPicker("claude-sonnet-4-6", null);
        feature.prepareModelPicker("claude-opus-4-8", null);

        assertEquals(1, catalog.listCalls,
            "opening the picker should reuse its background metadata snapshot");
    }

    @Test
    void openingModelPickerKeepsPromptVisible() {
        InputPanel input = new InputPanel();
        int promptRows = input.getPreferredSize().getRows();
        var query = new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(NOOP_CLIENT)
            .modelPreference("sonnet")
            .build());
        PreferencesFeature feature = new PreferencesFeature(
            immediateGui(), input, () -> 40, query,
            new CommandRegistry(), contextBuilder().build(), null,
            OutputStyleCatalog.builtIns(), _ -> {}, new CapturingSink(), null);
        feature.refreshModelPickerMetadata();

        feature.openModel();

        assertTrue(((ModelPickerDialog) feature.modelView()).isActive());
        assertEquals(promptRows, input.getPreferredSize().getRows(),
            "197 mounts the immediate model picker with shouldHidePromptInput=false");
    }

    static CommandContext.Builder contextBuilder() {
        return CommandContext.builder(
            "model", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0, ".", false);
    }

    static CommandRegistry registryWith(
            String name, Function<String, CommandResult> execute) {
        CommandRegistry registry = new CommandRegistry();
        registry.register(new Command() {
            @Override public CommandMetadata metadata() {
                return new CommandMetadata(name, name);
            }
            @Override public CommandResult execute(CommandContext context, String args) {
                return execute.apply(args);
            }
        });
        return registry;
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

    static final class CapturingSink implements ReplTranscriptSink {
        final List<String> breadcrumbs = new ArrayList<>();
        final List<String> lines = new ArrayList<>();

        @Override public void system(String text) { lines.add(text); }
        @Override public void breadcrumb(String commandLabel) { breadcrumbs.add(commandLabel); }
        @Override public void line(String text, TextColor color) { lines.add(text); }
    }

    static final class InMemoryCatalog implements CustomModelCatalog {
        private final Map<String, CustomModelConfig> models = new LinkedHashMap<>();
        @Override public List<CustomModelConfig> list() { return List.copyOf(models.values()); }
        @Override public Optional<CustomModelConfig> find(String name) { return Optional.ofNullable(models.get(name)); }
        @Override public void save(CustomModelConfig model) { models.put(model.modelName(), model); }
        @Override public boolean remove(String name) { return models.remove(name) != null; }
    }

    static final class CountingCatalog implements CustomModelCatalog {
        int listCalls;
        @Override public List<CustomModelConfig> list() {
            listCalls++;
            return List.of();
        }
        @Override public Optional<CustomModelConfig> find(String name) { return Optional.empty(); }
        @Override public void save(CustomModelConfig model) { }
        @Override public boolean remove(String name) { return false; }
    }
}
