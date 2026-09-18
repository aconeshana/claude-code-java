package com.claudecode.ui.lanterna.transcript;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.render.RenderingContext;
import com.claudecode.ui.render.ToolUseIndicatorRenderer;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.tools.ToolUseTag;
import com.fasterxml.jackson.databind.JsonNode;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import java.util.*;
import java.util.function.*;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes each {@link SDKMessage} to the renderer that owns it and keeps the per-turn
 * state those renderers share: the in-flight tool ledger, the streaming text window,
 * the tool-card lifecycle ({@code tool_streaming_start → permission → tool_result_*}),
 * retraction anchors for {@link SDKMessage.Tombstone} rollback and the logical-message
 * registry {@link MessagePanel} uses for message actions.
 *
 * <p>Rendering itself lives in the package-private collaborators, each behind a small
 * {@code Host} interface so it sees only the dispatcher state it needs:
 * {@link PendingToolLedger}, {@link ToolHeaderRenderer}, {@link ToolResultRenderer}
 * (with {@link FileChangeResultRenderer}), {@link ToolProgressRenderer},
 * {@link UserMessageRenderer}, {@link SystemMessageRenderer}, {@link ThinkingRenderer},
 * {@link AgentProgressPresenter} and {@link StreamingTextRenderer}.
 *
 * <ul>
 *   <li>{@code src/components/Message.tsx} — the per-message dispatch: assistant text /
 *       tool_use / thinking / redacted-thinking arms, user vs. tool-result routing, and the
 *       {@code if (!verbose && !transcript) return null} thinking gate.</li>
 *   <li>{@code src/components/Messages.tsx} — turn-scoped layout rules: the blank row
 *       before the first tool after text ({@code marginTop=1}) and after a tool result.</li>
 *   <li>{@code src/screens/REPL.tsx} — tool lifecycle projection from stream events
 *       (queued dim header, in-progress blink, permission {@code Waiting…} row, completion
 *       recolour) and the streaming-text commit that repaints authoritative text over the
 *       live window.</li>
 *   <li>{@code src/utils/messages.ts} — tombstone handling: a withdrawn message rolls the
 *       transcript back to its first rendered row so the fallback repaints clean ground.</li>
 *   <li>{@code src/utils/messages.ts} — {@code reorderMessagesInUI}: a {@code tool_result} is
 *       rendered under the {@code tool_use} carrying its id, never where it arrived in the
 *       stream. Authority: the 2.1.197 bundle's {@code y7l}. Here that ordering is produced by
 *       {@link #renderToolResult} relocating the rows a renderer appended (see
 *       {@link #ownCardResultTarget}), because the panel is append-only.</li>
 * </ul>
 */
public class LanternaMessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LanternaMessageDispatcher.class);
    private final ToolPresentationSnapshotStore presentationSnapshots;

    public LanternaMessageDispatcher() {
        this(new ToolPresentationSnapshotStore());
    }

    public LanternaMessageDispatcher(ToolPresentationSnapshotStore presentationSnapshots) {
        this.presentationSnapshots = presentationSnapshots == null
            ? new ToolPresentationSnapshotStore() : presentationSnapshots;
        this.results = new ToolResultRenderer(new ResultHost(), tools, this.presentationSnapshots);
    }

    /** Row gutter shared with tests; owned by {@link ToolResultLines}. */
    static final String BLACK_CIRCLE = ToolResultLines.BLACK_CIRCLE;

    /** Shared markdown renderer — thread-safe (Caffeine cache internally). */
    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();

    private boolean verbose = false;

    public void setVerbose(boolean v) { this.verbose = v; }

    private boolean transcriptMode = false;

    public void setTranscriptMode(boolean t) { this.transcriptMode = t; }

    /**
     * Resolves whether a tool acts as a transparent wrapper whose header is hidden from the UI.
     */
    private Function<String, Boolean> transparentWrapperLookup = _ -> false;

    public void setTransparentWrapperLookup(Function<String, Boolean> lookup) {
        if (lookup != null) this.transparentWrapperLookup = lookup;
    }

    /** True once the first tool of this turn has been rendered. */
    private boolean toolEmittedThisTurn = false;
    /** The next assistant text needs AssistantTextMessage's marginTop=1. */
    private boolean toolResultRenderedThisTurn = false;
    /** True once a non-empty TextBlock has been committed to the panel this
     *  turn, whether via streaming or a one-shot renderAssistant commit.
     *  the streaming window alone can't drive the blank-line-before-tool
     *  rule because it gets reset to false right after a TextBlock commits
     *  (see renderAssistant) — losing the signal that content just preceded
     *  a first tool call rendered from a one-shot (non-streamed) message. */
    private boolean textRenderedThisTurn = false;

    /** Background affordance wording, re-exported for the REPL status line. */
    public static final String BACKGROUND_HINT_TEXT = AgentProgressPresenter.BACKGROUND_HINT_TEXT;
    public static final int BACKGROUND_HINT_PADDING = AgentProgressPresenter.BACKGROUND_HINT_PADDING;

    /** In-flight tool calls and their per-id side tables for the current turn. */
    private final PendingToolLedger tools = new PendingToolLedger();
    /** Per-tool and generic result bodies; the dispatcher only completes the card. */
    private final ToolResultRenderer results;
    /** Tool header rows (dot, name, tag, argument preview). */
    private final ToolHeaderRenderer headers = new ToolHeaderRenderer(new HeaderHost(), tools);
    /** User echo, bash/local command output, channel and compact-summary rows. */
    private final UserMessageRenderer users = new UserMessageRenderer(new UserHost());
    /** System notices, turn summary, errors, retries, attachments, API status texts. */
    private final SystemMessageRenderer system = new SystemMessageRenderer(new SystemHost());
    /** Completed thinking blocks and their transcript visibility. */
    private final ThinkingRenderer thinking = new ThinkingRenderer(new ThinkingHost());
    /** Sub-agent progress rows, parallel-agent group cards and Agent result bodies. */
    private final AgentProgressPresenter agents = new AgentProgressPresenter(new AgentHost(), tools);
    /** The live-streaming text window (snapshot rollback, stable/unstable Markdown tail). */
    private final StreamingTextRenderer stream = new StreamingTextRenderer();

    /** Register a consumer notified when the visible-streaming-text window opens/closes. */
    public void onStreamTextVisibility(Consumer<Boolean> listener) {
        stream.onVisibility(listener);
    }

    List<Integer> streamingBoundaryParseInputLengthsForTest() {
        return stream.boundaryParseInputLengthsForTest();
    }

    /** Dispatcher services exposed to the agent presenter. */
    private final class AgentHost implements AgentProgressPresenter.Host {
        @Override public boolean verbose() { return verbose; }
        @Override public boolean transcriptMode() { return transcriptMode; }
        @Override public String expandHint() { return LanternaMessageDispatcher.this.expandHint(); }
        @Override public String expandShortcut() { return LanternaMessageDispatcher.this.expandShortcut(); }
        @Override public ToolResultRenderer.Placement beginToolResult(ToolResultBlock result, MessagePanel panel) {
            return LanternaMessageDispatcher.this.beginToolResult(result, panel);
        }
        @Override public List<MessagePanel.Segment> dimAgentHeader(String inputJson) {
            return headers.dimToolSegs("Agent", "", "", inputJson);
        }
    }

    /**
     * Renders the background affordance inside its owning tool card.
     *
     * @return {@code false} when this tool use has no card of its own (a plain Bash call, or an
     *     Agent folded into a group), so the caller can fall back to the status line instead of
     *     silently dropping the affordance.
     */
    public boolean showAgentBackgroundHint(String toolUseId, MessagePanel panel) {
        return agents.showBackgroundHint(toolUseId, panel);
    }

    /** Removes one completed/backgrounded Agent's transient progress projection. */
    public void clearAgentProgress(String toolUseId, MessagePanel panel) {
        agents.clear(toolUseId, panel);
    }

    /** Visibility flags exposed to the thinking renderer. */
    private final class ThinkingHost implements ThinkingRenderer.Host {
        @Override public boolean verbose() { return verbose; }
        @Override public boolean transcriptMode() { return transcriptMode; }
    }

    /** Transcript replay: hide every completed thinking block except {@code blockId}. */
    void showOnlyTranscriptThinkingBlock(String blockId) {
        thinking.showOnlyBlock(blockId);
    }

    /** Test seam for the completed-thinking layout. */
    void renderThinking(ThinkingBlock block, MessagePanel panel, RenderingContext ctx) {
        thinking.render(block, panel, ctx);
    }

    /** Dispatcher services exposed to the system-message renderer. */
    private final class SystemHost implements SystemMessageRenderer.Host {
        @Override public boolean verbose() { return verbose; }
        @Override public boolean transcriptMode() { return transcriptMode; }
        @Override public String expandHint() { return LanternaMessageDispatcher.this.expandHint(); }
        @Override public void renderLocalCommand(String content, MessagePanel panel) {
            users.renderTextBlock(content, panel);
        }
    }

    public void setTurnSummaryContext(Supplier<PendingBackgroundWork> pendingSupplier,
                                      Supplier<String> summarySupplier) {
        system.setTurnSummaryContext(pendingSupplier, summarySupplier);
    }

    /** Test seam for terminal hyperlink capability; production keeps the environment probe. */
    void setHyperlinkSupport(BooleanSupplier support) {
        system.setHyperlinkSupport(support);
    }

    /** Turn-summary line that also reports what the turn is still waiting on. */
    public void renderTurnSummary(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary) {
        system.renderTurnSummary(panel, elapsedMs, pendingAgentCount, pendingWorkflowCount,
            backgroundTaskSummary);
    }

    public void renderTurnSummary(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary,
                                  Long budgetTokens, Long budgetLimit,
                                  Integer budgetNudges, Integer briefHiddenCount) {
        system.renderTurnSummary(panel, elapsedMs, pendingAgentCount, pendingWorkflowCount,
            backgroundTaskSummary, budgetTokens, budgetLimit, budgetNudges, briefHiddenCount);
    }

    void renderTurnSummaryWithVisibility(MessagePanel panel, long elapsedMs,
                                         Integer pendingAgentCount, Integer pendingWorkflowCount,
                                         String backgroundTaskSummary,
                                         Long budgetTokens, Long budgetLimit,
                                         Integer budgetNudges, Integer briefHiddenCount,
                                         boolean showDuration) {
        system.renderTurnSummaryWithVisibility(panel, elapsedMs, pendingAgentCount,
            pendingWorkflowCount, backgroundTaskSummary, budgetTokens, budgetLimit,
            budgetNudges, briefHiddenCount, showDuration);
    }

    /** Dispatcher services exposed to the user-message renderer. */
    private final class UserHost implements UserMessageRenderer.Host {
        @Override public boolean transcriptMode() { return transcriptMode; }
        @Override public String expandShortcut() { return LanternaMessageDispatcher.this.expandShortcut(); }
        @Override public void renderToolResult(ToolResultBlock result, Object toolUseResult, MessagePanel panel) {
            LanternaMessageDispatcher.this.renderToolResult(result, toolUseResult, panel);
        }
    }

    /**
     * Called by {@code LanternaReplScreen.executeQuery} after painting the
     * {@code ⎿ [Image #N]} lines synchronously, so they show up together with
     * the {@code ❯ text} echo instead of lagging behind the ImageResizer step.
     */
    public void markImagesRenderedInline(Collection<Integer> pasteIds) {
        users.markImagesRenderedInline(pasteIds);
    }

    /**
     * Drops the next user-authored {@link SDKMessage.User} echo (text + images, no tool
     * result) because {@code executeQuery} already painted it synchronously.
     */
    public void suppressNextUserEcho() {
        users.suppressNextEcho();
    }

    /** The {@code ⎿ [Image #N]} chip below a prompt echo, hyperlinked to the cached file. */
    public void renderUserImageMessage(Integer imageId, MessagePanel panel) {
        users.renderImage(imageId, panel);
    }

    /**
     * Tool-result routing shared by user messages and transcript replay: structured and
     * registered bodies first, then the generic folded output.
     *
     * <p>Every renderer below appends at the panel tail, which is only correct for the
     * card that happens to be last. Concurrency-safe tools run as one parallel batch, so
     * an earlier card's result routinely arrives after later cards were already painted;
     * the rendered rows are therefore relocated under the card that owns the
     * {@code tool_use_id}.
     */
    private void renderToolResult(ToolResultBlock result, Object toolUseResult, MessagePanel panel) {
        if (result.toolUseId() != null && toolUseResult != null) {
            tools.recordResult(result.toolUseId(), toolUseResult);
        }
        int target = ownCardResultTarget(result.toolUseId(), panel);
        int before = panel.snapshotLineCount();
        if (!results.renderRegistered(toolUseResult, result, panel)
                && !renderGroupedAgentResult(toolUseResult, result, panel)
                && !agents.renderStructuredResult(toolUseResult, result, panel)
                && !results.renderStructuredNotebookEdit(toolUseResult, result, panel)
                && !results.renderStructuredFileChange(toolUseResult, result, panel)) {
            results.renderGeneric(result, panel);
        }
        fileResultRowsUnderOwnCard(target, before, panel);
    }

    /**
     * Source line the result rows of {@code toolUseId} must end up at, or {@code -1} when
     * appending at the tail is already correct (last card, unknown card, transparent
     * wrapper, or an Agent card whose progress rows this move must not straddle).
     */
    private int ownCardResultTarget(String toolUseId, MessagePanel panel) {
        if (toolUseId == null) return -1;
        PendingToolLedger.PendingTool owner = tools.findByToolUseId(toolUseId);
        if (owner == null || owner.transparent() || owner.lineIdx() < 0) return -1;
        if (agents.isGrouped(toolUseId) || agents.hasProgressBlock(toolUseId)) return -1;
        int next = tools.nextCardStart(owner.lineIdx());
        if (next <= owner.lineIdx() || next > panel.snapshotLineCount()) return -1;
        // The blank separator row directly above a card belongs to that card, not to the
        // result body being filed above it.
        while (next > owner.lineIdx() + 1 && panel.isBlankSourceLine(next - 1)) next--;
        return next;
    }

    /**
     * Relocates the rows just appended by a result renderer to {@code target} and
     * re-anchors every index the move invalidated.
     */
    private void fileResultRowsUnderOwnCard(int target, int before, MessagePanel panel) {
        if (target < 0 || target > before) return;
        int appended = panel.snapshotLineCount() - before;
        if (appended <= 0) return;
        int moved = panel.moveLines(before, appended, target);
        if (moved <= 0) return;
        shiftAnchorsFrom(target, moved);
        rowShiftListener.onRowsShifted(target, moved);
    }

    /**
     * Records a tool invocation without painting a card. {@link MessageCollapser} folds read,
     * search, shell and MCP calls into a single group row and strips their {@code tool_use} blocks
     * from the assistant envelope, so this renderer never sees them — but the ledger still needs the
     * name and input to attribute a result that escapes the group, e.g. because intervening text
     * sealed it before the result arrived. Such a result gets a card painted on arrival
     * ({@link #paintDeferredFoldedCard}) rather than an unattributed body at the bottom.
     */
    void recordFoldedInvocation(String toolUseId, String toolName, String inputJson) {
        if (StringUtils.isBlank(toolUseId)) return;
        PendingToolLedger.ToolInvocation invocation =
            new PendingToolLedger.ToolInvocation(toolName, inputJson);
        tools.recordInvocation(toolUseId, invocation);
        foldedInvocations.put(toolUseId, invocation);
    }

    /**
     * Paints the card of a folded call whose result the group never absorbed, so the body has a
     * header to be filed under. Consumes the record, so a second result for the same id — a
     * duplicate, or one the group did absorb — cannot paint a second card.
     */
    private PendingToolLedger.PendingTool paintDeferredFoldedCard(String toolUseId,
                                                                 MessagePanel panel) {
        PendingToolLedger.ToolInvocation invocation = foldedInvocations.remove(toolUseId);
        if (invocation == null || panel == null) return null;
        String toolName = invocation.toolName();
        String argsJson = invocation.inputJson() == null ? "" : invocation.inputJson();
        if (ToolVisualContractRegistry.hidesUse(toolName)
                || ToolVisualContractRegistry.useView(toolName, argsJson, verbose).hidden()) {
            return null;
        }
        if (stream.isOpen() || toolEmittedThisTurn || textRenderedThisTurn || !tools.isEmpty()) {
            panel.appendLine("", TextColor.ANSI.DEFAULT);
        }
        int lineIdx = panel.snapshotLineCount();
        panel.appendMixed(headers.dimToolSegs(toolName,
            headers.toolArgsPart(toolName, argsJson),
            headers.toolTagPart(toolName, argsJson, toolUseId), argsJson));
        toolEmittedThisTurn = true;
        return new PendingToolLedger.PendingTool(lineIdx, false, argsJson, toolName, -1,
            tools.nextLogicalId(), toolUseId, null);
    }

    /**
     * Re-anchors every row index this renderer owns after rows were inserted or removed at
     * {@code start}. The collapsed Read/Search group is repainted in place by
     * {@link MessageCollapser}, and its finalized form is one row shorter than its in-flight
     * form, so a card painted while the group was still growing sits one row above its
     * recorded anchor once the group settles. Without this, the header repaint on completion
     * and the result placement of that card both target a stale row.
     */
    void shiftAnchorsFrom(int start, int delta) {
        if (delta == 0 || start < 0) return;
        tools.shiftLines(start, delta);
        agents.shiftBlocks(start, delta);
        retractionAnchors.replaceAll((_, anchor) -> anchor >= start ? anchor + delta : anchor);
    }

    private boolean renderGroupedAgentResult(Object payload, ToolResultBlock result,
                                             MessagePanel panel) {
        if (!agents.renderGroupedResult(payload, result, panel)) return false;
        toolResultRenderedThisTurn = true;
        return true;
    }

    /** Dispatcher state exposed to the header renderer. */
    private final class HeaderHost implements ToolHeaderRenderer.Host {
        @Override public boolean verbose() { return verbose; }
        @Override public List<ProgressMessage> progressFor(String toolUseId) {
            return Objects.requireNonNullElseGet(tools.progress(toolUseId),
                () -> agents.transcriptProgress(toolUseId));
        }
    }

    /** Dispatcher services exposed to the result renderers. */
    private final class ResultHost implements ToolResultRenderer.Host {
        @Override public boolean verbose() { return verbose; }
        @Override public boolean transcriptMode() { return transcriptMode; }
        @Override public String expandHint() { return LanternaMessageDispatcher.this.expandHint(); }
        @Override public String expandShortcut() { return LanternaMessageDispatcher.this.expandShortcut(); }
        @Override public ToolResultRenderer.Placement beginToolResult(ToolResultBlock result, MessagePanel panel) {
            return LanternaMessageDispatcher.this.beginToolResult(result, panel);
        }
        @Override public boolean renderVerboseAgentTranscript(String toolUseId, JsonNode result, MessagePanel panel) {
            return agents.renderVerboseTranscript(toolUseId, result, panel);
        }
        @Override public String persistedPlan() { return LanternaMessageDispatcher.this.persistedPlan(); }
    }

    private Supplier<String> persistedPlanSupplier = () -> null;

    private Set<String> transcriptResolvedToolUseIds = Set.of();

    void setTranscriptRenderModel(TranscriptRenderModel model) {
        transcriptResolvedToolUseIds = model == null ? Set.of() : model.resolvedToolUseIds();
        agents.setTranscriptProgress(model == null ? null : model.agentProgressByToolUseId());
    }

    /**
     * First rendered line of each conversation message, keyed by its raw UUID —
     * the rewind point a {@link SDKMessage.Tombstone} naming that UUID rolls back
     * to. A tool-only assistant message paints its rows from the tool stream and
     * registers no logical message, so a UUID anchor is the only handle a
     * retraction has on it.
     *
     * <p>Bounded and cleared per turn: a replayed transcript dispatches thousands
     * of messages that will never be withdrawn.
     */
    private final Map<String, Integer> retractionAnchors =
        new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                return size() > MAX_RETRACTION_ANCHORS;
            }
        };

    /** Deepest tool-use chain a single fallback can withdraw; older anchors are dropped. */
    private static final int MAX_RETRACTION_ANCHORS = 256;

    /**
     * Folded calls whose card was deliberately not painted, keyed by {@code tool_use_id}. Emptied as
     * results arrive: a group that absorbs its own results never consults it.
     */
    private final Map<String, PendingToolLedger.ToolInvocation> foldedInvocations =
        new LinkedHashMap<>();

    /**
     * Notified when this dispatcher inserts rows in the middle of the panel, so an upstream
     * stage holding its own source-line anchors (the collapsed Read/Search group card) can
     * follow along.
     */
    @FunctionalInterface
    interface RowShiftListener {
        void onRowsShifted(int start, int delta);
    }

    private RowShiftListener rowShiftListener = (_, _) -> { };

    void setRowShiftListener(RowShiftListener listener) {
        if (listener != null) this.rowShiftListener = listener;
    }

    private UserKeybindingsStore keybindingsStore;

    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
    }

    private String expandHint() {
        return KeybindingHints.expand(keybindingsStore);
    }

    private String expandShortcut() {
        return KeybindingHints.shortcut(keybindingsStore,
            "app:toggleTranscript", "Global", "ctrl+o");
    }

    /**
     * Optional tag resolver — given (toolName, inputJson) returns an extra tag to append to the tool
     * header line, e.g.
     */
    public record ToolTagRequest(
        String toolName,
        String inputJson,
        String toolUseId,
        Object toolUseResult,
        List<ProgressMessage> progressMessages
    ) {}

    @FunctionalInterface
    public interface ToolTagLookup {
        Optional<ToolUseTag> resolve(ToolTagRequest request);
    }

    public void setToolTagLookup(ToolTagLookup lookup) {
        headers.setTagLookup(lookup);
    }

    /**
     * Optional per-tool inline header resolver — given (toolName, inputJson) returns an extra line
     * shown directly below the tool header with {@code " ⎿ "} indent.
     */
    private BiFunction<String, String, Optional<String>> inlineHeaderLookup = (_, _) -> Optional.empty();

    public void setInlineHeaderLookup(BiFunction<String, String, Optional<String>> lookup) {
        if (lookup != null) this.inlineHeaderLookup = lookup;
    }

    /**
     * Remembers the plan associated with an ExitPlanMode permission prompt.
     */
    public void rememberPlanForRejection(String toolUseId, String plan) {
        presentationSnapshots.publishPlan(presentationSnapshots.ticket(toolUseId), plan);
    }

    /**
     * Supplies the current session's persisted plan for transcript replay.
     */
    public void setPersistedPlanSupplier(Supplier<String> supplier) {
        persistedPlanSupplier = supplier == null ? () -> null : supplier;
    }

    /** Reset between user turns. Called by LanternaReplScreen on submit. */
    public void resetTurn() {
        toolEmittedThisTurn = false;
        toolResultRenderedThisTurn = false;
        textRenderedThisTurn = false;
        stream.close();
        tools.clear();
        presentationSnapshots.resetTurn();
        agents.resetTurn();
        retractionAnchors.clear();
        foldedInvocations.clear();
        users.resetTurn();
    }

    /** Returns true when no tool calls are in-flight — safe to trigger replay from MessageHistory. */
    public boolean isIdle() {
        return tools.isEmpty() && !stream.isOpen();
    }

    /**
     * Render retained tool start/result events into detached verbose rows. This reuses the
     * normal tool renderer so an expanded Read/Search group cannot drift from standalone tools.
     */
    List<MessagePanel.StyledLine> renderExpandedToolEvents(List<SDKMessage.StreamEvent> events) {
        LanternaMessageDispatcher renderer = new LanternaMessageDispatcher();
        renderer.setVerbose(true);
        renderer.setTransparentWrapperLookup(transparentWrapperLookup);
        renderer.setToolTagLookup(headers.tagLookup());
        renderer.setInlineHeaderLookup(inlineHeaderLookup);
        MessagePanel panel = new MessagePanel();
        for (SDKMessage.StreamEvent event : events) {
            if (Strings.CS.equals("tool_call_start", event.eventType())
                    && event.data() instanceof String data) {
                String toolName = Strings.CS.contains(data, "|")
                    ? data.substring(0, data.indexOf('|')) : data;
                renderer.dispatch(new SDKMessage.StreamEvent("tool_streaming_start", toolName), panel);
                renderer.dispatch(new SDKMessage.StreamEvent("tool_streaming_done", data), panel);
            }
            renderer.dispatch(event, panel);
        }
        return panel.snapshotStyledLines();
    }

    public void dispatch(SDKMessage message, MessagePanel panel) {
        dispatch(message, panel, RenderingContext.NORMAL);
    }

    /**
     * Dispatch with explicit {@link RenderingContext} — used when rendering
     * queued-preview messages that need dim/subtle styling.
     *
     * <p>Passes {@code ctx} through to {@link #renderThinking(ThinkingBlock, MessagePanel, RenderingContext)}
     * and (for the queued path) to {@link ToolUseIndicatorRenderer#pick(RenderingContext)}.
     *
     * @param message the message to render
     * @param panel   the target panel
     * @param ctx     the rendering context; {@link RenderingContext#NORMAL} for the
     *                regular non-queued path
     */
    public void dispatch(SDKMessage message, MessagePanel panel, RenderingContext ctx) {
        switch (message) {
            case SDKMessage.Assistant assistant   -> renderAssistant(assistant, panel, ctx);
            case SDKMessage.StreamEvent event     -> renderStreamEvent(event, panel);
            case SDKMessage.System notice         -> {
                int start = panel.snapshotLineCount();
                system.renderSystem(notice, panel);
                registerSystemLogicalMessage(notice, start, panel);
            }
            case SDKMessage.Error error           -> system.renderError(error, panel);
            case SDKMessage.Result _         -> { /* status bar owns token/cost summary */ }
            case SDKMessage.Progress progress     -> renderProgress(progress, panel);
            case SDKMessage.ApiRetry retry        -> system.renderRetry(retry, panel);
            case SDKMessage.ToolUseSummary _ -> { /* suppressed: rendered via tool_result_success StreamEvent */ }
            case SDKMessage.CompactBoundary _ -> system.renderCompactBoundary(panel);
            case SDKMessage.Attachment attachment -> {
                int start = panel.snapshotLineCount();
                system.renderAttachment(attachment, panel);
                registerAttachmentLogicalMessage(attachment, start, panel);
            }
            case SDKMessage.StreamRequestStart _ -> { /* suppress — status bar shows model */ }
            case SDKMessage.User user             -> users.render(user, panel);
            case SDKMessage.Tombstone tombstone   -> retract(tombstone.replacedUuid(), panel);
            default                              -> { /* sentinel/attachment — skip */ }
        }
    }

    private void renderAssistant(SDKMessage.Assistant msg, MessagePanel panel, RenderingContext ctx) {
        if (msg.message() == null || msg.message().message() == null) return;
        AssistantContent content = msg.message().message();
        if (content.content() == null) return;

        if (log.isDebugEnabled()) {
            log.debug("[DISPATCHER] renderAssistant: streaming={} snapshot={} lines={}",
                stream.isOpen(), stream.startSnapshot(), panel.snapshotLineCount());
        }

        int blockIndex = 0;
        int retractionAnchor = -1;
        for (ContentBlock block : content.content()) {
            int currentBlockIndex = blockIndex++;
            String logicalMessageId = msg.message().uuid() + ":" + currentBlockIndex;
            int logicalStart = block instanceof TextBlock && stream.isOpen()
                    && stream.startSnapshot() >= 0
                ? stream.startSnapshot() : panel.snapshotLineCount();
            // Text is anchored immediately. A tool-use anchor is resolved after
            // the switch because replay/fallback rendering may create its row.
            int blockAnchor = block instanceof ToolUseBlock ? -1 : logicalStart;
            if (blockAnchor >= 0 && (retractionAnchor < 0 || blockAnchor < retractionAnchor)) {
                retractionAnchor = blockAnchor;
            }
            switch (block) {
                case TextBlock text -> {
                    String textContent = text.text();

                    if (textContent == null || MessageConstants.isEmptyMessageText(textContent)) {
                        break;
                    }
                    textRenderedThisTurn = true;
                    // Check for rate limit error messages.
                    if (SystemMessageRenderer.isRateLimitError(textContent)) {
                        panel.appendMixed(List.of(
                            new MessagePanel.Segment("  ⚠ Rate limit: ", LanternaTheme.toolError()),
                            new MessagePanel.Segment(textContent, LanternaTheme.welcomeDim())
                        ));
                        break;
                    }

                    if (system.renderSpecialAssistantText(textContent, panel)) break;
                    if (!stream.matchesProjection(textContent)) {
                        stream.rollbackToStart(panel);
                        panel.appendMarkdown(textContent, MARKDOWN_RENDERER, true);
                    }
                    // Reset streaming markers so the NEXT round of streaming
                    // (subsequent Assistant messages in the same turn — multistep
                    // tool use loops) starts a fresh snapshot AFTER this rendered
                    // text. Otherwise, the next truncateLinesTo would roll the
                    // buffer back to the original snapshot and erase this round.
                    stream.close();
                }
                case ToolUseBlock toolUse -> {
                    String inputJson = toolUse.input() == null ? "{}" : toolUse.input().toString();
                    if (toolUse.id() != null) {
                        tools.recordInvocation(toolUse.id(), new PendingToolLedger.ToolInvocation(
                            toolUse.name(), inputJson));
                    }
                    // In queued-preview context, show the static dot indicator.
                    if (ctx.isInQueuedPreview()) {
                        ToolUseIndicatorRenderer.pick(ctx).render(panel, ctx);
                    } else {

                        // the durable source of the row; streaming events only enrich
                        // its queued/in-progress state. Reconstruct any missing stream
                        // projection so replay and provider-specific streams cannot
                        // swallow an otherwise valid tool call.
                        if (tools.findExact(toolUse.id()) == null) {
                            renderStreamEvent(new SDKMessage.StreamEvent(
                                "tool_streaming_start",
                                toolUse.name() + "|" + StringUtils.defaultString(toolUse.id())
                                    + "|" + StringUtils.defaultString(msg.message().uuid())), panel);
                        }
                        renderStreamEvent(new SDKMessage.StreamEvent(
                            "tool_streaming_done",
                            toolUse.name() + "|" + StringUtils.defaultString(toolUse.id())
                                + "|" + inputJson), panel);
                        if (transcriptMode && Strings.CS.equals("Agent", toolUse.name())
                                && !transcriptResolvedToolUseIds.contains(toolUse.id())) {
                            agents.renderVerboseTranscript(toolUse.id(), null, panel);
                        }
                    }
                }
                case ThinkingBlock reasoning -> {
                    if (ctx.isInQueuedPreview() || thinking.shouldRenderCompleted(logicalMessageId)) {
                        thinking.render(reasoning, panel, ctx);
                    }
                }
                case RedactedThinkingBlock _ -> {
                    // Encrypted thinking has no renderable body, so the row is the whole
                    // message. Gated only by verbose/transcript — deliberately NOT routed
                    // through shouldRenderCompletedThinking: findLastThinkingBlockId only
                    // ever matches ThinkingBlock, so a redacted block could never be the
                    // whitelisted id and would be permanently invisible in ctrl+o.
                    if (verbose || transcriptMode) {
                        panel.appendMixed(List.of(new MessagePanel.Segment(
                            Figures.TEARDROP_ASTERISK + " Thinking…",
                            LanternaTheme.welcomeDim(), null, null, Set.of(SGR.ITALIC))));
                    }
                }
                default -> {}
            }
            if (block instanceof ToolUseBlock toolUse) {
                blockAnchor = toolUseAnchor(toolUse);
                if (blockAnchor >= 0
                        && (retractionAnchor < 0 || blockAnchor < retractionAnchor)) {
                    retractionAnchor = blockAnchor;
                }
            }
            if (block instanceof TextBlock(String text1)) {
                int logicalEnd = panel.snapshotLineCount() - 1;
                if (logicalEnd >= logicalStart && text1 != null
                        && !MessageConstants.isEmptyMessageText(text1)) {
                    panel.registerLogicalMessage(
                        logicalMessageId,
                        msg.message().uuid(),
                        MessagePanel.LogicalMessageKind.ASSISTANT,
                        logicalStart,
                        logicalEnd,
                        text1,
                        null,
                        null,
                        null,
                        false);
                }
            }
        }
        if (retractionAnchor >= 0 && retractionAnchor < panel.snapshotLineCount()) {
            retractionAnchors.put(msg.message().uuid(), retractionAnchor);
        }
    }

    /**
     * Row the header for {@code toolUse} occupies, or {@code -1} when this panel
     * never drew one — a replayed transcript renders the assistant message
     * without ever having seen the tool stream that paints the header.
     */
    private int toolUseAnchor(ToolUseBlock toolUse) {
        PendingToolLedger.PendingTool pending = tools.findExact(toolUse.id());
        return pending == null ? -1 : pending.lineIdx();
    }

    /**
     * Rolls the transcript back to just before the withdrawn message, so a model fallback can repaint
     * over clean ground.
     */
    private void retract(String replacedUuid, MessagePanel panel) {
        if (StringUtils.isBlank(replacedUuid)) return;
        Integer anchor = retractionAnchors.remove(replacedUuid);
        if (anchor == null) {
            panel.truncateFromSourceUuid(replacedUuid);
            return;
        }
        panel.truncateLinesTo(anchor);
        resetTurn();
    }

    private void renderStreamEvent(SDKMessage.StreamEvent event, MessagePanel panel) {
        if (!(event.data() instanceof String evData)) return;

        switch (event.eventType()) {
            case "content_block_delta" -> {
                if (evData.isEmpty()) return;
                // A whitespace-only delta with no text block open renders nothing, so
                // never let it OPEN the streaming window: the rollback re-render
                // (StreamingTextRenderer rollback to its tail/start snapshot) would
                // erase any non-text row drawn after the snapshot — e.g. a tool
                // header appended by tool_streaming_start while the window was open.
                // Providers do emit stray "\n" deltas around tool_use blocks (seen
                // with OpenAI-compatible streams), and the final Assistant commit
                // repaints authoritative text anyway, so dropping these costs
                // nothing. Mid-text newlines (window already open) are unaffected.
                if (!stream.isOpen() && StringUtils.isBlank(evData)) return;
                if (!stream.isOpen()) {
                    if (toolResultRenderedThisTurn) {
                        panel.appendLine("", TextColor.ANSI.DEFAULT);
                        toolResultRenderedThisTurn = false;
                    }
                    stream.open(panel);
                }
                // 197 accumulates streamingText on every text_delta, but a tool_use
                // content_block_start clears it — and while that tool is unresolved no
                // further text can stream (the next assistant message only comes after
                // the tool result, i.e. once the pending-tool ledger is empty again). Some
                // providers emit straggler text deltas (a trailing newline, a late
                // batched flush) AFTER tool_streaming_start; treating those as visible
                // streaming text would hide the spinner for the whole tool execution.
                stream.setTextVisible(tools.isEmpty());
                stream.appendDelta(evData, panel);
            }
            case "content_block_stop" -> { /* final Assistant event commits the projection */ }
            case "tool_streaming_start" -> {
                // Stage 1 (QUEUED): tool_use block arrived in stream — show dim static dot.
                //
                // 197 clears streamingText on EVERY content_block_start (2.1.197 bundle:
                // a?.(()=>null) ahead of the content_block.type dispatch), so a tool_use
                // block start ends the visible-streaming-text phase — the spinner comes
                // back for the whole tool-input/streaming + execution window, blocking
                // Bash included. The rollback window (StreamingTextRenderer#isOpen) deliberately
                // stays open until the tool RESULT commits; only the spinner-facing
                // visibility closes here.
                stream.setTextVisible(false);
                String[] startParts = evData.split("\\|", 3);
                String toolName = startParts[0];
                String toolUseId = startParts.length > 1 ? startParts[1] : null;
                String groupMessageId = startParts.length > 2 ? startParts[2] : null;
                toolResultRenderedThisTurn = false;
                if (ToolVisualContractRegistry.hidesUse(toolName)) {
                    tools.addLast(new PendingToolLedger.PendingTool(-1, false, "", toolName, -1,
                        tools.nextLogicalId(), toolUseId, groupMessageId));
                    break;
                }
                if (Boolean.TRUE.equals(transparentWrapperLookup.apply(toolName))) {
                    // Transparent wrapper: suppress header. Push a sentinel so downstream
                    // tool_call_start / tool_result_* events know to skip this slot.
                    tools.addLast(new PendingToolLedger.PendingTool(-1, true, "", toolName, -1,
                        tools.nextLogicalId(), toolUseId, groupMessageId));
                    break;
                }
                if (stream.isOpen() || toolEmittedThisTurn || textRenderedThisTurn || !tools.isEmpty()) {
                    panel.appendLine("", TextColor.ANSI.DEFAULT);
                }
                List<MessagePanel.Segment> dimSegs = headers.dimToolSegs(toolName);
                int lineIdx = panel.snapshotLineCount();
                panel.appendMixed(dimSegs);
                tools.addLast(new PendingToolLedger.PendingTool(lineIdx, false, "", toolName, -1,
                    tools.nextLogicalId(), toolUseId, groupMessageId));
                toolEmittedThisTurn = true;
            }
            case "tool_streaming_done" -> {
                // Stage 1b: full input now known — update arg summary in the dim line.
                String[] parts = evData.split("\\|", 3);
                String toolName = parts[0];
                String eventToolUseId = parts.length >= 2 ? parts[1] : null;
                // Skip transparent wrapper slots.
                PendingToolLedger.PendingTool pending = tools.findExact(eventToolUseId);
                if (pending != null && pending.transparent()) {
                    break;
                }
                String argsJson = parts.length >= 3 ? parts[2] : "";
                String argsPart = headers.toolArgsPart(toolName, argsJson);
                String tag = headers.toolTagPart(toolName, argsJson, eventToolUseId);
                if (pending != null) {
                    ToolVisualContractRegistry.UseView useView =
                        ToolVisualContractRegistry.useView(toolName, argsJson, verbose);
                    int lineIdx = pending.lineIdx();
                    if (lineIdx < 0 && !useView.hidden()) {
                        if (stream.isOpen() || toolEmittedThisTurn || textRenderedThisTurn
                                || tools.size() > 1) {
                            panel.appendLine("", TextColor.ANSI.DEFAULT);
                        }
                        lineIdx = panel.snapshotLineCount();
                        panel.appendMixed(headers.dimToolSegs(toolName, argsPart, tag, argsJson));
                        toolEmittedThisTurn = true;
                    }
                    // Store input JSON for renderToolUseTag/result time and any newly revealed line.
                    PendingToolLedger.PendingTool updated = pending.withInputJson(argsJson).withLineIdx(lineIdx);
                    tools.replace(pending, updated);
                    if (lineIdx >= 0) {
                        panel.updateLine(lineIdx, headers.dimToolSegs(toolName, argsPart, tag, argsJson));
                    }
                }
                if (eventToolUseId != null) {
                    tools.recordInvocation(eventToolUseId, new PendingToolLedger.ToolInvocation(toolName, argsJson));
                }
                if (Strings.CS.equals("Agent", toolName)) {
                    agents.maybeCreateGroup(panel);
                }
            }
            case "tool_call_start" -> {
                // Stage 2 (IN-PROGRESS): tool about to execute — start blinking.

// Use peekFirst because tools execute serially in FIFO order —
                // the first streamed tool is the first to execute.
                String[] parts = evData.split("\\|", 3);
                String toolName = parts[0];
                String eventToolUseId = parts.length >= 2 ? parts[1] : null;
                // Skip transparent wrapper slots.
                PendingToolLedger.PendingTool first = tools.find(eventToolUseId);
                if (first != null && first.transparent()) {
                    break;
                }
                String callArgsJson = parts.length >= 3 ? parts[2] : "";
                if ((first != null && first.lineIdx() < 0)
                        || (first == null && ToolVisualContractRegistry
                            .useView(toolName, callArgsJson, verbose).hidden())) {
                    if (first == null) {
                        tools.addLast(new PendingToolLedger.PendingTool(-1, false, callArgsJson, toolName, -1,
                            tools.nextLogicalId(), eventToolUseId, null));
                    }
                    break;
                }
                if (agents.isGrouped(eventToolUseId)) {
                    agents.repaintGroup(eventToolUseId, panel);
                    break;
                }
                String argsPart = parts.length >= 3 ? headers.toolArgsPart(toolName, parts[2]) : "";
                String tag = headers.toolTagPart(toolName, callArgsJson, eventToolUseId);
                List<MessagePanel.Segment> dimSegs =
                    headers.dimToolSegs(toolName, argsPart, tag, callArgsJson);
                if (first != null) {
                    panel.startBlinkLine(first.lineIdx(), dimSegs);
                } else {
                    // Fallback (no streaming phase): render dim + start blinking
                    int lineIdx = panel.snapshotLineCount();
                    panel.appendMixed(dimSegs);
                    tools.addLast(new PendingToolLedger.PendingTool(lineIdx, false, callArgsJson, toolName, -1,
                        tools.nextLogicalId(), null, null));
                    panel.startBlinkLine(lineIdx, dimSegs);
                }
                PendingToolLedger.PendingTool active = tools.peekFirst();
                if (eventToolUseId != null) active = tools.find(eventToolUseId);
                if (Strings.CS.equals("Agent", toolName) && active != null) {
                    agents.beginProgress(active.toolUseId(), panel);
                }

// x:renderToolUseMessage + AssistantToolUseMessage inline body.
                inlineHeaderLookup.apply(toolName, callArgsJson).ifPresent(line ->
                    panel.appendMixed(List.of(
                        new MessagePanel.Segment("  ⎿  ", LanternaTheme.welcomeDim()),
                        new MessagePanel.Segment(line, LanternaTheme.welcomeDim())
                    )));
            }
            case "tool_result_success", "tool_result_error" -> {
                // Stage 3 (DONE): stop blinking, update to green/red IN-PLACE, show result.

                boolean isError = Strings.CS.equals("tool_result_error", event.eventType());
                int sep = evData.indexOf('|');
                String toolName = sep > 0 ? evData.substring(0, sep) : evData;
                String resultText = sep > 0 ? evData.substring(sep + 1) : "";
                // Pop the matching in-flight tool (FIFO — tools execute serially).
                // A null here is the replay/fallback path: the result arrived with
                // no matching streaming/start sentinel, so every per-tool field is
                // absent and we fall back to rendering without blink/anchor state.
                PendingToolLedger.PendingTool pending = tools.pollFirst();
                if (pending != null && pending.transparent()) {
                    // Transparent wrapper: discard sentinel, do not render.
                    break;
                }
                tools.recordResolved(pending);
                if (pending != null && pending.toolUseId() != null) {
                    if (agents.resolveGroupedMember(pending.toolUseId(), isError, panel)) {
                        toolResultRenderedThisTurn = true;
                        // Result committed as rows — recompute the streaming baseline
                        // after it, never roll back over it.
                        stream.closeIfOpen();
                        break;
                    }
                    agents.remove(pending.toolUseId(), panel);
                }
                ToolVisualContractRegistry.ResultMode resultMode =
                    ToolVisualContractRegistry.resultMode(toolName);
                // READ summarizes a structured payload, which a stream event does not carry.
                // The expanded Read/Search group replays its retained events through this
                // path (renderExpandedToolEvents), so suppressing the body here would empty
                // the expansion.
                if (!isError && resultMode != ToolVisualContractRegistry.ResultMode.DEFAULT
                        && resultMode != ToolVisualContractRegistry.ResultMode.READ) {
                    headers.complete(pending, LanternaTheme.toolSuccess(), panel);
                    toolResultRenderedThisTurn = true;
                    stream.closeIfOpen();
                    break;
                }
                // Hoist the nullable fields once; below we only touch these scalars,
                // so a replay-path null pending needs no repeated `pending != null`.
                String inputJson = pending != null ? pending.inputJson() : null;
                Integer pendingIdx = pending != null ? pending.lineIdx() : null;
                String logicalMessageId = pending != null ? pending.logicalMessageId()
                    : tools.nextLogicalId();
                TextColor dotColor = isError ? LanternaTheme.toolError() : LanternaTheme.toolSuccess();
                String tag = headers.toolTagPart(toolName, inputJson != null ? inputJson : "",
                    pending != null ? pending.toolUseId() : null);

                // renderToolUseMessage visible after the tool resolves.
                String argsPart = headers.toolArgsPart(toolName, inputJson);
                List<MessagePanel.Segment> doneSegs = headers.buildDoneSegs(
                    toolName, dotColor, tag, argsPart, inputJson);
                if (pendingIdx != null) {
                    panel.stopBlinkLine(pendingIdx, doneSegs);
                } else {
                    // Replay/fallback: no blink state — render directly.
// match the blank-line separator that tool_streaming_start normally adds.
                    if (stream.isOpen()) {
                        panel.appendLine("", TextColor.ANSI.DEFAULT);
                    }
                    panel.appendMixed(doneSegs);
                }
                if (!StringUtils.isBlank(resultText)) {
                    results.renderStreamedText(resultText, isError, panel);
                }
                ToolHeaderRenderer.PrimaryInput primary = ToolHeaderRenderer.extractPrimaryInput(toolName, inputJson);
                if (primary != null) {
                    int startLine = pendingIdx >= 0
                        ? pendingIdx : Math.max(0, panel.snapshotLineCount() - 1);
                    panel.registerLogicalMessage(
                        logicalMessageId,
                        MessagePanel.LogicalMessageKind.TOOL,
                        startLine,
                        panel.snapshotLineCount() - 1,
                        StringUtils.isBlank(resultText) ? primary.value() : resultText,
                        null,
                        primary.label(),
                        primary.value(),
                        false);
                }
                toolResultRenderedThisTurn = true;
                // Result committed — drop any live-stream baseline above it so a later
                // unstable text rollback cannot erase the tool's just-rendered rows.
                stream.closeIfOpen();
            }
            case "hook_call_start" -> {
                // evData = "toolName|PreToolUse" or "toolName|PostToolUse"
                String hookEvent = Strings.CS.contains(evData, "|") ? evData.substring(evData.indexOf('|') + 1) : "hook";
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(ToolResultLines.INDENT_PREFIX, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Running " + hookEvent + " hook…", LanternaTheme.welcomeDim())
                ));
            }
            case "hook_call_done" -> {
                // Hook progress line stays as dim historical record
            }
            case "permission_waiting" -> { // evData = toolName
                PendingToolLedger.PendingTool pending = tools.peekFirst();
                if (pending == null || pending.transparent() || pending.lineIdx() < 0) break;
                List<MessagePanel.Segment> waiting = List.of(
                    new MessagePanel.Segment(ToolResultLines.INDENT_PREFIX, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Waiting…", LanternaTheme.welcomeDim()));
                if (pending.statusLineIdx() >= 0) {
                    panel.updateLine(pending.statusLineIdx(), waiting);
                } else {
                    int statusLineIdx = panel.snapshotLineCount();
                    panel.appendMixed(waiting);
                    tools.removeFirst();
                    tools.addFirst(pending.withStatusLineIdx(statusLineIdx));
                }
            }
            case "permission_resolved", "permission_denied" -> {
                // State transitions — no additional line needed; tool_result_* will follow
            }
        }
    }

    private static void registerSystemLogicalMessage(
            SDKMessage.System msg, int startLine, MessagePanel panel) {
        if (msg == null || msg.message() == null) return;
        String subtype = msg.message().subtype();
        if (Strings.CS.equalsAny(subtype,
                "api_metrics", "stop_hook_summary", "turn_duration", "memory_saved",
                "agents_killed", "away_summary", "thinking")) {
            return;
        }
        int endLine = panel.snapshotLineCount() - 1;
        if (endLine < startLine) return;
        panel.registerLogicalMessage(
            msg.message().uuid(),
            MessagePanel.LogicalMessageKind.SYSTEM,
            startLine,
            endLine,
            msg.message().content(),
            null,
            null,
            null,
            false);
    }

    private static void registerAttachmentLogicalMessage(
            SDKMessage.Attachment attachment, int startLine, MessagePanel panel) {
        if (attachment == null || !Strings.CS.equalsAny(attachment.attachmentType(),
                "queued_command", "diagnostics", "hook_blocking_error",
                "hook_error_during_execution")) {
            return;
        }
        int endLine = panel.snapshotLineCount() - 1;
        if (endLine < startLine) return;
        panel.registerLogicalMessage(
            "attachment:" + attachment.attachmentType() + ":" + startLine,
            MessagePanel.LogicalMessageKind.ATTACHMENT,
            startLine,
            endLine,
            attachment.content(),
            null,
            null,
            null,
            false);
    }

    /**
     * Renders a progress message inline in the message panel.
     */
    private void renderProgress(SDKMessage.Progress msg, MessagePanel panel) {
        if (msg.message() == null) return;
        String progressToolUseId = msg.message().toolUseId();
        if (progressToolUseId != null) {
            tools.recordProgress(progressToolUseId, msg.message());
            PendingToolLedger.PendingTool pending = tools.find(progressToolUseId);
            if (pending != null && !pending.transparent() && pending.lineIdx() >= 0) {
                String argsPart = headers.toolArgsPart(pending.toolName(), pending.inputJson());
                String tag = headers.toolTagPart(
                    pending.toolName(), pending.inputJson(), progressToolUseId);
                panel.updateLine(pending.lineIdx(), headers.dimToolSegs(
                    pending.toolName(), argsPart, tag, pending.inputJson()));
            }
        }
        ProgressMessage.ProgressData data = msg.message().data();
        PendingToolLedger.ToolInvocation invocation = msg.message().toolUseId() == null ? null
            : tools.invocation(msg.message().toolUseId());
        if (data != null && Strings.CS.equals("waiting_for_task", data.type())
                && invocation != null
                && ToolVisualContractRegistry.resultMode(invocation.toolName())
                    == ToolVisualContractRegistry.ResultMode.TASK_OUTPUT) {
            ToolProgressRenderer.waitingForTask(msg.message().content(), panel);
            return;
        }
        if (data != null && Strings.CS.equals("agent_progress", data.type())) {
            agents.renderProgress(msg.message().toolUseId(), data, panel);
            return;
        }
        if (data != null && Strings.CS.equals("mcp_progress", data.type())) {
            ToolProgressRenderer.mcp(data, panel);
            return;
        }
        if (data != null && Strings.CS.equalsAny(data.type(),
                "query_update", "search_results_received")) {
            ToolProgressRenderer.webSearch(data, panel);
            return;
        }
        if (data != null && data.type() != null) {
            ToolProgressRenderer.shellSummary(data, panel);
        } else if (msg.message().content() != null) {
            panel.appendLine("⟳ " + msg.message().content(), LanternaTheme.agentCyan());
        }
    }

    /**
     * Completes the shared tool-card state before a generic or specialized result body renders.
     * Structured Edit/Write/Notebook payloads must pass through this path too; otherwise their
     * tool header keeps blinking and a permission {@code Waiting…} row remains stranded.
     */
    private ToolResultRenderer.Placement beginToolResult(ToolResultBlock result, MessagePanel panel) {
        toolResultRenderedThisTurn = true;
        // A tool result is a committed row: any still-open live-stream window must
        // not be allowed to roll back past it. Close the window so the next text
        // block re-snapshots AFTER this result (197 never rolls back a committed
        // tool result; see StreamingTextRenderer#close).
        stream.closeIfOpen();
        PendingToolLedger.PendingTool pending = tools.remove(result.toolUseId());
        if (pending == null) pending = paintDeferredFoldedCard(result.toolUseId(), panel);
        int replaceLine = -1;
        if (pending != null && !pending.transparent()) {
            replaceLine = pending.statusLineIdx();
            tools.recordResolved(pending);
            headers.complete(pending,
                result.isError() ? LanternaTheme.toolError() : LanternaTheme.toolSuccess(), panel);
        }
        return new ToolResultRenderer.Placement(pending, replaceLine);
    }

    private String persistedPlan() {
        try {
            return persistedPlanSupplier.get();
        } catch (RuntimeException _) {
            return null;
        }
    }

}
