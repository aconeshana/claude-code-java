package com.claudecode.tools.loop;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import com.claudecode.tools.skills.BundledSkillPromptRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopPromptResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void gateOffLeavesEveryPromptUntouched() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> false, () -> false, tempDir, tempDir);

        assertEquals(LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL,
            resolver.resolve(LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL));
        assertEquals("/loop check deploy", resolver.resolve("/loop check deploy"));
    }

    @Test
    void autonomousFirstFireCarriesPreambleAndLaterFireUsesShortReminder() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, tempDir);

        String first = resolver.resolve(LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL);
        String second = resolver.resolve(LoopPromptResolver.AUTONOMOUS_DYNAMIC_SENTINEL);

        assertTrue(Strings.CS.startsWith(first, "# Autonomous loop check\n"), first);
        assertTrue(Strings.CS.contains(first, "# Autonomous loop tick (dynamic pacing)"), first);
        assertEquals("# Autonomous loop tick (dynamic pacing)", second.lines().findFirst().orElseThrow());
        assertFalse(Strings.CS.contains(second, "You're being invoked on a timer"), second);
    }

    @Test
    void persistentAutonomousPreambleMatchesOfficialPolicyBranch() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> true, tempDir, tempDir);

        String preamble = resolver.autonomousPreamble();

        assertTrue(Strings.CS.contains(preamble, "following through on the *spirit* of the task they gave you"),
            preamble);
        assertTrue(Strings.CS.contains(preamble, "say so in one sentence and keep the loop alive"), preamble);
        assertTrue(Strings.CS.contains(preamble, "Before stopping, broaden once"), preamble);
        assertTrue(Strings.CS.contains(preamble, "broaden scope once before considering stopping"), preamble);
        assertFalse(Strings.CS.contains(preamble, "You're a steward, not an initiator."), preamble);
        assertFalse(Strings.CS.contains(preamble, "three consecutive \"nothing to do\" results means"), preamble);
    }

    @Test
    void autonomousDynamicLoopMatchesOfficial197NoArgumentPrompt() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, tempDir);

        String body = BundledSkillPromptRenderer.renderLoop("", true, true, resolver);

        assertEquals("""
            # /loop — autonomous default with dynamic pacing

            The user invoked `/loop` with no prompt and no interval. Run the autonomous check now, \
            then self-pace the next iteration via ScheduleWakeup — no cron.""",
            body.substring(0, body.indexOf("\n\n## Action")));
        assertTrue(Strings.CS.contains(body, "3. **Briefly confirm**: that this is the autonomous default in "
            + "dynamic-pacing mode, that you ran the check now, whether a Monitor is the primary wake "
            + "signal, and what fallback delay you're about to pick."), body);
        assertTrue(Strings.CS.contains(body, "`delaySeconds`: with a Monitor armed this is the fallback heartbeat "
            + "(lean 1200–1800s)."), body);
        assertTrue(Strings.CS.contains(body, "`prompt`: the literal string `<<autonomous-loop-dynamic>>`"), body);
        assertTrue(Strings.CS.contains(body, "5. **If woken by a `<task-notification>`**"), body);
        assertTrue(Strings.CS.contains(body, "6. **To stop the loop**"), body);
        assertFalse(Strings.CS.contains(body, "Call CronCreate"), body);
        assertEquals("ab8a29f939460174870cfc5604f25c20a054899309a32801224b8959d6edf6d4",
            sha256(body));
    }

    @Test
    void intervalOnlyLoopUsesOfficialFixedAutonomousBranchEvenWhenDynamicIsEnabled() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, tempDir);

        String leading = BundledSkillPromptRenderer.renderLoop("5m", true, true, resolver);
        String trailing = BundledSkillPromptRenderer.renderLoop(
            "every 5 minutes", true, true, resolver);

        for (String body : List.of(leading, trailing)) {
            assertTrue(Strings.CS.startsWith(body, "# /loop — schedule the autonomous default\n"), body);
            assertTrue(Strings.CS.contains(body, "input was empty or just the interval `5m`"), body);
            assertTrue(Strings.CS.contains(body, "Call CronCreate with:"), body);
            assertTrue(Strings.CS.contains(body, "`prompt`: the literal string `<<autonomous-loop>>`"), body);
            assertTrue(Strings.CS.contains(body, "recurring tasks auto-expire after 7 days"), body);
            assertFalse(Strings.CS.contains(body, "ScheduleWakeup"), body);
        }
    }

    @Test
    void projectLoopFileWinsAndChangedContentsAreRedelivered() throws Exception {
        Path projectFile = tempDir.resolve(".claude/loop.md");
        Path cwd = tempDir.resolve("subdir");
        Files.createDirectories(projectFile.getParent());
        Files.createDirectories(cwd);
        Files.writeString(projectFile, "first task\n");
        Files.writeString(cwd.resolve("loop.md"), "wrong task\n");
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, cwd);

        String first = resolver.resolve(LoopPromptResolver.LOOP_FILE_DYNAMIC_SENTINEL);
        String unchanged = resolver.resolve(LoopPromptResolver.LOOP_FILE_DYNAMIC_SENTINEL);
        Files.writeString(projectFile, "second task\n");
        String changed = resolver.resolve(LoopPromptResolver.LOOP_FILE_DYNAMIC_SENTINEL);

        assertTrue(Strings.CS.contains(first, "tasks from " + projectFile), first);
        assertTrue(Strings.CS.contains(first, "first task"), first);
        assertFalse(Strings.CS.contains(first, "wrong task"), first);
        assertFalse(Strings.CS.contains(unchanged, "first task"), unchanged);
        assertTrue(Strings.CS.contains(changed, "second task"), changed);
    }

    @Test
    void userConfigLoopFileIsTheOfficialFallbackNotCwdLoopMd() throws Exception {
        Path project = tempDir.resolve("project");
        Path cwd = project.resolve("subdir");
        Path configHome = tempDir.resolve("config-home");
        Files.createDirectories(cwd);
        Files.createDirectories(configHome);
        Files.writeString(cwd.resolve("loop.md"), "wrong cwd task\n");
        Files.writeString(configHome.resolve("loop.md"), "user config task\n");
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, project, cwd, configHome);

        LoopPromptResolver.LoopFile file = resolver.readLoopFile();

        assertEquals(configHome.resolve("loop.md"), file.path());
        assertEquals("user config task", file.content());
    }

    @Test
    void officialFirstFireCompositionHasNoInventedRuleSeparators() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, tempDir);

        String prompt = resolver.resolve(LoopPromptResolver.AUTONOMOUS_SENTINEL);

        assertFalse(Strings.CS.contains(prompt, "\n---\n"), prompt);
        assertTrue(Strings.CS.contains(prompt, "\n# Autonomous loop tick\n"), prompt);
    }

    @Test
    void missingDynamicLoopFileFallsBackToAutonomousAndKeepsFileSentinel() {
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, tempDir);

        String prompt = resolver.resolve(LoopPromptResolver.LOOP_FILE_DYNAMIC_SENTINEL);

        assertTrue(Strings.CS.contains(prompt, "loop.md is not currently present"), prompt);
        assertTrue(Strings.CS.contains(prompt, "`<<loop.md-dynamic>>`"), prompt);
    }

    @Test
    void loopFileIsTruncatedAtOfficialLimit() throws Exception {
        Path projectFile = tempDir.resolve(".claude/loop.md");
        Files.createDirectories(projectFile.getParent());
        Files.writeString(projectFile, "x".repeat(25_100));
        LoopPromptResolver resolver = new LoopPromptResolver(
            () -> true, () -> false, tempDir, tempDir);

        String prompt = resolver.resolve(LoopPromptResolver.LOOP_FILE_SENTINEL);

        assertTrue(Strings.CS.contains(prompt, "WARNING: loop.md was truncated to 25000 bytes"), prompt);
        assertFalse(Strings.CS.contains(prompt, "x".repeat(25_001)), prompt);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
