package com.claudecode.tools.lsp;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.lsp.LspService;
import com.claudecode.tools.AnnotatedTool;
import com.claudecode.tools.BuiltInTool;
import com.claudecode.tools.ToolTexts;
import com.claudecode.tools.ToolCallResult;
import com.claudecode.tools.ValidationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Built-in read-only tool adapter for the LSP integration module.
 */
@BuiltInTool(
    name = "LSP",
    shouldDefer = true,
    readOnly = true
)
public class LSPTool extends AnnotatedTool<JsonNode, String> {

    private static final Pattern FOUND_COUNT = Pattern.compile("(?m)^Found (\\d+) ");
    private static final Pattern ACROSS_FILES = Pattern.compile(" across (\\d+) files");

    private LspService lspService;
    public LSPTool() { this.lspService = null; }
    public LSPTool(LspService lspService) { this.lspService = lspService; }

    private LspService getOrCreateService() {
        if (lspService == null) this.lspService = new LspService();
        return lspService;
    }

    @Override
    public String description() {
        return ToolTexts.description("LSP");
    }

    @Override
    public String searchHint() {
        return "code intelligence (definitions, references, symbols, hover)";
    }

    @Override
    public boolean isLsp() {
        return true;
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = createObjectSchema();
        ObjectNode props = (ObjectNode) schema.get("properties");

        ObjectNode opProp = mapper().createObjectNode();
        opProp.put("type", "string");
        ArrayNode opEnum = mapper().createArrayNode();
        for (String op : new String[]{
            "goToDefinition", "findReferences", "hover",
            "documentSymbol", "workspaceSymbol", "goToImplementation",
            "prepareCallHierarchy", "incomingCalls", "outgoingCalls"
        }) opEnum.add(op);
        opProp.set("enum", opEnum);
        opProp.put("description", "The LSP operation to perform");
        props.set("operation", opProp);

        ObjectNode fileProp = mapper().createObjectNode();
        fileProp.put("type", "string");
        fileProp.put("description", "The absolute or relative path to the file");
        props.set("filePath", fileProp);

        ObjectNode lineProp = mapper().createObjectNode();
        lineProp.put("type", "integer");
        lineProp.put("minimum", 1);
        lineProp.put("description", "The line number (1-based, as shown in editors)");
        props.set("line", lineProp);

        ObjectNode charProp = mapper().createObjectNode();
        charProp.put("type", "integer");
        charProp.put("minimum", 1);
        charProp.put("description", "The character offset (1-based, as shown in editors)");
        props.set("character", charProp);

        schema.set("required", mapper().createArrayNode()
            .add("operation").add("filePath").add("line").add("character"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public ValidationResult validateInput(JsonNode input, ToolExecutionContext context) {
        String operation = input == null ? "" : input.path("operation").asText("");
        String filePath = input == null ? "" : input.path("filePath").asText("");
        int line = input == null ? 0 : input.path("line").asInt(0);
        int character = input == null ? 0 : input.path("character").asInt(0);
        if (!List.of("goToDefinition", "findReferences", "hover", "documentSymbol",
                "workspaceSymbol", "goToImplementation", "prepareCallHierarchy",
                "incomingCalls", "outgoingCalls").contains(operation)) {
            return ValidationResult.invalid("Invalid input: operation must be a supported LSP operation");
        }
        if (StringUtils.isBlank(filePath)) {
            return ValidationResult.invalid("Invalid input: filePath is required");
        }
        if (line <= 0 || character <= 0) {
            return ValidationResult.invalid("Invalid input: line and character must be positive integers");
        }
        Path resolved = resolvePath(filePath, context);
        if (isUncPath(resolved)) {

            return ValidationResult.valid();
        }
        if (!Files.exists(resolved)) {
            return ValidationResult.invalid("File does not exist: " + filePath);
        }
        if (!Files.isRegularFile(resolved)) {
            return ValidationResult.invalid("Path is not a file: " + filePath);
        }
        return ValidationResult.valid();
    }

    @Override
    public String call(JsonNode input, ToolExecutionContext context) {
        return callWithResult(input, context).rawResult();
    }

    @Override
    public ToolCallResult<String> callWithResult(JsonNode input, ToolExecutionContext context) {
        String result = callInternal(input, context);

        // tool-runner passing the validated args object), so no null re-guard.
        String operation = input.path("operation").asText("");
        String filePath = input.path("filePath").asText("");
        CountSummary counts = summarizeResult(operation, result);
        LspInvocation invocation = new LspInvocation(operation, result, filePath,
            counts.resultCount(), counts.fileCount());
        return new ToolCallResult<>(result, mapInvocation(result, invocation));
    }

    private String callInternal(JsonNode input, ToolExecutionContext context) {
        String operation = input.has("operation") ? input.get("operation").asText() : null;
        String filePath  = input.has("filePath")  ? input.get("filePath").asText()  : null;
        int line         = input.has("line")       ? input.get("line").asInt(1)      : 1;
        int character    = input.has("character")  ? input.get("character").asInt(1) : 1;

        if (StringUtils.isBlank(operation))
            return "Error: 'operation' is required. Valid: goToDefinition, findReferences, hover, documentSymbol, workspaceSymbol, goToImplementation, prepareCallHierarchy, incomingCalls, outgoingCalls";
        if (StringUtils.isBlank(filePath))
            return "Error: 'filePath' is required";

        LspService service = getOrCreateService();
        Path path;
        try {
            path = resolvePath(filePath, context);
        } catch (Exception _) {
            return "Error: invalid file path: " + filePath;
        }

// before dispatching; match that so a bad path fails cleanly instead of
        // producing a cryptic downstream error.
        if (!Files.exists(path)) return "Error: file does not exist: " + filePath;
        if (!Files.isRegularFile(path)) return "Error: not a file: " + filePath;
        try {
            if (Files.size(path) > 10_000_000L) {
                return "File too large for LSP analysis ("
                    + Math.ceil(Files.size(path) / 1_000_000d) + "MB exceeds 10MB limit)";
            }
        } catch (IOException _) {
            // Let the LSP server return its normal unavailable/error response.
        }

        return switch (operation) {
            case "goToDefinition"       -> formatList(service.goToDefinition(path, line, character));
            case "findReferences"       -> formatList(service.findReferences(path, line, character));
            case "hover"                -> service.hover(path, line, character);
            case "documentSymbol"       -> formatList(service.documentSymbol(path, line, character));
            case "workspaceSymbol"      -> formatList(service.workspaceSymbol(path, line, character));
            case "goToImplementation"   -> formatList(service.goToImplementation(path, line, character));
            case "prepareCallHierarchy" -> formatList(service.prepareCallHierarchy(path, line, character));
            case "incomingCalls"        -> formatList(service.incomingCalls(path, line, character));
            case "outgoingCalls"        -> formatList(service.outgoingCalls(path, line, character));
            default -> "Unknown operation: " + operation;
        };
    }


    private ToolResult mapInvocation(String text, LspInvocation invocation) {
        ObjectNode output = mapper().createObjectNode();
        output.put("operation", invocation.operation());
        output.put("result", invocation.result());
        output.put("filePath", invocation.filePath());
        output.put("resultCount", invocation.resultCount());
        output.put("fileCount", invocation.fileCount());
        return ToolResult.success(text).withToolUseResult(output);
    }

    private static String formatList(List<String> items) {
        if (items == null || items.isEmpty()) return "(no results)";
        return String.join("\n", items);
    }

    private static CountSummary summarizeResult(String operation, String result) {
        if (StringUtils.isBlank(result) || Strings.CS.startsWith(result, "(")
                || Strings.CS.startsWith(result, "No ")
                || Strings.CS.startsWith(result, "Error")
                || Strings.CS.startsWith(result, "Operation not supported")) {
            return new CountSummary(0, 0);
        }
        int count = 1;
        Matcher found = FOUND_COUNT.matcher(result);
        if (found.find()) count = Integer.parseInt(found.group(1));
        int files = count == 0 ? 0 : 1;
        Matcher across = ACROSS_FILES.matcher(result);
        if (across.find()) {
            files = Integer.parseInt(across.group(1));
        } else if (Strings.CS.equals("workspaceSymbol", operation)
                || Strings.CS.equals("incomingCalls", operation)
                || Strings.CS.equals("outgoingCalls", operation)) {
            long groupedFiles = result.lines()
                .map(String::stripTrailing)
                .filter(line -> !StringUtils.isBlank(line) && !Strings.CS.startsWith(line, "Found ")
                    && Strings.CS.endsWith(line, ":") && !Strings.CS.startsWith(line, " "))
                .count();
            if (groupedFiles > 0) files = (int) groupedFiles;
        }
        return new CountSummary(count, files);
    }

    private static Path resolvePath(String filePath, ToolExecutionContext context) {
        Path path = Path.of(filePath);
        if (path.isAbsolute()) return path.normalize();
        String cwd = context != null && context.workingDirectory() != null
            ? context.workingDirectory() : System.getProperty("user.dir");
        return Path.of(cwd).resolve(path).normalize();
    }

    private static boolean isUncPath(Path path) {
        String value = path.toString();
        return Strings.CS.startsWith( value, "\\\\") ||Strings.CS.startsWith( value, "//");
    }


    @Override
    public boolean isEnabled() {
        if (lspService == null) {
            return true;
        }
        return lspService.hasHealthyServer();
    }


    /** LSP is a read-only code-intelligence query; classify by operation/path. */
    @Override
    public Object toAutoClassifierInput(JsonNode input) {
        if (input == null) return "";
        String operation = input.path("operation").asText("");
        String path = input.path("filePath").asText("");
        return StringUtils.isBlank(path) ? operation : operation + " " + path;
    }


    private record LspInvocation(String operation, String result, String filePath,
                                 int resultCount, int fileCount) {}
    private record CountSummary(int resultCount, int fileCount) {}
}
