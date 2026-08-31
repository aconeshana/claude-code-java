package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.lsp.LspToolUseSummary;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.TaskOutputPaths;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Declarative tool-card presentation contracts used by both live rendering and transcript replay.
 */
final class ToolVisualContractRegistry {

    enum ResultMode {
        DEFAULT,
        HIDDEN,
        SEARCH,
        TASK_OUTPUT,
        SKILL,
        ASK_USER_QUESTION,
        TASK_STOP,
        SEND_MESSAGE,
        ENTER_WORKTREE,
        EXIT_WORKTREE,
        LSP,
        JSON_OUTPUT,
        MCP,
        WEB_FETCH,
        WEB_SEARCH,
        ENTER_PLAN_MODE,
        EXIT_PLAN_MODE,
        CRON_CREATE,
        CRON_DELETE,
        CRON_LIST
    }

    record UseView(boolean hidden, String displayName, String argumentText, String tag) {
        String argsPart() {
            return StringUtils.isBlank(argumentText) ? "" : "(" + argumentText + ")";
        }

        String tagPart() {
            return StringUtils.isBlank(tag) ? "" : " " + tag;
        }
    }

    private static final Set<String> HIDDEN_USE = Set.of(
        "EnterPlanMode", "ExitPlanMode",
        "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
        "ScheduleWakeup", "AskUserQuestion", "SendMessage",
        "TodoWrite", "ToolSearch");

    private static final Set<String> HIDDEN_RESULT = Set.of(
        "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
        "Workflow", "ScheduleWakeup", "TeamDelete",
        "TodoWrite", "ToolSearch");

    private static final Pattern WORKFLOW_DESCRIPTION = Pattern.compile(
        "(?:[\\\"']description[\\\"']|description)\\s*:\\s*[\\\"']([^\\\"']+)[\\\"']");

    private ToolVisualContractRegistry() {}

    static boolean hidesUse(String toolName) {
        return HIDDEN_USE.contains(toolName);
    }

    static ResultMode resultMode(String toolName) {
        if (HIDDEN_RESULT.contains(toolName)) return ResultMode.HIDDEN;
        if (isMcpAuthTool(toolName)) return ResultMode.HIDDEN;
        if (isMcpTool(toolName)) return ResultMode.MCP;
        return switch (toolName == null ? "" : toolName) {
            case "Grep", "Glob" -> ResultMode.SEARCH;
            case "TaskOutput", "AgentOutputTool", "BashOutputTool" -> ResultMode.TASK_OUTPUT;
            case "Skill" -> ResultMode.SKILL;
            case "AskUserQuestion" -> ResultMode.ASK_USER_QUESTION;
            case "TaskStop", "KillShell" -> ResultMode.TASK_STOP;
            case "SendMessage" -> ResultMode.SEND_MESSAGE;
            case "EnterWorktree" -> ResultMode.ENTER_WORKTREE;
            case "ExitWorktree" -> ResultMode.EXIT_WORKTREE;
            case "LSP" -> ResultMode.LSP;
            case "ListMcpResourcesTool", "ReadMcpResourceTool" -> ResultMode.JSON_OUTPUT;
            case "WebFetch" -> ResultMode.WEB_FETCH;
            case "WebSearch" -> ResultMode.WEB_SEARCH;
            case "EnterPlanMode" -> ResultMode.ENTER_PLAN_MODE;
            case "ExitPlanMode" -> ResultMode.EXIT_PLAN_MODE;
            case "CronCreate" -> ResultMode.CRON_CREATE;
            case "CronDelete" -> ResultMode.CRON_DELETE;
            case "CronList" -> ResultMode.CRON_LIST;
            default -> ResultMode.DEFAULT;
        };
    }

    static UseView useView(String toolName, String inputJson, boolean verbose) {
        JsonNode input = parse(inputJson);
        if (hidesUse(toolName) && !Strings.CS.equals("SendMessage", toolName)) {
            return new UseView(true, toolName, "", "");
        }
        if (isMcpAuthTool(toolName)) return mcpAuthUse(toolName);
        if (isMcpTool(toolName)) return mcpUse(toolName, input, verbose);
        return switch (toolName == null ? "" : toolName) {
            case "Agent" -> new UseView(false, defaultName(toolName, input),
                text(input, "description"), "");
            case "Read", "FileRead" -> readUse(input);
            case "Grep", "Glob" -> searchUse(input, verbose);
            case "Edit", "FileEdit" -> editUse(input, verbose);
            case "TaskOutput", "AgentOutputTool", "BashOutputTool" -> taskOutputUse(input);
            case "Skill" -> new UseView(false, "Skill", text(input, "skill", "name"), "");
            case "Workflow" -> new UseView(false, "Workflow", workflowSummary(input), "");
            case "AskUserQuestion" -> new UseView(true, "", "", "");
            case "TaskStop", "KillShell" -> new UseView(false, "TaskStop", "", "");
            case "TeamCreate" -> new UseView(false, "TeamCreate",
                "create team: " + text(input, "team_name"), "");
            case "TeamDelete" -> new UseView(false, "TeamDelete", "cleanup team: current", "");
            case "SendMessage" -> sendMessageUse(input);
            case "EnterWorktree" -> new UseView(false, "Creating worktree",
                "Creating worktree…", "");
            case "ExitWorktree" -> new UseView(false, "Exiting worktree",
                "Exiting worktree…", "");
            case "LSP" -> lspUse(input, verbose);
            case "ListMcpResourcesTool" -> new UseView(false, "listMcpResources",
                StringUtils.isBlank(text(input, "server")) ? "List all MCP resources"
                    : "List MCP resources from server \"" + text(input, "server") + "\"", "");
            case "ReadMcpResourceTool" -> readMcpResourceUse(input);
            case "WebFetch" -> webFetchUse(input, verbose);
            case "WebSearch" -> webSearchUse(input, verbose);
            case "CronCreate" -> cronCreateUse(input);
            case "CronDelete" -> new UseView(false, "CronDelete", text(input, "id"), "");
            case "CronList" -> new UseView(false, "CronList", "", "");
            default -> new UseView(false, defaultName(toolName, input), null, "");
        };
    }

    private static UseView cronCreateUse(JsonNode input) {
        String cron = text(input, "cron");
        String prompt = text(input, "prompt");
        String summary = cron;
        if (!StringUtils.isBlank(prompt)) summary += ": " + FormatUtils.truncateSingleLine(prompt, 60);
        return new UseView(false, "CronCreate", summary, "");
    }

    private static UseView mcpAuthUse(String toolName) {
        String[] parts = toolName.split("__", 3);
        String server = parts.length > 1 ? parts[1] : "mcp";
        return new UseView(false, server + " - authenticate (MCP)",
            "Authenticate " + server + " MCP server", "");
    }

    private static UseView lspUse(JsonNode input, boolean verbose) {
        String summary;
        if (!verbose) {
            summary = LspToolUseSummary.format(input).orElse("");
        } else {
            String operation = text(input, "operation");
            if (StringUtils.isBlank(operation)) return new UseView(false, "LSP", "", "");
            summary = "operation: \"" + operation + "\"";
            String path = text(input, "filePath");
            if (!StringUtils.isBlank(path)) summary += ", file: \"" + path + "\"";
            if (isPositionLspOperation(operation) && input.has("line") && input.has("character")) {
                summary += ", position: " + input.path("line").asInt()
                    + ":" + input.path("character").asInt();
            }
        }
        return new UseView(false, "LSP", summary, "");
    }

    private static boolean isPositionLspOperation(String operation) {
        return Strings.CS.equalsAny(operation, "goToDefinition", "findReferences", "hover",
            "goToImplementation");
    }

    private static UseView readMcpResourceUse(JsonNode input) {
        String server = text(input, "server");
        String uri = text(input, "uri");
        if (StringUtils.isBlank(server) || StringUtils.isBlank(uri)) {
            return new UseView(false, "readMcpResource", "", "");
        }
        return new UseView(false, "readMcpResource",
            "Read resource \"" + uri + "\" from server \"" + server + "\"", "");
    }

    private static UseView webFetchUse(JsonNode input, boolean verbose) {
        String url = text(input, "url");
        if (StringUtils.isBlank(url)) return new UseView(false, "WebFetch", "", "");
        if (!verbose) return new UseView(false, "WebFetch", url, "");
        String message = "url: \"" + url + "\"";
        String prompt = text(input, "prompt");
        if (!StringUtils.isBlank(prompt)) message += ", prompt: \"" + prompt + "\"";
        return new UseView(false, "WebFetch", message, "");
    }

    private static UseView webSearchUse(JsonNode input, boolean verbose) {
        String query = text(input, "query");
        if (StringUtils.isBlank(query)) return new UseView(false, "Web Search", "", "");
        StringBuilder message = new StringBuilder("\"").append(query).append('"');
        if (verbose) {
            appendDomainFilter(message, input.path("allowed_domains"), "only allowing domains: ");
            appendDomainFilter(message, input.path("blocked_domains"), "blocking domains: ");
        }
        return new UseView(false, "Web Search", message.toString(), "");
    }

    private static void appendDomainFilter(StringBuilder message, JsonNode domains,
                                           String label) {
        if (!domains.isArray() || domains.isEmpty()) return;
        List<String> values = new ArrayList<>();
        domains.forEach(value -> values.add(value.asText()));
        message.append(", ").append(label).append(String.join(", ", values));
    }

    private static UseView mcpUse(String toolName, JsonNode input, boolean verbose) {
        String[] parts = toolName.split("__", 3);
        String server = parts.length > 1 ? parts[1] : "mcp";
        String tool = parts.length > 2 ? parts[2] : toolName;
        List<String> args = new ArrayList<>();
        input.fields().forEachRemaining(entry -> {
            String rendered = entry.getValue().toString();
            if (!verbose && rendered.length() > 80) {
                rendered = rendered.substring(0, 80).stripTrailing() + "…";
            }
            args.add(entry.getKey() + ": " + rendered);
        });
        return new UseView(false, server + " - " + tool + " (MCP)",
            String.join(", ", args), "");
    }

    private static boolean isMcpTool(String toolName) {
        return toolName != null && Strings.CS.startsWith(toolName, "mcp__");
    }

    private static boolean isMcpAuthTool(String toolName) {
        return toolName != null && Strings.CS.startsWith(toolName, "mcp__")
            && Strings.CS.endsWith(toolName, "__authenticate");
    }

    private static UseView sendMessageUse(JsonNode input) {
        JsonNode message = input.path("message");
        if (!message.isObject()
                || !Strings.CS.equals("plan_approval_response", message.path("type").asText())) {
            return new UseView(true, "SendMessage", "", "");
        }
        String action = message.path("approve").asBoolean(false) ? "approve" : "reject";
        return new UseView(false, "SendMessage",
            action + " plan from: " + text(input, "to"), "");
    }

    private static UseView searchUse(JsonNode input, boolean verbose) {
        String pattern = text(input, "pattern");
        if (StringUtils.isBlank(pattern)) return new UseView(false, "Search", "", "");
        StringBuilder message = new StringBuilder("pattern: \"").append(pattern).append('"');
        String path = text(input, "path");
        if (!StringUtils.isBlank(path)) {
            message.append(", path: \"")
                .append(verbose ? path : displayPath(path))
                .append('"');
        }
        return new UseView(false, "Search", message.toString(), "");
    }

    private static UseView editUse(JsonNode input, boolean verbose) {
        String filePath = text(input, "file_path", "path");
        boolean plan = isPlanFile(filePath);
        String name;
        if (plan) {
            name = "Updated plan";
        } else if (input.has("edits")) {
            name = "Update";
        } else if (input.has("old_string") && input.path("old_string").asText().isEmpty()) {
            name = "Create";
        } else {
            name = "Update";
        }
        String message = plan || StringUtils.isBlank(filePath) ? "" : verbose ? filePath : displayPath(filePath);
        return new UseView(false, name, message, "");
    }

    private static UseView taskOutputUse(JsonNode input) {
        boolean block = !input.has("block") || input.path("block").asBoolean(true);
        return new UseView(false, "Task Output", block ? "" : "non-blocking", "");
    }

    private static UseView readUse(JsonNode input) {
        String filePath = text(input, "file_path");
        if (TaskOutputPaths.agentOutputTaskId(filePath) != null) {
            return new UseView(false, "Read agent output", "", "");
        }
        return new UseView(false, "Read", null, "");
    }

    private static String workflowSummary(JsonNode input) {
        String name = text(input, "name");
        if (!StringUtils.isBlank(name)) return "dynamic workflow: " + name;
        String script = text(input, "script");
        if (StringUtils.isBlank(script)) return "";
        Matcher matcher = WORKFLOW_DESCRIPTION.matcher(script);
        if (matcher.find()) return matcher.group(1);
        String first = script.lines().map(String::strip).filter(line -> !StringUtils.isBlank(line))
            .findFirst().orElse("");
        return first.length() <= 50 ? first : FormatUtils.truncate(first, 50);
    }

    private static String defaultName(String toolName, JsonNode input) {
        if (toolName == null) return "";
        if (Strings.CS.equals("Agent", toolName)) {
            String subtype = text(input, "subagent_type");
            if (!StringUtils.isBlank(subtype) && !Strings.CS.equalsAny(subtype, "general-purpose", "worker")) {
                return subtype;
            }
        }
        return switch (toolName) {
            case "Read", "FileRead" -> "Read";
            case "Write", "FileWrite" -> "Write";
            case "WebSearch" -> "Web Search";
            default -> toolName;
        };
    }

    private static JsonNode parse(String json) {
        JsonNode parsed = JsonUtils.safeParseJson(json == null ? "" : json);
        return parsed != null && parsed.isObject()
            ? parsed : JsonUtils.getMapper().createObjectNode();
    }

    private static String text(JsonNode input, String... fields) {
        for (String field : fields) {
            JsonNode value = input.path(field);
            if (value.isTextual() && !StringUtils.isBlank(value.asText())) return value.asText();
        }
        return "";
    }

    private static boolean isPlanFile(String filePath) {
        if (StringUtils.isBlank(filePath)) return false;
        try {
            Path file = Path.of(filePath).toAbsolutePath().normalize();
            return file.startsWith(PlanFiles.getPlansDirectory().toAbsolutePath().normalize());
        } catch (Exception _) {
            return false;
        }
    }

    private static String displayPath(String filePath) {
        if (StringUtils.isEmpty(filePath)) return "";
        try {
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            if (path.startsWith(cwd)) {
                String relative = cwd.relativize(path).toString();
                if (!relative.isEmpty()) return relative;
            }
            String home = System.getProperty("user.home");
            if (home != null) {
                Path homePath = Path.of(home).toAbsolutePath().normalize();
                if (path.startsWith(homePath)) {
                    return "~" + File.separator + homePath.relativize(path);
                }
            }
        } catch (Exception _) {
            return filePath;
        }
        return filePath;
    }
}
