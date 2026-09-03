package com.claudecode.commands;

import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.dream.DreamPort;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.commands.insights.InsightsPort;
import com.claudecode.commands.plugins.PluginRuntimePort;
import com.claudecode.commands.permissions.PermissionCommandPort;
import com.claudecode.commands.session.SessionCommandPort;
import com.claudecode.commands.tooling.ToolingCommandPorts;
import com.claudecode.commands.prompt.PromptShellExecutor;
import com.claudecode.commands.session.ResumeRequest;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MessageCompactor;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.settings.SettingsManagementPort;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;


public record CommandContext(
    CommandSessionState session,
    CommandApplicationPorts application,
    CommandPresentationPorts presentation
) {
    @FunctionalInterface
    public interface ModelApplyFromDialog {
        CommandResult apply(CommandContext context, String model, String effort);
    }

    @FunctionalInterface
    public interface AddDirApply {
        CommandResult apply(CommandContext context, String absolutePath, boolean remember);
    }

    @FunctionalInterface
    public interface CopyPickerLauncher {
        void launch(String fullText, List<CopyCommand.CodeBlock> codeBlocks, boolean skipPicker);
    }

    @FunctionalInterface
    public interface CopyApplyFromDialog {
        String apply(String text, String filename, boolean saveAlwaysPreference,
                     boolean writeOnly);
    }

    public record TagRemovalRequest(String tagName, Supplier<CommandResult> confirm,
                                    Supplier<CommandResult> cancel) { }

    public record PokemonHatchRequest(PokemonProfile current,
                                      Supplier<CommandResult> confirm,
                                      Supplier<CommandResult> cancel) { }

    public record AddDirValidationOutcome(String resolvedPath, String errorMessage) {
        public boolean isValid() { return errorMessage == null; }
    }

    public static Builder builder(
            String model,
            Supplier<List<Message>> messagesSupplier,
            Runnable clearMessages,
            Consumer<String> setModel,
            Supplier<Usage> usageSupplier,
            ToDoubleFunction<Usage> costCalculator,
            String workingDirectory,
            boolean remoteMode) {
        return new Builder(model, messagesSupplier, clearMessages, setModel,
            usageSupplier, costCalculator, workingDirectory, remoteMode);
    }

    public static final class Builder {
        private final String model;
        private Supplier<String> modelSupplier;
        private final Supplier<List<Message>> messagesSupplier;
        private final Runnable clearMessages;
        private final Consumer<String> setModel;
        private Consumer<List<Message>> loadMessages;
        private Consumer<List<Message>> loadCompactedMessages;
        private Consumer<Message> transcriptRecorder;
        private Supplier<String> currentSessionId;
        private final Supplier<Usage> usageSupplier;
        private final ToDoubleFunction<Usage> costCalculator;
        private Supplier<String> workingDirectory;
        private final boolean remoteMode;
        private PermissionCommandPort permissionCommands = PermissionCommandPort.none();
        private SessionCommandPort sessionCommands = SessionCommandPort.none();
        private ToolingCommandPorts toolingCommands = ToolingCommandPorts.none();
        private Function<String, String> sideQuestionRunner;
        private Supplier<MessageCompactor> compactService;
        private Consumer<String> btwDialogLauncher;
        private Consumer<String> sessionColorSetter;
        private Consumer<PokemonProfile> pokemonSetter;
        private Consumer<PokemonProfile> pokemonStatusPresenter;
        private Consumer<PokemonHatchRequest> pokemonHatchLauncher;
        private Consumer<String> effortValueSetter;
        private Supplier<String> effortValueSupplier;
        private Runnable effortDialogLauncher;
        private Consumer<String> exportDialogLauncher;
        private Runnable hooksDialogLauncher;
        private Runnable sandboxDialogLauncher;
        private PromptShellExecutor promptShellExecutor;
        private Function<List<Message>, String> titleGenerator;
        private Runnable postCompactCallback;
        private Runnable postCompactTranscriptCallback;
        private Runnable openMessageSelector;
        private HookDispatcher hookDispatcher;
        private Consumer<Message> messageAppender;
        private Supplier<String> goalGate;
        private Runnable goalDialogLauncher;
        private Consumer<CompactProgressEvent> onCompactProgress;
        private Supplier<Boolean> verboseSupplier;
        private Runnable memoryDialogLauncher;
        private Consumer<Path> openEditor;
        private Runnable doctorDialogLauncher;
        private DoctorPort doctor;
        private DreamPort dream;
        private Supplier<String> apiBaseUrlSupplier;
        private Supplier<List<StatusProperty>> statusRuntimePropertiesSupplier;
        private ConfigLiveSetters configLiveSetters;
        private Consumer<String> themeDialogLauncher;
        private Runnable configDialogLauncher;
        private BiFunction<CommandContext, String, CommandResult> themeApplyFromDialog;
        private Runnable statusDialogLauncher;
        private Runnable usageDialogLauncher;
        private Runnable modelDialogLauncher;
        private ModelApplyFromDialog modelApplyFromDialog;
        private Function<String, String> modelValidator;
        private Predicate<String> modelAllowed;
        private Consumer<String> addDirDialogLauncher;
        private Function<String, AddDirValidationOutcome> addDirValidator;
        private AddDirApply addDirApply;
        private Supplier<String> mcpStatusSupplier;
        private Runnable permissionsDialogLauncher;
        private Runnable agentsDialogLauncher;
        private Consumer<ResumeRequest> resumeLauncher;
        private Consumer<String> sessionIdSwitcher;
        private Runnable resetSessionCost;
        private Supplier<ContextData> contextDataCollector;
        private Runnable contextVisualizerLauncher;
        private CopyPickerLauncher copyPickerLauncher;
        private CopyApplyFromDialog copyApplyFromDialog;
        private Runnable diffDialogLauncher;
        private Runnable helpDialogLauncher;
        private Runnable skillsDialogLauncher;
        private Consumer<String> pluginDialogLauncher;
        private PluginRuntimePort pluginRuntime;
        private Runnable tasksDialogLauncher;
        private Runnable workflowsDialogLauncher;
        private Runnable statsDialogLauncher;
        private Supplier<InsightsPort> insightsPipeline;
        private SettingsManagementPort settingsManagement = SettingsManagementPort.none();
        private McpManagementPort mcpManagement = McpManagementPort.none();
        private Consumer<TagRemovalRequest> tagRemovalLauncher;
        private boolean nonInteractive;

        private Builder(String model, Supplier<List<Message>> messagesSupplier,
                Runnable clearMessages, Consumer<String> setModel,
                Supplier<Usage> usageSupplier, ToDoubleFunction<Usage> costCalculator,
                String workingDirectory, boolean remoteMode) {
            this.model = model;
            this.messagesSupplier = messagesSupplier;
            this.clearMessages = clearMessages;
            this.setModel = setModel;
            this.usageSupplier = usageSupplier;
            this.costCalculator = costCalculator;
            this.workingDirectory = () -> workingDirectory;
            this.remoteMode = remoteMode;
        }

        /**
         * Replaces the fixed directory given to {@link #builder} with a live one, so commands
         * keep resolving against the session's current project after a Bash {@code cd} or a
         * cross-project resume rather than against wherever the process was launched.
         */
        public Builder workingDirectorySupplier(Supplier<String> v) {
            if (v != null) workingDirectory = v;
            return this;
        }

        public Builder modelSupplier(Supplier<String> v) { modelSupplier = v; return this; }
        public Builder loadMessages(Consumer<List<Message>> v) { loadMessages = v; return this; }
        public Builder loadCompactedMessages(Consumer<List<Message>> v) {
            loadCompactedMessages = v;
            return this;
        }
        public Builder transcriptRecorder(Consumer<Message> v) { transcriptRecorder = v; return this; }
        public Builder currentSessionId(Supplier<String> v) { currentSessionId = v; return this; }
        public Builder permissionCommands(PermissionCommandPort v) {
            permissionCommands = v == null ? PermissionCommandPort.none() : v;
            return this;
        }
        public Builder sessionCommands(SessionCommandPort v) {
            sessionCommands = v == null ? SessionCommandPort.none() : v;
            return this;
        }
        public Builder toolingCommands(ToolingCommandPorts v) {
            toolingCommands = v == null ? ToolingCommandPorts.none() : v;
            return this;
        }
        public Builder sideQuestionRunner(Function<String, String> v) { sideQuestionRunner = v; return this; }
        public Builder compactService(Supplier<MessageCompactor> v) { compactService = v; return this; }
        public Builder btwDialogLauncher(Consumer<String> v) { btwDialogLauncher = v; return this; }
        public Builder sessionColorSetter(Consumer<String> v) { sessionColorSetter = v; return this; }
        public Builder pokemonSetter(Consumer<PokemonProfile> v) { pokemonSetter = v; return this; }
        public Builder pokemonStatusPresenter(Consumer<PokemonProfile> v) { pokemonStatusPresenter = v; return this; }
        public Builder pokemonHatchLauncher(Consumer<PokemonHatchRequest> v) { pokemonHatchLauncher = v; return this; }
        public Builder effortValueSetter(Consumer<String> v) { effortValueSetter = v; return this; }
        public Builder effortValueSupplier(Supplier<String> v) { effortValueSupplier = v; return this; }
        public Builder effortDialogLauncher(Runnable v) { effortDialogLauncher = v; return this; }
        public Builder exportDialogLauncher(Consumer<String> v) { exportDialogLauncher = v; return this; }
        public Builder hooksDialogLauncher(Runnable v) { hooksDialogLauncher = v; return this; }
        public Builder sandboxDialogLauncher(Runnable v) { sandboxDialogLauncher = v; return this; }
        public Builder promptShellExecutor(PromptShellExecutor v) { promptShellExecutor = v; return this; }
        public Builder titleGenerator(Function<List<Message>, String> v) { titleGenerator = v; return this; }
        public Builder postCompactCallback(Runnable v) { postCompactCallback = v; return this; }
        public Builder postCompactTranscriptCallback(Runnable v) { postCompactTranscriptCallback = v; return this; }
        public Builder openMessageSelector(Runnable v) { openMessageSelector = v; return this; }
        public Builder hookDispatcher(HookDispatcher v) { hookDispatcher = v; return this; }
        public Builder messageAppender(Consumer<Message> v) { messageAppender = v; return this; }
        public Builder goalGate(Supplier<String> v) { goalGate = v; return this; }
        public Builder goalDialogLauncher(Runnable v) { goalDialogLauncher = v; return this; }
        public Builder onCompactProgress(Consumer<CompactProgressEvent> v) { onCompactProgress = v; return this; }
        public Builder verboseSupplier(Supplier<Boolean> v) { verboseSupplier = v; return this; }
        public Builder memoryDialogLauncher(Runnable v) { memoryDialogLauncher = v; return this; }
        public Builder openEditor(Consumer<Path> v) { openEditor = v; return this; }
        public Builder doctorDialogLauncher(Runnable v) { doctorDialogLauncher = v; return this; }
        public Builder doctor(DoctorPort v) { doctor = v; return this; }
        public Builder dream(DreamPort v) { dream = v; return this; }
        public Builder apiBaseUrlSupplier(Supplier<String> v) { apiBaseUrlSupplier = v; return this; }
        public Builder statusRuntimePropertiesSupplier(Supplier<List<StatusProperty>> v) { statusRuntimePropertiesSupplier = v; return this; }
        public Builder configLiveSetters(ConfigLiveSetters v) { configLiveSetters = v; return this; }
        public Builder themeDialogLauncher(Consumer<String> v) { themeDialogLauncher = v; return this; }
        public Builder configDialogLauncher(Runnable v) { configDialogLauncher = v; return this; }
        public Builder themeApplyFromDialog(BiFunction<CommandContext, String, CommandResult> v) { themeApplyFromDialog = v; return this; }
        public Builder statusDialogLauncher(Runnable v) { statusDialogLauncher = v; return this; }
        public Builder usageDialogLauncher(Runnable v) { usageDialogLauncher = v; return this; }
        public Builder modelDialogLauncher(Runnable v) { modelDialogLauncher = v; return this; }
        public Builder modelApplyFromDialog(ModelApplyFromDialog v) { modelApplyFromDialog = v; return this; }
        public Builder modelValidator(Function<String, String> v) { modelValidator = v; return this; }
        public Builder modelAllowed(Predicate<String> v) { modelAllowed = v; return this; }
        public Builder addDirDialogLauncher(Consumer<String> v) { addDirDialogLauncher = v; return this; }
        public Builder addDirValidator(Function<String, AddDirValidationOutcome> v) { addDirValidator = v; return this; }
        public Builder addDirApply(AddDirApply v) { addDirApply = v; return this; }
        public Builder mcpStatusSupplier(Supplier<String> v) { mcpStatusSupplier = v; return this; }
        public Builder permissionsDialogLauncher(Runnable v) { permissionsDialogLauncher = v; return this; }
        public Builder agentsDialogLauncher(Runnable v) { agentsDialogLauncher = v; return this; }
        public Builder resumeLauncher(Consumer<ResumeRequest> v) { resumeLauncher = v; return this; }
        public Builder sessionIdSwitcher(Consumer<String> v) { sessionIdSwitcher = v; return this; }
        public Builder resetSessionCost(Runnable v) { resetSessionCost = v; return this; }
        public Builder contextDataCollector(Supplier<ContextData> v) { contextDataCollector = v; return this; }
        public Builder contextVisualizerLauncher(Runnable v) { contextVisualizerLauncher = v; return this; }
        public Builder copyPickerLauncher(CopyPickerLauncher v) { copyPickerLauncher = v; return this; }
        public Builder copyApplyFromDialog(CopyApplyFromDialog v) { copyApplyFromDialog = v; return this; }
        public Builder diffDialogLauncher(Runnable v) { diffDialogLauncher = v; return this; }
        public Builder helpDialogLauncher(Runnable v) { helpDialogLauncher = v; return this; }
        public Builder skillsDialogLauncher(Runnable v) { skillsDialogLauncher = v; return this; }
        public Builder pluginDialogLauncher(Consumer<String> v) { pluginDialogLauncher = v; return this; }
        public Builder pluginRuntime(PluginRuntimePort v) { pluginRuntime = v; return this; }
        public Builder tasksDialogLauncher(Runnable v) { tasksDialogLauncher = v; return this; }
        public Builder workflowsDialogLauncher(Runnable v) { workflowsDialogLauncher = v; return this; }
        public Builder statsDialogLauncher(Runnable v) { statsDialogLauncher = v; return this; }
        public Builder insightsPipeline(Supplier<InsightsPort> v) { insightsPipeline = v; return this; }
        public Builder settingsManagement(SettingsManagementPort v) { settingsManagement = v == null ? SettingsManagementPort.none() : v; return this; }
        public Builder mcpManagement(McpManagementPort v) { mcpManagement = v == null ? McpManagementPort.none() : v; return this; }
        public Builder tagRemovalLauncher(Consumer<TagRemovalRequest> v) { tagRemovalLauncher = v; return this; }
        public Builder nonInteractive(boolean v) { nonInteractive = v; return this; }

        public CommandContext build() {
            CommandSessionState session = new CommandSessionState(
                model, modelSupplier, messagesSupplier, clearMessages, setModel,
                usageSupplier, costCalculator, workingDirectory, remoteMode,
                loadMessages, loadCompactedMessages, transcriptRecorder, currentSessionId,
                sideQuestionRunner, compactService, sessionColorSetter, pokemonSetter,
                effortValueSetter, effortValueSupplier, titleGenerator, postCompactCallback,
                postCompactTranscriptCallback, hookDispatcher, messageAppender, goalGate,
                onCompactProgress, verboseSupplier, apiBaseUrlSupplier,
                statusRuntimePropertiesSupplier, configLiveSetters, modelValidator,
                modelAllowed, resumeLauncher, sessionIdSwitcher, resetSessionCost,
                contextDataCollector, mcpStatusSupplier, promptShellExecutor, nonInteractive);
            CommandApplicationPorts application = new CommandApplicationPorts(
                doctor, dream, pluginRuntime, insightsPipeline, settingsManagement,
                mcpManagement, permissionCommands, sessionCommands, toolingCommands);
            CommandPresentationPorts presentation = new CommandPresentationPorts(
                btwDialogLauncher, pokemonStatusPresenter, pokemonHatchLauncher,
                effortDialogLauncher, exportDialogLauncher, hooksDialogLauncher,
                sandboxDialogLauncher, openMessageSelector, goalDialogLauncher,
                memoryDialogLauncher, openEditor, doctorDialogLauncher,
                themeDialogLauncher, configDialogLauncher, themeApplyFromDialog,
                statusDialogLauncher, usageDialogLauncher, modelDialogLauncher,
                modelApplyFromDialog, addDirDialogLauncher, addDirValidator, addDirApply,
                permissionsDialogLauncher, agentsDialogLauncher, contextVisualizerLauncher,
                copyPickerLauncher, copyApplyFromDialog, diffDialogLauncher,
                helpDialogLauncher, skillsDialogLauncher, pluginDialogLauncher,
                tasksDialogLauncher, workflowsDialogLauncher, statsDialogLauncher,
                tagRemovalLauncher);
            return new CommandContext(session, application, presentation);
        }
    }

    public static CommandContext minimal() {
        return builder("claude-sonnet-4-20250514", List::of, () -> { }, _ -> { },
            () -> Usage.EMPTY, _ -> 0.0, System.getProperty("user.dir"), false).build();
    }
}
