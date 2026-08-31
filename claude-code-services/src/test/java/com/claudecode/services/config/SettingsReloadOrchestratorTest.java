package com.claudecode.services.config;

import org.apache.commons.lang3.Strings;

import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.HooksSettings;
import org.junit.jupiter.api.AfterEach;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the orchestrator's {@link SettingsReloadOrchestrator#reload}
 * apply-side plumbing without spinning up the OS file watcher — we call
 * {@code reload(source)} directly and verify the permission gate + hook
 * engine end up in the expected state.
 *
 * <p>Uses a temp home directory (via {@code user.home} system property
 * override) so tests don't touch the real.
 * The property is restored in {@code @AfterEach} on other test classes if
 * needed; here we set it in {@code @BeforeEach} and let the JVM's original
 * value be restored implicitly at process exit (test-only side effect,
 * safe for the surefire fork).
 */
class SettingsReloadOrchestratorTest {

    @TempDir Path fakeHome;

    private String originalHome;
    private String originalUserDir;
    private PermissionGate gate;
    private HookEngine hookEngine;
    private List<String> uiMessages;
    private SettingsReloadOrchestrator orchestrator;

    @BeforeEach
    void setup() throws IOException {
        originalHome = System.getProperty("user.home");
        originalUserDir = System.getProperty("user.dir");
// Route SettingsPaths.userSettingsPath into our temp dir.
        System.setProperty("user.home", fakeHome.toString());
        Files.createDirectories(fakeHome.resolve(".claude"));

        gate = new PermissionGate();
        hookEngine = new HookEngine(HooksSettings.EMPTY, fakeHome.toString());
        uiMessages = new ArrayList<>();

        // Give the orchestrator its own cwd (also isolated) so no project /
        // local settings files interfere with the assertions.
        String isolatedCwd = fakeHome.resolve("proj").toString();
        Files.createDirectories(Path.of(isolatedCwd, ".claude"));

        orchestrator = new SettingsReloadOrchestrator(
            gate, hookEngine, isolatedCwd, uiMessages::add);
    }

    // Restore user.home so it doesn't leak into sibling tests.
    @AfterEach
    void teardown() {
        if (originalHome != null) System.setProperty("user.home", originalHome);
        if (originalUserDir != null) System.setProperty("user.dir", originalUserDir);
        orchestrator.close();
    }

    @Test
    void reload_appliesPermissionRulesFromUserSettings() throws IOException {
        Files.writeString(fakeHome.resolve(".claude/settings.json"), """
            {
              "permissions": {
                "allow": ["Bash(git *)", "Read"],
                "deny": ["Write(**/.env)"]
              }
            }
            """);

        orchestrator.reload(RuleSource.USER_SETTINGS);

        List<PermissionRule> rules = gate.currentContext().rules();
        assertEquals(3, rules.size(), "should ingest 2 allow + 1 deny");
        assertTrue(rules.stream()
                .anyMatch(r -> r.behavior() == PermissionBehavior.DENY
                    && Strings.CS.equals(r.pattern().orElse(""), "**/.env")),
            "deny rule must round-trip through the pattern parser");
    }

    @Test
    void reload_preservesSessionRulesOnDiskChange() throws IOException {
        // Pre-existing session rule added via /permissions during runtime.
        PermissionRule sessionRule = PermissionRule.of(
            "Grep", PermissionBehavior.ALLOW, RuleSource.SESSION);
        gate.addRules(List.of(sessionRule));

        Files.writeString(fakeHome.resolve(".claude/settings.json"), """
            {"permissions": {"allow": ["Bash"]}}
            """);
        orchestrator.reload(RuleSource.USER_SETTINGS);

        List<PermissionRule> rules = gate.currentContext().rules();
        assertTrue(rules.contains(sessionRule),
            "SESSION rules must survive a disk sync — a user edit to settings.json "
                + "shouldn't drop rules the user added this session via /permissions");
    }

    @Test
    void reload_swapsHookSettings() throws IOException {
        Files.writeString(fakeHome.resolve(".claude/settings.json"), """
            {
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "echo pre"}]}
                ]
              }
            }
            """);

        orchestrator.reload(RuleSource.USER_SETTINGS);

        assertFalse(hookEngine.currentSettings().eventHooks().isEmpty(),
            "hook settings should reflect the on-disk config after reload");
    }

    @Test
    void reload_notifiesUiSinkWithHumanReadableSource() throws IOException {
        Files.writeString(fakeHome.resolve(".claude/settings.json"),
            "{\"permissions\": {\"allow\": []}}");

        orchestrator.reload(RuleSource.USER_SETTINGS);

        assertFalse(uiMessages.isEmpty(), "UI sink should receive a notification");
        assertTrue(Strings.CS.contains(uiMessages.getFirst(), "user settings"),
            "notification should use friendly source label, not enum name: " + uiMessages);
    }

    @Test
    void reloadAppliesBypassPermissionsKillSwitchToLiveGate() throws IOException {
        gate.setBypassPermissionsModeAvailable(true);
        gate.setMode(PermissionMode.BYPASS_PERMISSIONS);
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, gate.currentMode());

        Files.writeString(fakeHome.resolve(".claude/settings.json"), """
            {"permissions": {"disableBypassPermissionsMode": "disable"}}
            """);
        orchestrator.reload(RuleSource.USER_SETTINGS);

        assertFalse(gate.isBypassPermissionsModeAvailable());
        assertEquals(PermissionMode.DEFAULT, gate.currentMode());

        Files.writeString(fakeHome.resolve(".claude/settings.json"), "{}");
        orchestrator.reload(RuleSource.USER_SETTINGS);

        // kill-switch has fired; a fresh process recomputes startup capability.
        assertFalse(gate.isBypassPermissionsModeAvailable());
    }

    @Test
    void reload_malformedJson_treatsSourceAsEmptyAndAppliesSnapshot() throws IOException {

        Files.writeString(fakeHome.resolve(".claude/settings.json"), """
            {
              "permissions": {"allow": ["Bash(echo hi:*)"]},
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "echo pre"}]}
                ]
              }
            }
            """);
        orchestrator.reload(RuleSource.USER_SETTINGS);

        int baselineRuleCount = gate.currentContext().rules().size();
        int baselineHookEvents = hookEngine.currentSettings().eventHooks().size();
        assertTrue(baselineRuleCount > 0, "baseline should have loaded at least the allow rule");
        assertTrue(baselineHookEvents > 0, "baseline should have loaded PreToolUse hook");
        int uiCountBefore = uiMessages.size();

        // Step 2: user (or an editor mid-save) writes malformed JSON.
        Files.writeString(fakeHome.resolve(".claude/settings.json"), "{ this is broken");


        // empty, so the previously loaded rules/hooks are removed while the
        // rest of the settings snapshot is still applied.
        assertDoesNotThrow(() -> orchestrator.reload(RuleSource.USER_SETTINGS));

        assertEquals(0, gate.currentContext().rules().size(),
            "malformed JSON source must contribute no permission rules");
        assertEquals(0, hookEngine.currentSettings().eventHooks().size(),
            "malformed JSON source must contribute no hooks");

        // Step 4: the completed fan-out is still visible to the UI/listeners.
        assertTrue(uiMessages.size() > uiCountBefore,
            "orchestrator should surface the completed reload");
        assertTrue(Strings.CS.contains(uiMessages.getLast(), "reloaded"),
            "notification should describe a completed reload: " + uiMessages);
    }

    @Test
    void subscribeReload_firesOnEveryCompletedReload_includingMalformed() throws Exception {
        AtomicInteger fires = new AtomicInteger();
        AutoCloseable sub = orchestrator.subscribeReload(fires::incrementAndGet);

        try {
            Files.writeString(fakeHome.resolve(".claude/settings.json"),
                "{\"permissions\": {\"allow\": [\"Bash\"]}}");
            orchestrator.reload(RuleSource.USER_SETTINGS);
            assertEquals(1, fires.get(), "healthy reload must notify listener");

            Files.writeString(fakeHome.resolve(".claude/settings.json"), "{ oops");
            orchestrator.reload(RuleSource.USER_SETTINGS);
            assertEquals(2, fires.get(),
                "malformed reload still publishes the source-as-empty snapshot");

            Files.writeString(fakeHome.resolve(".claude/settings.json"),
                "{\"permissions\": {\"allow\": [\"Bash\", \"Read\"]}}");
            orchestrator.reload(RuleSource.USER_SETTINGS);
            assertEquals(3, fires.get(), "listener must fire again after recovery");
        } finally {
            sub.close();
        }
    }

    @Test
    void subscribeReload_unsubscribeStopsFurtherCalls() throws Exception {
        AtomicInteger fires = new AtomicInteger();
        AutoCloseable sub = orchestrator.subscribeReload(fires::incrementAndGet);

        Files.writeString(fakeHome.resolve(".claude/settings.json"),
            "{\"permissions\": {\"allow\": [\"Bash\"]}}");
        orchestrator.reload(RuleSource.USER_SETTINGS);
        assertEquals(1, fires.get());

        sub.close();

        orchestrator.reload(RuleSource.USER_SETTINGS);
        assertEquals(1, fires.get(), "closed subscription must not receive further events");
    }

    @Test
    void subscribeReload_listenerException_doesNotStopOtherListeners() throws Exception {
        AtomicInteger goodFires = new AtomicInteger();
        orchestrator.subscribeReload(() -> { throw new RuntimeException("boom"); });
        orchestrator.subscribeReload(goodFires::incrementAndGet);

        Files.writeString(fakeHome.resolve(".claude/settings.json"),
            "{\"permissions\": {\"allow\": [\"Bash\"]}}");
        orchestrator.reload(RuleSource.USER_SETTINGS);

        assertEquals(1, goodFires.get(),
            "a broken listener must not block downstream subscribers");
    }
}
