package com.claudecode.tools;

import com.claudecode.tools.output.SyntheticOutputTool;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Guards the static tool-text inventory against regressions back into Java literals. */
class ToolTextResourceInventoryTest {

    @Test
    void remainingStaticToolTextFamiliesHaveResourceSources() {
        String[] texts = {
            ToolTexts.prompt("Agent"),
            ToolTexts.description("Agent"),
            ToolTexts.prompt("ToolSearch", "available-deferred-tools"),
            ToolTexts.prompt("ToolSearch", "system-reminder"),
            ToolTexts.prompt("LSP"),
            ToolTexts.prompt("PowerShell", "template"),
            ToolTexts.description("StructuredOutput"),
            ToolTexts.prompt("StructuredOutput"),
            ToolTexts.prompt("AskUserQuestion", "markdown-preview"),
            ToolTexts.prompt("AskUserQuestion", "html-preview"),
            ToolTexts.prompt("TaskCreate", "teammate"),
            ToolTexts.prompt("TaskList", "teammate"),
            ToolTexts.description("McpAuth", "default"),
            ToolTexts.description("McpAuth", "named"),
            ToolTexts.description("TeamCreate"),
            ToolTexts.prompt("TeamCreate"),
            ToolTexts.description("TeamDelete"),
            ToolTexts.prompt("TeamDelete"),
            ToolTexts.description("WebBrowser"),
            ToolTexts.prompt("WebSearch", "template")
        };
        for (String text : texts) assertFalse(StringUtils.isBlank(text));
    }

    @Test
    void structuredOutputKeepsDescriptionAndPromptAsDistinctChannels() {
        SyntheticOutputTool tool = new SyntheticOutputTool(
            JsonNodeFactory.instance.objectNode().put("type", "object"));

        assertEquals(ToolTexts.description("StructuredOutput"), tool.description());
        assertEquals(ToolTexts.prompt("StructuredOutput"), tool.prompt(null));
    }

    @Test
    void enterPlanModeKeepsReleasedDescriptionAndPromptAsDistinctChannels() {
        com.claudecode.tools.plan.EnterPlanModeTool tool =
            new com.claudecode.tools.plan.EnterPlanModeTool();

        assertEquals(
            "Requests permission to enter plan mode for complex tasks requiring exploration and design",
            tool.description());
        assertEquals(ToolTexts.prompt("EnterPlanMode"), tool.prompt(null));
        assertFalse(tool.description().equals(tool.prompt(null)));
    }

    @Test
    void templateRenderingIsStrictAboutItsDataContract() {
        assertEquals("timeout=42", ToolTexts.render("timeout={{TIMEOUT}}", Map.of("TIMEOUT", 42)));
        assertThrows(IllegalArgumentException.class,
            () -> ToolTexts.render("{{MISSING}}", Map.of()));
        assertThrows(IllegalArgumentException.class,
            () -> ToolTexts.render("plain", Map.of("UNUSED", 1)));
    }
}
