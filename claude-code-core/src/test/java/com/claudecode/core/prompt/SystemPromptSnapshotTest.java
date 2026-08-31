package com.claudecode.core.prompt;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;


class SystemPromptSnapshotTest {

    private static final String PART_SEPARATOR = "\n\n===__PART_BOUNDARY__===\n\n";

    private static final String TS_DUMP_CWD = "/workspace/claude-code";

    private static final boolean TS_DUMP_IS_GIT = true;


    private static final boolean TS_DUMP_NON_INTERACTIVE = true;

    private static final String TS_DUMP_LANGUAGE = "Chinese";

    private SystemPromptService service;

    @BeforeEach
    void setUp() {
        SystemPromptSectionResolver.clearAll();
        service = new SystemPromptService();
    }

    private String loadFixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(
                "/prompt-snapshots/" + name + ".txt")) {
            assertNotNull(in, "missing fixture: " + name);
            // Text fixtures carry one conventional file-ending newline; the
            // assembled prompt itself intentionally does not.
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("\\n$", "");
        }
    }

    private String renderJava(SystemPromptConfig config) {
        List<String> parts = service.buildSystemPromptParts(config);
        return String.join(PART_SEPARATOR, parts);
    }

    /**
     * Report the first divergence in a way that's actually readable when a
     * ~25 KB snapshot mismatches. Standard {@code assertEquals} on huge
     * strings prints the whole diff which drowns the actual delta.
     */
    private void assertSnapshotEquals(String expected, String actual, String fixture) {
        // Fixture re-baseline mode: `./gradlew :claude-code-core:test
        // -Dregen.snapshots=true --tests '*SystemPromptSnapshotTest'` rewrites the
        // resource file from the current Java output instead of asserting. Used
        // only for intentional mechanical re-baselines; the
        // written fixtures are then committed and this flag left off so the test


        if (Boolean.getBoolean("regen.snapshots")) {
            try {
                Path p = Path.of(
                    "src/test/resources/prompt-snapshots/" + fixture + ".txt");
                Files.writeString(p, actual);
            } catch (IOException e) {
                fail("regen write failed for " + fixture + ": " + e);
            }
            return;
        }
        if (expected.equals(actual)) return;
        int minLen = Math.min(expected.length(), actual.length());
        int diffAt = 0;
        while (diffAt < minLen && expected.charAt(diffAt) == actual.charAt(diffAt)) diffAt++;
        int contextStart = Math.max(0, diffAt - 60);
        int contextEnd = Math.min(minLen, diffAt + 60);
        String expectedCtx = expected.substring(contextStart, Math.min(expected.length(), diffAt + 60));
        String actualCtx = actual.substring(contextStart, Math.min(actual.length(), diffAt + 60));
        fail(String.format("Snapshot mismatch in %s at char %d"
                + " (TS len=%d, Java len=%d)%n"
                + "  TS   : ...%s...%n"
                + "  Java : ...%s...",
            fixture, diffAt, expected.length(), actual.length(),
            expectedCtx.replace("\n", "\\n"),
            actualCtx.replace("\n", "\\n")));
    }

    @Test
    @DisplayName("minimal-no-tools fixture matches TS output byte-for-byte")
    void minimalNoTools() throws IOException {
        String expected = loadFixture("minimal-no-tools");
        String actual = renderJava(SystemPromptConfig.builder()
            .modelId("claude-sonnet-4-6")
            .workingDirectory(TS_DUMP_CWD)
            .isGitRepo(TS_DUMP_IS_GIT)
            .isNonInteractiveSession(TS_DUMP_NON_INTERACTIVE)
            .languagePreference(TS_DUMP_LANGUAGE)
            .enabledTools(Set.of())
            .build());
        assertSnapshotEquals(expected, actual, "minimal-no-tools");
    }

    @Test
    @DisplayName("standard-tools fixture matches TS output byte-for-byte")
    void standardTools() throws IOException {
        String expected = loadFixture("standard-tools");
        String actual = renderJava(SystemPromptConfig.builder()
            .modelId("claude-sonnet-4-6")
            .workingDirectory(TS_DUMP_CWD)
            .isGitRepo(TS_DUMP_IS_GIT)
            .isNonInteractiveSession(TS_DUMP_NON_INTERACTIVE)
            .languagePreference(TS_DUMP_LANGUAGE)
            .enabledTools(Set.of("Read", "Write", "Edit", "Bash", "Glob", "Grep",
                "TodoWrite", "Agent"))
            .build());
        assertSnapshotEquals(expected, actual, "standard-tools");
    }

    @Test
    @DisplayName("with-additional-dirs fixture matches TS output byte-for-byte")
    void withAdditionalDirs() throws IOException {
        String expected = loadFixture("with-additional-dirs");
        String actual = renderJava(SystemPromptConfig.builder()
            .modelId("claude-opus-4-6")
            .workingDirectory(TS_DUMP_CWD)
            .isGitRepo(TS_DUMP_IS_GIT)
            .isNonInteractiveSession(TS_DUMP_NON_INTERACTIVE)
            .languagePreference(TS_DUMP_LANGUAGE)
            .enabledTools(Set.of("Read", "Bash"))
            .additionalWorkingDirectories(List.of("/tmp/other-project"))
            .build());
        assertSnapshotEquals(expected, actual, "with-additional-dirs");
    }

    @Test
    @DisplayName("with-ask-user-question fixture matches TS output byte-for-byte")
    void withAskUserQuestion() throws IOException {
        String expected = loadFixture("with-ask-user-question");
        String actual = renderJava(SystemPromptConfig.builder()
            .modelId("claude-sonnet-4-6")
            .workingDirectory(TS_DUMP_CWD)
            .isGitRepo(TS_DUMP_IS_GIT)
            .isNonInteractiveSession(TS_DUMP_NON_INTERACTIVE)
            .languagePreference(TS_DUMP_LANGUAGE)
            .enabledTools(Set.of("Read", "Bash", "AskUserQuestion"))
            .build());
        assertSnapshotEquals(expected, actual, "with-ask-user-question");
    }

    @Test
    @DisplayName("haiku-model fixture matches TS output byte-for-byte")
    void haikuModel() throws IOException {
        String expected = loadFixture("haiku-model");
        String actual = renderJava(SystemPromptConfig.builder()
            .modelId("claude-haiku-4-5-20251001")
            .workingDirectory(TS_DUMP_CWD)
            .isGitRepo(TS_DUMP_IS_GIT)
            .isNonInteractiveSession(TS_DUMP_NON_INTERACTIVE)
            .languagePreference(TS_DUMP_LANGUAGE)
            .enabledTools(Set.of("Read", "Bash"))
            .build());
        assertSnapshotEquals(expected, actual, "haiku-model");
    }
}
