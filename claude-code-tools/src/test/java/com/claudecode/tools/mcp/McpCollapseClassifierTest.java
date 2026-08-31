package com.claudecode.tools.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpCollapseClassifierTest {

    @Test
    void allowsReleasedSearchAndReadNamesAfterNormalization() {
        assertTrue(McpCollapseClassifier.isCollapsible("mcp__slack__slack_search_public"));
        assertTrue(McpCollapseClassifier.isCollapsible("mcp__github__getFileContents"));
        assertTrue(McpCollapseClassifier.isCollapsible("mcp__filesystem__list-directory"));
    }

    @Test
    void rejectsMutatingAndUnknownToolsConservatively() {
        assertFalse(McpCollapseClassifier.isCollapsible("mcp__slack__send_message"));
        assertFalse(McpCollapseClassifier.isCollapsible("mcp__github__create_issue"));
        assertFalse(McpCollapseClassifier.isCollapsible("mcp__custom__read_everything"));
        assertFalse(McpCollapseClassifier.isCollapsible("Read"));
    }
}
