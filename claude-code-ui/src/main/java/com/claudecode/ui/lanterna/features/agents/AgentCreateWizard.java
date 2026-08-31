package com.claudecode.ui.lanterna.features.agents;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.agent.AgentFileWriter;
import com.claudecode.tools.agent.AgentGenerator;
import com.claudecode.tools.agent.AgentModelOptions;
import com.claudecode.tools.agent.AgentMemoryPrompt;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.tools.agent.AgentValidator;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.TextInputs;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * "Create new agent" wizard for {@code AgentsPanel} — 11 linear steps, only ever active as the
 * panel's CREATE-mode child (not its own {@link InlineOverlay}, same relationship {@link
 * AgentToolsPicker} etc.
 */
final class AgentCreateWizard extends Panel {

    enum Step { LOCATION, METHOD, GENERATE, TYPE, PROMPT, DESCRIPTION, TOOLS, MODEL, COLOR, MEMORY, CONFIRM }

    private record Choice(String label, String value) {}

    private static final List<Choice> LOCATION_CHOICES = List.of(
        new Choice("Project (.claude/agents/)", "project"),
        new Choice("Personal (~/.claude/agents/)", "user"));

    private static final List<Choice> METHOD_CHOICES = List.of(
        new Choice("Generate with Claude (recommended)", "generate"),
        new Choice("Manual configuration", "manual"));

    private static final int LEFT_PAD = 2;

    private boolean active;
    private Step currentStep;
    private final Deque<Integer> history = new ArrayDeque<>();

    // ── wizard data ──────────────────────────────────────────────────────────
    private AgentSource location = AgentSource.PROJECT;
    private int locationIdx;
    private int methodIdx;
    private final StringBuilder generationInput = new StringBuilder();
    private boolean generating;
    private volatile Thread generationWorker;
    private String generationError;
    /**
     * Incremented on every cancel/goBack out of GENERATE and on every new {@link #runGeneration}.
     */
    private int generationEpoch;
    private final StringBuilder agentTypeInput = new StringBuilder();
    private String agentTypeError;
    private final StringBuilder systemPromptInput = new StringBuilder();
    private String systemPromptError;
    private final StringBuilder whenToUseInput = new StringBuilder();
    private String whenToUseError;
    private List<String> selectedTools;
    private String selectedModel;
    private String selectedColor;
    private String selectedMemory;
    private int memoryIdx;
    private List<Choice> memoryChoices = List.of();
    private volatile boolean saving;

    // ── activation context ───────────────────────────────────────────────────
    private String cwd;
    private List<BuiltInAgentDefinitions.AgentDefinition> existingAgents = List.of();
    private List<String> availableToolNames = List.of();
    private Function<String, String> sideQuestionRunner;
    private Consumer<Path> openEditor;
    private Consumer<String> onComplete;
    private Runnable onWizardCancel;
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();
    private final MemoryCatalog memoryCatalog;

    private final AgentToolsPicker toolsPicker = new AgentToolsPicker();
    private final AgentModelPicker modelPicker = new AgentModelPicker();
    private final AgentColorPicker colorPicker = new AgentColorPicker();

    AgentCreateWizard() {
        this(MemoryCatalog.empty());
    }

    AgentCreateWizard(MemoryCatalog memoryCatalog) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.memoryCatalog = memoryCatalog != null ? memoryCatalog : MemoryCatalog.empty();
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
        for (Panel p : new Panel[] {toolsPicker, modelPicker, colorPicker}) {
            p.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
            addComponent(p);
        }
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
        toolsPicker.setKeybindingsStore(store);
        modelPicker.setKeybindingsStore(store);
        colorPicker.setKeybindingsStore(store);
    }

    void activate(String cwd, List<BuiltInAgentDefinitions.AgentDefinition> existingAgents,
            List<String> availableToolNames, Function<String, String> sideQuestionRunner,
            Consumer<Path> openEditor,
            Consumer<String> onComplete, Runnable onCancel) {
        this.cwd = cwd;
        this.existingAgents = existingAgents;
        this.availableToolNames = availableToolNames;
        this.sideQuestionRunner = sideQuestionRunner;
        this.openEditor = openEditor;
        this.onComplete = onComplete;
        this.onWizardCancel = onCancel;

        this.location = AgentSource.PROJECT;
        this.locationIdx = 0;
        this.methodIdx = 0;
        this.generationInput.setLength(0);
        this.generating = false;
        this.generationError = null;
        this.generationEpoch++;
        this.saving = false;
        this.agentTypeInput.setLength(0);
        this.agentTypeError = null;
        this.systemPromptInput.setLength(0);
        this.systemPromptError = null;
        this.whenToUseInput.setLength(0);
        this.whenToUseError = null;
        this.selectedTools = null;
        this.selectedModel = null;
        this.selectedColor = null;
        this.selectedMemory = null;
        this.memoryIdx = 0;
        this.history.clear();

        this.active = true;
        this.currentStep = Step.LOCATION;
        invalidate();
    }

    boolean isActive() { return active; }

    private void deactivate() {
        generationEpoch++;
        active = false;
        invalidate();
    }



    private void goNext() {
        Step[] steps = Step.values();
        int idx = currentStep.ordinal();
        if (idx < steps.length - 1) {
            if (!history.isEmpty()) history.addLast(idx);
            currentStep = steps[idx + 1];
            onEnterStep();
        } else {
            complete();
        }
        invalidate();
    }

    private void goBack() {
        if (!history.isEmpty()) {
            currentStep = Step.values()[history.removeLast()];
            onEnterStep();
        } else if (currentStep.ordinal() > 0) {
            currentStep = Step.values()[currentStep.ordinal() - 1];
            onEnterStep();
        } else {
            cancelWizard();
            return;
        }
        invalidate();
    }

    private void goToStep(Step target) {
        history.addLast(currentStep.ordinal());
        currentStep = target;
        onEnterStep();
        invalidate();
    }

    private void cancelWizard() {
        history.clear();
        Runnable cb = onWizardCancel;
        saving = false;
        deactivate();
        if (cb != null) cb.run();
    }

    private void complete() {
        Consumer<String> cb = onComplete;
        String type = agentTypeInput.toString();
        deactivate();
        if (cb != null) cb.accept(type);
        saving = false;
    }

    /** Seeds picker sub-components / derived state when a step becomes current. */
    private void onEnterStep() {
        switch (currentStep) {
            case TOOLS -> toolsPicker.activate(selectedTools, availableToolNames,
                ts -> { selectedTools = ts; goNext(); }, this::goBack);
            case MODEL -> modelPicker.activate(selectedModel,
                m -> { selectedModel = m; goNext(); }, this::goBack);
            case COLOR -> colorPicker.activate(null,
                !agentTypeInput.isEmpty() ? agentTypeInput.toString() : "agent",
                c -> { selectedColor = c; goNext(); }, this::goBack);
            case MEMORY -> {
                memoryChoices = buildMemoryChoices();
                memoryIdx = 0;
            }
            default -> { /* no seeding needed */ }
        }
    }

    private List<Choice> buildMemoryChoices() {
        Choice user = new Choice("User scope (~/.claude/agent-memory/)", "user");
        Choice project = new Choice("Project scope (.claude/agent-memory/)", "project");
        Choice local = new Choice("Local scope (.claude/agent-memory-local/)", "local");
        Choice none = new Choice("None (no persistent memory)", null);
        return location == AgentSource.USER
            ? List.of(withRecommended(user), none, project, local)
            : List.of(withRecommended(project), none, user, local);
    }

    private Choice withRecommended(Choice c) {
        return new Choice(c.label() + " (Recommended)", c.value());
    }

    // ── key handling ─────────────────────────────────────────────────────────

    void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        switch (currentStep) {
            case LOCATION -> handleLocationKey(key, deliver);
            case METHOD -> handleMethodKey(key, deliver);
            case GENERATE -> handleGenerateKey(key, deliver);
            case TYPE -> handleTypeKey(key, deliver);
            case PROMPT -> handlePromptKey(key, deliver);
            case DESCRIPTION -> handleDescriptionKey(key, deliver);
            case TOOLS -> toolsPicker.handleKey(key, deliver);
            case MODEL -> modelPicker.handleKey(key, deliver);
            case COLOR -> colorPicker.handleKey(key, deliver);
            case MEMORY -> handleMemoryKey(key, deliver);
            case CONFIRM -> handleConfirmKey(key, deliver);
        }
    }

    private void handleLocationKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchSelectBinding(key, deliver,
                () -> { locationIdx = InlineOverlay.cycleIndex(locationIdx, -1, LOCATION_CHOICES.size()); invalidate(); },
                () -> { locationIdx = InlineOverlay.cycleIndex(locationIdx, 1, LOCATION_CHOICES.size()); invalidate(); },
                () -> {
                    location = Strings.CS.equals("user", LOCATION_CHOICES.get(locationIdx).value())
                        ? AgentSource.USER : AgentSource.PROJECT;
                    goNext();
                }, this::cancelWizard, List.of("Select"))) return;
        if (t == KeyType.ARROW_UP) { locationIdx = InlineOverlay.cycleIndex(locationIdx, -1, LOCATION_CHOICES.size()); invalidate(); }
        else if (t == KeyType.ARROW_DOWN) { locationIdx = InlineOverlay.cycleIndex(locationIdx, 1, LOCATION_CHOICES.size()); invalidate(); }
        else if (t == KeyType.ENTER) {
            location = Strings.CS.equals("user", LOCATION_CHOICES.get(locationIdx).value()) ? AgentSource.USER : AgentSource.PROJECT;
            goNext();
        } else if (t == KeyType.ESCAPE) cancelWizard();
    }

    private void handleMethodKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchSelectBinding(key, deliver,
                () -> { methodIdx = InlineOverlay.cycleIndex(methodIdx, -1, METHOD_CHOICES.size()); invalidate(); },
                () -> { methodIdx = InlineOverlay.cycleIndex(methodIdx, 1, METHOD_CHOICES.size()); invalidate(); },
                () -> {
                    if (Strings.CS.equals("generate", METHOD_CHOICES.get(methodIdx).value())) goNext();
                    else goToStep(Step.TYPE);
                }, this::goBack, List.of("Select"))) return;
        if (t == KeyType.ARROW_UP) { methodIdx = InlineOverlay.cycleIndex(methodIdx, -1, METHOD_CHOICES.size()); invalidate(); }
        else if (t == KeyType.ARROW_DOWN) { methodIdx = InlineOverlay.cycleIndex(methodIdx, 1, METHOD_CHOICES.size()); invalidate(); }
        else if (t == KeyType.ENTER) {
            if (Strings.CS.equals("generate", METHOD_CHOICES.get(methodIdx).value())) goNext();
            else goToStep(Step.TYPE);
        } else if (t == KeyType.ESCAPE) goBack();
    }

    private void handleGenerateKey(KeyStroke key, AtomicBoolean deliver) {
        if (generating) {
            if (dispatchActionBinding(key, deliver, List.of("Settings"), action -> {
                    if (!Strings.CS.equals("confirm:no", action)) return false;
                    cancelGeneration();
                    return true;
                })) return;
            if (key.getKeyType() == KeyType.ESCAPE) {
                cancelGeneration();
            }
            deliver.set(false);
            return;
        }
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchActionBinding(key, deliver, List.of("Settings", "Chat"), action -> switch (action) {
                case "confirm:no" -> { resetGenerationAndGoBack(); yield true; }
                case "chat:externalEditor" -> { editBufferInExternalEditor(generationInput); yield true; }
                default -> false;
            })) return;
        if (t == KeyType.ESCAPE) {
            resetGenerationAndGoBack();
            return;
        }
        if (TextInputs.tryApplyKey(generationInput, key, false)) {
            invalidate();
            return;
        }
        if (t == KeyType.ENTER) { runGeneration(); }
    }

    private void cancelGeneration() {
        generating = false;
        generationEpoch++;
        Thread worker = generationWorker;
        generationWorker = null;
        if (worker != null) worker.interrupt();
        generationError = "Generation cancelled";
        invalidate();
    }

    private void resetGenerationAndGoBack() {
        generationInput.setLength(0);
        agentTypeInput.setLength(0);
        systemPromptInput.setLength(0);
        whenToUseInput.setLength(0);
        generationError = null;
        goBack();
    }

    private void runGeneration() {
        String trimmed = generationInput.toString().trim();
        if (trimmed.isEmpty()) {
            generationError = "Please describe what the agent should do";
            invalidate();
            return;
        }
        if (sideQuestionRunner == null) {
            generationError = "Generation is not available in this context";
            invalidate();
            return;
        }
        generationError = null;
        generating = true;
        int epoch = ++generationEpoch;
        invalidate();

        List<String> existingIdentifiers = existingAgents.stream().map(BuiltInAgentDefinitions.AgentDefinition::agentType).toList();
        String prompt = AgentGenerator.buildPrompt(trimmed, existingIdentifiers);
        Thread worker = Thread.ofVirtual().name("agent-generate").unstarted(() -> {
            AgentGenerator.GeneratedAgent generated = null;
            String failure = null;
            try {
                String response = sideQuestionRunner.apply(prompt);
                generated = AgentGenerator.parseResponse(response);
            } catch (Exception e) {
                failure = e.getMessage() != null ? e.getMessage() : "Failed to generate agent";
            }
            AgentGenerator.GeneratedAgent finalGenerated = generated;
            String finalFailure = failure;
            var gui = findGui();
            Runnable apply = () -> {
                if (epoch != generationEpoch) return; // cancelled or superseded — discard
                generationWorker = null;
                generating = false;
                if (finalGenerated != null) {
                    agentTypeInput.setLength(0);
                    agentTypeInput.append(finalGenerated.identifier());
                    whenToUseInput.setLength(0);
                    whenToUseInput.append(finalGenerated.whenToUse());
                    systemPromptInput.setLength(0);
                    systemPromptInput.append(finalGenerated.systemPrompt());
                    goToStep(Step.TOOLS);
                } else {
                    generationError = finalFailure;
                }
                invalidate();
            };
            if (gui != null) gui.getGUIThread().invokeLater(apply);
            else apply.run();
        });
        generationWorker = worker;
        worker.start();
    }

    private MultiWindowTextGUI findGui() {
        return getTextGUI() instanceof MultiWindowTextGUI mwtg ? mwtg : null;
    }

    private void handleTypeKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchActionBinding(key, deliver, List.of("Settings"), action -> {
                if (!Strings.CS.equals("confirm:no", action)) return false;
                goBack();
                return true;
            })) return;
        if (t == KeyType.ESCAPE) { goBack(); return; }
        if (TextInputs.tryApplyKey(agentTypeInput, key, false)) {
            invalidate();
            return;
        }
        if (t == KeyType.ENTER) {
            String type = agentTypeInput.toString().trim();
            String error = AgentValidator.validateAgentType(type);
            if (error == null) {
                boolean duplicate = existingAgents.stream()
                    .anyMatch(a -> a.agentType().equals(type) && a.source() != location);
                if (duplicate) error = "Agent type \"" + type + "\" already exists in " + location.displayName();
            }
            if (error != null) { agentTypeError = error; invalidate(); return; }
            agentTypeError = null;
            goNext();
        }
    }

    private void handlePromptKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchActionBinding(key, deliver, List.of("Settings", "Chat"), action -> switch (action) {
                case "confirm:no" -> { goBack(); yield true; }
                case "chat:externalEditor" -> { editBufferInExternalEditor(systemPromptInput); yield true; }
                default -> false;
            })) return;
        if (t == KeyType.ESCAPE) { goBack(); return; }
        if (TextInputs.tryApplyKey(systemPromptInput, key, false)) {
            invalidate();
            return;
        }
        if (t == KeyType.ENTER) {
            String prompt = systemPromptInput.toString().trim();
            if (prompt.isEmpty()) { systemPromptError = "System prompt is required"; invalidate(); return; }
            systemPromptError = null;
            goNext();
        }
    }

    private void handleDescriptionKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchActionBinding(key, deliver, List.of("Settings", "Chat"), action -> switch (action) {
                case "confirm:no" -> { goBack(); yield true; }
                case "chat:externalEditor" -> { editBufferInExternalEditor(whenToUseInput); yield true; }
                default -> false;
            })) return;
        if (t == KeyType.ESCAPE) { goBack(); return; }
        if (TextInputs.tryApplyKey(whenToUseInput, key, false)) {
            invalidate();
            return;
        }
        if (t == KeyType.ENTER) {
            String desc = whenToUseInput.toString().trim();
            if (desc.isEmpty()) { whenToUseError = "Description is required"; invalidate(); return; }
            whenToUseError = null;
            goNext();
        }
    }

    private void handleMemoryKey(KeyStroke key, AtomicBoolean deliver) {
        KeyType t = key.getKeyType();
        deliver.set(false);
        if (dispatchSelectBinding(key, deliver,
                () -> { memoryIdx = InlineOverlay.cycleIndex(memoryIdx, -1, memoryChoices.size()); invalidate(); },
                () -> { memoryIdx = InlineOverlay.cycleIndex(memoryIdx, 1, memoryChoices.size()); invalidate(); },
                () -> { selectedMemory = memoryChoices.get(memoryIdx).value(); goNext(); },
                this::goBack, List.of("Select", "Confirmation"))) return;
        if (t == KeyType.ARROW_UP) { memoryIdx = InlineOverlay.cycleIndex(memoryIdx, -1, memoryChoices.size()); invalidate(); }
        else if (t == KeyType.ARROW_DOWN) { memoryIdx = InlineOverlay.cycleIndex(memoryIdx, 1, memoryChoices.size()); invalidate(); }
        else if (t == KeyType.ENTER) { selectedMemory = memoryChoices.get(memoryIdx).value(); goNext(); }
        else if (t == KeyType.ESCAPE) goBack();
    }

    private void handleConfirmKey(KeyStroke key, AtomicBoolean deliver) {
        if (saving) {
            deliver.set(false);
            return;
        }
        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();
        deliver.set(false);
        if (dispatchActionBinding(key, deliver, List.of("Confirmation"), action -> {
                if (!Strings.CS.equals("confirm:no", action)) return false;
                goBack();
                return true;
            })) return;
        if (t == KeyType.ESCAPE) { goBack(); return; }
        if (t == KeyType.ENTER || (t == KeyType.CHARACTER && ch != null && Character.toLowerCase(ch) == 's')) {
            save(false);
        } else if (t == KeyType.CHARACTER && ch != null && Character.toLowerCase(ch) == 'e') {
            save(true);
        }
    }

    private boolean dispatchSelectBinding(KeyStroke key, AtomicBoolean deliver,
            Runnable previous, Runnable next, Runnable accept, Runnable cancel,
            List<String> contexts) {
        return dispatchActionBinding(key, deliver, contexts, action -> switch (action) {
            case "select:previous" -> { previous.run(); yield true; }
            case "select:next" -> { next.run(); yield true; }
            case "select:accept" -> { accept.run(); yield true; }
            case "select:cancel", "confirm:no" -> { cancel.run(); yield true; }
            default -> false;
        });
    }

    private boolean dispatchActionBinding(KeyStroke key, AtomicBoolean deliver,
            List<String> contexts, Function<String, Boolean> handler) {
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve(contexts, key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return true;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Boolean.TRUE.equals(handler.apply(value))) {
            deliver.set(false);
            return true;
        }
        return false;
    }

    private void editBufferInExternalEditor(StringBuilder buffer) {
        if (openEditor == null) return;
        Path temporary = null;
        try {
            temporary = Files.createTempFile("claude-agent-prompt-", ".md");
            Files.writeString(temporary, buffer.toString());
            openEditor.accept(temporary);
            String edited = Files.readString(temporary);
            buffer.setLength(0);
            buffer.append(edited);
            invalidate();
        } catch (Exception e) {
            generationError = "Failed to open external editor: " + e.getMessage();
            invalidate();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception _) { }
            }
        }
    }

    private void save(boolean openInEditor) {
        String type = agentTypeInput.toString().trim();
        String whenToUse = whenToUseInput.toString().trim();
        String basePrompt = systemPromptInput.toString().trim();
        List<String> tools = selectedTools == null ? null : List.copyOf(selectedTools);
        AgentSource source = location;
        String cwdSnapshot = cwd;
        String color = selectedColor;
        String model = selectedModel;
        String memory = selectedMemory;
        int epoch = ++generationEpoch;
        saving = true;
        generationError = null;
        invalidate();
        Thread.ofVirtual().name("agent-file-save").start(() -> {
            Path savedPath = null;
            String failure = null;
        try {
                String systemPrompt = systemPromptForSave(
                    basePrompt, type, memory, cwdSnapshot);
                AgentFileWriter.save(source, cwdSnapshot, type, whenToUse, tools, systemPrompt,
                    color, model, memory, true);
                savedPath = AgentFileWriter.newFilePath(source, cwdSnapshot, type);
        AgentDefinitionLoader.clearCache();
            } catch (Exception e) {
                failure = e.getMessage() == null ? e.toString() : e.getMessage();
            }
            Path finalSavedPath = savedPath;
            String finalFailure = failure;
            Runnable apply = () -> {
                if (!active || epoch != generationEpoch) return;
                if (finalFailure != null) {
                    generationError = "Failed to save agent: " + finalFailure;
                    saving = false;
                    invalidate();
                    return;
                }
        if (openInEditor && openEditor != null) {
                    openEditor.accept(finalSavedPath);
        }
        complete();
            };
            var gui = findGui();
            if (gui != null) gui.getGUIThread().invokeLater(apply);
            else apply.run();
        });
    }

    private String systemPromptForSave(String base, String agentType, String memory, String saveCwd) {
        if (!memoryCatalog.autoMemoryEnabled() || memory == null
                || StringUtils.isBlank(agentType) || saveCwd == null || StringUtils.isBlank(saveCwd)) {
            return base;
        }
        Path dir = memoryCatalog.agentMemoryDirectory(
            agentType, memory, Path.of(saveCwd));
        if (dir == null) return base;
        String memoryPrompt = AgentMemoryPrompt.build(dir, memory);
        return StringUtils.isBlank(memoryPrompt) ? base : base + "\n\n" + memoryPrompt;
    }

    // ── sizing ───────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return active ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return active ? super.previousFocus(fromThis) : null; }

    // ── Test accessors (package-private) ────────────────────────────────────

    Step currentStep() { return currentStep; }
    boolean savingForTest() { return saving; }
    String agentTypeError() { return agentTypeError; }
    String generationErrorText() { return generationError; }
    boolean isGenerating() { return generating; }
    AgentSource location() { return location; }
    List<String> selectedTools() { return selectedTools; }
    String selectedModel() { return selectedModel; }
    String selectedColor() { return selectedColor; }
    String colorPreviewText() { return colorPicker.previewText(); }
    String selectedMemory() { return selectedMemory; }
    String systemPromptText() { return systemPromptInput.toString(); }
    List<String> memoryChoiceLabels() { return memoryChoices.stream().map(Choice::label).toList(); }

    // ──────────────────────────────────────────────────────────────────────────

    private boolean stepAreaVisible() {
        return active && currentStep != Step.TOOLS && currentStep != Step.MODEL && currentStep != Step.COLOR;
    }

    private final class Area extends AbstractComponent<Area> {
        @Override protected ComponentRenderer<Area> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Area> {

        @Override
        public TerminalSize getPreferredSize(Area c) {
            if (!stepAreaVisible()) return new TerminalSize(0, 0);
            return new TerminalSize(76, totalRows());
        }

        private int totalRows() {
            return switch (currentStep) {
                case LOCATION -> 3 + LOCATION_CHOICES.size() + 2;
                case METHOD -> 3 + METHOD_CHOICES.size() + 2;
                case GENERATE -> 6;
                case TYPE, PROMPT, DESCRIPTION -> agentTypeError != null || systemPromptError != null || whenToUseError != null ? 6 : 5;
                case MEMORY -> 3 + memoryChoices.size() + 2;
                case CONFIRM -> 16;
                default -> 0;
            };
        }

        @Override
        public void drawComponent(TextGUIGraphics g, Area c) {
            if (!stepAreaVisible()) return;
            g.fill(' ');
            g.setForegroundColor(LanternaTheme.remember());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 0, "Create new agent");
            g.disableModifiers(SGR.BOLD);

            switch (currentStep) {
                case LOCATION -> drawChoices(g, "Choose location", LOCATION_CHOICES, locationIdx);
                case METHOD -> drawChoices(g, "Creation method", METHOD_CHOICES, methodIdx);
                case GENERATE -> drawGenerate(g);
                case TYPE -> drawTextField(g, "Enter a name for your agent:", agentTypeInput.toString(), agentTypeError);
                case PROMPT -> drawTextField(g, "Enter the system prompt for your agent:", systemPromptInput.toString(), systemPromptError);
                case DESCRIPTION -> drawTextField(g, "When should this agent be used?", whenToUseInput.toString(), whenToUseError);
                case MEMORY -> drawChoices(g, "Configure agent memory", memoryChoices, memoryIdx);
                case CONFIRM -> drawConfirm(g);
                default -> { /* TOOLS/MODEL/COLOR delegate to their own picker components */ }
            }
        }

        private void drawChoices(TextGUIGraphics g, String subtitle, List<Choice> choices, int idx) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, subtitle);
            for (int i = 0; i < choices.size(); i++) {
                int row = 4 + i;
                boolean selected = i == idx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, (selected ? "❯ " : "  ") + choices.get(i).label());
            }
            int footerRow = 4 + choices.size() + 1;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, footerRow, "↑↓ navigate · Enter select · Esc go back");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawGenerate(TextGUIGraphics g) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, "Describe what this agent should do and when it should be used:");
            if (generating) {
                g.setForegroundColor(LanternaTheme.suggestion());
                g.putString(LEFT_PAD, 4, "⠋ Generating agent from description...");
                return;
            }
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 4, "› " + generationInput + "█");
            if (generationError != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, 5, generationError);
            }
        }

        private void drawTextField(TextGUIGraphics g, String label, String value, String error) {
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, label);
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, 3, "› " + value + "█");
            int row = 4;
            if (error != null) {
                g.setForegroundColor(LanternaTheme.toolError());
                g.putString(LEFT_PAD, row, error);
                row++;
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row, "Enter continue · Esc go back");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawConfirm(TextGUIGraphics g) {
            List<String> lines = new ArrayList<>();
            lines.add("Name: " + agentTypeInput);
            lines.add("Location: " + AgentFileWriter.newFilePath(location, cwd, agentTypeInput.toString()));
            lines.add("Tools: " + (selectedTools == null ? "All tools" : selectedTools.isEmpty() ? "None" : String.join(", ", selectedTools)));
            lines.add("Model: " + AgentModelOptions.displayName(selectedModel));
            lines.add("Memory: " + (selectedMemory == null ? "None" : selectedMemory));
            lines.add("");
            lines.add("Description:");
            lines.add("  " + FormatUtils.truncate(whenToUseInput.toString(), 240));
            lines.add("");
            lines.add("System prompt:");
            lines.add("  " + FormatUtils.truncate(systemPromptInput.toString(), 240));

            var validation = AgentValidator.validate(agentTypeInput.toString(), location, whenToUseInput.toString(),
                selectedTools, systemPromptInput.toString(), availableToolNames, existingAgents);
            for (String w : validation.warnings()) lines.add("⚠ " + w);
            for (String e : validation.errors()) lines.add("✗ " + e);

            int row = 2;
            for (String line : lines) {
                g.setForegroundColor(LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, line);
                row++;
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row + 1, saving
                ? "Saving…"
                : "s/Enter save · e save and edit · Esc go back");
            g.disableModifiers(SGR.ITALIC);
        }
    }
}
