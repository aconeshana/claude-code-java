package com.claudecode.core.memdir;

import org.apache.commons.lang3.StringUtils;
import java.util.ArrayList;
import java.util.List;

/**
 * Prompt for the background memory-extraction agent.
 */
public final class ExtractMemoriesPrompt {

    private ExtractMemoriesPrompt() {}

    public static final String SYSTEM_PROMPT =
        "You are Claude, an AI assistant. You are running as a background task with a narrow, "
        + "specific job — follow the instructions in the user message exactly and do not do anything else.";

    /**
     * @param newMessageCount number of new user/assistant messages since the last extraction
     * @param existingMemories pre-scanned manifest text ({@link MemoryManifestScanner#formatManifest}),
     *                         or {@code ""} if the memory directory is empty
     */
    public static String buildUserPrompt(int newMessageCount, String existingMemories) {
        List<String> lines = new ArrayList<>();
        lines.add(opener(newMessageCount, existingMemories));
        lines.add("");
        lines.add("If the user explicitly asks you to remember something, save it immediately as "
            + "whichever type fits best. If they ask you to forget something, find and remove the "
            + "relevant entry.");
        lines.add("");
        lines.addAll(AutoMemoryPrompt.TYPES_SECTION_INDIVIDUAL);
        lines.addAll(AutoMemoryPrompt.WHAT_NOT_TO_SAVE_SECTION);
        lines.add("");
        lines.addAll(howToSave());
        return String.join("\n", lines);
    }

    private static String opener(int newMessageCount, String existingMemories) {
        String manifest = (StringUtils.isNotEmpty(existingMemories))
            ? "\n\n## Existing memory files\n\n" + existingMemories
                + "\n\nCheck this list before writing — update an existing file rather than creating a duplicate."
            : "";
        return String.join("\n", List.of(
            "You are now acting as the memory extraction subagent. Analyze the most recent ~"
                + newMessageCount + " messages above and use them to update your persistent memory systems.",
            "",
            "Available tools: Read, Grep, Glob, read-only Bash (ls/find/cat/stat/wc/head/tail and similar), "
                + "and Edit/Write for paths inside the memory directory only. Bash rm is not permitted. "
                + "All other tools — MCP, Agent, write-capable Bash, etc — will be denied.",
            "",
            "You have a limited turn budget. Edit requires a prior Read of the same file, so the efficient "
                + "strategy is: turn 1 — issue all Read calls in parallel for every file you might update; "
                + "turn 2 — issue all Write/Edit calls in parallel. Do not interleave reads and writes across "
                + "multiple turns.",
            "",
            "You MUST only use content from the last ~" + newMessageCount + " messages to update your "
                + "persistent memories. Do not waste any turns attempting to investigate or verify that "
                + "content further — no grepping source files, no reading code to confirm a pattern exists, "
                + "no git commands." + manifest));
    }

    private static List<String> howToSave() {
        List<String> lines = new ArrayList<>();
        lines.add("## How to save memories");
        lines.add("");
        lines.add("Saving a memory is a two-step process:");
        lines.add("");
        lines.add("**Step 1** — write the memory to its own file (e.g., `user_role.md`, "
            + "`feedback_testing.md`) using this frontmatter format:");
        lines.add("");
        lines.addAll(AutoMemoryPrompt.MEMORY_FRONTMATTER_EXAMPLE);
        lines.add("");
        lines.add("**Step 2** — add a pointer to that file in `" + AutoMemoryPrompt.ENTRYPOINT_NAME
            + "`. `" + AutoMemoryPrompt.ENTRYPOINT_NAME + "` is an index, not a memory — each entry "
            + "should be one line, under ~150 characters: `- [Title](file.md) — one-line hook`. "
            + "It has no frontmatter. Never write memory content directly into `"
            + AutoMemoryPrompt.ENTRYPOINT_NAME + "`.");
        lines.add("");
        lines.add("- `" + AutoMemoryPrompt.ENTRYPOINT_NAME + "` is always loaded into your system prompt — "
            + "lines after " + AutoMemoryPrompt.MAX_ENTRYPOINT_LINES + " will be truncated, so keep the index concise");
        lines.add("- Organize memory semantically by topic, not chronologically");
        lines.add("- Update or remove memories that turn out to be wrong or outdated");
        lines.add("- Do not write duplicate memories. First check if there is an existing memory you can "
            + "update before writing a new one.");
        return lines;
    }
}
