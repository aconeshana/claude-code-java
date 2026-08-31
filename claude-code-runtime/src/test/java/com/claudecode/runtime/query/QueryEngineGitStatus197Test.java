package com.claudecode.runtime.query;

import com.claudecode.core.engine.StreamingClient;

import org.apache.commons.lang3.Strings;
import com.claudecode.core.process.ProcessResult;
import com.claudecode.core.process.ProcessRunner;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;


class QueryEngineGitStatus197Test {

    @TempDir
    Path tempDir;

    @BeforeEach
    @AfterEach
    void resetProcessGitStatusSnapshot() throws Exception {
        Field field = DefaultQuerySession.class.getDeclaredField("gitStatusCache");
        field.setAccessible(true);
        field.set(null, null);
    }

    @Test
    void unbornRepositoryIsStillGitAndIncludesEmptyRecentCommitsSection() throws Exception {
        Path repo = tempDir.resolve("unborn-repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-b", "main");
        Files.writeString(repo.resolve("README.md"), "wire fixture\n");

        String prompt = engineFor(repo).assembleSystemPrompt(null);

        assertTrue(Strings.CS.contains(prompt, "Is a git repository: true"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Current branch: main"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Main branch (you will usually use this for PRs): main"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Status:\n?? README.md"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Recent commits:\n"), prompt);
    }

    @Test
    void oversizedStatusUsesReleasedBashToolTruncationText() throws Exception {
        Path repo = tempDir.resolve("large-status-repo");
        Files.createDirectories(repo);
        runGit(repo, "init", "-b", "main");
        for (int i = 0; i < 160; i++) {
            Files.writeString(repo.resolve("untracked-wire-file-%03d.txt".formatted(i)), "x\n");
        }

        String prompt = engineFor(repo).assembleSystemPrompt(null);

        assertTrue(Strings.CS.contains(prompt, 
            "... (truncated because it exceeds 2k characters. If you need more information, "
                + "run \"git status\" using BashTool)"), prompt);
    }

    private static DefaultQuerySession engineFor(Path repo) {
        return new DefaultQuerySession(QuerySessionSpec.builder()
            .llmClient(new StreamingClient() {
                @Override
                public Iterator<StreamingEvent> createStream(StreamRequest request) {
                    return List.<StreamingEvent>of().iterator();
                }

                @Override
                public String getModel() {
                    return "claude-sonnet-4-6";
                }
            })
            .model("claude-sonnet-4-6")
            .workingDirectory(repo.toString())
            .systemPrompt(null)
            .build());
    }

    private static void runGit(Path cwd, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        ProcessResult result = ProcessRunner.run(command, cwd, Duration.ofSeconds(10));
        assertTrue(result.succeeded(), () -> String.join(" ", command) + " failed: " + result.stderr());
    }
}
