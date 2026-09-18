package com.claudecode.ui.lanterna.transcript;

import static com.claudecode.ui.lanterna.transcript.ToolResultLines.INDENT_PREFIX;
import static com.claudecode.ui.lanterna.transcript.ToolResultLines.appendOrReplaceToolResultLine;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.transcript.PendingToolLedger.PendingTool;
import com.claudecode.ui.lanterna.transcript.PendingToolLedger.ToolInvocation;
import com.claudecode.ui.lanterna.transcript.ToolResultRenderer.Placement;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TextColor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Projects sub-agent activity under its tool card: the live {@code ⎿ Initializing… / Tool(args)}
 * progress rows (last three, with a {@code +N more} fold and a compact form on short
 * terminals), the {@code Running N agents…} group card for parallel Agent calls from one
 * message, the background-hint affordance, and the completed / backgrounded result bodies
 * including the verbose transcript replay of the child conversation.
 *
 * <p>Progress rows live above later tool cards, so every insert or removal shifts the
 * recorded line indexes of the pending-tool ledger, the other progress blocks and the group
 * cards. This class owns the shifts it causes itself; rows inserted elsewhere in the panel
 * reach these blocks through {@link #shiftBlocks}.
 *
 * <ul>
 *   <li>{@code src/tools/AgentTool/UI.tsx} — {@code renderToolUseProgressMessage}: last
 *       three activities, {@code +N more tool uses}, the short-terminal
 *       {@code In progress… · N tool uses · tokens} form, {@code Done (N tool uses · tokens ·
 *       duration)} and {@code Backgrounded agent} results.</li>
 *   <li>{@code src/components/AgentGroup.tsx} / {@code AgentTool/groupedRender.tsx} —
 *       {@code Running N agents…} card with {@code ├─}/{@code └─} member rows, per-member
 *       status, all-async {@code background agents launched (↓ manage)} form.</li>
 *   <li>{@code src/components/BackgroundHint.tsx} — {@code Press Ctrl+B to run in background}
 *       under {@code paddingLeft={5}}.</li>
 *   <li>{@code src/components/messages/AgentTranscript.tsx} — transcript view replays the
 *       child messages ({@code Prompt:} / {@code Response:}) through a verbose child
 *       dispatcher.</li>
 * </ul>
 */
final class AgentProgressPresenter {

    /** Dispatcher services the presenter relies on. */
    interface Host {
        boolean verbose();
        boolean transcriptMode();
        String expandHint();
        String expandShortcut();
        /** Completes the Agent's own tool card before a result body renders. */
        Placement beginToolResult(ToolResultBlock result, MessagePanel panel);
        /** Dim Agent header row (no tag, no args) used when a member is folded into a group. */
        List<MessagePanel.Segment> dimAgentHeader(String inputJson);
    }

    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();
    private static final String BLACK_CIRCLE = ToolResultLines.BLACK_CIRCLE;
    /**
     * Wording for the background affordance. It lives here, not on the progress event, because
     * upstream's renderer builds the component from a bare {@code {kind:"background_hint"}}.
     */
    static final String BACKGROUND_HINT_TEXT = "Press Ctrl+B to run in background";
    /** Upstream renders the affordance under {@code <Box paddingLeft={5}>}. */
    static final int BACKGROUND_HINT_PADDING = 5;

    private final Host host;
    private final PendingToolLedger tools;

    AgentProgressPresenter(Host host, PendingToolLedger tools) {
        this.host = host;
        this.tools = tools;
    }

    void resetTurn() {
        agentProgressBlocks.clear();
        agentGroupsByToolUseId.clear();
    }

    /** Transcript replay model: recorded progress per Agent tool use, or empty for live turns. */
    void setTranscriptProgress(Map<String, List<ProgressMessage>> progressByToolUseId) {
        transcriptAgentProgress = progressByToolUseId == null ? Map.of() : progressByToolUseId;
        renderedVerboseAgentToolUseIds.clear();
    }

    List<ProgressMessage> transcriptProgress(String toolUseId) {
        return transcriptAgentProgress.getOrDefault(toolUseId, List.of());
    }

    boolean isGrouped(String toolUseId) {
        return toolUseId != null && agentGroupsByToolUseId.containsKey(toolUseId);
    }

    /** True when this tool use owns progress rows below its card that a row move must not straddle. */
    boolean hasProgressBlock(String toolUseId) {
        return toolUseId != null && agentProgressBlocks.containsKey(toolUseId);
    }

    /**
     * Re-anchors progress blocks and group cards after rows were inserted elsewhere —
     * the mirror of the shifts {@link #renderAgentGroup} performs for its own edits.
     */
    void shiftBlocks(int start, int delta) {
        if (delta == 0) return;
        agentProgressBlocks.values().stream()
            .filter(block -> block.start >= start)
            .forEach(block -> block.start += delta);
        new HashSet<>(agentGroupsByToolUseId.values()).stream()
            .filter(group -> group.start >= start)
            .forEach(group -> group.start += delta);
    }

    /** Repaints the group card an already-grouped Agent belongs to (tool_call_start). */
    void repaintGroup(String toolUseId, MessagePanel panel) {
        AgentGroupBlock group = agentGroupsByToolUseId.get(toolUseId);
        if (group != null) renderAgentGroup(group, panel);
    }

    /** Opens the {@code ⎿ Initializing…} progress block for an Agent that just started. */
    void beginProgress(String toolUseId, MessagePanel panel) {
        if (toolUseId == null || agentProgressBlocks.containsKey(toolUseId)) return;
        int progressStart = panel.snapshotLineCount();
        panel.appendLine("  ⎿  Initializing…", LanternaTheme.welcomeDim());
        agentProgressBlocks.put(toolUseId, new AgentProgressBlock(progressStart));
        reflowAgentProgressBlocks(panel);
    }

    /**
     * Streamed tool_result for a grouped member: marks it resolved and repaints the card.
     *
     * @return {@code false} when the tool use is not part of a group
     */
    boolean resolveGroupedMember(String toolUseId, boolean isError, MessagePanel panel) {
        AgentGroupBlock group = agentGroupsByToolUseId.get(toolUseId);
        if (group == null) return false;
        AgentGroupMember member = agentGroupMember(group, toolUseId);
        if (member != null) {
            member.resolved = true;
            member.error = isError;
            member.async = member.launchedAsync;
        }
        renderAgentGroup(group, panel);
        return true;
    }

    private static final int MAX_AGENT_PROGRESS_MESSAGES = 3;
    private static final int ESTIMATED_AGENT_LINES_PER_TOOL = 9;
    private static final int AGENT_TERMINAL_BUFFER_LINES = 7;

    private static final String BACKGROUND_HINT_ROW =
        " ".repeat(BACKGROUND_HINT_PADDING) + BACKGROUND_HINT_TEXT;

    private static final class AgentProgressBlock {
        private int start;
        private int rowCount;
        private boolean backgroundHint;
        private int toolUseCount;
        private Long tokens;
        private final List<String> activity = new ArrayList<>();
        private final List<Message> transcriptMessages = new ArrayList<>();
        private final Map<String, Integer> transcriptMessageIndexes = new HashMap<>();
        private final Set<String> observedToolUseIds = new HashSet<>();
        private String prompt;
        private final Map<String, String> toolNames = new HashMap<>();

        private AgentProgressBlock(int start) {
            this.start = start;
            this.rowCount = 1;
        }
    }

    private static final class AgentGroupBlock {
        private int start;
        private int rowCount;
        private final List<AgentGroupMember> members;

        private AgentGroupBlock(int start, List<AgentGroupMember> members) {
            this.start = start;
            this.members = members;
        }
    }

    private static final class AgentGroupMember {
        private final String toolUseId;
        private final String agentType;
        private final String subtype;
        private final String description;
        private final boolean launchedAsync;
        private final AgentProgressBlock progress = new AgentProgressBlock(-1);
        private boolean resolved;
        private boolean error;
        private boolean async;
        private String lastActivity;

        private AgentGroupMember(String toolUseId, String agentType, String subtype,
                                 String description, boolean launchedAsync) {
            this.toolUseId = toolUseId;
            this.agentType = agentType;
            this.subtype = subtype;
            this.description = description;
            this.launchedAsync = launchedAsync;
            this.async = launchedAsync;
        }
    }

    private final Map<String, AgentProgressBlock> agentProgressBlocks = new HashMap<>();
    private final Map<String, AgentGroupBlock> agentGroupsByToolUseId = new HashMap<>();
    private Map<String, List<ProgressMessage>> transcriptAgentProgress = Map.of();


    private final Set<String> renderedVerboseAgentToolUseIds = new HashSet<>();

    void renderProgress(String toolUseId, ProgressMessage.ProgressData data,
            MessagePanel panel) {
        if (toolUseId == null) return;
        AgentGroupBlock group = agentGroupsByToolUseId.get(toolUseId);
        if (group != null) {
            AgentGroupMember member = agentGroupMember(group, toolUseId);
            if (member != null) {
                String activity = agentActivity(data.message(), member.progress);
                if (activity != null) member.lastActivity = activity;
                renderAgentGroup(group, panel);
            }
            return;
        }
        AgentProgressBlock block = agentProgressBlocks.get(toolUseId);
        boolean created = false;
        if (block == null) {
            int start = panel.snapshotLineCount();
            panel.appendLine("  ⎿  Initializing…", LanternaTheme.welcomeDim());
            block = new AgentProgressBlock(start);
            agentProgressBlocks.put(toolUseId, block);
            created = true;
        }
        if (data.message() != null) recordAgentTranscriptMessage(block, data.message());
        if (StringUtils.isNotBlank(data.prompt())) block.prompt = data.prompt();
        if (host.transcriptMode()) return;
        String activity = agentActivity(data.message(), block);
        if (activity != null) block.activity.add(activity);

        if (created) reflowAgentProgressBlocks(panel);
        else replaceAgentProgressRows(block, agentProgressRows(block, panel), panel);
    }

    /**
     * Renders the background affordance inside its owning tool card.
     *
     * @return {@code false} when this tool use has no card of its own (a plain Bash call, or an
     *     Agent folded into a group), so the caller can fall back to the status line instead of
     *     silently dropping the affordance.
     */
    boolean showBackgroundHint(String toolUseId, MessagePanel panel) {
        if (toolUseId == null) return false;
        if (agentGroupsByToolUseId.containsKey(toolUseId)) return false;
        AgentProgressBlock block = agentProgressBlocks.get(toolUseId);
        if (block == null) return false;
        if (block.backgroundHint) return true;
        block.backgroundHint = true;
        replaceAgentProgressRows(block, agentProgressRows(block, panel), panel);
        return true;
    }

    /** Removes one completed/backgrounded Agent's transient progress projection. */
    void clear(String toolUseId, MessagePanel panel) {
        AgentGroupBlock group = agentGroupsByToolUseId.get(toolUseId);
        if (group != null) {
            AgentGroupMember member = agentGroupMember(group, toolUseId);
            if (member != null) member.resolved = true;
            tools.removeById(toolUseId);
            renderAgentGroup(group, panel);
            return;
        }
        remove(toolUseId, panel);
    }

    void maybeCreateGroup(MessagePanel panel) {
        if (host.verbose() || host.transcriptMode()) return;
        List<PendingTool> pending = tools.snapshot();
        List<PendingTool> candidates = new ArrayList<>();
        String groupMessageId = pending.isEmpty() ? null : pending.getLast().groupMessageId();
        for (int i = pending.size() - 1; i >= 0; i--) {
            PendingTool tool = pending.get(i);
            if (!Strings.CS.equals("Agent", tool.toolName())
                    || tool.toolUseId() == null
                    || tool.inputJson() == null || StringUtils.isBlank(tool.inputJson())
                    || !Objects.equals(groupMessageId, tool.groupMessageId())) {
                break;
            }
            candidates.addFirst(tool);
        }
        if (candidates.size() < 2) return;

        Set<AgentGroupBlock> existingGroups = candidates.stream()
            .map(tool -> agentGroupsByToolUseId.get(tool.toolUseId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (existingGroups.size() > 1) return;
        AgentGroupBlock group = existingGroups.stream().findFirst().orElse(null);
        int start = group != null ? group.start
            : candidates.stream().mapToInt(PendingTool::lineIdx).min().orElse(-1);
        int end = group != null ? group.start + group.rowCount : start;
        List<AgentGroupMember> members = group != null
            ? group.members : new ArrayList<>();
        boolean addedMember = false;
        for (PendingTool tool : candidates) {
            AgentGroupMember existing = group == null
                ? null : agentGroupMember(group, tool.toolUseId());
            if (existing != null) continue;
            AgentProgressBlock progress = agentProgressBlocks.remove(tool.toolUseId());
            if (progress != null) end = Math.max(end, progress.start + progress.rowCount);
            end = Math.max(end, tool.lineIdx() + 1);
            panel.stopBlinkLine(tool.lineIdx(), host.dimAgentHeader(tool.inputJson()));
            var input = ToolResultRenderer.parseJsonObject(tool.inputJson());
            String type = ToolVisualContractRegistry
                .useView("Agent", tool.inputJson(), host.verbose()).displayName();
            String description = input.path("description").asText(type);
            boolean launchedAsync = input.path("run_in_background").asBoolean(false);
            AgentGroupMember member = new AgentGroupMember(
                tool.toolUseId(), type, input.path("subagent_type").asText(""),
                description, launchedAsync);
            if (progress != null) copyAgentProgress(progress, member.progress);
            members.add(member);
            addedMember = true;
        }
        if (!addedMember) return;

        Map<String, Integer> contentOrder = new HashMap<>();
        for (int i = 0; i < pending.size(); i++) {
            contentOrder.put(pending.get(i).toolUseId(), i);
        }
        members.sort(Comparator.comparingInt(member ->
            contentOrder.getOrDefault(member.toolUseId, Integer.MAX_VALUE)));

        if (group == null) group = new AgentGroupBlock(start, members);
        List<List<MessagePanel.Segment>> rows = agentGroupRows(group);
        int oldCount = end - start;
        panel.replaceLines(start, oldCount, rows);
        group.rowCount = rows.size();
        syncAgentGroupBlink(group, rows, panel);
        int delta = rows.size() - oldCount;
        int groupEnd = end;

        tools.reanchor(tool -> {
            boolean grouped = candidates.stream().anyMatch(candidate ->
                Strings.CS.equals(candidate.toolUseId(), tool.toolUseId()));
            if (grouped) return tool.withLineIdx(start);
            return tool.shiftedAfter(groupEnd, delta);
        });
        if (delta != 0) {
            agentProgressBlocks.values().stream()
                .filter(block -> block.start >= groupEnd)
                .forEach(block -> block.start += delta);
            new HashSet<>(agentGroupsByToolUseId.values()).stream()
                .filter(other -> other.start >= groupEnd)
                .forEach(other -> other.start += delta);
        }
        for (AgentGroupMember member : members) {
            agentGroupsByToolUseId.put(member.toolUseId, group);
        }
        reflowAgentProgressBlocks(panel);
    }

    boolean renderGroupedResult(Object payload, ToolResultBlock result,
                                             MessagePanel panel) {
        AgentGroupBlock group = agentGroupsByToolUseId.get(result.toolUseId());
        if (group == null) return false;
        AgentGroupMember member = agentGroupMember(group, result.toolUseId());
        if (member == null) return false;

        tools.removeById(result.toolUseId());
        member.resolved = true;
        member.error = result.isError();
        if (payload != null) {
            var node = JsonUtils.getMapper().valueToTree(payload);
            String status = node.path("status").asText("");
            member.async = member.launchedAsync
                || Strings.CS.equals("async_launched", status)
                || Strings.CS.equals("remote_launched", status)
                || Strings.CS.equals("teammate_spawned", status);
            if (node.has("totalToolUseCount")) {
                member.progress.toolUseCount = node.path("totalToolUseCount").asInt();
            }
            if (node.has("totalTokens")) {
                member.progress.tokens = node.path("totalTokens").asLong();
            }
        }
        renderAgentGroup(group, panel);
        return true;
    }

    private static AgentGroupMember agentGroupMember(AgentGroupBlock group, String toolUseId) {
        return group.members.stream()
            .filter(member -> Strings.CS.equals(toolUseId, member.toolUseId))
            .findFirst().orElse(null);
    }

    /**
 * Grouping is a visual reflow, not a new Agent execution.
     */
    private static void copyAgentProgress(AgentProgressBlock source,
                                          AgentProgressBlock target) {
        target.backgroundHint = source.backgroundHint;
        target.toolUseCount = source.toolUseCount;
        target.tokens = source.tokens;
        target.activity.addAll(source.activity);
        target.transcriptMessages.addAll(source.transcriptMessages);
        target.transcriptMessageIndexes.putAll(source.transcriptMessageIndexes);
        target.observedToolUseIds.addAll(source.observedToolUseIds);
        target.prompt = source.prompt;
        target.toolNames.putAll(source.toolNames);
    }

    private void renderAgentGroup(AgentGroupBlock group, MessagePanel panel) {
        List<List<MessagePanel.Segment>> rows = agentGroupRows(group);
        int oldCount = group.rowCount;
        panel.replaceLines(group.start, oldCount, rows);
        int delta = rows.size() - oldCount;
        group.rowCount = rows.size();
        syncAgentGroupBlink(group, rows, panel);
        if (delta == 0) return;
        tools.shiftLines(group.start + oldCount, delta);
        agentProgressBlocks.values().stream()
            .filter(block -> block.start >= group.start + oldCount)
            .forEach(block -> block.start += delta);
        new HashSet<>(agentGroupsByToolUseId.values()).stream()
            .filter(other -> other != group && other.start >= group.start + oldCount)
            .forEach(other -> other.start += delta);
    }

    private List<List<MessagePanel.Segment>> agentGroupRows(AgentGroupBlock group) {
        boolean allResolved = group.members.stream().allMatch(member -> member.resolved);
        boolean anyError = group.members.stream().anyMatch(member -> member.error);
        boolean allAsync = group.members.stream().allMatch(member -> member.async);
        boolean allSameType = group.members.stream()
            .allMatch(member -> Strings.CS.equals(group.members.getFirst().agentType, member.agentType));
        String commonType = allSameType
            && !Strings.CS.equals("Agent", group.members.getFirst().agentType)
                ? group.members.getFirst().agentType : null;
        String noun = commonType != null ? commonType + " agents" : "agents";
        TextColor dot = !allResolved ? LanternaTheme.welcomeDim()
            : anyError ? LanternaTheme.toolError() : LanternaTheme.toolSuccess();
        List<List<MessagePanel.Segment>> rows = new ArrayList<>();
        List<MessagePanel.Segment> header = new ArrayList<>();
        header.add(new MessagePanel.Segment(BLACK_CIRCLE, dot));
        if (!allResolved) {
            header.add(new MessagePanel.Segment("Running ", TextColor.ANSI.DEFAULT));
            header.add(new MessagePanel.Segment(Integer.toString(group.members.size()),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            header.add(new MessagePanel.Segment(" " + noun + "…", TextColor.ANSI.DEFAULT));
            if (!allAsync) {
                header.add(new MessagePanel.Segment(" " + host.expandHint(),
                    LanternaTheme.welcomeDim()));
            }
        } else if (allAsync) {
            header.add(new MessagePanel.Segment(Integer.toString(group.members.size()),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            header.add(new MessagePanel.Segment(" background agents launched ",
                TextColor.ANSI.DEFAULT));
            header.add(new MessagePanel.Segment("(↓ manage)", LanternaTheme.welcomeDim()));
        } else {
            header.add(new MessagePanel.Segment(Integer.toString(group.members.size()),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            header.add(new MessagePanel.Segment(" " + noun + " finished ",
                TextColor.ANSI.DEFAULT));
            header.add(new MessagePanel.Segment(host.expandHint(), LanternaTheme.welcomeDim()));
        }
        rows.add(List.copyOf(header));

        for (int i = 0; i < group.members.size(); i++) {
            AgentGroupMember member = group.members.get(i);
            boolean last = i == group.members.size() - 1;
            boolean backgrounded = member.async && member.resolved;
            String label = allSameType
                ? member.description
                : member.agentType + " (" + member.description + ")";
            String uses = member.progress.toolUseCount == 1 ? "tool use" : "tool uses";
            String stats = backgrounded ? "" : " · " + member.progress.toolUseCount + " " + uses
                + (member.progress.tokens != null
                    ? " · " + FormatUtils.formatTokens(member.progress.tokens) + " tokens" : "");
            TextColor memberColor = member.resolved
                ? LanternaTheme.inputText() : LanternaTheme.welcomeDim();
            if (allSameType) {
                rows.add(List.of(
                    new MessagePanel.Segment("   " + (last ? "└─ " : "├─ "),
                        LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment(label, memberColor, null, null, Set.of(SGR.BOLD)),
                    new MessagePanel.Segment(stats, memberColor)));
            } else {
                TextColor background = LanternaTheme.agentColor(AgentColorStore.get(member.subtype));
                List<MessagePanel.Segment> memberRow = new ArrayList<>();
                memberRow.add(new MessagePanel.Segment(
                    "   " + (last ? "└─ " : "├─ "), LanternaTheme.welcomeDim()));
                memberRow.add(background == null
                    ? new MessagePanel.Segment(member.agentType, memberColor,
                        null, null, Set.of(SGR.BOLD))
                    : new MessagePanel.Segment(member.agentType,
                        LanternaTheme.inverseText(), background, null, Set.of(SGR.BOLD)));
                memberRow.add(new MessagePanel.Segment(
                    " (" + member.description + ")" + stats, memberColor));
                rows.add(memberRow);
            }
            if (!backgrounded) {
                String status = !member.resolved
                    ? member.lastActivity != null ? member.lastActivity : "Initializing…"
                    : "Done";
                rows.add(List.of(new MessagePanel.Segment(
                    "   " + (last ? "   " : "│  ") + Figures.RESULT_BRANCH + "  " + status,
                    LanternaTheme.welcomeDim())));
            }
        }
        return rows;
    }

    private static void syncAgentGroupBlink(AgentGroupBlock group,
            List<List<MessagePanel.Segment>> rows, MessagePanel panel) {
        if (group.members.stream().anyMatch(member -> !member.resolved)) {
            panel.startBlinkLine(group.start, rows.getFirst());
        } else {
            panel.stopBlinkLine(group.start, rows.getFirst());
        }
    }

    private List<List<MessagePanel.Segment>> agentProgressRows(AgentProgressBlock block,
            MessagePanel panel) {
        if (block.transcriptMessages.isEmpty() && block.activity.isEmpty()) {
            List<List<MessagePanel.Segment>> rows = new ArrayList<>();
            rows.add(List.of(new MessagePanel.Segment(
                "  ⎿  Initializing…", LanternaTheme.welcomeDim())));
            if (block.backgroundHint) {
                rows.add(List.of(new MessagePanel.Segment(
                    BACKGROUND_HINT_ROW, LanternaTheme.welcomeDim())));
            }
            return rows;
        }
        // A zero-sized panel is common in unit/replay construction and means

        int terminalRows = panel.getSize().getRows();
        if (terminalRows > 0 && terminalRows
                < agentProgressBlocks.size() * ESTIMATED_AGENT_LINES_PER_TOOL
                    + AGENT_TERMINAL_BUFFER_LINES) {
            String uses = block.toolUseCount == 1 ? "tool use" : "tool uses";
            String tokenText = block.tokens != null && block.tokens > 0
                ? " · " + FormatUtils.formatTokens(block.tokens) + " tokens" : "";
            return List.of(List.of(new MessagePanel.Segment(
                "  ⎿  In progress… · " + block.toolUseCount + " " + uses
                    + tokenText + " · " + host.expandHint(),
                LanternaTheme.welcomeDim())));
        }
        List<String> visible = block.activity.isEmpty()
            ? List.of("  ⎿  Initializing…")
            : block.activity.subList(Math.max(0,
                block.activity.size() - MAX_AGENT_PROGRESS_MESSAGES), block.activity.size());
        List<List<MessagePanel.Segment>> rows = new ArrayList<>();
        int hidden = Math.max(0, block.activity.size() - visible.size());
        if (hidden > 0) {
            rows.add(List.of(new MessagePanel.Segment(
                "  ⎿  +" + hidden + " more tool uses " + host.expandHint(),
                LanternaTheme.welcomeDim())));
        }
        for (String line : visible) {
            rows.add(List.of(new MessagePanel.Segment(
                block.activity.isEmpty() ? line : "  ⎿  " + line,
                LanternaTheme.welcomeDim())));
        }
        if (block.backgroundHint) {
            rows.add(List.of(new MessagePanel.Segment(
                BACKGROUND_HINT_ROW, LanternaTheme.welcomeDim())));
        }
        return rows;
    }

    private String agentActivity(Message message, AgentProgressBlock block) {
        if (message instanceof AssistantMessage assistant
                && assistant.message() != null && assistant.message().content() != null) {
            if (assistant.message().usage() != null) {
                var usage = assistant.message().usage();
                block.tokens = usage.inputTokens() + usage.outputTokens()
                    + usage.cacheCreationInputTokens() + usage.cacheReadInputTokens();
            }
            for (ContentBlock content : assistant.message().content()) {
                if (content instanceof ToolUseBlock toolUse) {
                    if (!block.observedToolUseIds.add(toolUse.id())) return null;
                    block.toolUseCount++;
                    block.toolNames.put(toolUse.id(), toolUse.name());
                    String summary = ToolHeaderRenderer.summarizeInputJson(toolUse.name(), toolUse.input().toString());
                    return toolUse.name() + (StringUtils.isBlank(summary) ? "" : "(" + summary + ")");
                }
            }
        }
        if (message instanceof UserMessage user && user.message() != null
                && user.message().blocks() != null) {
            for (ContentBlock content : user.message().blocks()) {
                if (content instanceof ToolResultBlock(var toolUseId, _, _, _, _)) {
                    return block.toolNames.getOrDefault(toolUseId, "Tool") + " completed";
                }
            }
        }
        return null;
    }

    private static void recordAgentTranscriptMessage(AgentProgressBlock block,
            Message message) {
        String uuid = message.uuid();
        if (StringUtils.isBlank(uuid)) {
            block.transcriptMessages.add(message);
            return;
        }
        Integer existing = block.transcriptMessageIndexes.get(uuid);
        if (existing == null) {
            block.transcriptMessageIndexes.put(uuid, block.transcriptMessages.size());
            block.transcriptMessages.add(message);
        } else {
            block.transcriptMessages.set(existing, message);
        }
    }

    private void replaceAgentProgressRows(AgentProgressBlock block,
            List<List<MessagePanel.Segment>> rows, MessagePanel panel) {
        int oldCount = block.rowCount;
        panel.replaceLines(block.start, oldCount, rows);
        int delta = rows.size() - oldCount;
        block.rowCount = rows.size();
        if (delta != 0) {
            tools.shiftLines(block.start + oldCount, delta);
            agentProgressBlocks.values().stream()
                .filter(other -> other != block && other.start > block.start)
                .forEach(other -> other.start += delta);
            new HashSet<>(agentGroupsByToolUseId.values()).stream()
                .filter(group -> group.start > block.start)
                .forEach(group -> group.start += delta);
        }
    }

    private void reflowAgentProgressBlocks(MessagePanel panel) {
        if (panel == null || agentProgressBlocks.isEmpty()) return;
        List<AgentProgressBlock> blocks = agentProgressBlocks.values().stream()
            .sorted(Comparator.comparingInt(block -> block.start))
            .toList();
        for (AgentProgressBlock block : blocks) {
            replaceAgentProgressRows(block, agentProgressRows(block, panel), panel);
        }
    }

    void remove(String toolUseId, MessagePanel panel) {
        AgentProgressBlock block = agentProgressBlocks.remove(toolUseId);
        if (block == null) return;
        int removed = block.rowCount;
        panel.replaceLines(block.start, removed, List.of());
        tools.shiftLines(block.start + removed, -removed);
        agentProgressBlocks.values().stream()
            .filter(other -> other.start > block.start)
            .forEach(other -> other.start -= removed);
        new HashSet<>(agentGroupsByToolUseId.values()).stream()
            .filter(group -> group.start > block.start)
            .forEach(group -> group.start -= removed);
        reflowAgentProgressBlocks(panel);
    }

    boolean renderStructuredResult(Object payload, ToolResultBlock result,
                                                MessagePanel panel) {
        if (payload == null || result.isError()) return false;
        var node = JsonUtils.getMapper().valueToTree(payload);
        String status = node.path("status").asText("");
        boolean completed = Strings.CS.equals("completed", status)
            && node.has("totalDurationMs") && node.has("totalTokens")
            && node.has("totalToolUseCount") && node.has("prompt");
        boolean backgrounded = Strings.CS.equals("async_launched", status)
            && node.has("agentId") && node.has("prompt") && node.has("outputFile");
        if (!completed && !backgrounded) return false;

        Placement placement = host.beginToolResult(result, panel);
        int replaceLine = placement.replaceLine();
        boolean renderedTranscript = renderVerboseTranscript(
            result.toolUseId(), node, panel);
        if (!host.transcriptMode()) remove(result.toolUseId(), panel);

        if (backgrounded) {
            if (host.transcriptMode() && !renderedTranscript) {
                String prompt = node.path("prompt").asText("");
                if (!StringUtils.isBlank(prompt)) {
                    appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                        new MessagePanel.Segment(INDENT_PREFIX + "Prompt:",
                            LanternaTheme.toolSuccess())));
                    panel.appendMarkdown(prompt, MARKDOWN_RENDERER, false);
                    replaceLine = -1;
                }
            }
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(
                    "Backgrounded agent (↓ manage · " + host.expandShortcut() + " to expand)",
                    LanternaTheme.welcomeDim())));
            return true;
        }

        if (host.transcriptMode()) {
            if (!renderedTranscript) {
                String prompt = node.path("prompt").asText("");
                if (!StringUtils.isBlank(prompt)) {
                    appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                        new MessagePanel.Segment(INDENT_PREFIX + "Prompt:",
                            LanternaTheme.toolSuccess())));
                    panel.appendMarkdown(prompt, MARKDOWN_RENDERER, false);
                    replaceLine = -1;
                }
            }
            String response = agentContentText(node.path("content"));
            if (!StringUtils.isBlank(response)) {
                panel.appendMixed(List.of(new MessagePanel.Segment(
                    INDENT_PREFIX + "Response:", LanternaTheme.toolSuccess())));
                panel.appendMarkdown(response, MARKDOWN_RENDERER, false);
            }
        }

        int toolUses = node.path("totalToolUseCount").asInt();
        long tokens = node.path("totalTokens").asLong();
        long durationMs = node.path("totalDurationMs").asLong();
        String summary = "Done (" + toolUses + " "
            + (toolUses == 1 ? "tool use" : "tool uses")
            + " · " + FormatUtils.formatTokens(tokens) + " tokens"
            + " · " + FormatUtils.formatDuration(durationMs, true, false) + ")";
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(summary, LanternaTheme.welcomeDim())));
        if (!host.transcriptMode()) {
            panel.appendLine("     " + host.expandHint(), LanternaTheme.welcomeDim());
        }
        return true;
    }

    private static String agentContentText(JsonNode content) {
        if (content == null || !content.isArray()) return "";
        StringBuilder text = new StringBuilder();
        for (var block : content) {
            if (!Strings.CS.equals("text", block.path("type").asText())) continue;
            if (!text.isEmpty()) text.append('\n');
            text.append(block.path("text").asText(""));
        }
        return text.toString();
    }

    boolean renderVerboseTranscript(String toolUseId, JsonNode result,
            MessagePanel panel) {
        if (!host.transcriptMode() || toolUseId == null
                || renderedVerboseAgentToolUseIds.contains(toolUseId)) {
            return false;
        }
        List<Message> childMessages = new ArrayList<>();
        for (ProgressMessage progress : transcriptAgentProgress.getOrDefault(
                toolUseId, List.of())) {
            if (progress.data() != null && progress.data().message() != null) {
                childMessages.add(progress.data().message());
            }
        }
        AgentProgressBlock live = agentProgressBlocks.get(toolUseId);
        if (childMessages.isEmpty() && live != null) {
            childMessages.addAll(live.transcriptMessages);
        }
        String prompt = agentPrompt(toolUseId, result, live);
        if (childMessages.isEmpty() && StringUtils.isBlank(prompt)) return false;

        renderedVerboseAgentToolUseIds.add(toolUseId);
        remove(toolUseId, panel);
        if (StringUtils.isNotBlank(prompt)) {
            panel.appendMixed(List.of(new MessagePanel.Segment(
                INDENT_PREFIX + "Prompt:", LanternaTheme.toolSuccess())));
            panel.appendMarkdown(prompt, MARKDOWN_RENDERER, false);
        }

        LanternaMessageDispatcher childDispatcher = new LanternaMessageDispatcher();
        childDispatcher.setVerbose(true);
        MessageCollapser childCollapser = new MessageCollapser(childDispatcher, false);
        childCollapser.setShowAll(true);
        for (Message child : childMessages) {
            SDKMessage sdk = childSdkMessage(child);
            if (sdk != null) childCollapser.dispatch(sdk, panel);
        }
        childCollapser.resetTurn();
        return true;
    }

    private String agentPrompt(String toolUseId, JsonNode result, AgentProgressBlock live) {
        for (ProgressMessage progress : transcriptAgentProgress.getOrDefault(
                toolUseId, List.of())) {
            if (progress.data() != null && StringUtils.isNotBlank(progress.data().prompt())) {
                return progress.data().prompt();
            }
        }
        if (live != null && StringUtils.isNotBlank(live.prompt)) return live.prompt;
        if (result != null && StringUtils.isNotBlank(result.path("prompt").asText())) {
            return result.path("prompt").asText();
        }
        ToolInvocation invocation = tools.invocation(toolUseId);
        if (invocation == null || StringUtils.isBlank(invocation.inputJson())) return null;
        JsonNode input = JsonUtils.safeParseJson(invocation.inputJson());
        return input != null && StringUtils.isNotBlank(input.path("prompt").asText())
            ? input.path("prompt").asText() : null;
    }

    private static SDKMessage childSdkMessage(Message message) {
        return switch (message) {
            case UserMessage user when containsToolResult(user) -> new SDKMessage.User(user);
            case AssistantMessage assistant -> new SDKMessage.Assistant(assistant, Usage.EMPTY);
            case SystemMessage system -> new SDKMessage.System(system);
            default -> null;
        };
    }

    private static boolean containsToolResult(UserMessage user) {
        return user.message() != null && user.message().blocks() != null
            && user.message().blocks().stream().anyMatch(ToolResultBlock.class::isInstance);
    }
}
