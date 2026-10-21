package com.claudecode.tools.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.commons.lang3.Strings;
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

    @Test
    void previewShowsLeadingTabsAsTwoSpacesButWritesTheFileUntouched() {
        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            "class A {\n\tint x;\n}\n", "int x;", "int x;\n\tint y;", false);

        assertEquals("class A {\n\tint x;\n\tint y;\n}\n", preview.newContent());
        assertTrue(preview.hunks().getFirst().lines().contains("+  int y;"));
    }

    @Test
    void oldStringWithCharactersMatchesFileTextThatSpellsThemAsEscapes() {
        FileEditTool.EditPreview lower = FileEditTool.previewEdit(
            "String s = \"\\u4e2d\";\n", "\"中\"", "\"国\"", false);
        assertTrue(lower.error().isEmpty());
        assertEquals("String s = \"\\u56fd\";\n", lower.newContent());

        FileEditTool.EditPreview upper = FileEditTool.previewEdit(
            "String s = \"\\u4E2D\";\n", "\"中\"", "\"国\"", false);
        assertEquals("String s = \"\\u56FD\";\n", upper.newContent());
    }

    @Test
    void newCharactersReuseTheFilesSpellingForCharactersItAlreadyEscaped() {
        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            "x = \"\\u4E2D\";\n", "\"中\"", "\"中文\"", false);

        assertEquals("x = \"\\u4E2D\\u6587\";\n", preview.newContent());
    }

    @Test
    void oldStringWithEscapesMatchesFileTextThatHoldsTheCharacters() {
        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            "title = \"中\";\n", "\"\\u4e2d\"", "\"\\u56fd\"", false);

        assertTrue(preview.error().isEmpty());
        assertEquals("title = \"国\";\n", preview.newContent());
    }

    @Test
    void doubledBackslashBeforeUIsNotDecoded() {
        FileEditTool.EditPreview preview = FileEditTool.previewEdit(
            "p = \"C:\\\\u4e2d\";\n", "\"C:\\\\u4e2d\"", "\"D\"", false);

        assertEquals("p = \"D\";\n", preview.newContent());
    }

    @Test
    void notFoundErrorMentionsTheEscapeSwapOnlyWhenEscapesCouldBeInvolved() {
        String withNote = FileEditTool.previewEdit("abc\n", "中", "x", false).error();
        assertTrue(Strings.CS.endsWith(withNote, "copy the exact surrounding text.)"), withNote);
        assertTrue(Strings.CS.contains(withNote,
            "String: 中\n(note: Edit also tried swapping"));

        String plain = FileEditTool.previewEdit("abc\n", "zzz", "x", false).error();
        assertEquals("Error: String to replace not found in file.\nString: zzz", plain);
    }
}
