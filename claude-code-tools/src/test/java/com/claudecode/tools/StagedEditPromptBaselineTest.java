package com.claudecode.tools;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/** Guards the staged, not-yet-active 2.1.236 Edit prompt resources. */
class StagedEditPromptBaselineTest {

    private static final String ROOT = "/tool-text/official/2.1.236/prompts/";

    @Test
    void defaultRenderingMatchesTheTemplateTheBundleBuilds() throws IOException {
        String rendered = ToolTexts.render(read("Edit.txt"), Map.of(
            "READ_REQUIREMENT", read("Edit/read-requirement.txt"),
            "LINE_PREFIX", read("Edit/line-prefix.txt"),
            "UNIQUENESS_RULES", read("Edit/uniqueness.txt")));

        assertEquals("""
            Performs exact string replacements in files.
            Usage:
            - You must use your `Read` tool at least once in the conversation before editing. This tool will error if you attempt an edit without reading the file.
            - When editing text from Read tool output, ensure you preserve the exact indentation (tabs/spaces) as it appears AFTER the line number prefix. The line number prefix format is: line number + tab. Everything after that is the actual file content to match. Never include any part of the line number prefix in the old_string or new_string.
            - ALWAYS prefer editing existing files in the codebase. NEVER write new files unless explicitly required.
            - Only use emojis if the user explicitly requests it. Avoid adding emojis to files unless asked.
            - The edit will FAIL if `old_string` is not unique in the file. Either provide a larger string with more surrounding context to make it unique or use `replace_all` to change every instance of `old_string`.
            - Use `replace_all` for replacing and renaming strings across the file. This parameter is useful if you want to rename a variable for instance.""",
            rendered);
    }

    @Test
    void gatedFragmentsRenderIntoTheirTemplates() throws IOException {
        String minimal = ToolTexts.render(read("Edit.txt"), Map.of(
            "READ_REQUIREMENT", read("Edit/read-requirement-outside-cwd.txt"),
            "LINE_PREFIX", read("Edit/line-prefix-tab-or-colon.txt"),
            "UNIQUENESS_RULES", read("Edit/uniqueness-minimal-anchor.txt")));
        assertTrue(Strings.CS.contains(minimal, "outside the working directory"));
        assertTrue(Strings.CS.contains(minimal, "(a tab or `:`)"));
        assertTrue(Strings.CS.contains(minimal,
            "Keep `old_string` minimal \u2014 usually 1-3 lines"));

        String lean = ToolTexts.render(read("Edit/lean.txt"), Map.of(
            "READ_REQUIREMENT", read("Edit/lean-read-requirement.txt"),
            "LINE_PREFIX", read("Edit/lean-line-prefix-tab-or-colon.txt")));
        assertEquals("""
            Performs exact string replacement in a file.
            - You must Read the file in this conversation before editing, or the call will fail.
            - `old_string` must match the file exactly, including indentation, and be unique \u2014 the edit fails otherwise. Strip the Read line prefix (line number + a single tab or `:`) before matching.
            - `replace_all: true` replaces every occurrence instead.""", lean);
    }

    @Test
    void activeBaselineStaysAt197() {
        assertTrue(Strings.CS.startsWith(ToolTexts.prompt("Edit"),
            "Performs exact string replacements in files.\n\nUsage:\n"));
    }

    private static String read(String name) throws IOException {
        try (InputStream in = StagedEditPromptBaselineTest.class
                .getResourceAsStream(ROOT + name)) {
            assertNotNull(in, "missing staged resource " + name);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return Strings.CS.endsWith(text, "\n") ? text.substring(0, text.length() - 1) : text;
        }
    }
}
