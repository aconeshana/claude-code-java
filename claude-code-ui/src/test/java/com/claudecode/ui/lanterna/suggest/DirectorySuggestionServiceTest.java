package com.claudecode.ui.lanterna.suggest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectorySuggestionServiceTest {

    @Test
    void repeatedReadsUseTheCachedDirectorySnapshot(@TempDir Path tempDir) throws Exception {
        Path first = Files.createFile(tempDir.resolve("alpha.png"));
        DirectorySuggestionService service = new DirectorySuggestionService();
        String token = tempDir.resolve("a").toString();

        List<SuggestionPanel.Suggestion> initial = service.build(token);
        Path addedAfterScan = Files.createFile(tempDir.resolve("apricot.png"));
        List<SuggestionPanel.Suggestion> cached = service.build(token);

        assertTrue(initial.stream().anyMatch(item -> item.primary().equals(first.toString())));
        assertFalse(cached.stream().anyMatch(item -> item.primary().equals(addedAfterScan.toString())),
            "the released implementation reuses a five-minute directory snapshot");
    }
}
