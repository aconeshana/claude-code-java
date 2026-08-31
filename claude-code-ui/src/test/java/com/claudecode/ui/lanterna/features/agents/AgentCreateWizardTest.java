package com.claudecode.ui.lanterna.features.agents;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentCreateWizard}.
 */
class AgentCreateWizardTest {

    private static void awaitSave(AgentCreateWizard wizard) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (wizard.savingForTest() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(wizard.savingForTest(), "agent save timed out");
    }

    @TempDir Path tempDir;
    private String originalHome;
    private Path homeDir;
    private Path projectDir;

    @BeforeEach
    void redirect() throws Exception {
        originalHome = System.getProperty("user.home");
        homeDir = tempDir.resolve("home");
        projectDir = tempDir.resolve("project");
        Files.createDirectories(homeDir);
        Files.createDirectories(projectDir);
        System.setProperty("user.home", homeDir.toString());
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", originalHome);
    }

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(AgentCreateWizard w, KeyStroke k) {
        w.handleKey(k, new AtomicBoolean(true));
    }

    private static void type(AgentCreateWizard w, String s) {
        for (char c : s.toCharArray()) send(w, new KeyStroke(c, false, false));
    }

    private AgentCreateWizard wizardWithCallbacks(Consumer<String> onComplete, Runnable onCancel) {
        AgentCreateWizard w = new AgentCreateWizard();
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"), null, null, onComplete, onCancel);
        return w;
    }

    private void awaitStep(AgentCreateWizard w, AgentCreateWizard.Step expected) {
        long deadline = System.currentTimeMillis() + 2000;
        while (w.currentStep() != expected && w.generationErrorText() == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException _) {}
        }
    }

    // ── basic navigation ─────────────────────────────────────────────────────

    @Test
    void activate_startsOnLocationStep() {
        AgentCreateWizard w = wizardWithCallbacks(_ -> {}, () -> {});
        assertEquals(AgentCreateWizard.Step.LOCATION, w.currentStep());
    }

    @Test
    void locationStep_selectsUser_thenAdvancesToMethod() {
        AgentCreateWizard w = wizardWithCallbacks(_ -> {}, () -> {});
        send(w, DOWN); // Project -> Personal
        send(w, ENTER);
        assertEquals(AgentCreateWizard.Step.METHOD, w.currentStep());
        assertEquals(AgentSource.USER, w.location());
    }

    @Test
    void escFromLocationStep_cancels() {
        boolean[] cancelled = {false};
        AgentCreateWizard w = wizardWithCallbacks(_ -> {}, () -> cancelled[0] = true);
        send(w, ESC);
        assertTrue(cancelled[0]);
    }

    @Test
    void manualMethod_jumpsToTypeStep_backReturnsToMethod() {
        AgentCreateWizard w = wizardWithCallbacks(_ -> {}, () -> {});
        send(w, ENTER); // LOCATION -> METHOD (project, default)
        send(w, DOWN);  // Generate -> Manual
        send(w, ENTER); // goToStep(TYPE)
        assertEquals(AgentCreateWizard.Step.TYPE, w.currentStep());

        send(w, ESC); // Back from a goToStep-jumped step returns to the jump origin (METHOD)
        assertEquals(AgentCreateWizard.Step.METHOD, w.currentStep());
    }

    @Test
    void linearBack_decrementsWithoutHistory() {
        AgentCreateWizard w = wizardWithCallbacks(_ -> {}, () -> {});
        send(w, ENTER); // LOCATION -> METHOD (plain goNext, empty history stays empty)
        send(w, ESC);   // goBack: history empty, currentIndex>0 -> LOCATION
        assertEquals(AgentCreateWizard.Step.LOCATION, w.currentStep());
    }

    @Test
    void selectAndSettingsBindingsCanBeRebound() throws Exception {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Select","bindings":{
                "x":"select:next","z":"select:accept","q":"select:cancel",
                "down":null,"enter":null,"escape":null
              }},
              {"context":"Settings","bindings":{
                "q":"confirm:no","escape":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AgentCreateWizard w = wizardWithCallbacks(_ -> {}, () -> {});
            w.setKeybindingsStore(store);

            send(w, DOWN);
            assertEquals(AgentSource.PROJECT, w.location(), "unbound Down must not move Location");
            send(w, new KeyStroke('x', false, false));
            send(w, new KeyStroke('z', false, false));
            assertEquals(AgentSource.USER, w.location());
            assertEquals(AgentCreateWizard.Step.METHOD, w.currentStep());

            send(w, new KeyStroke('x', false, false));
            send(w, new KeyStroke('z', false, false));
            assertEquals(AgentCreateWizard.Step.TYPE, w.currentStep());
            send(w, ESC);
            assertEquals(AgentCreateWizard.Step.TYPE, w.currentStep(), "Settings Escape is unbound");
            send(w, new KeyStroke('q', false, false));
            assertEquals(AgentCreateWizard.Step.METHOD, w.currentStep());
        } finally {
            store.dispose();
        }
    }

    @Test
    void externalEditorBindingUpdatesPromptBuffer() throws Exception {
        Path file = tempDir.resolve("keybindings-editor.json");
        Files.writeString(file, """
            [{"context":"Chat","bindings":{
              "x":"chat:externalEditor","ctrl+g":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AgentCreateWizard w = new AgentCreateWizard();
            w.setKeybindingsStore(store);
            w.activate(projectDir.toString(), List.of(), List.of("Read"), null,
                path -> {
                    try {
                        Files.writeString(path, "Edited multiline prompt\nfrom editor");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, _ -> {}, () -> {});
            send(w, ENTER); // LOCATION -> METHOD
            send(w, DOWN); send(w, ENTER); // manual -> TYPE
            type(w, "review-agent"); send(w, ENTER); // -> PROMPT

            send(w, new KeyStroke('x', false, false));
            assertEquals("Edited multiline prompt\nfrom editor", w.systemPromptText());
        } finally {
            store.dispose();
        }
    }

    // ── manual path through to TYPE/PROMPT/DESCRIPTION ──────────────────────

    private AgentCreateWizard atTypeStepManualPath(Consumer<String> onComplete) {
        AgentCreateWizard w = wizardWithCallbacks(onComplete, () -> {});
        send(w, ENTER); // LOCATION -> METHOD
        send(w, DOWN); send(w, ENTER); // Manual -> goToStep(TYPE)
        return w;
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    @Test
    void typeStep_invalidName_showsErrorAndStays() {
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        type(w, "ab"); // too short
        send(w, ENTER);
        assertEquals(AgentCreateWizard.Step.TYPE, w.currentStep());
        assertNotNull(w.agentTypeError());
    }

    @Test
    void typeStep_validName_advancesToPrompt() {
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        type(w, "code-reviewer");
        send(w, ENTER);
        assertEquals(AgentCreateWizard.Step.PROMPT, w.currentStep());
    }

    @Test
    void typeStep_pasteInsertsTextAndConsumesKey() {
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);
        w.handleKey(new PasteKeyStroke("pasted-name"), deliver);
        assertFalse(deliver.get(), "paste inside the wizard must be consumed, not leaked to the main input");
        send(w, ENTER);
        assertEquals(AgentCreateWizard.Step.PROMPT, w.currentStep());
    }

    @Test
    void fullManualPath_toToolsModelColorMemory() {
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        type(w, "code-reviewer");
        send(w, ENTER); // -> PROMPT
        type(w, "You are a thorough and careful code reviewer.");
        send(w, ENTER); // -> DESCRIPTION
        type(w, "Use this agent after writing code");
        send(w, ENTER); // -> TOOLS
        assertEquals(AgentCreateWizard.Step.TOOLS, w.currentStep());

        send(w, ENTER); // Continue on Tools picker (all tools selected by default)
        assertEquals(AgentCreateWizard.Step.MODEL, w.currentStep());

        send(w, ENTER); // confirm default model on Model picker
        assertEquals(AgentCreateWizard.Step.COLOR, w.currentStep());

        send(w, ENTER); // confirm default color on Color picker
        assertEquals(AgentCreateWizard.Step.MEMORY, w.currentStep());

        send(w, ENTER); // confirm recommended memory option
        assertEquals(AgentCreateWizard.Step.CONFIRM, w.currentStep());
    }

    @Test
    void modelStepStoresTheReleasedLiteralInheritValue() {
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        type(w, "inherit-reviewer"); send(w, ENTER);
        type(w, "Review carefully"); send(w, ENTER);
        type(w, "Use for reviews"); send(w, ENTER);
        send(w, ENTER); // tools -> model; creation default is Sonnet (index 1)

        send(w, DOWN); send(w, DOWN); send(w, DOWN); // Sonnet -> Opus -> Haiku -> Inherit
        send(w, ENTER);

        assertEquals("inherit", w.selectedModel(),
            "ModelStep stores the selector value verbatim in wizardData");
    }

    @Test
    void returningToColorStepResetsToAutomaticAndUsesReleasedFallbackName() {
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        type(w, "color-reviewer"); send(w, ENTER);
        type(w, "Review carefully"); send(w, ENTER);
        type(w, "Use for reviews"); send(w, ENTER);
        send(w, ENTER); // tools -> model
        send(w, ENTER); // model -> color

        assertEquals("Preview:  @color-reviewer ", w.colorPreviewText());
        send(w, DOWN);  // automatic -> first explicit color
        send(w, ENTER); // -> memory
        assertNotNull(w.selectedColor());

        send(w, ESC);
        send(w, ENTER); // accept automatic -> memory
        assertNull(w.selectedColor());
    }

    @Test
    void memoryStep_projectLocation_choiceLabelsMatchTsDirectoryStrings() {

        // ".claude/agent-memory/" (singular "agent") for every scope,
        // never ".claude/agents-memory/".
        AgentCreateWizard w = atTypeStepManualPath(_ -> {});
        type(w, "code-reviewer");
        send(w, ENTER); // -> PROMPT
        type(w, "You are a thorough and careful code reviewer.");
        send(w, ENTER); // -> DESCRIPTION
        type(w, "Use this agent after writing code");
        send(w, ENTER); // -> TOOLS
        send(w, ENTER); // -> MODEL
        send(w, ENTER); // -> COLOR
        send(w, ENTER); // -> MEMORY
        assertEquals(AgentCreateWizard.Step.MEMORY, w.currentStep());

        List<String> labels = w.memoryChoiceLabels();
        assertTrue(labels.stream().anyMatch(l -> Strings.CS.contains(l, "Project scope (.claude/agent-memory/)")), labels.toString());
        assertTrue(labels.stream().noneMatch(l -> Strings.CS.contains(l, "agents-memory")), labels.toString());
    }

    @Test
    void saveSnapshotsLiveAgentMemoryPrompt() throws Exception {
        Path memoryDir = tempDir.resolve("agent-memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("MEMORY.md"), "- Existing reviewer convention");
        MemoryCatalog catalog = new MemoryCatalog() {
            @Override public List<File> scan(Path cwd) { return List.of(); }
            @Override public boolean autoMemoryEnabled() { return true; }
            @Override public Path agentMemoryDirectory(String type, String scope, Path cwd) {
                return memoryDir;
            }
        };
        AgentCreateWizard w = new AgentCreateWizard(catalog);
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"), null,
            null, _ -> {}, () -> {});
        send(w, ENTER); // location -> method
        send(w, DOWN); send(w, ENTER); // manual -> type
        type(w, "memory-reviewer"); send(w, ENTER);
        type(w, "Review code carefully"); send(w, ENTER);
        type(w, "Use after implementation"); send(w, ENTER);
        send(w, ENTER); // tools
        send(w, ENTER); // model
        send(w, ENTER); // color
        send(w, ENTER); // memory: recommended project
        send(w, ENTER); // save
        awaitSave(w);

        String saved = Files.readString(
            projectDir.resolve(".claude/agents/memory-reviewer.md"));
        assertTrue(Strings.CS.contains(saved, "# Persistent Agent Memory"), saved);
        assertTrue(Strings.CS.contains(saved, "- Existing reviewer convention"), saved);
    }

    @Test
    void confirmStep_saves_andCallsOnComplete() throws Exception {
        String[] completedType = {null};
        AgentCreateWizard w = atTypeStepManualPath(t -> completedType[0] = t);
        type(w, "code-reviewer");
        send(w, ENTER);
        type(w, "You are a thorough and careful code reviewer.");
        send(w, ENTER);
        type(w, "Use this agent after writing code");
        send(w, ENTER); // DESCRIPTION submit -> TOOLS
        send(w, ENTER); // Tools Continue -> MODEL
        send(w, ENTER); // Model confirm -> COLOR
        send(w, ENTER); // Color confirm -> MEMORY
        send(w, ENTER); // Memory confirm -> CONFIRM
        assertEquals(AgentCreateWizard.Step.CONFIRM, w.currentStep());

        send(w, ENTER); // save
        awaitSave(w);

        assertEquals("code-reviewer", completedType[0]);
        Path saved = projectDir.resolve(".claude/agents/code-reviewer.md");
        assertTrue(Files.isReadable(saved));
        assertTrue(Strings.CS.contains(Files.readString(saved), "code reviewer"));
    }

    @Test
    void confirmStep_eKey_savesAndOpensEditor() throws Exception {
        String[] completedType = {null};
        Path[] openedPath = {null};
        AgentCreateWizard w = new AgentCreateWizard();
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"), null,
            p -> openedPath[0] = p, t -> completedType[0] = t, () -> {});
        send(w, ENTER); // LOCATION -> METHOD
        send(w, DOWN); send(w, ENTER); // Manual -> TYPE
        type(w, "code-reviewer");
        send(w, ENTER); // -> PROMPT
        type(w, "You are a thorough and careful code reviewer.");
        send(w, ENTER); // -> DESCRIPTION
        type(w, "Use this agent after writing code");
        send(w, ENTER); // -> TOOLS
        send(w, ENTER); // -> MODEL
        send(w, ENTER); // -> COLOR
        send(w, ENTER); // -> MEMORY
        send(w, ENTER); // -> CONFIRM
        assertEquals(AgentCreateWizard.Step.CONFIRM, w.currentStep());

        send(w, new KeyStroke('e', false, false)); // save and open in editor
        awaitSave(w);

        assertEquals("code-reviewer", completedType[0]);
        assertNotNull(openedPath[0], "'e' must invoke openEditor with the saved file's path");
        assertEquals(projectDir.resolve(".claude/agents/code-reviewer.md"), openedPath[0]);
    }

    // ── generate path ────────────────────────────────────────────────────────

    @Test
    void generateSuccess_jumpsToToolsStep_backReturnsToGenerateStep() {
        String canned = "{\"identifier\":\"test-gen-agent\",\"whenToUse\":\"Use when testing\",\"systemPrompt\":\"You are a test agent.\"}";
        AgentCreateWizard w = new AgentCreateWizard();
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"), _ -> canned, null, _ -> {}, () -> {});

        send(w, ENTER); // LOCATION -> METHOD
        send(w, ENTER); // Generate (default) -> goNext -> GENERATE
        assertEquals(AgentCreateWizard.Step.GENERATE, w.currentStep());

        type(w, "a code reviewer agent");
        send(w, ENTER); // triggers async generation
        awaitStep(w, AgentCreateWizard.Step.TOOLS);

        assertEquals(AgentCreateWizard.Step.TOOLS, w.currentStep());

        send(w, ESC); // Back from a goToStep-jumped step returns to the jump origin (GENERATE)
        assertEquals(AgentCreateWizard.Step.GENERATE, w.currentStep());
    }

    @Test
    void generateCancelledThenLateCompletion_isDiscarded() throws InterruptedException {

        // sideQuestionRunner has no cancellation hook, so a background thread
        // may still be running when the user cancels. A late result must not
        // silently resurrect the wizard into TOOLS after the user already
        // navigated away.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        String canned = "{\"identifier\":\"late-agent\",\"whenToUse\":\"x\",\"systemPrompt\":\"y\"}";
        AgentCreateWizard w = new AgentCreateWizard();
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"), _ -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
                throw new RuntimeException("cancelled", e);
            }
            return canned;
        }, null, _ -> {}, () -> {});

        send(w, ENTER); // -> METHOD
        send(w, ENTER); // -> GENERATE
        type(w, "a code reviewer agent");
        send(w, ENTER); // starts the (blocked) background generation
        assertTrue(w.isGenerating());

        send(w, ESC); // cancel while still blocked
        assertFalse(w.isGenerating());
        assertEquals(AgentCreateWizard.Step.GENERATE, w.currentStep());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS),
            "cancel must interrupt the in-flight runner, not merely ignore its result");

        release.countDown(); // let the stale background thread finish late
        Thread.sleep(200);

        assertEquals(AgentCreateWizard.Step.GENERATE, w.currentStep(),
            "a late result from a cancelled generation must not move the wizard");
        assertEquals("Generation cancelled", w.generationErrorText());
    }

    @Test
    void generateFailure_showsErrorAndStaysOnGenerateStep() {
        AgentCreateWizard w = new AgentCreateWizard();
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"), _ -> "not valid json", null, _ -> {}, () -> {});

        send(w, ENTER); // -> METHOD
        send(w, ENTER); // -> GENERATE
        type(w, "a code reviewer agent");
        send(w, ENTER);

        long deadline = System.currentTimeMillis() + 2000;
        while (w.generationErrorText() == null && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException _) {}
        }

        assertEquals(AgentCreateWizard.Step.GENERATE, w.currentStep());
        assertNotNull(w.generationErrorText());
    }

    @Test
    void generateStep_emptyDescription_showsErrorWithoutCallingRunner() {
        boolean[] called = {false};
        AgentCreateWizard w = new AgentCreateWizard();
        w.activate(projectDir.toString(), List.of(), List.of("Read", "Bash"),
            _ -> { called[0] = true; return "{}"; }, null, _ -> {}, () -> {});
        send(w, ENTER); // METHOD
        send(w, ENTER); // GENERATE
        send(w, ENTER); // submit with empty input
        assertFalse(called[0]);
        assertNotNull(w.generationErrorText());
    }

    // ── inactive wizard ignores keys ─────────────────────────────────────────

    @Test
    void inactiveWizard_ignoresKeys() {
        AgentCreateWizard w = new AgentCreateWizard();
        AtomicBoolean deliver = new AtomicBoolean(true);
        w.handleKey(ENTER, deliver);
        assertTrue(deliver.get());
    }
}
