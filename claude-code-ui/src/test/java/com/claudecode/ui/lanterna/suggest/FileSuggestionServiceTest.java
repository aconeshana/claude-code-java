package com.claudecode.ui.lanterna.suggest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSuggestionServiceTest {

    @Test
    void respectGitignoreControlsWhetherIgnoredFilesAppear(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve(".gitignore"), "ignored.txt\n");
        Files.writeString(repo.resolve("tracked.txt"), "tracked\n");
        Files.writeString(repo.resolve("ignored.txt"), "ignored\n");
        assertEquals(0, new ProcessBuilder("git", "init", "--quiet")
            .directory(repo.toFile()).start().waitFor());
        assertEquals(0, new ProcessBuilder("git", "add", ".gitignore", "tracked.txt")
            .directory(repo.toFile()).start().waitFor());

        String originalDir = System.getProperty("user.dir");
        System.setProperty("user.dir", repo.toString());
        try {
            AtomicBoolean respectGitignore = new AtomicBoolean(true);
            FileSuggestionService service =
                new FileSuggestionService(null, null, respectGitignore::get);

            assertTrue(service.build("ignored").isEmpty());

            respectGitignore.set(false);
            List<SuggestionPanel.Suggestion> suggestions = service.build("ignored");
            assertEquals(List.of("ignored.txt"), suggestions.stream()
                .map(SuggestionPanel.Suggestion::primary).toList());
        } finally {
            if (originalDir == null) System.clearProperty("user.dir");
            else System.setProperty("user.dir", originalDir);
        }
    }
}
