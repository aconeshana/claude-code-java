package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.SourceCodePainter.displayPath;

import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.tools.ToolUseTag;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.PendingToolLedger.PendingTool;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Builds the one-row tool header: the status dot, the (possibly renamed) tool name, an
 * optional external tag (task id, agent name) and the argument preview, in the dim
 * queued/in-progress form and the resolved success/error form. Also derives the
 * "primary input" a tool card is indexed by for message actions.
 *
 * <ul>
 *   <li>{@code src/components/messages/AssistantToolUseMessage.tsx} — dot colour by
 *       state, tool display name, argument summary in parentheses.</li>
 *   <li>{@code src/tools/AgentTool/UI.tsx} — agent headers are painted on the
 *       sub-agent's colour with inverse bold text.</li>
 *   <li>{@code src/utils/toolSummary.ts} — first-string-value input summary with a
 *       100-char cap; Write shows the display path instead.</li>
 * </ul>
 */
final class ToolHeaderRenderer {

    /** Dispatcher state consulted while building a header. */
    interface Host {
        boolean verbose();
        /** Progress events recorded for a tool use (live ledger first, transcript model second). */
        List<ProgressMessage> progressFor(String toolUseId);
    }

    private static final String BLACK_CIRCLE = ToolResultLines.BLACK_CIRCLE;

    private final Host host;
    private final PendingToolLedger tools;
    private LanternaMessageDispatcher.ToolTagLookup tagLookup = _ -> Optional.empty();

    ToolHeaderRenderer(Host host, PendingToolLedger tools) {
        this.host = host;
        this.tools = tools;
    }

    void setTagLookup(LanternaMessageDispatcher.ToolTagLookup lookup) {
        this.tagLookup = lookup != null ? lookup : _ -> Optional.empty();
    }

    LanternaMessageDispatcher.ToolTagLookup tagLookup() { return tagLookup; }

    /** Stops the header blinking and repaints it in its resolved colour. */
    void complete(PendingTool pending, TextColor color, MessagePanel panel) {
        if (pending == null || pending.transparent() || pending.lineIdx() < 0) return;
        panel.stopBlinkLine(pending.lineIdx(), buildDoneSegs(
            pending.toolName(), color,
            toolTagPart(pending.toolName(), pending.inputJson(), pending.toolUseId()),
            toolArgsPart(pending.toolName(), pending.inputJson()), pending.inputJson()));
    }

    /**
     * Builds dim (queued/in-progress) tool call segments — no args yet (stage 1: tool_streaming_start).
     */
    List<MessagePanel.Segment> dimToolSegs(String toolName) {
        return dimToolSegs(toolName, "", "");
    }

    /** Builds dim tool call segments with an optional tag (e.g. task ID). */
    List<MessagePanel.Segment> dimToolSegs(String toolName, String argsPart, String tag) {
        return dimToolSegs(toolName, argsPart, tag, "");
    }

    List<MessagePanel.Segment> dimToolSegs(String toolName, String argsPart,
                                                   String tag, String inputJson) {
        List<MessagePanel.Segment> segs = new ArrayList<>();
        segs.add(new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.welcomeDim()));
        segs.add(toolNameSegment(toolName, inputJson, TextColor.ANSI.DEFAULT));
        if (!tag.isEmpty()) {
            segs.add(new MessagePanel.Segment(tag, LanternaTheme.welcomeDim()));
        }
        segs.add(new MessagePanel.Segment(argsPart, LanternaTheme.welcomeDim()));
        return segs;
    }

    /**
     * Builds resolved (done) tool call segments with success/error color and optional tag, keeping the
     * argument preview that {@code dimToolSegs} put up while the tool was running.
     */
    List<MessagePanel.Segment> buildDoneSegs(String toolName, TextColor dotColor,
                                                     String tag, String argsPart,
                                                     String inputJson) {
        List<MessagePanel.Segment> segs = new ArrayList<>();
        segs.add(new MessagePanel.Segment(BLACK_CIRCLE, dotColor));
        segs.add(toolNameSegment(toolName, inputJson, LanternaTheme.inputText()));
        if (!tag.isEmpty()) {
            segs.add(new MessagePanel.Segment(tag, LanternaTheme.welcomeDim()));
        }
        if (StringUtils.isNotEmpty(argsPart)) {
            segs.add(new MessagePanel.Segment(argsPart, LanternaTheme.welcomeDim()));
        }
        return segs;
    }

    MessagePanel.Segment toolNameSegment(String toolName, String inputJson,
                                                 TextColor fallback) {
        String visibleName = ToolVisualContractRegistry
            .useView(toolName, inputJson, host.verbose()).displayName();
        if (!Strings.CS.equals("Agent", toolName)) {
            return new MessagePanel.Segment(visibleName, fallback);
        }
        String colorName = AgentColorStore.get(agentSubtype(inputJson));
        TextColor background = LanternaTheme.agentColor(colorName);
        return background == null
            ? new MessagePanel.Segment(visibleName, fallback)
            : new MessagePanel.Segment(
                visibleName, LanternaTheme.inverseText(), background, null,
                Set.of(SGR.BOLD));
    }

    /**
     * Extracts a concise display summary from a raw JSON string.
     * Looks for the first short string value as the primary argument.
     */
    private static final int SUMMARY_MAX_LEN = 100;

    record PrimaryInput(String label, String value) {}

    private static final Map<String, String[]> PRIMARY_INPUT_FIELD = Map.ofEntries(
        //          tool            label      field
        Map.entry("Read",          new String[]{"path",    "file_path"}),
        Map.entry("FileRead",      new String[]{"path",    "file_path"}),
        Map.entry("Edit",          new String[]{"path",    "file_path"}),
        Map.entry("FileEdit",      new String[]{"path",    "file_path"}),
        Map.entry("Write",         new String[]{"path",    "file_path"}),
        Map.entry("FileWrite",     new String[]{"path",    "file_path"}),
        Map.entry("NotebookEdit",  new String[]{"path",    "notebook_path"}),
        Map.entry("Bash",          new String[]{"command", "command"}),
        Map.entry("Grep",          new String[]{"pattern", "pattern"}),
        Map.entry("Glob",          new String[]{"pattern", "pattern"}),
        Map.entry("WebFetch",      new String[]{"url",     "url"}),
        Map.entry("WebSearch",     new String[]{"query",   "query"}),
        Map.entry("Task",          new String[]{"prompt",  "prompt"}),
        Map.entry("Agent",         new String[]{"prompt",  "prompt"})
    );
    static PrimaryInput extractPrimaryInput(String toolName, String json) {
        if (toolName == null || json == null || StringUtils.isBlank(json)) return null;
        var parsed = JsonUtils.safeParseJson(json);
        if (parsed == null || !parsed.isObject()) return null;
        if (Strings.CS.equals("Tmux", toolName)) {
            var args = parsed.path("args");
            if (!args.isArray()) return null;
            List<String> values = new ArrayList<>();
            args.forEach(value -> values.add(value.asText()));
            return new PrimaryInput("command", "tmux " + String.join(" ", values));
        }
        String[] spec = PRIMARY_INPUT_FIELD.get(toolName);
        if (spec == null) return null;
        var value = parsed.path(spec[1]);
        return value.isTextual() && !StringUtils.isBlank(value.asText())
            ? new PrimaryInput(spec[0], value.asText()) : null;
    }

    static String summarizeInputJson(String toolName, String json) {
        if (StringUtils.isBlank(json)) return "";
        var parsedInput = JsonUtils.safeParseJson(json);
        if (parsedInput != null && parsedInput.isObject() && parsedInput.isEmpty()) return "";
        if (Strings.CS.equalsAny(toolName, "Write", "FileWrite")) {
            if (parsedInput != null && parsedInput.path("file_path").isTextual()) {
                return displayPath(parsedInput.path("file_path").asText());
            }
        }
        // Try to extract the first quoted string value from the JSON
        // e.g. {"command":"ls -la"} → "ls -la"
        int q1 = json.indexOf('"');
        if (q1 >= 0) {
            int q2 = json.indexOf('"', q1 + 1);  // end of key
            int q3 = q2 >= 0 ? json.indexOf('"', q2 + 1) : -1;  // start of value
            int q4 = q3 >= 0 ? json.indexOf('"', q3 + 1) : -1;  // end of value
            if (q4 > q3) {
                String val = json.substring(q3 + 1, q4);
                if (!val.isEmpty()) {
                    return val.length() <= SUMMARY_MAX_LEN ? val : FormatUtils.truncate(val, SUMMARY_MAX_LEN);
                }
            }
        }
        // Fallback: truncate raw JSON
        return json.length() <= SUMMARY_MAX_LEN ? json : FormatUtils.truncate(json, SUMMARY_MAX_LEN);
    }

    String toolArgsPart(String toolName, String inputJson) {
        ToolVisualContractRegistry.UseView view =
            ToolVisualContractRegistry.useView(toolName, inputJson, host.verbose());
        if (view.argumentText() != null) return view.argsPart();
        return argsPart(toolName, inputJson);
    }

    String toolTagPart(String toolName, String inputJson, String toolUseId) {
        List<ProgressMessage> progress = toolUseId == null ? List.of() : host.progressFor(toolUseId);
        Optional<ToolUseTag> external = tagLookup.resolve(new LanternaMessageDispatcher.ToolTagRequest(
            toolName, inputJson == null ? "" : inputJson, toolUseId,
            tools.result(toolUseId), progress));
        return external.map(tag -> " " + tag.text()).orElseGet(
          () -> ToolVisualContractRegistry.useView(toolName, inputJson, host.verbose()).tagPart());
    }

    private static String argsPart(String toolName, String inputJson) {
        String summary = summarizeInputJson(toolName, inputJson);
        return StringUtils.isBlank(summary) ? "" : "(" + summary + ")";
    }

    static String agentSubtype(String inputJson) {
        if (StringUtils.isBlank(inputJson)) return "";
        var input = JsonUtils.safeParseJson(inputJson);
        return input == null ? "" : input.path("subagent_type").asText("");
    }
}
