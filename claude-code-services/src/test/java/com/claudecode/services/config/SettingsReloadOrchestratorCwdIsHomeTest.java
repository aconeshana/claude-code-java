package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduces the exact scenario the developer hit during hand-testing:
 * the {@code java -jar ...} process runs with {@code cwd == $HOME}, so
 * {@link SettingsPaths#userSettingsPath} and
 * {@link SettingsPaths#projectSettingsPath(String)} collapse to the same
 * file. The reload path must still surface the rule and label the source
 * as "user settings" (not "project settings").
 */
class SettingsReloadOrchestratorCwdIsHomeTest {

    @TempDir Path fakeHome;

    private String originalHome;
    private PermissionGate gate;
    private HookEngine hookEngine;
    private List<String> uiMessages;
    private SettingsReloadOrchestrator orchestrator;

    @BeforeEach
    void setup() throws IOException {
        originalHome = System.getProperty("user.home");
        System.setProperty("user.home", fakeHome.toString());
        Files.createDirectories(fakeHome.resolve(".claude"));

        gate = new PermissionGate();
        hookEngine = new HookEngine(HooksSettings.EMPTY, fakeHome.toString());
        uiMessages = new ArrayList<>();

        // cwd == $HOME — the pathological case from the E2E screenshot.
        orchestrator = new SettingsReloadOrchestrator(
            gate, hookEngine, fakeHome.toString(), uiMessages::add);
    }

    @AfterEach
    void teardown() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
        orchestrator.close();
    }

    @Test
    void reload_cwdEqualsHome_stillLandsRuleAndLabelsAsUser() throws IOException {
        Files.writeString(fakeHome.resolve(".claude/settings.json"), """
            {
              "permissions": {"allow": ["Bash(echo hotreload:*)"]}
            }
            """);

        orchestrator.reload(RuleSource.USER_SETTINGS);

        List<PermissionRule> rules = gate.currentContext().rules();


        long hotreloadRules = rules.stream()
            .filter(r -> Strings.CS.contains(r.pattern().orElse(""), "hotreload"))
            .count();
        assertEquals(2, hotreloadRules,
            "cwd==home must preserve the two source-labelled rules from the "
                + "same physical file: " + rules);

        String msg = uiMessages.getLast();
        assertTrue(Strings.CS.contains(msg, "user settings"),
            "cwd==home must not mislabel a user edit as 'project settings': " + msg);
        assertTrue(Strings.CS.contains(msg, "2 permission rules"),
            "count in UI message should reflect the source-labelled view: " + msg);
    }
}
