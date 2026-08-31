package com.claudecode.ui.lanterna.features.agents;

import org.apache.commons.lang3.Strings;

import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskState;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AgentsPanel}.
 */
class AgentsPanelTest {

    @TempDir Path tempDir;
    private String originalHome;
    private Path homeDir;
    private Path projectDir;

    @BeforeEach
    void redirect() throws IOException {
        originalHome = System.getProperty("user.home");
        homeDir = tempDir.resolve("home");
        projectDir = tempDir.resolve("project");
        Files.createDirectories(homeDir);
        Files.createDirectories(projectDir);
        System.setProperty("user.home", homeDir.toString());
        AgentDefinitionLoader.clearCache();
    }

    @AfterEach
    void restore() {
        System.setProperty("user.home", originalHome);
        AgentDefinitionLoader.clearCache();
    }

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(AgentsPanel p, KeyStroke k) {
        p.handleKey(k, new AtomicBoolean(true));
    }

    /**
     * Navigates from LIST's default selection (the Create row, index 0) down
     * to the given agent and presses Enter to select it, robust against
     * {@code ~/.claude/agents/*.md} pollution from the real developer
     * machine: {@link com.claudecode.tools.agent.AgentDefinitionLoader} resolves
     * {@code ClaudePaths.AGENTS_DIR} from a {@code static final} field set at
     * class-load time, so redirecting {@code user.home} in {@code @BeforeEach}
     * has no effect if another test in the same JVM fork already triggered
     * that static init first — a hardcoded arrow-key count would silently
     * break depending on test run order, while indexing into the live
     * {@link AgentsPanel#listedAgentTypes} does not.
     */
    private static void selectAgent(AgentsPanel p, String agentType) {
        if (p.tab() == AgentsPanel.Tab.ACTIVE) send(p, new KeyStroke(KeyType.ARROW_RIGHT));
        send(p, DOWN);
        List<String> types = p.selectableAgentTypes();
        int idx = types.indexOf(agentType);
        assertTrue(idx >= 0, "agent not found in list: " + agentType + " (list: " + types + ")");
        for (int i = 0; i <= idx; i++) send(p, DOWN); // selectable index 0 = Create row, so agent i sits at index i+1
        send(p, ENTER);
    }

    private record Recorded(String text, TextColor color) {}

    private AgentsPanel openedPanel(List<Recorded> recorded, Runnable onClose) {
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read", "Bash"),
            (text, color) -> recorded.add(new Recorded(text, color)), _ -> {}, onClose);
        return p;
    }

    private AgentsPanel openedPanel(List<Recorded> recorded, List<String> submitted, Runnable onClose) {
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read", "Bash"),
            new AgentsPanel.Inventory(
                AgentDefinitionLoader.getAll(projectDir.toString()),
                AgentDefinitionLoader.getActive(projectDir.toString())),
            submitted::add,
            (text, color) -> recorded.add(new Recorded(text, color)), _ -> {}, onClose);
        return p;
    }

    private void writeProjectAgent(String name, String description) throws IOException {
        Path dir = projectDir.resolve(".claude/agents");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".md"), """
            ---
            name: %s
            description: %s
            ---

            You are a helpful agent.
            """.formatted(name, description));
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void show_startsOnListMode() {
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        assertEquals(AgentsPanel.Mode.LIST, p.mode());
        assertEquals(AgentsPanel.Tab.ACTIVE, p.tab());
        assertTrue(p.isActive());
    }

    @Test
    void activeTabShowsRunningThenFiveMostRecentCompletedAgents() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState running = registry.store().createWithId("agent-running", TaskType.LOCAL_AGENT, "running work", null);
        registry.store().updateAgentType(running.id(), "reviewer");
        registry.store().updateStatus(running.id(), TaskStatus.RUNNING);
        for (int i = 0; i < 6; i++) {
            TaskState done = registry.store().createWithId("agent-done-" + i, TaskType.LOCAL_AGENT, "done " + i, null);
            registry.store().updateAgentType(done.id(), "reviewer");
            registry.store().updateStatus(done.id(), TaskStatus.RUNNING);
            registry.store().updateStatus(done.id(), TaskStatus.COMPLETED);
        }
        AgentsPanel p = new AgentsPanel(com.claudecode.runtime.memory.MemoryCatalog.empty(), registry);
        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(List.of(), List.of()), (_, _) -> {}, _ -> {}, () -> {});

        assertEquals(6, p.activeTaskIds().size());
        assertEquals("agent-running", p.activeTaskIds().getFirst());
        assertFalse(p.activeTaskIds().contains("agent-done-0"));
    }

    @Test
    void tabKeysSwitchBetweenActiveAndLibraryAndEnterViewsTask() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState running = registry.store().createWithId("agent-running", TaskType.LOCAL_AGENT, "running work", null);
        registry.store().updateAgentType(running.id(), "reviewer");
        registry.store().updateStatus(running.id(), TaskStatus.RUNNING);
        List<String> viewed = new ArrayList<>();
        AgentsPanel p = new AgentsPanel(com.claudecode.runtime.memory.MemoryCatalog.empty(), registry);
        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(List.of(), List.of()), _ -> {}, viewed::add,
            (_, _) -> {}, _ -> {}, () -> {});

        send(p, DOWN);
        send(p, ENTER);
        assertEquals(List.of("agent-running"), viewed);
        assertFalse(p.isActive());

        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(List.of(), List.of()), (_, _) -> {}, _ -> {}, () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));
        assertEquals(AgentsPanel.Tab.LIBRARY, p.tab());
        send(p, new KeyStroke(KeyType.ARROW_LEFT));
        assertEquals(AgentsPanel.Tab.ACTIVE, p.tab());
    }

    @Test
    void tabsStartFocusedAndDownHandsControlToTheFirstRunningAgent() {
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState first = registry.store().createWithId("agent-first", TaskType.LOCAL_AGENT, "first", null);
        registry.store().updateAgentType(first.id(), "reviewer");
        registry.store().updateStatus(first.id(), TaskStatus.RUNNING);
        TaskState second = registry.store().createWithId("agent-second", TaskType.LOCAL_AGENT, "second", null);
        registry.store().updateAgentType(second.id(), "reviewer");
        registry.store().updateStatus(second.id(), TaskStatus.RUNNING);
        List<String> viewed = new ArrayList<>();
        AgentsPanel p = new AgentsPanel(com.claudecode.runtime.memory.MemoryCatalog.empty(), registry);
        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(List.of(), List.of()), _ -> {}, viewed::add,
            (_, _) -> {}, _ -> {}, () -> {});

        assertTrue(p.headerFocusedForTest());
        send(p, DOWN);
        assertFalse(p.headerFocusedForTest());
        assertEquals(0, p.activeTaskIndexForTest());
        send(p, ENTER);

        assertEquals(List.of("agent-first"), viewed);
    }

    @Test
    void tabHeaderUsesReleasedPaddedLabelsInsteadOfSelectPointers() {
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});

        String rendered = rendered(p);

        assertTrue(rendered.contains(" Running "), rendered);
        assertTrue(rendered.contains(" Library "), rendered);
        assertFalse(rendered.contains("❯ Running"), rendered);
        assertFalse(rendered.contains("❯ Library"), rendered);
    }

    @Test
    void upFromFirstLibraryChoiceReturnsToTabHeaderWithoutWrapping() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});

        send(p, new KeyStroke(KeyType.ARROW_RIGHT));
        assertEquals(AgentsPanel.Tab.LIBRARY, p.tab());
        assertTrue(p.headerFocusedForTest());
        send(p, DOWN);
        assertFalse(p.headerFocusedForTest());
        assertEquals(0, p.librarySelectionIndexForTest());
        send(p, UP);

        assertTrue(p.headerFocusedForTest());
        assertEquals(0, p.librarySelectionIndexForTest());
    }

    @Test
    void show_canUseAnInventoryLoadedOffTheGuiThread() {
        AgentsPanel p = new AgentsPanel();

        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read"),
            new AgentsPanel.Inventory(List.of(), List.of()),
            (_, _) -> {}, _ -> {}, () -> {});

        assertTrue(p.isActive());
        assertTrue(p.listedAgentTypes().isEmpty(),
            "the supplied snapshot must be used instead of rescanning built-ins/files");
    }

    @Test
    void list_includesBuiltInAgents() {
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        assertTrue(p.listedAgentTypes().contains("general-purpose"));
        assertTrue(p.listedAgentTypes().contains("Explore"));
        assertFalse(p.selectableAgentTypes().contains("general-purpose"));
        assertFalse(p.selectableAgentTypes().contains("Explore"));
    }

    @Test
    void libraryGroupsFollowReleasedSourceOrder() {
        List<BuiltInAgentDefinitions.AgentDefinition> agents = List.of(
            agent("managed-one", AgentSource.MANAGED),
            agent("user-one", AgentSource.USER),
            agent("project-one", AgentSource.PROJECT),
            agent("plugin-one", AgentSource.PLUGIN),
            agent("cli-one", AgentSource.FLAG_SETTINGS));
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(agents, agents), (_, _) -> {}, _ -> {}, () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));

        String screen = rendered(p);
        assertTrue(screen.indexOf("User agents") < screen.indexOf("Project agents"), screen);
        assertTrue(screen.indexOf("Project agents") < screen.indexOf("Managed agents"), screen);
        assertTrue(screen.indexOf("Managed agents") < screen.indexOf("Plugin agents"), screen);
        assertTrue(screen.indexOf("Plugin agents") < screen.indexOf("CLI arg agents"), screen);
    }

    @Test
    void libraryShowsReleasedBaseDirectoryBesideCustomSourceHeading() {
        BuiltInAgentDefinitions.AgentDefinition projectAgent = agent("reviewer", AgentSource.PROJECT)
            .toBuilder()
            .filePath(Path.of("/work/.claude/agents/reviewer.md"))
            .build();
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(List.of(projectAgent), List.of(projectAgent)),
            (_, _) -> {}, _ -> {}, () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));

        String screen = rendered(p);
        assertTrue(screen.contains("Project agents (/work/.claude/agents)"), screen);
    }

    @Test
    void libraryUsesReleasedGroupAndCreateLabels() {
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));

        String screen = rendered(p);
        assertTrue(screen.contains("Create new agent"), screen);
        assertFalse(screen.contains("+ Create new agent"), screen);
        assertTrue(screen.contains("Built-in (always available):"), screen);
    }

    @Test
    void libraryShowsReleasedEmptyCustomAgentGuidanceBeforeBuiltIns() {
        List<BuiltInAgentDefinitions.AgentDefinition> builtIns =
            BuiltInAgentDefinitions.getBuiltInAgents();
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read", "Bash"),
            new AgentsPanel.Inventory(builtIns, builtIns), (_, _) -> {}, _ -> {}, () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));

        String screen = rendered(p);
        assertTrue(screen.contains(
            "No agents found. Create specialized subagents that Claude can delegate to."), screen);
        assertTrue(screen.indexOf("No agents found.")
            < screen.indexOf("Built-in (always available):"), screen);
    }

    @Test
    void libraryUsesReleasedTerminalHeightViewport() {
        List<BuiltInAgentDefinitions.AgentDefinition> agents = new ArrayList<>();
        for (int i = 1; i <= 8; i++) agents.add(agent("agent-" + i, AgentSource.USER));
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of(),
            new AgentsPanel.Inventory(agents, agents), (_, _) -> {}, _ -> {}, () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));

        TerminalSize size = new TerminalSize(76, 20);
        p.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        p.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        String screen = renderedText(image);

        assertTrue(screen.contains("↓ 2 more"), screen);
        assertFalse(screen.contains("agent-7"), screen);
        assertFalse(screen.contains("agent-8"), screen);
    }

    @Test
    void list_includesCustomProjectAgent() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        assertTrue(p.listedAgentTypes().contains("my-reviewer"));
    }

    @Test
    void list_projectAgentsSortBeforeBuiltIns() throws IOException {

        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        List<String> types = p.listedAgentTypes();
        assertTrue(types.indexOf("my-reviewer") < types.indexOf("general-purpose"),
            "project agents must be listed before built-in agents: " + types);
    }

    @Test
    void escFromList_closesPanel() {
        boolean[] closed = {false};
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> closed[0] = true);
        send(p, ESC);
        assertTrue(closed[0]);
        assertFalse(p.isActive());
    }

    @Test
    void enterOnCreateRow_entersCreateMode() {
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        send(p, new KeyStroke(KeyType.ARROW_RIGHT));
        send(p, DOWN);
        send(p, ENTER); // "+ Create new agent" is the first selectable row
        assertEquals(AgentsPanel.Mode.CREATE, p.mode());
        assertTrue(p.createWizard().isActive());
    }

    // ── agent menu / view ────────────────────────────────────────────────────

    @Test
    void selectingAgent_opensAgentMenu() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        assertEquals(AgentsPanel.Mode.AGENT_MENU, p.mode());
        assertEquals("my-reviewer", p.selectedAgent().agentType());
    }

    @Test
    void agentMenuSelectBindingsCanBeRebound() throws Exception {
        writeProjectAgent("my-reviewer", "Reviews code");
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Select","bindings":{
                "x":"select:next","z":"select:accept","q":"select:cancel",
                "down":null,"enter":null,"escape":null
              }},
              {"context":"Confirmation","bindings":{"escape":null}}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
            selectAgent(p, "my-reviewer");
            p.setKeybindingsStore(store);

            send(p, DOWN);
            send(p, ENTER);
            assertEquals(AgentsPanel.Mode.AGENT_MENU, p.mode(),
                "unbound Down/Enter must not activate a menu item");
            send(p, new KeyStroke('x', false, false));
            send(p, new KeyStroke('x', false, false));
            send(p, new KeyStroke('z', false, false));
            assertEquals(AgentsPanel.Mode.EDIT_MENU, p.mode());
            send(p, ESC);
            assertEquals(AgentsPanel.Mode.EDIT_MENU, p.mode(), "unbound Escape must not go back");
        } finally {
            store.dispose();
        }
    }

    @Test
    void runAgent_submitsMentionPromptAndClosesPanel() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        List<String> submitted = new ArrayList<>();
        boolean[] closed = {false};
        AgentsPanel p = openedPanel(new ArrayList<>(), submitted, () -> closed[0] = true);
        selectAgent(p, "my-reviewer");

        send(p, ENTER); // Run agent
        assertEquals(AgentsPanel.Mode.RUN, p.mode());
        for (char c : "review this diff".toCharArray()) send(p, new KeyStroke(c, false, false));
        send(p, ENTER);

        assertEquals(List.of("@agent-my-reviewer review this diff"), submitted);
        assertTrue(closed[0]);
        assertFalse(p.isActive());
    }

    @Test
    void runAgentPromptSupportsCursorEditing() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        List<String> submitted = new ArrayList<>();
        AgentsPanel p = openedPanel(new ArrayList<>(), submitted, () -> {});
        selectAgent(p, "my-reviewer");
        send(p, ENTER);
        for (char c : "abc".toCharArray()) send(p, new KeyStroke(c, false, false));
        send(p, new KeyStroke(KeyType.ARROW_LEFT));
        send(p, new KeyStroke(KeyType.BACKSPACE));
        send(p, ENTER);

        assertEquals(List.of("@agent-my-reviewer ac"), submitted);
    }

    @Test
    void runAgentPromptAcceptsBracketedPasteAtTheCursor() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        List<String> submitted = new ArrayList<>();
        AgentsPanel p = openedPanel(new ArrayList<>(), submitted, () -> {});
        selectAgent(p, "my-reviewer");
        send(p, ENTER);
        for (char c : "ac".toCharArray()) send(p, new KeyStroke(c, false, false));
        send(p, new KeyStroke(KeyType.ARROW_LEFT));
        send(p, new PasteKeyStroke("foo\nbar"));
        send(p, ENTER);

        assertEquals(List.of("@agent-my-reviewer afoo barc"), submitted);
    }

    @Test
    void agentMenuOffersViewRunningInstanceForMatchingAgentType() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        TaskRegistry registry = new TaskRegistry(TaskStore.inMemory());
        TaskState running = registry.store().createWithId("agent-running", TaskType.LOCAL_AGENT, "reviewing", null);
        registry.store().updateAgentType(running.id(), "my-reviewer");
        registry.store().updateStatus(running.id(), TaskStatus.RUNNING);
        List<String> viewed = new ArrayList<>();
        AgentsPanel p = new AgentsPanel(com.claudecode.runtime.memory.MemoryCatalog.empty(), registry);
        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read"),
            AgentsPanel.loadInventory(projectDir.toString()), _ -> {}, viewed::add,
            (_, _) -> {}, _ -> {}, () -> {});

        selectAgent(p, "my-reviewer");
        send(p, DOWN); // Run agent -> View running instance
        send(p, ENTER);

        assertEquals(List.of("agent-running"), viewed);
        assertFalse(p.isActive());
    }

    @Test
    void flagAgentsAreRunAndViewOnlyLikeReleased197() {
        BuiltInAgentDefinitions.AgentDefinition flagAgent = BuiltInAgentDefinitions.AgentDefinition
            .builder("flag-reviewer", "Reviews code")
            .source(AgentSource.FLAG_SETTINGS)
            .systemPrompt("Review the requested code carefully.")
            .build();
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read"),
            new AgentsPanel.Inventory(List.of(flagAgent), List.of(flagAgent)),
            (_, _) -> {}, _ -> {}, () -> {});

        selectAgent(p, "flag-reviewer");
        send(p, DOWN); send(p, DOWN); // Run -> View -> Back
        send(p, ENTER);

        assertEquals(AgentsPanel.Mode.LIST, p.mode(),
            "flag settings agents must not expose edit/delete actions");
    }

    @Test
    void viewAgent_thenEsc_returnsToAgentMenu() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer"); // AGENT_MENU
        send(p, DOWN); send(p, ENTER); // "View agent" -> VIEW
        assertEquals(AgentsPanel.Mode.VIEW, p.mode());
        send(p, ESC);
        assertEquals(AgentsPanel.Mode.AGENT_MENU, p.mode());
    }

    @Test
    void detailViewExpandsForTheFullSystemPromptLikeReleased197() {
        String prompt = "This is a deliberately long system prompt. ".repeat(30);
        BuiltInAgentDefinitions.AgentDefinition agent = BuiltInAgentDefinitions.AgentDefinition
            .builder("long-reviewer", "Reviews code when a detailed review is needed")
            .source(AgentSource.PROJECT)
            .filePath(projectDir.resolve(".claude/agents/long-reviewer.md"))
            .systemPrompt(prompt)
            .permissionMode("plan")
            .skills(List.of("reviewing", "testing"))
            .build();
        AgentsPanel p = new AgentsPanel();
        p.show(() -> projectDir.toString(), _ -> "{}", List.of("Read"),
            new AgentsPanel.Inventory(List.of(agent), List.of(agent)),
            (_, _) -> {}, _ -> {}, () -> {});

        selectAgent(p, "long-reviewer");
        send(p, DOWN); send(p, ENTER); // View agent

        assertEquals(AgentsPanel.Mode.VIEW, p.mode());
        assertTrue(p.calculatePreferredSize().getRows() > 20,
            "the released detail view renders the full prompt instead of a 200-character preview");
    }

    // ── delete ───────────────────────────────────────────────────────────────

    @Test
    void deleteAgent_confirmDefaultChoice_removesFileAndRecordsChange() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        List<Recorded> recorded = new ArrayList<>();
        AgentsPanel p = openedPanel(recorded, () -> {});
        selectAgent(p, "my-reviewer"); // AGENT_MENU
        send(p, DOWN); send(p, DOWN); send(p, DOWN); // -> "Delete agent"
        send(p, ENTER);
        assertEquals(AgentsPanel.Mode.DELETE_CONFIRM, p.mode());

        send(p, ENTER); // "Yes, delete"

        assertEquals(AgentsPanel.Mode.LIST, p.mode());
        assertFalse(Files.exists(projectDir.resolve(".claude/agents/my-reviewer.md")));
        assertTrue(recorded.stream().anyMatch(r -> Strings.CS.equals(r.text(), "Deleted agent: my-reviewer")));
    }

    @Test
    void deleteAgent_selectNo_keepsFile() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        send(p, DOWN); send(p, DOWN); send(p, DOWN);
        send(p, ENTER);
        send(p, DOWN); // "No, cancel"
        send(p, ENTER);

        assertEquals(AgentsPanel.Mode.AGENT_MENU, p.mode());
        assertTrue(Files.exists(projectDir.resolve(".claude/agents/my-reviewer.md")));
    }

    // ── edit ─────────────────────────────────────────────────────────────────

    @Test
    void editTools_confirmSelection_persistsAndRecordsChange() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        List<Recorded> recorded = new ArrayList<>();
        AgentsPanel p = openedPanel(recorded, () -> {});
        selectAgent(p, "my-reviewer"); // AGENT_MENU
        send(p, DOWN); send(p, DOWN); // -> "Edit agent"
        send(p, ENTER); // EDIT_MENU
        send(p, DOWN); // "Open in editor" -> "Edit tools"
        send(p, ENTER); // EDIT_TOOLS

        assertEquals(AgentsPanel.Mode.EDIT_TOOLS, p.mode());
        send(p, ENTER); // Tools picker "Continue" (default: all tools selected)

        assertEquals(AgentsPanel.Mode.EDIT_MENU, p.mode());
        assertTrue(recorded.isEmpty(), "unchanged tools must not create a fake update");
        assertTrue(Strings.CS.contains(Files.readString(projectDir.resolve(".claude/agents/my-reviewer.md")), "my-reviewer"));
    }

    @Test
    void editColor_confirmSelection_persistsColor() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer"); // AGENT_MENU
        send(p, DOWN); send(p, DOWN); // Edit agent
        send(p, ENTER); // EDIT_MENU
        send(p, DOWN); send(p, DOWN); send(p, DOWN); // Edit color
        send(p, ENTER); // EDIT_COLOR

        assertEquals(AgentsPanel.Mode.EDIT_COLOR, p.mode());
        send(p, DOWN); // automatic -> red
        send(p, ENTER);

        assertEquals(AgentsPanel.Mode.AGENT_MENU, p.mode());
        assertTrue(Strings.CS.contains(Files.readString(projectDir.resolve(".claude/agents/my-reviewer.md")), "color: red"));
    }

    @Test
    void editModel_persistsReleasedLiteralInheritValue() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        send(p, DOWN); send(p, DOWN); send(p, ENTER); // Edit agent -> EDIT_MENU
        send(p, DOWN); send(p, DOWN);  // Open editor -> tools -> model
        send(p, ENTER);

        assertEquals(AgentsPanel.Mode.EDIT_MODEL, p.mode());
        send(p, DOWN); send(p, DOWN); send(p, DOWN); // Sonnet -> Opus -> Haiku -> Inherit
        send(p, ENTER);

        String saved = Files.readString(projectDir.resolve(".claude/agents/my-reviewer.md"));
        assertTrue(Strings.CS.contains(saved, "model: inherit"), saved);
    }

    @Test
    void escFromEditMenu_returnsToAgentMenuWithoutChanges() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        send(p, DOWN); send(p, DOWN);
        send(p, ENTER); // EDIT_MENU
        send(p, ESC);
        assertEquals(AgentsPanel.Mode.AGENT_MENU, p.mode());
    }

    @Test
    void editMenuClampsAtEndsLikeReleasedAgentEditor() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        send(p, DOWN); send(p, DOWN); send(p, ENTER); // Edit agent -> EDIT_MENU at first item

        send(p, UP);   // stays on first item
        send(p, DOWN); // moves to Edit tools
        send(p, ENTER);

        assertEquals(AgentsPanel.Mode.EDIT_TOOLS, p.mode(),
            "AgentEditor clamps navigation instead of wrapping first ↔ last");
    }

    @Test
    void editMenuEndsAtEditColorInsteadOfAddingABackChoice() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        send(p, DOWN); send(p, DOWN); send(p, ENTER);

        send(p, DOWN); send(p, DOWN); send(p, DOWN); send(p, DOWN); send(p, DOWN);
        send(p, ENTER);

        assertEquals(AgentsPanel.Mode.EDIT_COLOR, p.mode(),
            "released AgentEditor has four choices; Escape is the only way back");
    }

    @Test
    void editMenuShowsTheReleasedSourceLine() throws IOException {
        writeProjectAgent("my-reviewer", "Reviews code");
        AgentsPanel p = openedPanel(new ArrayList<>(), () -> {});
        selectAgent(p, "my-reviewer");
        send(p, DOWN); send(p, DOWN); send(p, ENTER);

        String screen = rendered(p);
        assertTrue(screen.contains("Source: Project"), screen);
    }

    // ── inactive panel ───────────────────────────────────────────────────────

    @Test
    void inactivePanel_ignoresKeys() {
        AgentsPanel p = new AgentsPanel();
        AtomicBoolean deliver = new AtomicBoolean(true);
        p.handleKey(ENTER, deliver);
        assertTrue(deliver.get());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private static String rendered(AgentsPanel panel) {
        TerminalSize size = panel.calculatePreferredSize();
        panel.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        panel.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        return renderedText(image);
    }

    private static String renderedText(BasicTextImage image) {
        TerminalSize size = image.getSize();
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                text.append(image.getCharacterAt(column, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }

    private static BuiltInAgentDefinitions.AgentDefinition agent(
            String name, AgentSource source) {
        return BuiltInAgentDefinitions.AgentDefinition.builder(name, "Use " + name)
            .source(source)
            .systemPrompt("You are " + name)
            .build();
    }
}
