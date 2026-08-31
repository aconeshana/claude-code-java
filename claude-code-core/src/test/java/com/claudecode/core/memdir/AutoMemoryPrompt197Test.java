package com.claudecode.core.memdir;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.prompt.SystemPromptConfig;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.prompt.SystemPromptSectionResolver;
import com.claudecode.core.prompt.SystemPromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;


class AutoMemoryPrompt197Test {

    @BeforeEach
    void reset() {
        SystemPromptSectionResolver.clearAll();
    }

    @Test
    void memorySectionMatches197Verbatim() throws Exception {
        String fixture;
        try (InputStream in = getClass().getResourceAsStream("/ts197/03-Memory.txt")) {
            assertNotNull(in, "missing 197 memory fixture");
            fixture = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\n$", "");
        }
        // The capture's only dynamic slot is the memory dir path.
        Path capturedDir = Path.of("/Users/example/.claude/projects/-Users-example/memory");
        assertEquals(fixture, AutoMemoryPrompt.memorySection197(capturedDir));
    }

    @Test
    void releasedSdkCliAutoMemoryUsesCapturedFrontmatterAndOmitsIndexContent(
            @TempDir Path tmp) {
        String prompt = AutoMemoryPrompt.buildReleased197SystemPrompt(
            tmp.resolve("memory"));

        assertTrue(Strings.CS.startsWith(prompt, "# auto memory\n\n"));
        assertTrue(Strings.CS.contains(prompt, """
            name: {{short-kebab-case-slug}}
            description: {{one-line summary — used to decide relevance in future conversations, so be specific}}
            metadata:
              type: {{user, feedback, project, reference}}\
            """));
        assertTrue(Strings.CS.contains(prompt, "Link related memories with [[their-name]]."));
        assertTrue(Strings.CS.contains(prompt, "These exclusions apply even when the user explicitly asks you to save."));
        assertEquals(1, prompt.split("## Memory and other forms of persistence", -1).length - 1);
        assertFalse(Strings.CS.contains(prompt, "## MEMORY.md"),
            "MEMORY.md content is injected through user context, not system");
        assertTrue(Strings.CS.endsWith(prompt, "\n\n"),
            "released section has two trailing newlines before the next part");
    }

    @Test
    void ensureAutoMemDirCreatesAndMemoizes(@TempDir Path tmp) {
        // baseOverride keeps the created dirs inside the temp base — never
        // the developer's real ~/.claude/projects.
        Path cwd = tmp.resolve("proj");
        Path base = tmp.resolve("claude-home");
        Path dir1 = AutoMemoryPrompt.ensureAutoMemDir(cwd, base);
        assertTrue(Files.isDirectory(dir1), "memory dir must exist after ensure");
        assertSame(dir1, AutoMemoryPrompt.ensureAutoMemDir(cwd, base),
            "second ensure for same cwd must return the memoized instance");
        assertTrue(dir1.endsWith("memory"), dir1.toString());
        assertTrue(dir1.startsWith(base), "override base must contain the dir");
    }

    @Test
    void assembledPromptPlacesAutoMemoryAfterBoundaryAndGuidance(@TempDir Path tmp) {
        String prompt = new SystemPromptService().buildSystemPrompt(
            SystemPromptConfig.builder()
                .workingDirectory("/tmp/proj")
                .enabledTools(Set.of("Agent", "Skill", "TaskCreate"))
                .hasSkills(true)
                .memoryDir(tmp.resolve("memory"))
                .build());
        int guidance = prompt.indexOf("# Session-specific guidance");
        int memory = prompt.indexOf("# auto memory");
        int boundary = prompt.indexOf(SystemPromptConstants.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
        assertTrue(boundary >= 0 && guidance > boundary && memory > guidance,
            "expected boundary < guidance < # auto memory, got g=" + guidance
                + " m=" + memory + " b=" + boundary);
        assertTrue(Strings.CS.contains(prompt, "persistent, file-based memory system at `"
                + tmp.resolve("memory") + "/`"),
            "section must name the supplied dir with trailing slash");
    }

    @Test
    void nullMemoryDirOmitsSection() {
        String prompt = new SystemPromptService().buildSystemPrompt(
            SystemPromptConfig.builder()
                .workingDirectory("/tmp/proj")
                .build());
        assertFalse(Strings.CS.contains(prompt, "# auto memory"), "no dir → no section");
    }

    @Test
    void truncateEntrypointCapsLinesAndBytes() {
        String longContent = "- line\n".repeat(300);
        String truncated = AutoMemoryPrompt.truncateEntrypoint(longContent);
        // 200 content lines + truncation marker appendix.
        assertTrue(Strings.CS.contains(truncated, "<!-- Truncated"), "marker missing");
        long contentLines = truncated.lines().filter(l -> Strings.CS.equals(l, "- line")).count();
        assertEquals(AutoMemoryPrompt.MAX_ENTRYPOINT_LINES, contentLines);
    }
}
