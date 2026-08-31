package com.claudecode.cli;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.PowerShellEncodedCommand;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.apache.commons.lang3.StringUtils;

/** Model-facing, write-only boundary used exclusively by {@code /statusline}. */
@Explanation("Prevents status-line setup agents from reading or echoing unrelated user settings")
@BuiltInTool(
    name = ConfigureStatusLineTool.NAME,
    shouldDefer = true,
    strict = true
)
final class ConfigureStatusLineTool extends AnnotatedTool<JsonNode, String> {

    static final String NAME = "ConfigureStatusLine";
    private static final int MAX_COMMAND_LENGTH = 8_192;

    private final BiConsumer<String, Integer> writer;
    private final Platform platform;

    ConfigureStatusLineTool(BiConsumer<String, Integer> writer) {
        this(writer, Platform.CURRENT);
    }

    ConfigureStatusLineTool(BiConsumer<String, Integer> writer, Platform platform) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    public String description() {
        return "Configure the user status line without reading any other settings";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = createObjectSchema();
        ObjectNode properties = (ObjectNode) schema.get("properties");

        ObjectNode command = mapper().createObjectNode();
        command.put("type", "string");
        command.put("minLength", 1);
        command.put("maxLength", MAX_COMMAND_LENGTH);
        command.put("description",
            platform == Platform.WIN32
                ? "A single-line PowerShell script body. Read status JSON with "
                    + "[Console]::In.ReadToEnd(); do not include powershell.exe or -Command."
                : "A single-line status command. It receives Claude Code status JSON on stdin.");
        properties.set("command", command);

        ObjectNode padding = mapper().createObjectNode();
        padding.put("type", "integer");
        padding.put("minimum", 0);
        padding.put("maximum", Integer.MAX_VALUE);
        padding.put("description", "Optional non-negative left padding; defaults to 0.");
        properties.set("padding", padding);

        schema.set("required", mapper().createArrayNode().add("command"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String command = input == null ? null : input.path("command").asText(null);
        if (StringUtils.isBlank(command)) {
            return ValidationResult.invalid("command must not be blank");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            return ValidationResult.invalid(
                "command exceeds the maximum length of " + MAX_COMMAND_LENGTH);
        }
        if (command.indexOf('\0') >= 0 || (platform != Platform.WIN32
                && (command.indexOf('\r') >= 0 || command.indexOf('\n') >= 0))) {
            return ValidationResult.invalid("command must be a single line");
        }
        if (platform == Platform.WIN32 && command.contains("`u{")) {
            return ValidationResult.invalid(
                "command must use Windows PowerShell 5.1 syntax and ASCII display text");
        }
        JsonNode padding = input.get("padding");
        if (padding != null && (!padding.isIntegralNumber()
                || !padding.canConvertToInt() || padding.asInt() < 0)) {
            return ValidationResult.invalid("padding must be a non-negative integer");
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        String command = input.path("command").asText();
        String persisted = platform == Platform.WIN32
            ? PowerShellEncodedCommand.encode(command) : command;
        writer.accept(persisted, input.path("padding").asInt(0));
        return "Configured the user status line.";
    }

    @Override
    public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext context) {
        return PermissionDecision.allow();
    }

    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        return "statusLine";
    }
}
