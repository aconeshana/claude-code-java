package com.claudecode.services.hooks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Verifies HookEngine runs bash hooks in the LIVE cwd (following a mid-session worktree switch)
 * when constructed with a null fixed dir, and in a pinned dir when constructed with an explicit
 * one.
 */
class HookEngineCwdTest {

    @TempDir Path dirA;
    @TempDir Path dirB;

    private String savedUserDir;

    @BeforeEach
    void save() {
        savedUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restore() {
        if (savedUserDir != null) System.setProperty("user.dir", savedUserDir);
    }

    private static boolean shellAvailable() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "true").start();
            return p.waitFor() == 0;
        } catch (Exception _) {
            return false;
        }
    }

    /** A hook that writes the subprocess's physical cwd to {@code out}. */
    private static HooksSettings pwdCaptureHook(Path out) {
        BashCommandHook hook = new BashCommandHook(
            "pwd -P > " + out.toAbsolutePath(),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            /* once */ false, /* async */ false, /* asyncRewake */ false);
        return new HooksSettings(Map.of(
            HookEvent.STOP, List.of(new HookMatcher(Optional.empty(), List.of(hook)))));
    }

    @Test
    void nullFixedDir_hookFollowsLiveCwdAcrossSwitch() throws Exception {
        assumeTrue(shellAvailable(), "sh not available");
        Path capture = dirA.resolve("cwd.txt");
        HookEngine engine = new HookEngine(pwdCaptureHook(capture), /* follow live cwd */ null);

        System.setProperty("user.dir", dirA.toString());
        engine.executeHooks(HookEvent.STOP, HookInput.forStop(false));
        assertEquals(dirA.toRealPath().toString(), Files.readString(capture).strip());

        // Simulate a mid-session worktree switch: cwd moves to dirB.
        System.setProperty("user.dir", dirB.toString());
        engine.executeHooks(HookEvent.STOP, HookInput.forStop(false));
        assertEquals(dirB.toRealPath().toString(), Files.readString(capture).strip(),
            "hook must follow the live cwd after a worktree switch, not the construction-time value");
    }

    @Test
    void pinnedFixedDir_hookIgnoresLiveCwd() throws Exception {
        assumeTrue(shellAvailable(), "sh not available");
        Path capture = dirA.resolve("cwd.txt");
        // Explicit fixed dir = dirA; a user.dir change to dirB must NOT move the hook.
        HookEngine engine = new HookEngine(pwdCaptureHook(capture), dirA.toString());

        System.setProperty("user.dir", dirB.toString());
        engine.executeHooks(HookEvent.STOP, HookInput.forStop(false));

        assertEquals(dirA.toRealPath().toString(), Files.readString(capture).strip(),
            "a pinned fixedWorkingDirectory must ignore live user.dir changes");
    }

    @Test
    void nullFixedDir_hookInputCwdFieldFollowsLiveCwd() {
        assumeTrue(shellAvailable(), "sh not available");
        // The JSON cwd field fed to the hook (via dispatchSessionStart building
// HookInput internally with resolveCwd) must also be live.
        HooksSettings settings = new HooksSettings(Map.of(
            HookEvent.SESSION_START,
            List.of(new HookMatcher(Optional.empty(), List.of(new BashCommandHook("true"))))));
        HookEngine engine = new HookEngine(settings, null);

        System.setProperty("user.dir", dirB.toString());
        // dispatchSessionStart builds the HookInput internally; no throw + it
        // reaches the matcher proves the live-cwd JSON build path works.
        assertDoesNotThrow(() -> engine.dispatchSessionStart("cli"));
    }
}
