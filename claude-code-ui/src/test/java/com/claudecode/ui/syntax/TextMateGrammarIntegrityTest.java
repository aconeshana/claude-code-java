package com.claudecode.ui.syntax;

import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Verifies structural invariants that TM4E otherwise reports only at runtime. */
class TextMateGrammarIntegrityTest {

    private static final ObjectMapper ISOLATED_MAPPER = new ObjectMapper();

    @Test
    void bundledGrammarsHaveNoDanglingLocalRepositoryIncludes() throws Exception {
        JsonNode index = readResource("/grammars/index.json");
        Set<String> files = new HashSet<>();
        index.path("languages").forEach(entry -> files.add(entry.path("file").asText()));

        Set<String> dangling = new HashSet<>();
        for (String file : files) {
            JsonNode grammar = readResource("/grammars/" + file);
            Set<String> repositoryRules = new HashSet<>();
            collectRepositoryRuleNames(grammar, repositoryRules);
            Set<String> localIncludes = new HashSet<>();
            collectLocalIncludes(grammar, localIncludes);
            localIncludes.removeAll(repositoryRules);
            localIncludes.forEach(include -> dangling.add(file + "#" + include));
        }
        assertTrue(dangling.isEmpty(),
            () -> "bundled grammars have dangling local includes: " + dangling);
    }

    private static JsonNode readResource(String path) throws Exception {
        try (InputStream input = TextMateGrammarIntegrityTest.class.getResourceAsStream(path)) {
            assertTrue(input != null, "missing resource " + path);
            return ISOLATED_MAPPER.readTree(input);
        }
    }

    private static void collectRepositoryRuleNames(JsonNode node, Set<String> names) {
        if (node == null) return;
        if (node.isObject()) {
            JsonNode repository = node.get("repository");
            if (repository != null && repository.isObject()) {
                repository.fieldNames().forEachRemaining(names::add);
            }
            node.forEach(child -> collectRepositoryRuleNames(child, names));
        } else if (node.isArray()) {
            node.forEach(child -> collectRepositoryRuleNames(child, names));
        }
    }

    private static void collectLocalIncludes(JsonNode node, Set<String> includes) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (Strings.CS.equals("include", field.getKey()) && field.getValue().isTextual()) {
                    String include = field.getValue().asText();
                    if (Strings.CS.startsWith(include, "#")) includes.add(include.substring(1));
                } else {
                    collectLocalIncludes(field.getValue(), includes);
                }
            }
        } else if (node.isArray()) {
            node.forEach(child -> collectLocalIncludes(child, includes));
        }
    }
}
