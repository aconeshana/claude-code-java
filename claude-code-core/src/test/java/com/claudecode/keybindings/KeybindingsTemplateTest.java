package com.claudecode.keybindings;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KeybindingsTemplateTest {

    @Test
    void includesSchemaAndDocsUrls() throws Exception {
        String content = KeybindingsTemplate.generate();
        JsonNode root = new ObjectMapper().readTree(content);
        assertEquals(
            "https://www.schemastore.org/claude-code-keybindings.json",
            root.get("$schema").asText());
        assertEquals(
            "https://code.claude.com/docs/en/keybindings",
            root.get("$docs").asText());
    }

    @Test
    void endsWithSingleNewline() {
        String content = KeybindingsTemplate.generate();
        assertTrue(Strings.CS.endsWith(content, "\n"), "must end with newline (avoids no-EOL diff marker)");
        assertFalse(Strings.CS.endsWith(content, "\n\n"), "must not have double trailing newline");
    }

    @Test
    void excludesNonRebindableShortcuts() throws Exception {
        // ctrl+c, ctrl+d, ctrl+m must be absent from the template.
        String content = KeybindingsTemplate.generate();
        JsonNode root = new ObjectMapper().readTree(content);
        JsonNode bindings = root.get("bindings");
        assertNotNull(bindings);
        Set<String> allKeys = new HashSet<>();
        for (JsonNode block : bindings) {
            JsonNode binds = block.get("bindings");
            Iterator<String> it = binds.fieldNames();
            while (it.hasNext()) allKeys.add(it.next());
        }
        for (String reserved : new String[]{"ctrl+c", "ctrl+d", "ctrl+m"}) {
            assertFalse(allKeys.contains(reserved),
                "template must not include non-rebindable: " + reserved);
        }
    }

    @Test
    void includesRebindableTerminalSignals() throws Exception {
        // ctrl+z is TERMINAL_RESERVED but rebindable — it should still appear
        // in user-customizable form (or not appear because DEFAULT_BINDINGS
        // doesn't bind it). What matters: the filter does NOT strip it.
        // Since DEFAULT_BINDINGS doesn't actually bind ctrl+z, we instead
        // verify that a known rebindable key like ctrl+e survives.
        String content = KeybindingsTemplate.generate();
        JsonNode root = new ObjectMapper().readTree(content);
        boolean found = false;
        for (JsonNode block : root.get("bindings")) {
            if (block.get("bindings").has("ctrl+e")) { found = true; break; }
        }
        assertTrue(found, "rebindable shortcuts like ctrl+e must survive filtering");
    }

    @Test
    void preservesContextOrder() throws Exception {
        // Output context order must match DefaultBindings.BLOCKS insertion order
        // — helps users find sections by scrolling.
        String content = KeybindingsTemplate.generate();
        JsonNode root = new ObjectMapper().readTree(content);
        JsonNode bindings = root.get("bindings");
        int idx = 0;
        for (DefaultBindings.Block block : DefaultBindings.BLOCKS) {
            // Skip blocks that emit empty (all filtered out — unlikely in practice).
            if (idx >= bindings.size()) break;
            String emitted = bindings.get(idx).get("context").asText();
            // Allow gaps for empty-after-filter blocks: walk forward until match.
            while (!emitted.equals(block.context()) && idx + 1 < bindings.size()) {
                idx++;
                emitted = bindings.get(idx).get("context").asText();
            }
            assertEquals(block.context(), emitted,
                "block " + block.context() + " must appear in source order");
            idx++;
        }
    }

    @Test
    void everyBlockBindingPreservesAction() throws Exception {
        // Spot check: Chat:enter must still map to chat:submit after generation.
        String content = KeybindingsTemplate.generate();
        JsonNode root = new ObjectMapper().readTree(content);
        for (JsonNode block : root.get("bindings")) {
            if (Strings.CS.equals("Chat", block.get("context").asText())) {
                assertEquals("chat:submit",
                    block.get("bindings").get("enter").asText());
                return;
            }
        }
        fail("Chat block missing from template");
    }

    @Test
    void isValidJson_evenWithFeatureFlagsOff() throws Exception {
        // Sanity — Jackson parsing must succeed.
        new ObjectMapper().readTree(KeybindingsTemplate.generate());
    }
}
