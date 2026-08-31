package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelCatalog;
import com.claudecode.core.model.ModelNames;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.core.text.StringUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline model-picker dialog — sits above {@link InputPanel} in the SmartLayout stack, occupying
 * zero rows when idle.
 */
public final class ModelPickerDialog extends Panel implements InlineOverlay {

    private enum ViewMode { LIST, DELETE_CONFIRM }

    /** Effort metadata resolved with the option snapshot, away from the GUI thread. */
    private record EffortOptionMetadata(
        String focusModel,
        boolean supportsEffort,
        EffortHelpers.EffortCapabilities capabilities,
        List<String> supportedLevels,
        String defaultLevel
    ) {}

    /** A pickable option. {@code value == null} means "Default (recommended)". */
    record ModelOption(String value, String label, String description,
                       EffortOptionMetadata effortMetadata) {
        ModelOption(String value, String label, String description) {
            this(value, label, description, null);
        }
    }

    /** Confirm result: model, optional effort, or the add-model action. */
    public record ModelPickResult(String model, String effort, boolean addCustomModel) {
        public ModelPickResult(String model, String effort) {
            this(model, effort, false);
        }

        static ModelPickResult addCustomModelAction() {
            return new ModelPickResult(null, null, true);
        }
    }

    /** Immutable picker data assembled away from the GUI thread. */
    public record PreparedModelPicker(
        String modelPreference,
        String currentEffort,
        String priorPersistedEffort,
        String defaultModel,
        List<ModelOption> options,
        Set<String> customModelNames
    ) {
        public PreparedModelPicker {
            options = List.copyOf(options);
            customModelNames = customModelNames == null ? Set.of() : Set.copyOf(customModelNames);
        }
    }

    private static final int LEFT_PAD    = 2;
    private static final int HEADER_ROW  = 0;
    private static final int SUBTITLE_ROW = 1;
    private static final int OPTIONS_START = 3;
    private static final int MIN_WIDTH   = 76;
    private static final String ADD_CUSTOM_MODEL_VALUE = "\u0000add-custom-model";
    private static final String SUBTITLE =
        "Switch between Claude models. Your pick becomes the default for new sessions. "
        + "For other/previous model names, specify with --model.";

    private boolean active;
    private List<ModelOption> options;
    private int selectedIdx;
    /** Index of the model that was active when the picker opened — marked with a ✓, unmoving. */
    private int originalIdx;
    /** Displayed effort level (null until the user toggles or a session effort seeds it). */
    private String effort;
    private boolean hasToggledEffort;
    /** User-tier effort loaded before the dialog reaches the GUI thread. */
    private String priorPersistedEffort;
    /** Exact names loaded from the custom-model catalogue. */
    private Set<String> customModelNames = Set.of();
    private Consumer<ModelPickResult> onResult;
    /** Last rendered picker extent; stable arrow-key frames reuse its cleared background. */
    private TerminalSize renderedSize;
    private int lastRenderedSelection = -1;
    private ModelOption lastRenderedEffortOption;
    private String lastRenderedEffortLevel;
    private int lastRenderedEffortWidth;
    private Consumer<Runnable> guiInvoker;
    private long renderGeneration;
    private boolean effortRefreshDeferred;
    private boolean effortRefreshScheduled;
    /** Offline organization allowlist; defaults to unrestricted for standalone/test use. */
    private Predicate<String> modelAllowed = _ -> true;
    private Supplier<List<CustomModelConfig>> customModelsSupplier;
    private Function<String, CompletionStage<Void>> customModelDeleteHandler;
    private boolean builtInFamiliesVisible = true;
    private ViewMode viewMode = ViewMode.LIST;
    private String pendingDeleteModel;
    private int pendingDeleteIndex = -1;
    private boolean pendingDeleteWasCurrent;
    private boolean deleteInFlight;
    private String deleteError;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public ModelPickerDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        PickerArea area = new PickerArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    @Override
    protected ComponentRenderer<Panel> createDefaultRenderer() {
        return new StablePickerPanelRenderer();
    }

    /**
     * Picker geometry is immutable between arrow presses. Lanterna's stock
     * {@link Panel.DefaultPanelRenderer} nevertheless reruns the linear layout
     * whenever the child is invalid, even though only two pointer cells changed.
     * Retain the existing child bounds until the panel size or layout manager
     * changes, while still drawing the child into the retained window image.
     */
    private final class StablePickerPanelRenderer implements ComponentRenderer<Panel> {
        private TerminalSize lastSize;

        @Override
        public TerminalSize getPreferredSize(Panel component) {
            return getLayoutManager().getPreferredSize(getChildrenList());
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, Panel component) {
            TerminalSize size = graphics.getSize();
            if (lastSize == null || !lastSize.equals(size) || getLayoutManager().hasChanged()) {
                getLayoutManager().doLayout(size, getChildrenList());
                lastSize = size;
            }
            for (Component child : getChildrenList()) {
                if (!child.isVisible()) continue;
                child.draw(graphics.newTextGraphics(child.getPosition(), child.getSize()));
            }
        }
    }

    /** Supplies the live settings-backed allowlist without coupling UI to services. */
    public void setModelAllowed(Predicate<String> modelAllowed) {
        this.modelAllowed = modelAllowed != null ? modelAllowed : _ -> true;
    }

    /** Controls whether the first-party Default and complete Claude family list are offered. */
    @Explanation("Hides unmapped first-party models for custom gateways and third-party providers")
    public void setBuiltInFamiliesVisible(boolean visible) {
        this.builtInFamiliesVisible = visible;
    }


    @Explanation("Adds user-defined Anthropic, Chat Completions, and Responses models to /model.")
    public void setCustomModelsSupplier(Supplier<List<CustomModelConfig>> supplier) {
        this.customModelsSupplier = supplier;
    }

    /** Supplies the background deletion boundary only for the interactive {@code /model} picker. */
    @Explanation("Adds confirmed deletion for Java-managed custom model endpoints")
    public void setCustomModelDeleteHandler(
            Function<String, CompletionStage<Void>> handler) {
        this.customModelDeleteHandler = handler;
    }

    /** Attach the live merged user/default keybinding resolver. */
    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /** Queues the auxiliary effort-row refresh after the selection pointer frame. */
    public synchronized void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
    }

    /**
     * Activate the picker. Must run on the GUI thread.
     *
     * @param modelPreference the nullable model preference (pre-selected;
     *                        null = Default (recommended))
     * @param currentEffort the session's current effort level (seeds the effort row; may be null)
     * @param onResult      invoked with the chosen {@link ModelPickResult} on Enter, or
     *                      {@code null} on Esc / cancellation. Called from the GUI thread.
     */
    public synchronized void show(String modelPreference, String currentEffort,
                                  Consumer<ModelPickResult> onResult) {
        show(prepare(modelPreference, currentEffort,
            UiSettings.readUserStringFromSettings("effortLevel"), null,
            SubprocessEnvironment::get), onResult);
    }

    /**
     * Production preload path: all settings/catalog I/O has already completed
     * on a worker, so mounting and confirming the picker are memory-only.
     */
    public synchronized void show(String modelPreference, String currentEffort,
                                  String priorPersistedEffort,
                                  List<CustomModelConfig> customModels,
                                  Consumer<ModelPickResult> onResult) {
        show(prepare(modelPreference, currentEffort, priorPersistedEffort,
            customModels, SubprocessEnvironment::get), onResult);
    }

    /** Builds settings-backed option visibility and custom rows off the GUI thread. */
    public PreparedModelPicker prepare(String modelPreference, String currentEffort,
                                       String priorPersistedEffort,
                                       List<CustomModelConfig> customModels) {
        return prepare(modelPreference, currentEffort, priorPersistedEffort,
            customModels, SubprocessEnvironment::get);
    }

    /**
     * {@link #show} with an injectable env lookup — the seam that keeps
     * {@link ModelPickerDialogTest} independent of the real process environment
     * (a dev machine may legitimately have {@code ANTHROPIC_DEFAULT_*_MODEL} set,
     * which would otherwise make option content, and therefore test assertions,
     * depend on who's running the suite). Production callers use the public
     * {@link #show(String, String, Consumer)} overload, which wires
     * {@link SubprocessEnvironment}, which includes settings.env and SDK
     * runtime environment overlays.
     */
    synchronized void show(String modelPreference, String currentEffort,
                           Consumer<ModelPickResult> onResult,
                           Function<String, String> envLookup) {
        show(prepare(modelPreference, currentEffort, null, null, envLookup), onResult);
    }

    private PreparedModelPicker prepare(String modelPreference, String currentEffort,
                                        String priorPersistedEffort,
                                        List<CustomModelConfig> customModels,
                                        Function<String, String> envLookup) {
        String preparedDefaultModel = ModelNames.defaultMainLoopModel(envLookup);
        List<ModelOption> candidates = buildOptions(
            modelPreference, envLookup, builtInFamiliesVisible);
        List<ModelOption> filtered = new ArrayList<>(filterAllowed(candidates));
        List<CustomModelConfig> resolvedCustomModels = resolveCustomModels(customModels);
        appendCustomModels(filtered, resolvedCustomModels);

        // the active model as an unfiltered "Current model" row when policy
        // no longer allows it. Keep that row selectable so a session already
        // running under a now-narrower allowlist remains representable.
        if (modelPreference != null
                && (builtInFamiliesVisible
                    || !ModelCatalog.isBuiltInSelection(modelPreference))
                && filtered.stream().noneMatch(option ->
                    ModelCatalog.sameModel(modelPreference, option.value(), envLookup))) {
            ModelOption current = candidates.stream()
                .filter(option -> ModelCatalog.sameModel(modelPreference, option.value(), envLookup))
                .findFirst()
                .orElse(new ModelOption(modelPreference, ModelNames.displayName(modelPreference),
                    "Current session model"));
            filtered.add(current);
        }
        if (customModels != null || customModelsSupplier != null) {
            filtered.add(new ModelOption(ADD_CUSTOM_MODEL_VALUE, "Add custom model…",
                "Configure Anthropic, Chat Completions, or Responses"));
        }
        Set<String> preparedCustomNames = resolvedCustomModels.stream()
                .filter(Objects::nonNull)
                .map(CustomModelConfig::modelName)
                .collect(Collectors.toUnmodifiableSet());
        List<ModelOption> preparedOptions = filtered.stream()
            .map(option -> prepareEffortMetadata(option, preparedDefaultModel, envLookup))
            .toList();
        String preparedModelPreference = modelPreference == null ? null : preparedOptions.stream()
            .map(ModelOption::value)
            .filter(value -> ModelCatalog.sameModel(modelPreference, value, envLookup))
            .findFirst()
            .orElse(modelPreference);
        return new PreparedModelPicker(preparedModelPreference, currentEffort,
            priorPersistedEffort, preparedDefaultModel, preparedOptions, preparedCustomNames);
    }

    private static ModelOption prepareEffortMetadata(
            ModelOption option, String preparedDefaultModel,
            Function<String, String> envLookup) {
        String focusModel = ADD_CUSTOM_MODEL_VALUE.equals(option.value())
            ? ""
            : option.value() != null
                ? ModelCatalog.resolve(option.value(), envLookup)
                : preparedDefaultModel;
        boolean supports = EffortHelpers.modelSupportsEffort(focusModel);
        EffortHelpers.EffortCapabilities capabilities =
            EffortHelpers.capabilitiesForModel(focusModel);
        List<String> levels = supports
            ? EffortHelpers.supportedEffortLevels(focusModel) : List.of();
        String defaultLevel = EffortHelpers.defaultEffortLevelForModel(focusModel);
        return new ModelOption(option.value(), option.label(), option.description(),
            new EffortOptionMetadata(
                focusModel, supports, capabilities, levels, defaultLevel));
    }

    /** Mounts a precomputed immutable snapshot; this method performs no I/O. */
    public synchronized void show(PreparedModelPicker prepared,
                                  Consumer<ModelPickResult> onResult) {
        this.onResult = onResult;
        this.options = prepared.options();
        this.selectedIdx = findIndex(options, prepared.modelPreference());
        this.originalIdx = selectedIdx;
        this.effort = (org.apache.commons.lang3.StringUtils.isNotBlank(prepared.currentEffort()))
            ? EffortHelpers.convertEffortValueToLevel(prepared.currentEffort()) : null;
        this.hasToggledEffort = false;
        this.priorPersistedEffort = prepared.priorPersistedEffort();
        this.customModelNames = prepared.customModelNames();
        this.viewMode = ViewMode.LIST;
        this.pendingDeleteModel = null;
        this.pendingDeleteIndex = -1;
        this.pendingDeleteWasCurrent = false;
        this.deleteInFlight = false;
        this.deleteError = null;
        this.active = true;
        this.renderGeneration++;
        this.effortRefreshDeferred = false;
        this.effortRefreshScheduled = false;
        resetRenderedFrame();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (ch == 'c' || ch == 'd') {
                resolve(null);
                deliver.set(false);
                return;
            }
        }

        if (keybindings.isCustomizationEnabled()) {
            ContextKeybindingDispatcher.Result resolved =
                keybindings.resolve(List.of("ModelPicker", "Select"), key);
            if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
                deliver.set(false);
                return;
            }
            if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
                dispatchAction(value);
                deliver.set(false);
                return;
            }
        }

        if (viewMode == ViewMode.DELETE_CONFIRM) {
            if (t == KeyType.ENTER) startDelete();
            else if (t == KeyType.ESCAPE) cancelDeleteConfirmation();
            deliver.set(false);
            return;
        }

        if (t == KeyType.CHARACTER && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'x'
                && !key.isCtrlDown() && !key.isAltDown()) {
            beginDeleteSelected();
            deliver.set(false);
            return;
        }

        if (t == KeyType.ARROW_UP) {
            moveSelection(-1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            moveSelection(1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_LEFT || t == KeyType.ARROW_RIGHT) {
            adjustEffort(t == KeyType.ARROW_RIGHT ? 1 : -1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            resolveSelected();
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            resolve(null);
            deliver.set(false);
        }
    }

    private void dispatchAction(String action) {
        switch (action) {
            case "select:previous" -> {
                if (viewMode == ViewMode.LIST) moveSelection(-1);
            }
            case "select:next" -> {
                if (viewMode == ViewMode.LIST) moveSelection(1);
            }
            case "select:accept" -> {
                if (viewMode == ViewMode.DELETE_CONFIRM) startDelete();
                else resolveSelected();
            }
            case "select:cancel" -> {
                if (viewMode == ViewMode.DELETE_CONFIRM) cancelDeleteConfirmation();
                else resolve(null);
            }
            case "modelPicker:decreaseEffort" -> {
                if (viewMode == ViewMode.LIST) adjustEffort(-1);
            }
            case "modelPicker:increaseEffort" -> {
                if (viewMode == ViewMode.LIST) adjustEffort(1);
            }
            case "modelPicker:deleteCustomModel" -> beginDeleteSelected();
            default -> {
                // Global actions are owned by the outer screen.
            }
        }
    }

    private void moveSelection(int delta) {
        ModelOption previous = options.get(selectedIdx);
        selectedIdx = InlineOverlay.cycleIndex(selectedIdx, delta, options.size());
        ModelOption selected = options.get(selectedIdx);
        if (effortRefreshScheduled || !sameEffortRow(previous, selected)) {
            deferEffortRefresh();
        }
        invalidate();
    }

    private boolean sameEffortRow(ModelOption first, ModelOption second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        boolean firstCustom = ADD_CUSTOM_MODEL_VALUE.equals(first.value());
        boolean secondCustom = ADD_CUSTOM_MODEL_VALUE.equals(second.value());
        if (firstCustom || secondCustom) return firstCustom && secondCustom;
        EffortOptionMetadata firstMetadata = first.effortMetadata();
        EffortOptionMetadata secondMetadata = second.effortMetadata();
        if (firstMetadata.supportsEffort() != secondMetadata.supportsEffort()) return false;
        if (!firstMetadata.supportsEffort()) {
            return Objects.equals(first.label(), second.label());
        }
        String firstDisplay = displayEffort(firstMetadata);
        String secondDisplay = displayEffort(secondMetadata);
        return Objects.equals(firstDisplay, secondDisplay)
            && (Objects.equals(firstDisplay, firstMetadata.defaultLevel())
                == Objects.equals(secondDisplay, secondMetadata.defaultLevel()));
    }

    /**
     * Adjust the focused model's effort.
     */
    private void adjustEffort(int direction) {
        effortRefreshDeferred = false;
        ModelOption opt = options.get(selectedIdx);
        EffortOptionMetadata metadata = opt.effortMetadata();
        if (!metadata.supportsEffort() || metadata.supportedLevels().isEmpty()) return;
        String base = effort != null ? effort : metadata.defaultLevel();
        int current = metadata.supportedLevels().indexOf(base);
        if (current < 0) {
            current = metadata.supportedLevels().indexOf(metadata.defaultLevel());
            if (current < 0) current = 0;
        }
        effort = metadata.supportedLevels().get(Math.floorMod(
            current + Integer.signum(direction), metadata.supportedLevels().size()));
        hasToggledEffort = true;
        invalidate();
    }

    /**
     * Builds the option list: Default + stable Fable/Opus/Sonnet/Haiku aliases (with label/description
     * and effective ID resolved through {@link ModelCatalog}), then appends {@code modelPreference} at
     * the end when it isn't already one of the four values above.
     */
    private static List<ModelOption> buildOptions(String modelPreference,
                                                   Function<String, String> envLookup,
                                                   boolean includeBuiltIns) {
        List<ModelOption> base = new ArrayList<>();
        if (includeBuiltIns) {
            base.add(new ModelOption(null, "Default (recommended)",
                "Use the default model (currently "
                    + ModelNames.displayName(ModelNames.defaultMainLoopModel(envLookup)) + ")"));
        }
        for (ModelCatalog.Family family :
                ModelCatalog.pickerFamilies(includeBuiltIns, envLookup)) {
            base.add(new ModelOption(family.alias(), ModelCatalog.label(family, envLookup),
                ModelCatalog.description(family, envLookup)));
        }
        boolean opusPlanAvailable = includeBuiltIns
            || base.stream().anyMatch(option -> Strings.CS.equals("opus", option.value()))
                && base.stream().anyMatch(option -> Strings.CS.equals("sonnet", option.value()));
        if (opusPlanAvailable && Strings.CI.equals("opusplan",
                modelPreference != null ? modelPreference.trim() : "")) {


            base.add(new ModelOption("opusplan", "Opus Plan Mode",
                "Use Opus in plan mode, Sonnet otherwise"));
        } else if (modelPreference != null
                && (includeBuiltIns || !ModelCatalog.isBuiltInSelection(modelPreference))
                && base.stream().noneMatch(o ->
                ModelCatalog.sameModel(modelPreference, o.value(), envLookup))) {
            base.add(new ModelOption(modelPreference, ModelNames.displayName(modelPreference),
                "Current session model"));
        }
        return base;
    }

    private List<ModelOption> filterAllowed(List<ModelOption> candidates) {
        return candidates.stream()
            .filter(option -> option.value() == null || isAllowed(option.value()))
            .toList();
    }

    private boolean isAllowed(String model) {
        try {
            return modelAllowed.test(model);
        } catch (RuntimeException _) {
            return false;
        }
    }

    private void appendCustomModels(List<ModelOption> target,
                                    List<CustomModelConfig> customModels) {
        for (CustomModelConfig model : customModels) {
            if (model == null || !isAllowed(model.modelName())
                    || target.stream().anyMatch(option -> model.modelName().equals(option.value()))) {
                continue;
            }
            target.add(new ModelOption(model.modelName(), model.modelName(),
                model.protocol().displayName() + " · " + model.baseUrl()));
        }
    }

    private List<CustomModelConfig> resolveCustomModels(List<CustomModelConfig> preloaded) {
        if (preloaded != null) return preloaded;
        if (customModelsSupplier == null) return List.of();
        try {
            List<CustomModelConfig> loaded = customModelsSupplier.get();
            return loaded == null ? List.of() : loaded;
        } catch (RuntimeException _) {
            return List.of();
        }
    }

    private static int findIndex(List<ModelOption> opts, String model) {
        for (int i = 0; i < opts.size(); i++) {
            if (model != null && model.equals(opts.get(i).value())) return i;
        }
        return 0;
    }

    private static int findDefaultIndex(List<ModelOption> opts) {
        for (int i = 0; i < opts.size(); i++) {
            if (opts.get(i).value() == null) return i;
        }
        return -1;
    }

    /** The effective model default shown when the current session level is incompatible. */
    private String displayEffort(EffortOptionMetadata metadata) {
        if (effort == null) return metadata.defaultLevel();
        return metadata.supportedLevels().contains(effort)
            ? effort : metadata.defaultLevel();
    }

    private synchronized void resolveSelected() {
        ModelOption opt = options.get(selectedIdx);
        if (ADD_CUSTOM_MODEL_VALUE.equals(opt.value())) {
            resolve(ModelPickResult.addCustomModelAction());
            return;
        }
        EffortOptionMetadata metadata = opt.effortMetadata();
        String focusModel = metadata.focusModel();
        String resolvedEffort = null;
        if (metadata.supportsEffort()) {
            EffortHelpers.EffortCapabilities capabilities = metadata.capabilities();
            boolean unknownCustom = customModelNames.contains(focusModel)
                && !capabilities.known();
            boolean incompatibleCurrent = effort != null
                && capabilities.known() && !capabilities.supports(effort);
            if (!hasToggledEffort && (unknownCustom || incompatibleCurrent)) {
                // A level inherited from the previous model is not an explicit
                // choice for this endpoint. Clear it to auto; the request router
                // can then learn support only after the user deliberately picks
                // a level with ←/→.
                resolve(new ModelPickResult(opt.value(), "auto"));
                return;
            }
            String modelDefault = metadata.defaultLevel();
            // The picker preserves only an explicit user-tier value. Project or
// policy effort must not leak into ~/on writes.
            // This snapshot was loaded before mounting; Enter performs no I/O.
            resolvedEffort = EffortHelpers.resolvePickerEffortPersistence(
                displayEffort(metadata), modelDefault,
                priorPersistedEffort, hasToggledEffort);
        }
        resolve(new ModelPickResult(opt.value(), resolvedEffort));
    }

    private synchronized void beginDeleteSelected() {
        if (viewMode != ViewMode.LIST || customModelDeleteHandler == null
                || options == null || options.isEmpty()) return;
        ModelOption selected = options.get(selectedIdx);
        String modelName = selected.value();
        if (modelName == null || !customModelNames.contains(modelName)) return;
        viewMode = ViewMode.DELETE_CONFIRM;
        pendingDeleteModel = modelName;
        pendingDeleteIndex = selectedIdx;
        pendingDeleteWasCurrent = selectedIdx == originalIdx;
        deleteInFlight = false;
        deleteError = null;
        resetRenderedFrame();
    }

    private synchronized void cancelDeleteConfirmation() {
        if (viewMode != ViewMode.DELETE_CONFIRM || deleteInFlight) return;
        clearDeleteState();
        resetRenderedFrame();
    }

    private synchronized void startDelete() {
        if (viewMode != ViewMode.DELETE_CONFIRM || deleteInFlight
                || customModelDeleteHandler == null || pendingDeleteModel == null) return;
        deleteInFlight = true;
        deleteError = null;
        resetRenderedFrame();
        long generation = renderGeneration;
        String modelName = pendingDeleteModel;
        CompletionStage<Void> deletion;
        try {
            deletion = customModelDeleteHandler.apply(modelName);
            if (deletion == null) {
                throw new IllegalStateException("Custom model delete handler returned no completion");
            }
        } catch (RuntimeException failure) {
            finishDelete(generation, modelName, failure);
            return;
        }
        deletion.whenComplete((_, failure) -> deliverDeleteCompletion(
            generation, modelName, failure));
    }

    private void deliverDeleteCompletion(long generation, String modelName, Throwable failure) {
        Runnable completion = () -> finishDelete(generation, modelName, failure);
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null) {
            completion.run();
            return;
        }
        try {
            invoker.accept(completion);
        } catch (RuntimeException _) {
            completion.run();
        }
    }

    private synchronized void finishDelete(
            long generation, String modelName, Throwable failure) {
        if (!active || generation != renderGeneration
                || viewMode != ViewMode.DELETE_CONFIRM
                || !Objects.equals(modelName, pendingDeleteModel)) return;
        deleteInFlight = false;
        if (failure != null) {
            deleteError = "Could not delete custom model";
            resetRenderedFrame();
            return;
        }

        int removeIndex = pendingDeleteIndex;
        if (removeIndex < 0 || removeIndex >= options.size()
                || !Objects.equals(modelName, options.get(removeIndex).value())) {
            removeIndex = -1;
            for (int i = 0; i < options.size(); i++) {
                if (Objects.equals(modelName, options.get(i).value())) {
                    removeIndex = i;
                    break;
                }
            }
        }
        if (removeIndex >= 0) {
            List<ModelOption> updatedOptions = new ArrayList<>(options);
            updatedOptions.remove(removeIndex);
            options = List.copyOf(updatedOptions);
            Set<String> updatedNames = new HashSet<>(customModelNames);
            updatedNames.remove(modelName);
            customModelNames = Set.copyOf(updatedNames);
            if (removeIndex < originalIdx) originalIdx--;
            else if (removeIndex == originalIdx) originalIdx = findDefaultIndex(options);
            selectedIdx = options.isEmpty() ? 0 : Math.min(removeIndex, options.size() - 1);
        }
        clearDeleteState();
        renderGeneration++;
        effortRefreshDeferred = false;
        effortRefreshScheduled = false;
        resetRenderedFrame();
    }

    private void clearDeleteState() {
        viewMode = ViewMode.LIST;
        pendingDeleteModel = null;
        pendingDeleteIndex = -1;
        pendingDeleteWasCurrent = false;
        deleteInFlight = false;
        deleteError = null;
    }

    private synchronized void resolve(ModelPickResult result) {
        if (!active) return;
        Consumer<ModelPickResult> cb = onResult;
        hide();
        if (cb != null) cb.accept(result);
    }

    private synchronized void hide() {
        active = false;
        renderGeneration++;
        effortRefreshDeferred = false;
        effortRefreshScheduled = false;
        clearDeleteState();
        onResult = null;
        invalidate();
    }

    private void resetRenderedFrame() {
        renderedSize = null;
        lastRenderedSelection = -1;
        lastRenderedEffortOption = null;
        lastRenderedEffortLevel = null;
        lastRenderedEffortWidth = 0;
        invalidate();
    }

    private void deferEffortRefresh() {
        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null) return;
        effortRefreshDeferred = true;
        if (effortRefreshScheduled) return;
        effortRefreshScheduled = true;
        long generation = renderGeneration;
        try {
            invoker.accept(() -> completeDeferredEffortRefresh(generation));
        } catch (RuntimeException _) {
            completeDeferredEffortRefresh(generation);
        }
    }

    private synchronized void completeDeferredEffortRefresh(long generation) {
        if (!active || generation != renderGeneration) return;
        effortRefreshDeferred = false;
        effortRefreshScheduled = false;
        invalidate();
    }

    /** Total rows: header + subtitle + blank + N options + blank + effort + blank + footer.
     *  0 while idle — {@code options} is only built by {@link #show}; {@code PickerRenderer}'s
     *  {@code getPreferredSize} is queried by the layout manager even when inactive. */
    private int totalRows() {
        if (options == null) return 0;
        return OPTIONS_START + options.size() + 4;
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()), totalRows());
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }


    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class PickerArea extends AbstractComponent<PickerArea> {
        @Override protected ComponentRenderer<PickerArea> createDefaultRenderer() {
            return new PickerRenderer();
        }
    }

    private final class PickerRenderer implements ComponentRenderer<PickerArea> {

        @Override
        public TerminalSize getPreferredSize(PickerArea c) {
            return new TerminalSize(LEFT_PAD * 2 + MIN_WIDTH, totalRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, PickerArea c) {
            if (!active) return;
            TerminalSize size = g.getSize();
            int cols = size.getColumns();
            if (viewMode == ViewMode.DELETE_CONFIRM) {
                g.fill(' ');
                renderedSize = size;
                drawDeleteConfirmation(g, cols);
                lastRenderedSelection = -1;
                return;
            }
            List<ModelOption> opts = options;
            boolean fullRender = !size.equals(renderedSize)
                || lastRenderedSelection < 0;
            if (fullRender) {
                g.fill(' ');
                renderedSize = size;
                drawStaticFrame(g, cols, opts);
                for (int i = 0; i < opts.size(); i++) {
                    drawOption(g, cols, opts, i);
                }
            } else if (lastRenderedSelection != selectedIdx) {
                drawPointer(g, lastRenderedSelection, false);
                drawPointer(g, selectedIdx, true);
            }

            // Effort row.
            int effortRow = OPTIONS_START + opts.size() + 1;
            if (!effortRefreshDeferred || fullRender) {
                ModelOption selectedOption = opts.get(selectedIdx);
                String effortLevel = renderedEffortLevel(selectedOption);
                if (fullRender || !sameEffortRow(lastRenderedEffortOption, selectedOption)
                        || !Objects.equals(lastRenderedEffortLevel, effortLevel)) {
                    if (!fullRender && lastRenderedEffortWidth > 0) {
                        g.fillRectangle(new TerminalPosition(LEFT_PAD, effortRow),
                            new TerminalSize(Math.min(lastRenderedEffortWidth,
                                Math.max(0, cols - LEFT_PAD)), 1), ' ');
                    }
                    lastRenderedEffortWidth = drawEffortRow(
                        g, selectedOption, effortLevel, effortRow);
                    lastRenderedEffortOption = selectedOption;
                    lastRenderedEffortLevel = effortLevel;
                }
            }
            lastRenderedSelection = selectedIdx;
        }

        private String renderedEffortLevel(ModelOption option) {
            if (ADD_CUSTOM_MODEL_VALUE.equals(option.value())) return null;
            EffortOptionMetadata metadata = option.effortMetadata();
            return metadata.supportsEffort() ? displayEffort(metadata) : null;
        }

        private void drawStaticFrame(TextGUIGraphics g, int cols, List<ModelOption> opts) {
            g.setForegroundColor(LanternaTheme.remember());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, HEADER_ROW, "Select model");
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, SUBTITLE_ROW,
                InlineOverlay.clip(SUBTITLE, cols - LEFT_PAD));
            int footerRow = OPTIONS_START + opts.size() + 3;
            g.enableModifiers(SGR.ITALIC);
            String footer = customModelDeleteHandler != null
                ? "x delete custom · Enter confirm · Esc cancel"
                : "Enter confirm · Esc cancel";
            g.putString(LEFT_PAD, footerRow, InlineOverlay.clip(footer, cols - LEFT_PAD));
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawDeleteConfirmation(TextGUIGraphics g, int cols) {
            g.setForegroundColor(LanternaTheme.toolWarning());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, HEADER_ROW, "Delete custom model?");
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 2, InlineOverlay.clip(
                pendingDeleteModel != null ? pendingDeleteModel : "", cols - LEFT_PAD));
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 3, InlineOverlay.clip(
                "This removes its endpoint and stored credentials from ~/.claude/model.json.",
                cols - LEFT_PAD));
            if (pendingDeleteWasCurrent) {
                g.setForegroundColor(LanternaTheme.toolWarning());
                g.putString(LEFT_PAD, 4, InlineOverlay.clip(
                    "The current session will switch to Default.", cols - LEFT_PAD));
            }
            int footerRow = Math.max(6, totalRows() - 1);
            if (deleteError != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, Math.max(5, footerRow - 2),
                    InlineOverlay.clip(deleteError, cols - LEFT_PAD));
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            String footer = deleteInFlight ? "Deleting…"
                : deleteError != null ? "Enter retry · Esc back"
                : "Enter delete · Esc back";
            g.putString(LEFT_PAD, footerRow, InlineOverlay.clip(footer, cols - LEFT_PAD));
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawOption(TextGUIGraphics g, int cols,
                                List<ModelOption> opts, int index) {
            int row = OPTIONS_START + index;
            ModelOption option = opts.get(index);
            String numberedLabel = (index + 1) + ". " + option.label()
                + (index == originalIdx ? " ✓" : "");
            drawPointer(g, index, index == selectedIdx);
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD + 2, row, numberedLabel);
            g.setForegroundColor(LanternaTheme.ghostText());
            int descX = LEFT_PAD + 2 + numberedLabel.length() + 2;
            if (descX < cols - 4) {
                g.putString(descX, row,
                    InlineOverlay.clip(option.description(), cols - descX));
            }
        }

        private void drawPointer(TextGUIGraphics g, int index, boolean selected) {
            if (index < 0 || index >= options.size()) return;
            g.setForegroundColor(selected
                ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            g.putString(LEFT_PAD, OPTIONS_START + index, selected ? "❯ " : "  ");
        }

        private int drawEffortRow(TextGUIGraphics g, ModelOption opt,
                                  String display, int row) {
            if (ADD_CUSTOM_MODEL_VALUE.equals(opt.value())) {
                String text = "Configure once; credentials stay in ~/.claude/model.json";
                g.setForegroundColor(LanternaTheme.subtle());
                g.putString(LEFT_PAD, row, text);
                return text.length();
            }
            EffortOptionMetadata metadata = opt.effortMetadata();
            if (metadata.supportsEffort()) {
                String glyph = EffortHelpers.effortLevelToSymbol(display);
                String modelDefault = metadata.defaultLevel();
                String label = " " + StringUtils.capitalize(display) + " effort"
                    + (display.equals(modelDefault) ? " (default)" : "") + "  ";
                int x = LEFT_PAD;
                // glyph — claude accent
                g.setForegroundColor(LanternaTheme.claude());
                g.putString(x, row, glyph);
                x += glyph.length();
                // level text — dim
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(x, row, label);
                x += label.length();
                // hint — subtle
                g.setForegroundColor(LanternaTheme.subtle());
                String hint = "← → to adjust";
                g.putString(x, row, hint);
                return x + hint.length() - LEFT_PAD;
            } else {
                String glyph = EffortHelpers.effortLevelToSymbol("low");
                String text = glyph + " Effort not supported for " + opt.label();
                g.setForegroundColor(LanternaTheme.subtle());
                g.putString(LEFT_PAD, row, text);
                return text.length();
            }
        }
    }
}
