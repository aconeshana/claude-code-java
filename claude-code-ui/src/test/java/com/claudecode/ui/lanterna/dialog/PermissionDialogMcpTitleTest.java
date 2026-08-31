package com.claudecode.ui.lanterna.dialog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;


class PermissionDialogMcpTitleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void title_mcpTool_rendersServerAndToolWithMcpSuffix() {

        assertEquals("github - search_users (MCP)",
            PermissionDialog.toolTitle("mcp__github__search_users"));
    }

    @Test
    void title_mcpTool_withDoubleUnderscoreInToolName_survivesRoundTrip() {


        assertEquals("srv - a__b (MCP)",
            PermissionDialog.toolTitle("mcp__srv__a__b"));
    }

    @Test
    void title_builtinTool_stillMapsToFriendlyLabel() {
        assertEquals("Bash command",  PermissionDialog.toolTitle("Bash"));
        assertEquals("Read file",     PermissionDialog.toolTitle("Read"));
        assertEquals("Update tasks",  PermissionDialog.toolTitle("TodoWrite"));
    }

    @Test
    void title_malformedMcpPrefix_fallsThroughToDefault() {
        // Missing tool part → not a valid MCP wire name → default label.
        assertEquals("Tool use", PermissionDialog.toolTitle("mcp__onlyserver"));
        assertEquals("Tool use", PermissionDialog.toolTitle("mcp__"));
    }

    @Test
    void parseMcpToolName_nonMcpName_returnsNull() {
        assertNull(PermissionDialog.parseMcpToolName("Bash"));
        assertNull(PermissionDialog.parseMcpToolName(null));
        assertNull(PermissionDialog.parseMcpToolName("mcp_github_x"));   // single underscore
    }

    @Test
    void parseMcpToolName_validName_returnsParts() {
        var p = PermissionDialog.parseMcpToolName("mcp__github__list_repos");
        assertNotNull(p);
        assertEquals("github", p.server());
        assertEquals("list_repos", p.tool());
    }
}
