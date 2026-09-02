package com.claudecode.ui.lanterna.features.settings;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.StatusProperty;
import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.commands.impl.info.StatusCommand;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.ui.lanterna.dialog.EffortSliderDialog;
import com.claudecode.ui.lanterna.dialog.CustomModelDialog;
import com.claudecode.ui.lanterna.dialog.ModelPickerDialog;
import com.claudecode.ui.lanterna.dialog.ThemePickerDialog;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicLong;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplTranscriptSink;
import com.claudecode.ui.lanterna.status.StatusDiagnostics;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Cohesive preferences feature for model, effort, theme, and the shared settings tabs.
 */
public final class PreferencesFeature implements ReplCommandUiBridge.Preferences {

    private final WindowBasedTextGUI gui;
    private final InputPanel inputPanel;
    private final QuerySession queryEngine;
    private final CommandRegistry commandRegistry;
    private final CommandContext commandContext;
    private final Consumer<String> themePreview;
    private final ReplTranscriptSink sink;
    private final EffortSliderDialog effortDialog;
    private final ModelPickerDialog modelDialog;
    private final CustomModelDialog customModelDialog;
    private final ThemePickerDialog themeDialog;
    private final SettingsTabContainer settingsDialog;
    private final DoctorPort doctor;
    private final CustomModelCatalog customModels;
    private final AtomicLong statusDiagnosticsGeneration = new AtomicLong();
    /** Latest immediate result for each setting changed during one open Config panel. */
    private final Map<String, String> immediateSettingOutputs = new LinkedHashMap<>();
    private final AtomicLong modelPickerGeneration = new AtomicLong();
    private final AtomicBoolean hotUiPreparationStarted = new AtomicBoolean();
    private final CompletableFuture<Void> hotUiReady = new CompletableFuture<>();
    private volatile Runnable effortChanged = () -> {};
    private volatile ModelPickerMetadata modelPickerMetadata;
    private volatile ModelPickerDialog.PreparedModelPicker warmedModelPicker;
    private volatile PreparedSettings warmedSettings;

    private record ModelPickerMetadata(String priorPersistedEffort,
                                       List<CustomModelConfig> customModels) {
        private ModelPickerMetadata {
            customModels = List.copyOf(customModels == null ? List.of() : customModels);
        }
    }

    private record PreparedSettings(
            Map<String, String> values,
            boolean hasAssistantMessage,
            String priorPersistedEffort,
            List<CustomModelConfig> customModels,
            ModelPickerDialog.PreparedModelPicker modelPicker) {
        private PreparedSettings {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            customModels = customModels == null ? null : List.copyOf(customModels);
        }
    }

    PreferencesFeature(CommandRegistry commandRegistry,
                       CommandContext commandContext,
                       ReplTranscriptSink sink) {
        this(null, null, null, commandRegistry, commandContext, null, _ -> {}, sink,
            null, null, null, null, null, null);
    }

    PreferencesFeature(CommandRegistry commandRegistry,
                       CommandContext commandContext,
                       ReplTranscriptSink sink,
                       CustomModelCatalog customModels) {
        this(null, null, null, commandRegistry, commandContext, null, _ -> {}, sink,
            null, new ModelPickerDialog(), null, null, null, customModels);
    }

    public PreferencesFeature(WindowBasedTextGUI gui,
                       InputPanel inputPanel,
                       IntSupplier terminalRowsSupplier,
                       QuerySession queryEngine,
                       CommandRegistry commandRegistry,
                       CommandContext commandContext,
                       DoctorPort doctor,
                       OutputStyleCatalog outputStyles,
                       Consumer<String> themePreview,
                       ReplTranscriptSink sink,
                       CustomModelCatalog customModels) {
        this(gui, inputPanel, queryEngine, commandRegistry, commandContext, doctor, themePreview, sink,
            new EffortSliderDialog(), new ModelPickerDialog(), new CustomModelDialog(),
            new ThemePickerDialog(),
            new SettingsTabContainer(terminalRowsSupplier, outputStyles), customModels);
    }

    private PreferencesFeature(WindowBasedTextGUI gui,
                               InputPanel inputPanel,
                               QuerySession queryEngine,
                               CommandRegistry commandRegistry,
                               CommandContext commandContext,
                               DoctorPort doctor,
                               Consumer<String> themePreview,
                               ReplTranscriptSink sink,
                               EffortSliderDialog effortDialog,
                               ModelPickerDialog modelDialog,
                               CustomModelDialog customModelDialog,
                               ThemePickerDialog themeDialog,
                               SettingsTabContainer settingsDialog,
                               CustomModelCatalog customModels) {
        this.gui = gui;
        this.inputPanel = inputPanel;
        this.queryEngine = queryEngine;
        this.commandRegistry = commandRegistry;
        this.commandContext = commandContext;
        this.doctor = doctor;
        this.themePreview = themePreview != null ? themePreview : _ -> {};
        this.sink = sink;
        this.effortDialog = effortDialog;
        this.modelDialog = modelDialog;
        this.customModelDialog = customModelDialog;
        this.themeDialog = themeDialog;
        this.settingsDialog = settingsDialog;
        this.customModels = customModels;
        if (commandContext != null) {
            var configuration = commandContext.application().settings().configuration();
            if (this.themeDialog != null) {
                this.themeDialog.setSyntaxHighlightingAccess(
                    configuration::syntaxHighlightingDisabled,
                    configuration::saveSyntaxHighlightingDisabled);
            }
            if (this.settingsDialog != null) {
                this.settingsDialog.setSyntaxHighlightingAccess(
                    configuration::syntaxHighlightingDisabled,
                    configuration::saveSyntaxHighlightingDisabled);
            }
        }
        Predicate<String> modelAllowed = commandContext != null
            ? commandContext.session().modelAllowed() : null;
        if (this.modelDialog != null) this.modelDialog.setModelAllowed(modelAllowed);
        if (this.modelDialog != null && customModels != null) {
            this.modelDialog.setCustomModelsSupplier(customModels::list);
            this.modelDialog.setCustomModelDeleteHandler(this::deleteCustomModel);
        }
        if (this.modelDialog != null && gui != null) {
            this.modelDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        }
        if (this.settingsDialog != null) this.settingsDialog.setModelAllowed(modelAllowed);
        if (this.settingsDialog != null && gui != null) {
            this.settingsDialog.setGuiInvoker(task -> gui.getGUIThread().invokeLater(task));
        }
        if (this.settingsDialog != null && commandRegistry != null && commandContext != null) {
            this.settingsDialog.setStatsTabRequest(() -> {
                statusDiagnosticsGeneration.incrementAndGet();
                suppressInput(false);
                commandRegistry.dispatch("/stats", commandContext);
            });
        }
        if (gui == null || modelDialog == null) hotUiReady.complete(null);
    }

    /** Starts all settings/model preparation after provider visibility is configured. */
    public CompletionStage<Void> startHotUiPreparation() {
        if (hotUiReady.isDone()) return hotUiReady;
        if (!hotUiPreparationStarted.compareAndSet(false, true)) return hotUiReady;
        Thread.ofVirtual().name("hot-ui-preparation").start(() -> {
            try {
                warmModelPicker();
                warmedSettings = loadPreparedSettings();
            } catch (RuntimeException _) {
                // Optional UI metadata degrades to the existing async open path.
            } finally {
                hotUiReady.complete(null);
            }
        });
        return hotUiReady;
    }

    public List<InlineOverlay> overlays() {
        return List.of(effortDialog, modelDialog, customModelDialog, themeDialog, settingsDialog);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        modelDialog.setKeybindingsStore(store);
        themeDialog.setKeybindingsStore(store);
        settingsDialog.setKeybindingsStore(store);
    }

    /** Applies provider-aware built-in model visibility to both model picker entry points. */
    public void setBuiltInModelFamiliesVisible(boolean visible) {
        modelDialog.setBuiltInFamiliesVisible(visible);
        settingsDialog.setBuiltInModelFamiliesVisible(visible);
        warmedModelPicker = null;
        warmedSettings = null;
    }

    /** Live HUD refresh hook after /effort changes the active session value. */
    public void setEffortChanged(Runnable effortChanged) {
        this.effortChanged = effortChanged != null ? effortChanged : () -> {};
    }

    public Component effortView() { return effortDialog; }
    public Component modelView() { return modelDialog; }
    public Component customModelView() { return customModelDialog; }
    public Component themeView() { return themeDialog; }
    public Component settingsView() { return settingsDialog; }

    /** Coalesces settings search bytes drained from one terminal poll. */
    public void beginInputBatch() {
        if (settingsDialog != null) settingsDialog.beginInputBatch();
    }

    /** Whether Settings currently owns terminal input. */
    public boolean isSettingsActive() {
        return settingsDialog != null && settingsDialog.isActive();
    }

    /** Publishes one final settings filter generation for the current poll. */
    public void endInputBatch() {
        if (settingsDialog != null) settingsDialog.endInputBatch();
    }

    @Override
    public void openEffort() {
        if (gui == null || effortDialog == null || queryEngine == null) return;
        String current = queryEngine.configuration().getConfig().effortValue();
        List<String> supported = EffortHelpers.supportedEffortLevels(
            queryEngine.configuration().getConfig().model());
        gui.getGUIThread().invokeLater(() -> {
            suppressInput(true);
            effortDialog.show(current, supported, level -> {
                suppressInput(false);
                handleEffortResult(level);
            });
        });
    }

    @Override
    public void openModel() {
        if (gui == null || modelDialog == null || queryEngine == null) return;
        String modelPreference = queryEngine.configuration().getConfig().modelPreference();
        String currentEffort = commandContext.session().effortValueSupplier() != null
            ? commandContext.session().effortValueSupplier().get() : null;
        long generation = modelPickerGeneration.incrementAndGet();
        ModelPickerMetadata cached = modelPickerMetadata;
        if (cached != null) {
            ModelPickerDialog.PreparedModelPicker prepared = warmedModelPicker;
            if (prepared == null
                    || !Objects.equals(prepared.modelPreference(), modelPreference)
                    || !Objects.equals(prepared.currentEffort(), currentEffort)) {
                prepared = prepareModelPicker(modelPreference, currentEffort);
            }
            ModelPickerDialog.PreparedModelPicker snapshot = prepared;
            runOnGuiThread(() -> showModelPicker(generation, snapshot));
            return;
        }
        Thread.ofVirtual().name("model-picker-load").start(() -> {
            try {
                refreshModelPickerMetadata();
                ModelPickerDialog.PreparedModelPicker prepared =
                    prepareModelPicker(modelPreference, currentEffort);
                gui.getGUIThread().invokeLater(() -> showModelPicker(generation, prepared));
            } catch (RuntimeException failure) {
                gui.getGUIThread().invokeLater(() -> {
                    if (modelPickerGeneration.get() != generation) return;
                    sink.line("  Could not load model picker: " + safeMessage(failure),
                        LanternaTheme.toolError());
                });
            }
        });
    }

    void refreshModelPickerMetadata() {
        String priorPersisted = UiSettings.readUserStringFromSettings("effortLevel");
        List<CustomModelConfig> customSnapshot = List.of();
        if (customModels != null) {
            try {
                customSnapshot = List.copyOf(customModels.list());
            } catch (RuntimeException _) {
                customSnapshot = List.of();
            }
        }
        modelPickerMetadata = new ModelPickerMetadata(priorPersisted, customSnapshot);
        warmedModelPicker = null;
        warmedSettings = null;
    }

    private void warmModelPicker() {
        refreshModelPickerMetadata();
        if (queryEngine == null || commandContext == null) return;
        String modelPreference = queryEngine.configuration().getConfig().modelPreference();
        String currentEffort = commandContext.session().effortValueSupplier() != null
            ? commandContext.session().effortValueSupplier().get() : null;
        warmedModelPicker = prepareModelPicker(modelPreference, currentEffort);
    }

    private PreparedSettings loadPreparedSettings() {
        if (commandContext == null) return null;
        Map<String, String> values = new LinkedHashMap<>(
            commandContext.application().settings().configuration().values(
                commandContext.session().workingDirectory()));
        String modelPreference = queryEngine != null
            ? queryEngine.configuration().getConfig().modelPreference() : null;
        values.put("model", ConfigPanel.modelPreferenceValue(modelPreference));
        boolean hasAssistantMessage = commandContext.session().messagesSupplier().get().stream()
            .anyMatch(AssistantMessage.class::isInstance);
        ModelPickerMetadata metadata = modelPickerMetadata;
        if (metadata == null) {
            refreshModelPickerMetadata();
            metadata = modelPickerMetadata;
        }
        ModelPickerDialog.PreparedModelPicker configModel = modelDialog.prepare(
            modelPreference, null, metadata.priorPersistedEffort(), metadata.customModels());
        return new PreparedSettings(values, hasAssistantMessage,
            metadata.priorPersistedEffort(), metadata.customModels(), configModel);
    }

    ModelPickerDialog.PreparedModelPicker prepareModelPicker(
            String modelPreference, String currentEffort) {
        ModelPickerMetadata cached = modelPickerMetadata;
        if (cached == null) {
            refreshModelPickerMetadata();
            cached = modelPickerMetadata;
        }
        return modelDialog.prepare(modelPreference, currentEffort,
            cached.priorPersistedEffort(), cached.customModels());
    }

    private void showModelPicker(long generation,
                                 ModelPickerDialog.PreparedModelPicker prepared) {
        if (modelPickerGeneration.get() != generation) return;
        modelDialog.show(prepared, result -> {
            modelPickerGeneration.incrementAndGet();
            handleModelResult(result);
        });
    }

    /** Avoid an extra event-loop turn when a slash command already runs on Lanterna's GUI thread. */
    private void runOnGuiThread(Runnable action) {
        if (gui.getGUIThread().getThread() == Thread.currentThread()) action.run();
        else gui.getGUIThread().invokeLater(action);
    }

    void handleModelResult(ModelPickerDialog.ModelPickResult result) {
        if (result == null) {
            sink.line("  Model picker dismissed", LanternaTheme.welcomeDim());
            return;
        }
        warmedSettings = null;
        if (result.addCustomModel()) {
            openCustomModelEditor();
            return;
        }
        boolean resetEffortToAuto = Strings.CS.equals("auto", result.effort());
        if (gui != null && !resetEffortToAuto
                && (StringUtils.isBlank(result.effort()))) {
// A plain model pick mutates session state in memory and queues its settings write
// asynchronously.
            CommandResult commandResult = commandContext.presentation().modelApplyFromDialog() != null
                ? commandContext.presentation().modelApplyFromDialog().apply(
                    commandContext, result.model(), null)
                : null;
            sink.breadcrumb("/model");
            appendResult(commandResult);
            return;
        }
        Runnable apply = () -> {
            if (StringUtils.isNotBlank(result.effort())) {
                commandRegistry.dispatch("/effort " + result.effort(), commandContext);
            }
            String appliedEffort = resetEffortToAuto ? null : result.effort();
            CommandResult commandResult = commandContext.presentation().modelApplyFromDialog() != null
                ? commandContext.presentation().modelApplyFromDialog().apply(
                    commandContext, result.model(), appliedEffort)
                : null;
            Runnable render = () -> {
                sink.breadcrumb("/model");
                appendResult(commandResult);
            };
            if (gui != null) gui.getGUIThread().invokeLater(render);
            else render.run();
        };
        if (gui != null) Thread.ofVirtual().name("model-picker-apply").start(apply);
        else apply.run();
    }

    private void openCustomModelEditor() {
        if (gui == null || customModelDialog == null || customModels == null) return;
        gui.getGUIThread().invokeLater(() -> {
            suppressInput(true);
            customModelDialog.show(result -> {
                suppressInput(false);
                if (result == null) {
                    sink.line("  Custom model setup cancelled", LanternaTheme.welcomeDim());
                } else {
                    handleCustomModelResult(result);
                }
            });
        });
    }

    void handleCustomModelResult(CustomModelConfig model) {
        if (model == null || customModels == null) return;
        Runnable save = () -> {
            try {
                customModels.save(model);
                refreshModelPickerMetadata();
                Runnable apply = () -> handleModelResult(
                    new ModelPickerDialog.ModelPickResult(model.modelName(), null));
                if (gui != null) gui.getGUIThread().invokeLater(apply);
                else apply.run();
            } catch (RuntimeException e) {
                Runnable render = () -> sink.line(
                    "  Could not save custom model: " + safeMessage(e),
                    LanternaTheme.toolError());
                if (gui != null) gui.getGUIThread().invokeLater(render);
                else render.run();
            }
        };
        if (gui != null) Thread.ofVirtual().name("custom-model-save").start(save);
        else save.run();
    }

    @Explanation("Adds confirmed deletion for Java-managed custom model endpoints")
    CompletionStage<Void> deleteCustomModel(String modelName) {
        if (customModels == null || StringUtils.isBlank(modelName)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Custom model name is required"));
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread.ofVirtual().name("custom-model-delete").start(() -> {
            try {
                customModels.remove(modelName);
                refreshModelPickerMetadata();
                if (queryEngine != null && Objects.equals(modelName,
                        queryEngine.configuration().getConfig().modelPreference())) {
                    if (commandContext != null
                            && commandContext.presentation().modelApplyFromDialog() != null) {
                        commandContext.presentation().modelApplyFromDialog().apply(
                            commandContext, null, null);
                    } else {
                        queryEngine.configuration().setModel(null);
                    }
                }
                completion.complete(null);
            } catch (RuntimeException failure) {
                completion.completeExceptionally(failure);
            }
        });
        return completion;
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return StringUtils.isBlank(message) ? "invalid configuration" : message;
    }

    @Override
    public void showEffortNotification(String value) {
        if (gui == null || inputPanel == null || queryEngine == null) return;
        effortChanged.run();
        String text = EffortHelpers.getEffortNotificationText(
            value, queryEngine.configuration().getConfig().model());
        if (text == null) return;
        gui.getGUIThread().invokeLater(() -> inputPanel.showTransientHint(text, 12_000));
    }

    void handleEffortResult(String level) {
        if (level == null) return;
        Runnable apply = () -> {
            CommandResult result = commandRegistry.dispatch("/effort " + level, commandContext);
            Runnable render = () -> {
                sink.breadcrumb("/effort");
                appendResult(result);
            };
            if (gui != null) gui.getGUIThread().invokeLater(render);
            else render.run();
        };
        if (gui != null) Thread.ofVirtual().name("effort-picker-apply").start(apply);
        else apply.run();
    }

    @Override
    public void openTheme(String current) {
        if (gui == null || themeDialog == null) return;
        gui.getGUIThread().invokeLater(() -> {
            suppressInput(true);
            themeDialog.show(current, themePreview, chosen -> {
                suppressInput(false);
                handleThemeResult(chosen);
            });
        });
    }

    void handleThemeResult(String name) {
        if (name == null) {
            sink.line("  Theme picker dismissed", LanternaTheme.welcomeDim());
            return;
        }
        Runnable apply = () -> {
            CommandResult result = commandContext.presentation().themeApplyFromDialog() != null
                ? commandContext.presentation().themeApplyFromDialog().apply(commandContext, name)
                : null;
            Runnable render = () -> {
                sink.breadcrumb("/theme");
                appendResult(result);
            };
            if (gui != null) gui.getGUIThread().invokeLater(render);
            else render.run();
        };
        if (gui != null) Thread.ofVirtual().name("theme-picker-apply").start(apply);
        else apply.run();
    }

    @Override public void openConfig() { openSettings(SettingsTabContainer.Tab.CONFIG); }
    @Override public void openStatus() { openSettings(SettingsTabContainer.Tab.STATUS); }
    @Override public void openUsage() { openSettings(SettingsTabContainer.Tab.USAGE); }

    private void openSettings(SettingsTabContainer.Tab defaultTab) {
        if (gui == null || settingsDialog == null) return;
        long generation = statusDiagnosticsGeneration.incrementAndGet();
        runOnGuiThread(() -> suppressInput(true));
        PreparedSettings cached = warmedSettings;
        if (cached != null) {
            runOnGuiThread(() -> showPreparedSettings(defaultTab, generation, cached));
            return;
        }
        Thread.ofVirtual().name("settings-dialog-load").start(() -> {
            PreparedSettings loaded;
            try {
                loaded = loadPreparedSettings();
            } catch (RuntimeException _) {
                gui.getGUIThread().invokeLater(() -> {
                    if (statusDiagnosticsGeneration.get() == generation) suppressInput(false);
                });
                return;
            }
            warmedSettings = loaded;
            gui.getGUIThread().invokeLater(() ->
                showPreparedSettings(defaultTab, generation, loaded));
        });
    }

    private void showPreparedSettings(
            SettingsTabContainer.Tab defaultTab, long generation,
            PreparedSettings prepared) {
        if (statusDiagnosticsGeneration.get() != generation || prepared == null) return;
        immediateSettingOutputs.clear();
        settingsDialog.setModelPickerMetadata(
            prepared.priorPersistedEffort(), prepared.customModels());
        settingsDialog.setPreparedModelPicker(prepared.modelPicker());
        settingsDialog.show(defaultTab, new LinkedHashMap<>(prepared.values()),
            prepared.hasAssistantMessage(),
            () -> List.of(new StatusProperty(null, "Loading status…")),
            themePreview,
            this::handleImmediateSetting,
            pending -> {
                suppressInput(false);
                handleSettingsClose(settingsDialog.selectedTab(), pending);
            });
        Thread.ofVirtual().name("settings-status-load").start(() ->
            loadStatusProperties(generation));
        Thread.ofVirtual().name("settings-diagnostics-load").start(() ->
            loadStatusDiagnostics(generation));
    }

    private void loadStatusProperties(long generation) {
        List<StatusProperty> properties;
        try {
            properties = StatusCommand.buildProperties(commandContext);
        } catch (RuntimeException _) {
            properties = List.of(new StatusProperty(null, "Unable to load status"));
        }
        List<StatusProperty> result = properties;
        gui.getGUIThread().invokeLater(() -> {
            if (statusDiagnosticsGeneration.get() == generation
                    && settingsDialog.isActive()) {
                settingsDialog.updateStatusProperties(result);
            }
        });
    }

    private void loadStatusDiagnostics(long generation) {
        if (doctor == null) return;
        List<String> diagnostics;
        try {
            diagnostics = StatusDiagnostics.from(doctor.collect());
        } catch (RuntimeException _) {
            diagnostics = List.of();
        }
        List<String> result = diagnostics;
        gui.getGUIThread().invokeLater(() -> {
            if (statusDiagnosticsGeneration.get() == generation
                    && settingsDialog.isActive()) {
                settingsDialog.updateStatusDiagnostics(result);
            }
        });
    }

    void handleSettingsResult(SettingsTabContainer.Tab closedTab,
                              Map<String, String> pending) {
        String tabName = switch (closedTab) {
            case STATUS -> "Status";
            case CONFIG -> "Config";
            case USAGE -> "Usage";
        };
        String slashCommand = "/" + tabName.toLowerCase(Locale.ROOT);
        if (pending == null) {
            sink.line("  " + tabName + " dialog dismissed", LanternaTheme.welcomeDim());
            return;
        }
        warmedSettings = null;
        Runnable apply = () -> {
            List<String> changes = new ArrayList<>();
            for (Map.Entry<String, String> entry : pending.entrySet()) {
                CommandResult result = Strings.CS.equals(entry.getKey(), "model")
                    ? commandRegistry.dispatch("/model " + entry.getValue(), commandContext)
                    : new ConfigCommand().applySetting(
                        commandContext, entry.getKey(), entry.getValue());
                if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
                    changes.add(result.output().strip());
                }
            }
            Runnable render = () -> {
                sink.breadcrumb(slashCommand);
                if (changes.isEmpty()) {
                    sink.line("  ⎿  No changes", LanternaTheme.welcomeDim());
                } else {
                    changes.forEach(change ->
                        sink.line("  ⎿  " + change, LanternaTheme.welcomeDim()));
                }
            };
            if (gui != null) gui.getGUIThread().invokeLater(render);
            else render.run();
        };
        if (gui != null) Thread.ofVirtual().name("settings-dialog-apply").start(apply);
        else apply.run();
    }

    /** Applies one released-197 Config edit at the moment the row changes. */
    void handleImmediateSetting(String key, String value) {
        CommandResult result = Strings.CS.equals(key, "model")
            ? commandRegistry.dispatch("/model " + value, commandContext)
            : new ConfigCommand().applySetting(commandContext, key, value);
        // The hot snapshot predates this synchronous write. Reusing it on the
        // next open would render the old value and could toggle the setting in
        // the wrong direction, most visibly when Config is closed and reopened
        // quickly on Windows terminals.
        warmedSettings = null;
        String output = result != null ? result.output() : null;
        if (StringUtils.isBlank(output)) immediateSettingOutputs.remove(key);
        else immediateSettingOutputs.put(key, output.strip());
    }

    /** Closes an immediate-apply settings session without writing the same values twice. */
    void handleSettingsClose(SettingsTabContainer.Tab closedTab,
                             Map<String, String> pending) {
        if (pending != null && !pending.isEmpty()) {
            sink.breadcrumb("/config");
            for (String key : pending.keySet()) {
                String output = immediateSettingOutputs.get(key);
                if (StringUtils.isNotBlank(output)) {
                    sink.line("  ⎿  " + output, LanternaTheme.welcomeDim());
                }
            }
            immediateSettingOutputs.clear();
            return;
        }
        immediateSettingOutputs.clear();
        String tabName = switch (closedTab) {
            case STATUS -> "Status";
            case CONFIG -> "Config";
            case USAGE -> "Settings";
        };
        sink.line("  " + tabName + " dialog dismissed", LanternaTheme.welcomeDim());
    }

    private void appendResult(CommandResult result) {
        if (result != null && result.output() != null && !StringUtils.isBlank(result.output())) {
            sink.line("  ⎿  " + result.output(), LanternaTheme.welcomeDim());
        }
    }

    private void suppressInput(boolean suppressed) {
        if (inputPanel != null) inputPanel.setSuppressed(suppressed);
    }
}
