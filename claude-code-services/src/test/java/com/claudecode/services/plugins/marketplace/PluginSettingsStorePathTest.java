package com.claudecode.services.plugins.marketplace;

import com.claudecode.core.state.CwdState;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import org.apache.commons.lang3.Strings;

/** Verifies production plugin settings writes follow the live session settings root. */
class PluginSettingsStorePathTest {

    @TempDir
    Path tempDir;

    private final Path originalCwd = CwdState.getOriginalCwd();

    @AfterEach
    void restoreCwd() {
        if (originalCwd == null) CwdState.clearForTesting();
        else CwdState.setOriginalCwd(originalCwd);
    }

    @Test
    void projectWritesFollowAnInSessionWorktreeSwitch() throws Exception {
        Path firstRoot = tempDir.resolve("first");
        Path secondRoot = tempDir.resolve("second");
        Files.createDirectories(firstRoot);
        Files.createDirectories(secondRoot);
        CwdState.clearForTesting();

        PluginSettingsStore store = PluginSettingsStore.standard(firstRoot.toString());
        CwdState.setOriginalCwd(firstRoot);
        store.setEnabledPlugin("demo@local", true, PluginScope.PROJECT);
        CwdState.setOriginalCwd(secondRoot);
        store.setEnabledPlugin("demo@worktree", true, PluginScope.PROJECT);

        assertTrue(Files.exists(firstRoot.resolve(".claude/settings.json")));
        assertTrue(Strings.CS.contains(Files.readString(firstRoot.resolve(".claude/settings.json")), "demo@local"));
        assertTrue(Files.exists(secondRoot.resolve(".claude/settings.json")));
        assertTrue(Strings.CS.contains(Files.readString(secondRoot.resolve(".claude/settings.json")), "demo@worktree"));
    }
}
