package com.claudecode.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FileEditPreviewTest {

    @Test
    void previewUsesTheSameQuoteNormalizationAndReplacementAsExecution() {
        String content = "const title = “old”;\n";

        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            content, "\"old\"", "\"new\"", false);

        assertEquals("const title = “new”;\n", preview.newContent());
        assertTrue(preview.hunks().getFirst().lines().contains("-const title = “old”;"));
        assertTrue(preview.hunks().getFirst().lines().contains("+const title = “new”;"));
    }

    @Test
    void previewReplaceAllChangesEveryOccurrence() {
        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            "old\nkeep\nold\n", "old", "new", true);

        assertEquals("new\nkeep\nnew\n", preview.newContent());
        assertTrue(preview.error().isEmpty());
    }
}
