package com.claudecode.ui.lanterna.statusline;

import com.claudecode.core.message.Usage;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.lang3.Strings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden text and threshold coverage for the built-in status HUD renderer. */
class BuiltInClaudeHudRendererTest {

    @Test
    void defaultExpandedLayoutMatchesClaudeHudShape() {
        StatusLineInput input = input(new Usage(80_000, 2_000, 5_000, 5_000), 45);

        String plain = stripAnsi(BuiltInClaudeHudRenderer.render(input, 120));

        assertEquals("""
            [Opus 4.8] │ my-project +shared
            Context █████░░░░░ 45%""", plain);
    }

    @Test
    void showsEffectiveEffortBesideTheModelWithoutChangingContextLayout() {
        StatusLineInput input = input(new Usage(80_000, 2_000, 5_000, 5_000), 45);

        String plain = stripAnsi(BuiltInClaudeHudRenderer.render(input, 120, "medium"));

        assertEquals("""
            [Opus 4.8] ◐ medium │ my-project +shared
            Context █████░░░░░ 45%""", plain);
    }

    @Test
    void labelsServerSelectedCustomEffortAsAuto() {
        StatusLineInput input = input(new Usage(80_000, 2_000, 5_000, 5_000), 45);

        String plain = stripAnsi(BuiltInClaudeHudRenderer.renderProjectLine(input, "auto"));

        assertEquals("[Opus 4.8] effort:auto │ my-project +shared", plain);
    }

    @Test
    void adaptsBarWidthAndShowsCriticalTokenBreakdown() {
        StatusLineInput input = input(new Usage(150_000, 2_000, 10_000, 10_000), 85);

        String plain = stripAnsi(BuiltInClaudeHudRenderer.renderContextLine(input, 4));

        assertEquals("Context ███░ 85% (in: 150k, cache: 20k)", plain);
        assertEquals(4, BuiltInClaudeHudRenderer.adaptiveBarWidth(40));
        assertEquals(6, BuiltInClaudeHudRenderer.adaptiveBarWidth(80));
        assertEquals(10, BuiltInClaudeHudRenderer.adaptiveBarWidth(120));
        assertTrue(Strings.CS.contains(
            BuiltInClaudeHudRenderer.renderContextLine(input, 4), "\u001b[31m"));
    }

    @Test
    void gptFallbackDoesNotAddCachedTokensToInputAgain() {
        Usage usage = new Usage(80, 2, 0, 60, 82L);
        StatusLineInput input = StatusLineInput.builder("session", "/work/my-project")
            .transcriptPath("/tmp/session.jsonl")
            .model("proxy-gpt-5.6-sol", "GPT 5.6 Sol")
            .projectDir("/work/my-project")
            .version("0.1.0")
            .contextWindow(100, usage, null, null)
            .build();

        String plain = stripAnsi(BuiltInClaudeHudRenderer.renderContextLine(input, 10));

        assertEquals("Context ████████░░ 80%", plain);
    }

    @Test
    void appendsPinnedMetricsInStrictPriorityOrder() {
        SessionMetricsSnapshot metrics = new SessionMetricsSnapshot(true,
            1, 35, 301_000, 3_400, 294_000, 35,
            1_000, 1_247, 23_550, 1_247, 0, 447_450);
        StatusLineInput input = StatusLineInput.builder("session", "/work/my-project")
            .model("claude-opus-4-8", "Opus 4.8")
            .contextWindow(200_000, new Usage(1, 1, 0, 0), 45, 55)
            .sessionMetrics(metrics)
            .build();

        String line = stripAnsi(BuiltInClaudeHudRenderer.render(input, 240)).lines().toList().get(1);

        assertEquals("Context █████░░░░░ 45% | 1 turns · 35 steps | LLM 5m1s · Tool call 3.4s"
            + " | TTFT avg 8.4s · 1247 tok/s | Cache hit 95% | Input 471K tok · Output 1.2K tok", line);
    }

    @Test
    void truncationKeepsAnsiAwareLeftPrefixAndOneEllipsis() {
        String truncated = BuiltInClaudeHudRenderer.truncateAnsi(
            "\u001b[2mContext\u001b[0m 中文 metrics", 12);
        String plain = stripAnsi(truncated);
        assertEquals("Context 中…", plain);
    }

    @Test
    void untrackedFilesStillMarkTheRepositoryDirty(@TempDir Path repository) throws Exception {
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.com");
        run(repository, "git", "config", "user.name", "Test");
        Files.writeString(repository.resolve("tracked.txt"), "tracked");
        run(repository, "git", "add", "tracked.txt");
        run(repository, "git", "commit", "-qm", "initial");
        Files.writeString(repository.resolve("untracked.txt"), "untracked");
        StatusLineInput input = StatusLineInput.builder("session", repository.toString())
            .transcriptPath("/tmp/session.jsonl")
            .model("claude-opus-4-8", "Opus 4.8")
            .projectDir(repository.toString())
            .version("0.1.0")
            .contextWindow(200_000, null, 0, 100)
            .build();

        String plain = stripAnsi(BuiltInClaudeHudRenderer.renderProjectLine(input));

        assertTrue(Strings.CS.contains(plain, "git:(master*)")
                || Strings.CS.contains(plain, "git:(main*)"), plain);
    }

    private static StatusLineInput input(Usage usage, int percent) {
        return StatusLineInput.builder("session", "/work/my-project")
            .transcriptPath("/tmp/session.jsonl")
            .model("claude-opus-4-8", "Opus 4.8")
            .projectDir("/work/my-project")
            .addedDirs(List.of("/work/shared"))
            .version("0.1.0")
            .contextWindow(200_000, usage, percent, 100 - percent)
            .build();
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private static void run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(cwd.toFile()).start();
        int exitCode = process.waitFor();
        String error = new String(process.getErrorStream().readAllBytes());
        assertEquals(0, exitCode, error);
    }
}
