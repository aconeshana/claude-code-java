package com.claudecode.keybindings;


import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;




public final class KeybindingsTemplate {

    private KeybindingsTemplate() {}

    static final String SCHEMA_URL  = "https://www.schemastore.org/claude-code-keybindings.json";
    static final String DOCS_URL    = "https://code.claude.com/docs/en/keybindings";

    /**
     * Returns the full template content (pretty-printed JSON, trailing newline).
     */
    public static String generate() {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("$schema", SCHEMA_URL);
        root.put("$docs",   DOCS_URL);

        ArrayNode bindingsArr = root.putArray("bindings");
        for (DefaultBindings.Block block : DefaultBindings.BLOCKS) {
            Map<String, String> filtered = filterReservedShortcuts(block.bindings());
            if (filtered.isEmpty()) continue;
            ObjectNode b = bindingsArr.addObject();
            b.put("context", block.context());
            ObjectNode binds = b.putObject("bindings");

            for (Map.Entry<String, String> e : filtered.entrySet()) {
                binds.put(e.getKey(), e.getValue());
            }
        }

        try {
            return JsonUtils.toPrettyJson(root) + "\n";
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render keybindings template", e);
        }
    }


    static Map<String, String> filterReservedShortcuts(Map<String, String> bindings) {
        Set<String> reserved = new HashSet<>();
        for (var rs : ReservedShortcuts.NON_REBINDABLE) {
            reserved.add(ReservedShortcuts.normalizeKeyForComparison(rs.key()));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            String norm = ReservedShortcuts.normalizeKeyForComparison(e.getKey());
            if (!reserved.contains(norm)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
