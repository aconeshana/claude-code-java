package com.claudecode.core.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the human-readable one-line summary shown when the LSP tool is invoked, enriching
 * position-based operations with the symbol under the cursor.
 */
public final class LspToolUseSummary {

    private static final Set<String> POSITION_OPS = Set.of(
            "goToDefinition", "findReferences", "hover", "goToImplementation");

    private LspToolUseSummary() {}

    /**
     * Formats the LSP tool input into a summary string, or {@link Optional#empty}
     * if the input is missing the {@code operation} field.
     */
    public static Optional<String> format(JsonNode input) {
        if (input == null || !input.has("operation")) return Optional.empty();
        String operation = input.get("operation").asText();
        String filePath = input.has("filePath") ? input.get("filePath").asText() : null;
        int line = input.has("line") ? input.get("line").asInt(0) : 0;
        int character = input.has("character") ? input.get("character").asInt(0) : 0;
        String display = displayPath(filePath);

        if (POSITION_OPS.contains(operation) && filePath != null) {
            Optional<String> symbol =
                    SymbolAtPosition.symbolAt(Path.of(filePath), line - 1, character - 1);
          return symbol.map(s -> "operation: \"" + operation
                  + "\", symbol: \"" + s + "\", in: \"" + display + "\"")
              .or(() -> Optional.of("operation: \"" + operation
                  + "\", file: \"" + display + "\", position: " + line + ":" + character));
        }

        StringBuilder sb = new StringBuilder("operation: \"").append(operation).append("\"");
        if (filePath != null) sb.append(", file: \"").append(display).append("\"");
        return Optional.of(sb.toString());
    }

/**
     * Compact display: the file name (last path segment).
     */
    private static String displayPath(String filePath) {
        if (filePath == null) return "";
        try {
            return Path.of(filePath).getFileName().toString();
        } catch (Exception _) {
            return filePath;
        }
    }
}
