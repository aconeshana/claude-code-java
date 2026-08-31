package com.claudecode.tools.web;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import com.claudecode.tools.files.NotebookEditTool;
import com.claudecode.tools.tasks.TodoWriteTool;

class MorePromptPortsTest {

    @Test
    void webFetchTool_descriptionPorted() {
        String d = new WebFetchTool().description();

        assertTrue(Strings.CS.contains(d, "Fetches content from a specified URL and processes it using an AI model"));
        assertTrue(Strings.CS.contains(d, "Includes a self-cleaning 15-minute cache"));
        assertTrue(Strings.CS.contains(d, "HTTP URLs will be automatically upgraded to HTTPS"));
        assertTrue(Strings.CS.contains(d, "If an MCP-provided web fetch tool is available"));
    }

    @Test
    void webSearchTool_descriptionPorted() {
        String d = new WebSearchTool().description();

        assertTrue(Strings.CS.contains(d, "Allows Claude to search the web and use the results to inform responses"));
        assertTrue(Strings.CS.contains(d, "Web search is only available in the US"));
        assertTrue(Strings.CS.contains(d, "MUST include a \"Sources:\" section"));
        assertTrue(Strings.CS.contains(d, "Domain filtering is supported"));
        assertTrue(Strings.CS.contains(d, "The current month is "));
    }

    @Test
    void webSearchTool_promptPorted() {
        String prompt = new WebSearchTool().prompt(null);
        assertTrue(Strings.CS.contains(prompt, "CRITICAL REQUIREMENT - You MUST follow this"), prompt);
        assertTrue(Strings.CS.contains(prompt, "Sources:"), prompt);
        assertTrue(Strings.CS.contains(prompt, "The current month is "), prompt);
    }

    @Test
    void webSearchTool_rendersLocalMonthLikeReleasedPrompt() {
        String template = new WebSearchTool().description();
        String august = WebSearchTool.renderDescription(
            template,
            Instant.parse("2026-08-01T19:00:00Z"),
            ZoneId.of("America/Los_Angeles"),
            null);
        assertTrue(Strings.CS.contains(august, "The current month is August 2026"), august);

        String overridden = WebSearchTool.renderDescription(
            template,
            Instant.parse("2026-08-15T19:00:00Z"),
            ZoneId.of("America/Los_Angeles"),
            "2026-08-01");
        assertTrue(Strings.CS.contains(overridden, "The current month is July 2026"), overridden);
    }

    @Test
    void todoWriteTool_descriptionPorted() {
        String d = new TodoWriteTool().description();
        assertTrue(Strings.CS.startsWith(d, "Update the todo list for the current session"), d);
        assertTrue(Strings.CS.contains(d, "in_progress at all times"));
        assertTrue(Strings.CS.contains(d, "content (imperative)"));
        assertTrue(Strings.CS.contains(d, "activeForm (present continuous)"));
    }

    @Test
    void notebookEditTool_descriptionPorted() {
        String d = new NotebookEditTool().description();
        // Verbatim phrases from the product's canonical description


        assertTrue(Strings.CS.contains(d, "Replaces, inserts, or deletes a single cell in a Jupyter notebook"));
        assertTrue(Strings.CS.contains(d, "notebook_path` must be an absolute path"));
        assertTrue(Strings.CS.contains(d, "cell_id` is the `id` attribute shown in the Read tool's `<cell id=\"...\">` output"));
        assertTrue(Strings.CS.contains(d, "edit_mode` defaults to `replace`. Use `insert`"));
        assertTrue(Strings.CS.contains(d, "Use `delete` to remove the cell"));
        assertEquals(
            "The type of the cell (code or markdown). If not specified, it defaults to the "
                + "current cell type. If using edit_mode=insert, this is required.",
            new NotebookEditTool().inputSchema().at("/properties/cell_type/description").asText());
    }
}
