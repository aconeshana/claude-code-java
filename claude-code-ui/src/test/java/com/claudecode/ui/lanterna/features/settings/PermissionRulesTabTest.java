package com.claudecode.ui.lanterna.features.settings;

import java.util.Locale;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.lanterna.TextColor;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link PermissionRulesTab}'s list/search/add/delete state machine.
 */
class PermissionRulesTabTest {

    private static void awaitSave(PermissionRulesTab tab) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (tab.savingForTest() && System.nanoTime() < deadline) Thread.onSpinWait();
        assertFalse(tab.savingForTest(), "permission settings write timed out");
    }

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
        UiSettings.configure(new TestSettingsBackend());
    }

    @AfterEach
    void restore() {
        UiSettings.configure(null);
        System.setProperty("user.home", originalHome);
    }

    /** UI-layer test double: verifies delegation without depending on services. */
    private final class TestSettingsBackend implements UiSettings.Backend {
        @Override public boolean globalBoolean(String key, boolean defaultValue) { return defaultValue; }
        @Override public String globalString(String key, String defaultValue) { return defaultValue; }
        @Override public int globalInt(String key, int defaultValue) { return defaultValue; }
        @Override public void setGlobal(String key, Object value) { }
        @Override public boolean spinnerTipsEnabled() { return true; }
        @Override public boolean prefersReducedMotion() { return false; }
        @Override public Boolean policyBoolean(String key) { return null; }
        @Override public SandboxConfig sandboxConfig() { return SandboxConfig.disabled(); }
        @Override
        public void addPermissionRule(String cwd, PermissionBehavior behavior,
                                      String ruleString, RuleSource tier) {
            mutate(cwd, behavior, ruleString, tier, true);
        }

        @Override
        public void removePermissionRule(String cwd, PermissionBehavior behavior,
                                         String ruleString, RuleSource tier) {
            mutate(cwd, behavior, ruleString, tier, false);
        }

        private void mutate(String cwd, PermissionBehavior behavior, String ruleString,
                            RuleSource tier, boolean add) {
            Path file = switch (tier) {
                case USER_SETTINGS -> homeDir.resolve(".claude/settings.json");
                case PROJECT_SETTINGS -> Path.of(cwd).resolve(".claude/settings.json");
                case LOCAL_SETTINGS -> Path.of(cwd).resolve(".claude/settings.local.json");
                default -> throw new IllegalArgumentException("not a writable settings tier: " + tier);
            };
            try {
                Files.createDirectories(file.getParent());
                ObjectNode root = Files.isReadable(file)
                    ? (ObjectNode) JsonUtils.getMapper().readTree(file.toFile())
                    : JsonUtils.getMapper().createObjectNode();
                ObjectNode permissions = root.with("permissions");
                ArrayNode values = permissions.withArray(behavior.name().toLowerCase(Locale.ROOT));
                if (add) {
                    boolean exists = false;
                    for (var value : values) exists |= ruleString.equals(value.asText());
                    if (!exists) values.add(ruleString);
                } else {
                    for (int i = values.size() - 1; i >= 0; i--) {
                        if (ruleString.equals(values.get(i).asText())) values.remove(i);
                    }
                }
                JsonUtils.getMapper().writeValue(file.toFile(), root);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(PermissionRulesTab t, KeyStroke k) {
        t.handleKey(k, new AtomicBoolean(true));
    }

    private static void type(PermissionRulesTab t, String s) {
        for (char c : s.toCharArray()) {
            send(t, new KeyStroke(c, false, false));
        }
    }

    private PermissionRulesTab tabWithGate(PermissionGate gate) {
        PermissionRulesTab t = new PermissionRulesTab(PermissionBehavior.ALLOW);
        t.bind(() -> gate, () -> projectDir.toString());
        t.setTabVisible(true);
        t.reload();
        return t;
    }

    private PermissionGate gateWith(PermissionRule... rules) {
        return new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(projectDir)
            .rules(List.of(rules))
            .build());
    }

    // ── loading / filtering ──────────────────────────────────────────────────

    @Test
    void reload_onlyLoadsRulesMatchingOwnBehavior() {
        PermissionGate gate = gateWith(
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS),
            PermissionRule.of("Read", PermissionBehavior.DENY, RuleSource.USER_SETTINGS));
        PermissionRulesTab t = tabWithGate(gate);
        assertEquals(List.of("Bash"), t.filteredRuleStrings());
    }

    @Test
    void ruleList_capsVisibleOptionsAtTen() {
        List<PermissionRule> rules = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            rules.add(PermissionRule.of("Bash(rule-" + i + ")",
                PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS));
        }
        PermissionRulesTab t = tabWithGate(gateWith(rules.toArray(PermissionRule[]::new)));


        assertEquals(17, t.getPreferredSize().getRows());
    }

    @Test
    void typingInList_entersSearchAndFilters() {
        PermissionGate gate = gateWith(
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS),
            PermissionRule.of("Read", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS));
        PermissionRulesTab t = tabWithGate(gate);
        type(t, "Re");
        assertEquals(PermissionRulesTab.Mode.SEARCH, t.mode());
        assertEquals(List.of("Read"), t.filteredRuleStrings());
    }

    @Test
    void upAtTopOfList_entersSearchMode() {
        PermissionRulesTab t = tabWithGate(gateWith());
        send(t, UP);
        assertEquals(PermissionRulesTab.Mode.SEARCH, t.mode());
    }

    @Test
    void upInSearch_requestsHeaderFocus() {
        PermissionRulesTab t = tabWithGate(gateWith());
        AtomicBoolean requested = new AtomicBoolean(false);
        t.setOnFocusHeaderRequest(() -> requested.set(true));
        send(t, UP); // LIST -> SEARCH
        send(t, UP); // SEARCH -> focus header
        assertTrue(requested.get());
    }

    // ── paste (regression: see AddDirDialog's inputPhase_pasteInsertsTextAndConsumesTheKey) ──

    @Test
    void addInput_pasteInsertsTextAndConsumesTheKey() {

        // real Lanterna focus underneath (the main chat input) instead of
        // landing in this rule-string field.
        PermissionRulesTab t = tabWithGate(gateWith());
        send(t, ENTER); // ADD_INPUT

        AtomicBoolean deliver = new AtomicBoolean(true);
        t.handleKey(new PasteKeyStroke("Bash(git *)"), deliver);

        assertFalse(deliver.get(), "a paste inside the tab must be consumed, not leaked to the main input");
        send(t, ENTER); // submit -> ADD_DESTINATION
        assertEquals(PermissionRulesTab.Mode.ADD_DESTINATION, t.mode());
    }

    @Test
    void search_pasteInsertsTextAndFilters() {
        PermissionGate gate = gateWith(
            PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS),
            PermissionRule.of("Read", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS));
        PermissionRulesTab t = tabWithGate(gate);
        send(t, UP); // LIST -> SEARCH

        AtomicBoolean deliver = new AtomicBoolean(true);
        t.handleKey(new PasteKeyStroke("Re"), deliver);

        assertFalse(deliver.get());
        assertEquals(List.of("Read"), t.filteredRuleStrings());
    }

    // ── closing ──────────────────────────────────────────────────────────────

    @Test
    void escFromList_requestsClose() {
        PermissionRulesTab t = tabWithGate(gateWith());
        AtomicBoolean closed = new AtomicBoolean(false);
        t.setOnCloseRequest(() -> closed.set(true));
        send(t, ESC);
        assertTrue(closed.get());
    }

    // ── add rule flow ────────────────────────────────────────────────────────

    @Test
    void addRule_viaLocalSettings_persistsAndUpdatesGate() throws IOException {
        PermissionGate gate = gateWith();
        PermissionRulesTab t = tabWithGate(gate);

        // selectedIndex starts on the synthetic "Add a new rule..." row (only row, index 0)
        send(t, ENTER);
        assertEquals(PermissionRulesTab.Mode.ADD_INPUT, t.mode());

        type(t, "Bash(git *)");
        send(t, ENTER);
        assertEquals(PermissionRulesTab.Mode.ADD_DESTINATION, t.mode());

        send(t, ENTER); // destinationIdx defaults to 0 = "Project settings (local)"
        awaitSave(t);

        assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
        assertEquals(List.of("Bash(git *)"), t.filteredRuleStrings());
        assertTrue(gate.currentContext().rules().stream()
            .anyMatch(r -> Strings.CS.equals(r.toolName(), "Bash") && r.source() == RuleSource.LOCAL_SETTINGS));
        Path localSettings = projectDir.resolve(".claude").resolve("settings.local.json");
        assertTrue(Files.isReadable(localSettings));
        assertTrue(Strings.CS.contains(Files.readString(localSettings), "Bash(git *)"));
    }

    @Test
    void addRule_escFromInput_discardsAndReturnsToList() {
        PermissionRulesTab t = tabWithGate(gateWith());
        send(t, ENTER); // ADD_INPUT
        type(t, "Bash");
        send(t, ESC);
        assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
        assertEquals(List.of(), t.filteredRuleStrings());
    }

    @Test
    void addRule_escFromDestination_discardsWithoutPersisting() throws IOException {
        PermissionGate gate = gateWith();
        PermissionRulesTab t = tabWithGate(gate);
        send(t, ENTER);
        type(t, "Bash");
        send(t, ENTER); // ADD_DESTINATION
        send(t, ESC);
        assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
        assertEquals(List.of(), t.filteredRuleStrings());
        assertFalse(Files.exists(projectDir.resolve(".claude").resolve("settings.local.json")));
    }

    // ── delete rule flow ─────────────────────────────────────────────────────

    @Test
    void deleteRule_fromLocalSettings_removesFromDiskAndGate() throws IOException {
        Path localSettings = projectDir.resolve(".claude").resolve("settings.local.json");
        Files.createDirectories(localSettings.getParent());
        Files.writeString(localSettings, "{\"permissions\": {\"allow\": [\"Bash\"]}}");
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.LOCAL_SETTINGS);
        PermissionGate gate = gateWith(rule);
        PermissionRulesTab t = tabWithGate(gate);

        send(t, ENTER); // open RULE_DETAIL on the only rule row (index 0)
        assertEquals(PermissionRulesTab.Mode.RULE_DETAIL, t.mode());
        assertEquals(rule, t.detailRule());

        send(t, ENTER);
        awaitSave(t);

        assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
        assertEquals(List.of(), t.filteredRuleStrings());
        assertTrue(gate.currentContext().rules().isEmpty());
        assertFalse(Strings.CS.contains(Files.readString(localSettings), "\"Bash\""));
    }

    @Test
    void deleteRule_sessionSource_removesFromGateOnlyNoDiskTouch() {
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.SESSION);
        PermissionGate gate = gateWith(rule);
        PermissionRulesTab t = tabWithGate(gate);

        send(t, ENTER);
        send(t, ENTER);

        assertTrue(gate.currentContext().rules().isEmpty());
        assertFalse(Files.exists(projectDir.resolve(".claude")));
    }

    @Test
    void ruleDetail_policySource_hasNoDeleteOption() {
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.POLICY_SETTINGS);
        PermissionGate gate = gateWith(rule);
        PermissionRulesTab t = tabWithGate(gate);

        send(t, ENTER);
        assertEquals(PermissionRulesTab.Mode.RULE_DETAIL, t.mode());
        send(t, ENTER);
        assertEquals(PermissionRulesTab.Mode.RULE_DETAIL, t.mode(), "policy-sourced rules cannot be deleted");
        assertFalse(gate.currentContext().rules().isEmpty());

        send(t, ESC);
        assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
    }

    @Test
    void ruleDetail_declineWithN_returnsToListWithoutDeleting() {
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.LOCAL_SETTINGS);
        PermissionGate gate = gateWith(rule);
        PermissionRulesTab t = tabWithGate(gate);

        send(t, ENTER);
        send(t, new KeyStroke('n', false, false));

        assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
        assertFalse(gate.currentContext().rules().isEmpty());
    }

    @Test
    void ruleDetail_yDoesNotDeleteBecauseTsUsesSelectNotYesShortcut() {
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.SESSION);
        PermissionGate gate = gateWith(rule);
        PermissionRulesTab t = tabWithGate(gate);

        send(t, ENTER);
        send(t, new KeyStroke('y', false, false));

        assertEquals(PermissionRulesTab.Mode.RULE_DETAIL, t.mode());
        assertFalse(gate.currentContext().rules().isEmpty());
    }

    @Test
    void nestedStatesUseRuntimeSettingsConfirmationAndSelectBindings() throws Exception {
        Path file = tempDir.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Settings","bindings":{"x":"confirm:no","escape":null}},
              {"context":"Confirmation","bindings":{"z":"confirm:no","escape":null}},
              {"context":"Select","bindings":{"q":"select:next","a":"select:accept","escape":null}}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            PermissionGate gate = gateWith();
            PermissionRulesTab t = tabWithGate(gate);
            t.setKeybindingsStore(store);

            send(t, ENTER); // ADD_INPUT
            send(t, ESC);
            assertEquals(PermissionRulesTab.Mode.ADD_INPUT, t.mode());
            send(t, new KeyStroke('x', false, false));
            assertEquals(PermissionRulesTab.Mode.LIST, t.mode());

            send(t, ENTER);
            type(t, "Bash");
            send(t, ENTER); // ADD_DESTINATION
            send(t, new KeyStroke('q', false, false));
            send(t, new KeyStroke('a', false, false));
            awaitSave(t);
            assertTrue(gate.currentContext().rules().stream()
                .anyMatch(r -> r.source() == RuleSource.PROJECT_SETTINGS));

            send(t, ENTER); // RULE_DETAIL
            send(t, ESC);
            assertEquals(PermissionRulesTab.Mode.RULE_DETAIL, t.mode());
            send(t, new KeyStroke('z', false, false));
            assertEquals(PermissionRulesTab.Mode.LIST, t.mode());
        } finally {
            store.dispose();
        }
    }



    private record Recorded(String text, TextColor color) {}

    @Test
    void addRule_recordsAddedLine() throws IOException {
        PermissionGate gate = gateWith();
        PermissionRulesTab t = tabWithGate(gate);
        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));

        send(t, ENTER); // ADD_INPUT (only row is "+ Add a new rule...")
        type(t, "Bash(git *)");
        send(t, ENTER); // ADD_DESTINATION
        send(t, ENTER); // destinationIdx defaults to 0 = Local settings
        awaitSave(t);

        assertEquals(1, recorded.size());
        assertEquals("Added allow rule Bash(git *)", recorded.getFirst().text());
    }

    @Test
    void addRule_shadowedByExistingAskRule_alsoRecordsUnreachableWarning() {
        PermissionRule toolWideAsk = PermissionRule.of("Bash", PermissionBehavior.ASK, RuleSource.USER_SETTINGS);
        PermissionGate gate = gateWith(toolWideAsk);
        PermissionRulesTab t = tabWithGate(gate); // Allow tab: the existing Ask rule isn't in its own filtered list

        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));

        send(t, ENTER); // only row is still "+ Add a new rule..." (Allow tab has no Allow rules yet)
        type(t, "Bash(git *)");
        send(t, ENTER);
        send(t, ENTER); // destinationIdx defaults to 0 = Local settings
        awaitSave(t);

        assertEquals(4, recorded.size());
        assertEquals("Added allow rule Bash(git *)", recorded.getFirst().text());
        assertTrue(Strings.CS.startsWith(recorded.get(1).text(), "⚠ Warning: Bash(git *) is shadowed"), recorded.get(1).text());
        assertTrue(Strings.CS.startsWith(recorded.get(2).text(), "  Shadowed by \"Bash\" ask rule"), recorded.get(2).text());
        assertTrue(Strings.CS.startsWith(recorded.get(3).text(), "  Fix:"), recorded.get(3).text());
    }

    @Test
    void addRule_notShadowed_recordsNoWarning() {
        PermissionGate gate = gateWith();
        PermissionRulesTab t = tabWithGate(gate);
        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));

        send(t, ENTER);
        type(t, "Bash(git *)");
        send(t, ENTER);
        send(t, ENTER);
        awaitSave(t);

        assertEquals(1, recorded.size(), "no shadowing rule present — only the plain Added line");
    }

    @Test
    void deleteRule_recordsDeletedLine() {
        PermissionRule rule = PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.SESSION);
        PermissionGate gate = gateWith(rule);
        PermissionRulesTab t = tabWithGate(gate);
        List<Recorded> recorded = new ArrayList<>();
        t.setChangeRecorder((text, color) -> recorded.add(new Recorded(text, color)));

        send(t, ENTER); // RULE_DETAIL
        send(t, ENTER);

        assertEquals(1, recorded.size());
        assertEquals("Deleted allow rule Bash", recorded.getFirst().text());
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
