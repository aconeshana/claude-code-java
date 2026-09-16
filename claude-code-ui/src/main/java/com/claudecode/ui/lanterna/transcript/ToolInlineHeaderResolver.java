package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.lsp.LspToolUseSummary;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Resolves the one-line inline header shown under a tool-use row before the tool's own
 * summary renders: {@code $ <command>} for Bash and the LSP operation summary for LSP.
 * Every other tool renders no inline header.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code tools/BashTool/UI.tsx} — {@code renderToolUseMessage} showing the command
 *       line under the Bash tool-use row.</li>
 *   <li>{@code tools/LSPTool/UI.tsx} — LSP operation summary line.</li>
 * </ul>
 */
public final class ToolInlineHeaderResolver {

    private ToolInlineHeaderResolver() {}

    public static Optional<String> resolve(String toolName, String argsJson) {
        if (StringUtils.isBlank(argsJson)) return Optional.empty();
        if (Strings.CS.equals("Bash", toolName)) {
            try {
                JsonNode root = JsonUtils.getMapper().readTree(argsJson);
                JsonNode cmd = root.get("command");
                if (cmd == null || !cmd.isTextual()) return Optional.empty();
                String command = cmd.asText().strip();
                return command.isEmpty() ? Optional.empty() : Optional.of("$ " + command);
            } catch (Exception _) {
                return Optional.empty();
            }
        }
        if (Strings.CS.equals("LSP", toolName)) {
            try {
                return LspToolUseSummary.format(JsonUtils.getMapper().readTree(argsJson));
            } catch (Exception _) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
