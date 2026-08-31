package com.claudecode.services.hooks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies the skill-root environment carried by prompt-command hooks. */
class HookEngineSkillRootTest {

    @TempDir Path skillRoot;

    @Test
    void extraHookReceivesSkillRootInCommandTemplateAndEnvironment() throws Exception {
        assumeTrue(shellAvailable(), "sh not available");
        String command = "printf '%s' \"$CLAUDE_PLUGIN_ROOT\" > "
            + "\"${CLAUDE_PLUGIN_ROOT}/captured-root.txt\"";
        HooksSettings settings = new HooksSettings(Map.of(
            HookEvent.STOP,
            List.of(new HookMatcher(Optional.empty(),
                List.of(new BashCommandHook(command))))));
        HookEngine engine = new HookEngine(HooksSettings.EMPTY, null);

        engine.addExtraHooks(settings, skillRoot);
        engine.executeHooks(HookEvent.STOP, HookInput.forStop(false));

        assertEquals(skillRoot.toString(),
            Files.readString(skillRoot.resolve("captured-root.txt")));
    }

    private static boolean shellAvailable() {
        try {
            Process process = new ProcessBuilder("sh", "-c", "true").start();
            return process.waitFor() == 0;
        } catch (Exception _) {
            return false;
        }
    }
}
