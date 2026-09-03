package com.claudecode.ui.lanterna.transcript;

import com.claudecode.commands.XmlConstants;
import com.claudecode.core.constants.Figures;
import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.core.diff.StructuredPatchHunk;
import com.claudecode.core.engine.AbortException;
import com.claudecode.core.imagestore.ImageStore;
import com.claudecode.core.mcp.ChannelMessageWrapper;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.FriendlyApiError;
import com.claudecode.core.message.GoalStatusAttachment;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.ProgressMessage;
import com.claudecode.core.message.RedactedThinkingBlock;
import com.claudecode.core.message.RefusalErrorMessage;
import com.claudecode.core.message.RefusalLearnMoreLink;
import com.claudecode.core.message.RefusalFallbackFeature;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.SummarizeMetadata;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ThinkingBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.Message;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.ui.Ansi;
import com.claudecode.ui.DiffRenderer;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.ui.lanterna.components.AnsiToSegments;
import com.claudecode.ui.lanterna.components.SpinnerVerbs;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.ui.lanterna.dialog.RejectedFileChangePreview;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.render.HighlightedThinkingRenderer;
import com.claudecode.ui.render.RenderingContext;
import com.claudecode.ui.render.ToolUseIndicatorRenderer;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.AgentColorStore;
import com.claudecode.core.io.PathUtils;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.tasks.PendingBackgroundWork;
import com.claudecode.tools.ToolUseTag;
import com.claudecode.ui.syntax.ScopeColorMap;
import com.claudecode.ui.syntax.TmTokenizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.claudecode.core.text.XmlTagUtils;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.SGR;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches SDKMessage to MessagePanel.
 *
 * <ul>
 *   <li>{@code src/components/messages/AssistantThinkingMessage.tsx} — the thinking body
 *       layout: a {@code minWidth: 2} dim+italic {@code ∴} gutter with dim Markdown
 *       beside it, no label word and no leading blank row. Authoritative formula from the
 *       2.1.197 bundle's {@code Fzn}; its non-Markdown else branch is dead there because
 *       the dispatch gate already forces the flag on, so it is not ported.</li>
 *   <li>{@code src/components/messages/AssistantRedactedThinkingMessage.tsx} — the
 *       sibling redacted-thinking component ({@code Dcl} in the 2.1.197 bundle),
 *       rendering the single row {@code ✻ Thinking…} with no body.</li>
 *   <li>{@code src/components/Message.tsx} — the thinking dispatch gate
 *       {@code if (!verbose && !transcript) return null}, mirrored by
 *       {@link #shouldRenderCompletedThinking} plus the verbose/transcript checks on the
 *       redacted arm.</li>
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
    }

    /**
     * Optional callback fired on transitions of the render-layer "visible streaming
     * text" state. Mirrors 197's {@code visibleStreamingText} (REPL.tsx): in 197 the
     * underlying {@code streamingText} is set by {@code text_delta} accumulation and
     * cleared on EVERY {@code content_block_start} (verified in the 2.1.197 bundle:
     * the stream-event switch runs {@code a?.(()=>null)} before dispatching on
     * {@code content_block.type}) — so a {@code tool_use} block start ends the
     * visible-text phase even though the tool result has not committed yet. This
     * state is therefore deliberately NOT {@link #streamedThisTurn}: that window
     * stays open across tool execution for snapshot-rollback safety, while the
     * spinner-facing visibility must close at {@code tool_streaming_start}.
     * Invoked with {@code true} when visible streaming text starts (first
     * {@code content_block_delta} of a text phase) and {@code false} when it ends
     * (a tool stream starts, the text commits to the panel, or the turn resets).
     * Fires only on actual transitions. Wired by {@code LanternaSessionSink} to
     * drive {@link
     * com.claudecode.ui.lanterna.components.SpinnerStateMachine#onStreamTextVisibility}.
     * Null when no consumer is registered (e.g. replay-only dispatch).
     */
    private Consumer<Boolean> onStreamTextVisibility;

    /** Register a consumer notified when the visible-streaming-text window opens/closes. */
    public void onStreamTextVisibility(Consumer<Boolean> listener) {
        this.onStreamTextVisibility = listener;
    }

    private Supplier<PendingBackgroundWork> pendingBackgroundWorkSupplier;
    private Supplier<String> backgroundTaskSummarySupplier;

    public void setTurnSummaryContext(Supplier<PendingBackgroundWork> pendingSupplier,
                                      Supplier<String> summarySupplier) {
        this.pendingBackgroundWorkSupplier = pendingSupplier;
        this.backgroundTaskSummarySupplier = summarySupplier;
    }


    static final String BLACK_CIRCLE =
        Strings.CI.contains(System.getProperty("os.name", ""), "mac") ? "⏺ " : "● ";

    /**
     * Help article every refusal announcement ends with. The announcement text and
     * the refusal error line are built in core, so the url lives there too and this
     * is only the reader's name for it.
     */
    static final String REFUSAL_HELP_URL = RefusalErrorMessage.LEARN_MORE_URL;

    /**
     * Terminal hyperlink capability. Production reads the process environment;
     * tests inject a constant so neither the environment nor {@code Ansi}'s
     * package-private overload has to be widened.
     */
    private BooleanSupplier hyperlinkSupport = Ansi::supportsHyperlinks;

    /** Shared markdown renderer — thread-safe (Caffeine cache internally). */
    private static final MarkdownRenderer MARKDOWN_RENDERER = MarkdownRenderer.shared();

    /** Two-column gutter carrying the thinking glyph on the first body row. */
    private static final String THINKING_GUTTER = "∴ ";
    /** Continuation rows align under the gutter. */
    private static final String THINKING_INDENT = "  ";

    /** Parses {@code <channel source="..." [attrs]>content</channel>} — compiled once. */
    private static final Pattern CHANNEL_RE = Pattern.compile(
        "<channel\\s+source=\"([^\"]+)\"([^>]*)>\\n?([\\s\\S]*?)\\n?</channel>",
        Pattern.CASE_INSENSITIVE
    );
    /** Extracts the optional {@code user} attribute from the channel tag's attribute string. */
    private static final Pattern CHANNEL_USER_ATTR_RE = Pattern.compile("\\buser=\"([^\"]+)\"");


    private boolean verbose = false;

    public void setVerbose(boolean v) { this.verbose = v; }


    private boolean transcriptMode = false;

    /** Whether transcript replay should hide every completed thinking block except one. */
    private boolean hidePastThinking = false;

    private String visibleTranscriptThinkingBlockId;

    public void setTranscriptMode(boolean t) { this.transcriptMode = t; }

    void showOnlyTranscriptThinkingBlock(String blockId) {
        hidePastThinking = true;
        visibleTranscriptThinkingBlockId = blockId;
    }

    /**
     * Resolves whether a tool acts as a transparent wrapper whose header is hidden from the UI.
     */
    private Function<String, Boolean> transparentWrapperLookup = _ -> false;

    public void setTransparentWrapperLookup(Function<String, Boolean> lookup) {
        if (lookup != null) this.transparentWrapperLookup = lookup;
    }

    /**
     * Paste ids whose {@code [Image #N]} tag has already been rendered synchronously in the current
     * live turn's echo pass (see {@code LanternaReplScreen.renderInlineImages}).
     */
    private final Set<Integer> imagesRenderedInlineThisTurn = new HashSet<>();

    /**
     * Called by {@code LanternaReplScreen.executeQuery} after painting the
     * {@code ⎿ [Image #N]} lines synchronously, so they show up together with
     * the {@code ❯ text} echo instead of lagging behind the ImageResizer step.
     */
    public void markImagesRenderedInline(Collection<Integer> pasteIds) {
        if (pasteIds != null) imagesRenderedInlineThisTurn.addAll(pasteIds);
    }

    /**
     * One-shot flag: when true, the next {@link SDKMessage.User} event whose content is user-authored
     * (text + images, no {@link ToolResultBlock}) is dropped instead of rendered — because {@code
     * executeQuery} already painted both the {@code ❯ text} echo and any inline {@code ⎿ [Image #N]}
     * lines synchronously.
     */
    private boolean suppressNextUserEcho = false;

    public void suppressNextUserEcho() {
        this.suppressNextUserEcho = true;
    }

    /**
     * Tracks whether the current turn has any active streaming content,
     * so we can suppress the duplicate final Assistant render.
     */
    private boolean streamedThisTurn = false;
    /** Snapshot of MessagePanel line count before streaming starts this turn. */
    private int     streamStartSnapshot = -1;
    /** First line of the replaceable final Markdown block. */
    private int streamTailSnapshot = -1;
    private final StringBuilder streamingMarkdownText = new StringBuilder();
    private String streamingStrippedMarkdownText = "";
    private int streamingStablePrefixLength = 0;
    /** Diagnostic contract: characters handed to the stable-boundary parser per delta. */
    private final List<Integer> streamingBoundaryParseInputLengths = new ArrayList<>();
    private boolean streamingHasStableContent = false;
/**
     * True once the first tool of this turn has been rendered.
     */
    private boolean toolEmittedThisTurn = false;
    /** The next assistant text needs AssistantTextMessage's marginTop=1. */
    private boolean toolResultRenderedThisTurn = false;
    /** True once a non-empty TextBlock has been committed to the panel this
     *  turn, whether via streaming or a one-shot renderAssistant commit.
     *  {@link #streamedThisTurn} alone can't drive the blank-line-before-tool
     *  rule because it gets reset to false right after a TextBlock commits
     *  (see renderAssistant) — losing the signal that content just preceded
     *  a first tool call rendered from a one-shot (non-streamed) message. */
    private boolean textRenderedThisTurn = false;

    /**
     * One in-flight tool call awaiting its result.
     */
    private record PendingTool(int lineIdx, boolean transparent, String inputJson,
                               String toolName, int statusLineIdx, String logicalMessageId,
                               String toolUseId, String groupMessageId) {
        PendingTool withInputJson(String json) {
            return new PendingTool(lineIdx, transparent, json, toolName, statusLineIdx,
                logicalMessageId, toolUseId, groupMessageId);
        }
        PendingTool withStatusLineIdx(int index) {
            return new PendingTool(lineIdx, transparent, inputJson, toolName, index,
                logicalMessageId, toolUseId, groupMessageId);
        }
        PendingTool withLineIdx(int index) {
            return new PendingTool(index, transparent, inputJson, toolName, statusLineIdx,
                logicalMessageId, toolUseId, groupMessageId);
        }
        PendingTool shiftedAfter(int start, int delta) {
            int shiftedLine = lineIdx >= start ? lineIdx + delta : lineIdx;
            int shiftedStatus = statusLineIdx >= start ? statusLineIdx + delta : statusLineIdx;
            return new PendingTool(shiftedLine, transparent, inputJson, toolName,
                shiftedStatus, logicalMessageId, toolUseId, groupMessageId);
        }
    }

    private static final int MAX_AGENT_PROGRESS_MESSAGES = 3;
    private static final int ESTIMATED_AGENT_LINES_PER_TOOL = 9;
    private static final int AGENT_TERMINAL_BUFFER_LINES = 7;

    /**
     * Wording for the background affordance. It lives here, not on the progress event, because
     * upstream's renderer builds the component from a bare {@code {kind:"background_hint"}}.
     */
    public static final String BACKGROUND_HINT_TEXT = "Press Ctrl+B to run in background";

    /** Upstream renders the affordance under {@code <Box paddingLeft={5}>}. */
    public static final int BACKGROUND_HINT_PADDING = 5;

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

    /**
     * FIFO queue of in-flight tool calls. Three-stage flow per tool:
     *  1. tool_streaming_start → push dim static line (queued, no args yet)
     *  2. tool_streaming_done  → update args in the dim line (still static)
     *  3. tool_call_start      → start blinking the dim line (in-progress)
     *  4. tool_result_*        → pop, stop blinking, update to green/red + result (done)
     * Tools execute serially → FIFO ordering is guaranteed.
     */
    private final Deque<PendingTool> pendingTools = new ArrayDeque<>();
    private final Map<String, ToolInvocation> toolInvocations = new HashMap<>();
    private final Map<String, List<ProgressMessage>> toolProgressByToolUseId = new HashMap<>();
    private final Map<String, Object> toolResultsByToolUseId = new HashMap<>();

    private Supplier<String> persistedPlanSupplier = () -> null;

    private record ToolInvocation(String toolName, String inputJson) {}
    private final Map<String, AgentProgressBlock> agentProgressBlocks = new HashMap<>();
    private final Map<String, AgentGroupBlock> agentGroupsByToolUseId = new HashMap<>();
    private Map<String, List<ProgressMessage>> transcriptAgentProgress = Map.of();
    private Set<String> transcriptResolvedToolUseIds = Set.of();
    private final Set<String> renderedVerboseAgentToolUseIds = new HashSet<>();

    void setTranscriptRenderModel(TranscriptRenderModel model) {
        if (model == null) {
            transcriptAgentProgress = Map.of();
            transcriptResolvedToolUseIds = Set.of();
        } else {
            transcriptAgentProgress = model.agentProgressByToolUseId();
            transcriptResolvedToolUseIds = model.resolvedToolUseIds();
        }
        renderedVerboseAgentToolUseIds.clear();
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
    private long logicalToolSequence;
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

    private ToolTagLookup toolTagLookup = _ -> Optional.empty();

    public void setToolTagLookup(ToolTagLookup lookup) {
        this.toolTagLookup = lookup != null ? lookup : _ -> Optional.empty();
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

    /** Notify the registered consumer (if any) of a window transition. */
    private void fireStreamTextVisibility(boolean visible) {
        if (onStreamTextVisibility != null) onStreamTextVisibility.accept(visible);
    }

    /**
     * Current spinner-facing "visible streaming text" state — see the
     * {@link #onStreamTextVisibility} javadoc. Distinct from
     * {@link #streamedThisTurn}: the rollback window stays open across tool
     * execution, this one closes when a tool stream starts (197 clears
     * {@code streamingText} on every {@code content_block_start}).
     */
    private boolean streamTextVisible = false;

    /** Transition the visible-streaming-text state, firing the listener only on change. */
    private void setStreamTextVisible(boolean visible) {
        if (streamTextVisible == visible) return;
        streamTextVisible = visible;
        fireStreamTextVisibility(visible);
    }

    /**
     * Close the visible-streaming-text window and restore the subset of state the
     * live stream owns. Mirrors the reset {@code renderAssistant} performs after it
     * commits a text block (java equivalent of 197 closing one assistant message's
     * text block before the next message's stream begins). The next
     * {@code content_block_delta} therefore takes a fresh {@link #streamStartSnapshot}
     * at the panel's CURRENT line count — after any tool result — so a subsequent
     * unstable-text rollback ({@code truncateLinesTo(streamStartSnapshot)}) never
     * erases a tool result / diff committed in between. 197 has no snapshot
     * truncation at all (its streamed text is a per-message id override); the
     * rollback here exists only to retract a still-unstable suffix of one text block.
     */
    private void closeStreamingWindow() {
        streamedThisTurn = false;
        streamStartSnapshot = -1;
        streamTailSnapshot = -1;
        streamingStrippedMarkdownText = "";
        streamingStablePrefixLength = 0;
        streamingBoundaryParseInputLengths.clear();
        streamingHasStableContent = false;
        streamingMarkdownText.setLength(0);
        setStreamTextVisible(false);
    }

    /** Reset between user turns. Called by LanternaReplScreen on submit. */
    public void resetTurn() {
        streamedThisTurn = false;
        toolEmittedThisTurn = false;
        toolResultRenderedThisTurn = false;
        textRenderedThisTurn = false;
        streamStartSnapshot = -1;
        streamTailSnapshot = -1;
        streamingMarkdownText.setLength(0);
        streamingStrippedMarkdownText = "";
        streamingStablePrefixLength = 0;
        streamingBoundaryParseInputLengths.clear();
        streamingHasStableContent = false;
        pendingTools.clear();
        toolInvocations.clear();
        toolProgressByToolUseId.clear();
        toolResultsByToolUseId.clear();
        presentationSnapshots.resetTurn();
        agentProgressBlocks.clear();
        agentGroupsByToolUseId.clear();
        retractionAnchors.clear();
        imagesRenderedInlineThisTurn.clear();
        suppressNextUserEcho = false;
        setStreamTextVisible(false);
    }

    /** Returns true when no tool calls are in-flight — safe to trigger replay from MessageHistory. */
    public boolean isIdle() {
        return pendingTools.isEmpty() && !streamedThisTurn;
    }

    List<Integer> streamingBoundaryParseInputLengthsForTest() {
        return List.copyOf(streamingBoundaryParseInputLengths);
    }

    /**
     * Render retained tool start/result events into detached verbose rows. This reuses the
     * normal tool renderer so an expanded Read/Search group cannot drift from standalone tools.
     */
    List<MessagePanel.StyledLine> renderExpandedToolEvents(List<SDKMessage.StreamEvent> events) {
        LanternaMessageDispatcher renderer = new LanternaMessageDispatcher();
        renderer.setVerbose(true);
        renderer.setTransparentWrapperLookup(transparentWrapperLookup);
        renderer.setToolTagLookup(toolTagLookup);
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
            case SDKMessage.System system         -> {
                int start = panel.snapshotLineCount();
                renderSystem(system, panel);
                registerSystemLogicalMessage(system, start, panel);
            }
            case SDKMessage.Error error           -> renderError(error, panel);
            case SDKMessage.Result _         -> { /* status bar owns token/cost summary */ }
            case SDKMessage.Progress progress     -> renderProgress(progress, panel);
            case SDKMessage.ApiRetry retry        -> renderRetry(retry, panel);
            case SDKMessage.ToolUseSummary _ -> { /* suppressed: rendered via tool_result_success StreamEvent */ }
            case SDKMessage.CompactBoundary _ -> renderCompactBoundary(panel);
            case SDKMessage.Attachment attachment -> {
                int start = panel.snapshotLineCount();
                renderAttachment(attachment, panel);
                registerAttachmentLogicalMessage(attachment, start, panel);
            }
            case SDKMessage.StreamRequestStart _ -> { /* suppress — status bar shows model */ }
            case SDKMessage.User user             -> renderUser(user, panel);
            case SDKMessage.Tombstone tombstone   -> retract(tombstone.replacedUuid(), panel);
            default                              -> { /* sentinel/attachment — skip */ }
        }
    }

    /**
     * User messages carry tool results and optional command metadata.
     */
    private void renderUser(SDKMessage.User msg, MessagePanel panel) {
        if (msg.message() == null || msg.message().message() == null) return;
        // Meta messages are model-facing only. Transcript-only messages (notably
        // compact summaries) are hidden in the normal view and replayed by the
        // dedicated Ctrl+O dispatcher, whose transcriptMode flag is true.
        if (!MessageConstants.shouldShowUserMessage(msg.message(), transcriptMode)) return;
        if (msg.message().isCompactSummary()) {
            renderCompactSummary(msg.message(), panel);
            return;
        }
        MessageContent mc = msg.message().message();
        if (mc.blocks() == null) {
            // Text-shaped content — the {"text": ...} shape every plain prompt


            if (suppressNextUserEcho) {
                suppressNextUserEcho = false;
                panel.bindLatestUnboundUserSourceUuid(msg.message().uuid());
                return;
            }
            String text = mc.text();
            int start = panel.snapshotLineCount();
            renderUserTextBlock(text, panel);
            registerUserLogicalMessage(
                msg.message().uuid(), msg.message().uuid(), text, start, panel);
            return;
        }
        // One-shot echo suppression: skip the outgoing live-turn user message
        // that executeQuery already painted synchronously. Only applies when
        // ALL blocks are user-authored (text/image) — a tool_result carrier
        // still needs to render (tool feedback in the agentic loop).
        if (suppressNextUserEcho) {
            boolean hasToolResult = mc.blocks().stream()
                .anyMatch(ToolResultBlock.class::isInstance);
            if (!hasToolResult) {
                suppressNextUserEcho = false;
                panel.bindLatestUnboundUserSourceUuid(msg.message().uuid());
                return;
            }
        }
// imagePasteIds is a parallel list carrying the pasted-content id for each ImageBlock, in
// order of appearance.

        List<Integer> imagePasteIds = msg.message().imagePasteIds();
        int imageBlockIndex = 0;
        int blockIndex = 0;
        for (ContentBlock block : mc.blocks()) {
            int currentBlockIndex = blockIndex++;
            if (block instanceof ToolResultBlock result) {
                if (result.toolUseId() != null && msg.message().toolUseResult() != null) {
                    toolResultsByToolUseId.put(result.toolUseId(), msg.message().toolUseResult());
                }
                if (!renderRegisteredToolResult(
                            msg.message().toolUseResult(), result, panel)
                        && !renderGroupedAgentResult(
                            msg.message().toolUseResult(), result, panel)
                        && !renderStructuredAgentResult(
                            msg.message().toolUseResult(), result, panel)
                        && !renderStructuredNotebookEditResult(
                            msg.message().toolUseResult(), result, panel)
                        && !renderStructuredFileChangeResult(
                            msg.message().toolUseResult(), result, panel)) {
                    renderToolResult(result, panel);
                }
            } else if (block instanceof TextBlock(String text)) {
                int start = panel.snapshotLineCount();
                renderUserTextBlock(text, panel);
                registerUserLogicalMessage(
                    msg.message().uuid() + ":" + currentBlockIndex,
                    msg.message().uuid(), text, start, panel);
            } else if (block instanceof ImageBlock) {


                // "[Image #N]" chip below the ❯ query line, hyperlinked to the
                // locally-cached PNG so iTerm/Kitty/etc. Cmd+click can open it.
                Integer imageId = (imagePasteIds != null
                        && imageBlockIndex < imagePasteIds.size())
                    ? imagePasteIds.get(imageBlockIndex) : null;
                imageBlockIndex++;
                // Skip if executeQuery already painted this [Image #N] line
                // synchronously in the echo pass — otherwise we'd double the
                // ⎿ line once ImageResizer completes and the SDK User event
                // finally flows through the stream. See
                // imagesRenderedInlineThisTurn's field doc.
                if (imageId != null && imagesRenderedInlineThisTurn.contains(imageId)) {
                    continue;
                }
                renderUserImageMessage(imageId, panel);
            }
        }
    }

    private void renderCompactSummary(UserMessage message, MessagePanel panel) {
        SummarizeMetadata metadata = message.summarizeMetadata();
        panel.appendLine("", LanternaTheme.welcomeDim());

        String title = metadata == null ? "Compact summary" : "Summarized conversation";
        List<MessagePanel.Segment> header = new ArrayList<>();
        header.add(new MessagePanel.Segment(BLACK_CIRCLE, TextColor.ANSI.DEFAULT));
        header.add(new MessagePanel.Segment(
            title, TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
        if (metadata == null && !transcriptMode) {
            header.add(new MessagePanel.Segment(
                " (" + expandShortcut() + " to expand)", LanternaTheme.welcomeDim()));
        }
        panel.appendMixed(header);

        if (transcriptMode) {
            appendCompactSummaryBody(message.message(), panel);
            return;
        }
        if (metadata == null) return;

        String position = Strings.CS.equals("up_to", metadata.direction())
            ? "up to this point" : "from this point";
        panel.appendLine(INDENT_PREFIX + "Summarized " + metadata.messagesSummarized()
            + " messages " + position, LanternaTheme.welcomeDim());
        if (StringUtils.isNotBlank(metadata.userContext())) {
            panel.appendLine(INDENT_CONT + "Context: “" + metadata.userContext() + "”",
                LanternaTheme.welcomeDim());
        }
        panel.appendLine(INDENT_CONT + "(" + expandShortcut() + " to expand history)",
            LanternaTheme.welcomeDim());
    }

    private static void appendCompactSummaryBody(MessageContent content, MessagePanel panel) {
        if (content == null) return;
        String text = content.text();
        if (text == null && content.blocks() != null) {
            text = content.blocks().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        }
        if (text == null) return;
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            panel.appendLine((index == 0 ? INDENT_PREFIX : INDENT_CONT) + lines[index],
                TextColor.ANSI.DEFAULT);
        }
    }

    private static void registerUserLogicalMessage(
            String id, String sourceUuid, String rawText, int startLine, MessagePanel panel) {
        int endLine = panel.snapshotLineCount() - 1;
        String text = MessageConstants.stripSystemReminders(rawText);
        if (endLine < startLine || StringUtils.isBlank(text) || Strings.CS.startsWith(text, "<")
                || MessageConstants.INTERRUPT_MESSAGE.equals(text)
                || MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text)) {
            return;
        }
        panel.registerLogicalMessage(
            id,
            sourceUuid,
            MessagePanel.LogicalMessageKind.USER,
            startLine,
            endLine,
            text,
            text,
            null,
            null,
            false);
    }


    public void renderUserImageMessage(Integer imageId, MessagePanel panel) {
        String label = imageId != null ? "[Image #" + imageId + "]" : "[Image]";
        // Attach the hyperlink via Segment.hyperlink so the Screen's diff loop
        // emits the OSC 8 sequence. Embedding raw ESC ] 8 ; ; URL ESC \ in the
        // segment text would render as literal characters — drawSegments does
        // not parse ANSI escapes out of segment text (only drawLine does).
        String url = null;
        if (imageId != null && Ansi.supportsHyperlinks()) {
            String path = ImageStore.getStoredImagePath(imageId);
            if (path != null) {
// new File(...).toURI produces file:///... with proper

                // pathToFileURL(imagePath).href.
                url = new File(path).toURI().toString();
            }
        }
        MessagePanel.Segment labelSeg = url != null
            ? MessagePanel.Segment.hyperlink(label, TextColor.ANSI.DEFAULT, url)
            : new MessagePanel.Segment(label, TextColor.ANSI.DEFAULT);
        panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            labelSeg
        ));
    }

    /**
     * Renders a TextBlock in a user message that may contain command metadata.
     */
    private void renderUserTextBlock(String text, MessagePanel panel) {
        if (text == null) return;

// Background Agent/Task completion — the XML is an internal model-facing protocol, not
// user-authored terminal content.
        if (Strings.CS.contains(text, "<" + XmlConstants.TASK_NOTIFICATION_TAG)) {
            renderAgentNotification(text, panel);
            return;
        }



        // <bash-input>/<bash-stdout>/<bash-stderr> UserMessages appended by
        // processBashCommand (LanternaReplScreen.handleBashModeInput in Java).
        if (Strings.CS.startsWith(text, "<" + XmlConstants.BASH_INPUT_TAG)) {
            renderBashInput(text, panel);
            return;
        }
        if (Strings.CS.startsWith(text, "<" + XmlConstants.BASH_STDOUT_TAG)
                || Strings.CS.startsWith(text, "<" + XmlConstants.BASH_STDERR_TAG)) {
            renderBashOutput(text, panel);
            return;
        }


        if (Strings.CS.startsWith(text, "<" + XmlConstants.LOCAL_COMMAND_STDOUT_TAG)
                || Strings.CS.startsWith(text, "<" + XmlConstants.LOCAL_COMMAND_STDERR_TAG)) {
            renderLocalCommandOutput(text, panel);
            return;
        }


        // <InterruptedByUser/> here. In Java the live turn already paints that
        // line from TurnOutcome (LanternaSessionSink), so the streamed message
        // is swallowed; session replay paints it in
        // SessionController.replayLoadedMessages instead. Rendering here too
        // would double the line on every live interrupt.
        if (MessageConstants.INTERRUPT_MESSAGE.equals(text)
                || MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text)) {
            return;
        }


        if (Strings.CS.startsWith(text, "<" + ChannelMessageWrapper.CHANNEL_TAG)) {
            renderChannelMessage(text, panel);
            return;
        }

        String commandMessage = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_MESSAGE_TAG).orElse(null);
        if (StringUtils.isBlank(commandMessage)) {
            // Plain-text user message — accept feedback (Tab amend) lands here after the
// tool_result block.
            String trimmed = UserMessageStyle.truncateForDisplay(text).strip();
            if (trimmed.isEmpty()) return;
            panel.appendMixed(List.of());
            // Multi-line prompts: each line is a separate MessagePanel row so
            // \n renders as an actual line break. First row carries the "❯ "
            // marker; continuation rows are indented to line up under it.
            String[] userLines = trimmed.split("\n", -1);
            for (int i = 0; i < userLines.length; i++) {
                String prefix = (i == 0) ? "❯ " : "  ";
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(prefix, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment(userLines[i], LanternaTheme.inputText())
                ));
            }
            return;
        }
        String args = XmlTagUtils.extractTag(text, XmlConstants.COMMAND_ARGS_TAG).orElse(null);
        boolean isSkillFormat = Strings.CS.equals("true", XmlTagUtils.extractTag(text, XmlConstants.SKILL_FORMAT_TAG).orElse(null));

        String display;
        if (isSkillFormat) {
            display = "Skill(" + commandMessage + ")";
        } else {
            display = "/" + commandMessage + (StringUtils.isNotBlank(args) ? " " + args : "");
        }

        panel.appendMixed(List.of());
        panel.appendMixed(List.of(
            new MessagePanel.Segment("❯ ", LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(display, LanternaTheme.inputText())
        ));
    }


    private void renderAgentNotification(String text, MessagePanel panel) {
        String summary = XmlTagUtils.extractTag(text, XmlConstants.SUMMARY_TAG).orElse(null);
        if (StringUtils.isBlank(summary)) return;

        String status = XmlTagUtils.extractTag(text, XmlConstants.STATUS_TAG).orElse(null);
        TextColor statusColor = switch (status == null ? "" : status) {
            case "completed" -> LanternaTheme.toolSuccess();
            case "failed" -> LanternaTheme.toolError();
            case "killed" -> LanternaTheme.toolWarning();
            default -> LanternaTheme.inputText();
        };


        panel.appendMixed(List.of());
        panel.appendMixed(List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, statusColor),
            new MessagePanel.Segment(summary.strip(), LanternaTheme.inputText())
        ));
    }


    private void renderBashInput(String text, MessagePanel panel) {
        String cmd = XmlTagUtils.extractTag(text, XmlConstants.BASH_INPUT_TAG).orElse("");
        panel.appendMixed(List.of());  // marginTop=1 equivalent
        panel.appendMixed(List.of(
            new MessagePanel.Segment("! ",  LanternaTheme.bashBorder(), LanternaTheme.bashBg()),
            new MessagePanel.Segment(cmd,   LanternaTheme.inputText(),  LanternaTheme.bashBg()),
            new MessagePanel.Segment(" ",   LanternaTheme.inputText(),  LanternaTheme.bashBg())
        ));
    }

    /**
     * Render a {@code <bash-stdout>…</bash-stdout><bash-stderr>…</bash-stderr>} UserMessage.
     */
    private void renderBashOutput(String text, MessagePanel panel) {
        String stdout = XmlTagUtils.extractTag(text, XmlConstants.BASH_STDOUT_TAG).orElse(null);
        String stderr = XmlTagUtils.extractTag(text, XmlConstants.BASH_STDERR_TAG).orElse(null);
        boolean hasStdout = stdout != null && !stdout.trim().isEmpty();
        boolean hasStderr = stderr != null && !stderr.trim().isEmpty();
        if (!hasStdout && !hasStderr) {
            panel.appendMixed(List.of(
                new MessagePanel.Segment("    (No output)", LanternaTheme.welcomeDim())
            ));
            return;
        }
        if (hasStdout) renderBashOutputBlock(stdout.trim(), panel, TextColor.ANSI.DEFAULT);
        if (hasStderr) renderBashOutputBlock(stderr.trim(), panel, LanternaTheme.toolError());
    }

    private void renderBashOutputBlock(String content, MessagePanel panel, TextColor color) {

        String formatted = ShellOutputFormatter.linkifyUrls(
            ShellOutputFormatter.tryJsonFormatContent(content));
        String normalized = formatted.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) last--;
        for (int i = 0; i < last; i++) {
            panel.appendMixed(List.of(
                new MessagePanel.Segment("    " + lines[i], color)
            ));
        }
    }

    /**
     * Render {@code <local-command-stdout>} / {@code <local-command-stderr>} user message.
     */
    private void renderLocalCommandOutput(String text, MessagePanel panel) {
        String stdout = XmlTagUtils.extractTag(text, XmlConstants.LOCAL_COMMAND_STDOUT_TAG).orElse(null);
        String stderr = XmlTagUtils.extractTag(text, XmlConstants.LOCAL_COMMAND_STDERR_TAG).orElse(null);

        boolean hasContent = (stdout != null && !stdout.trim().isEmpty())
                          || (stderr != null && !stderr.trim().isEmpty());
        if (!hasContent) {

            panel.appendMixed(List.of(
                new MessagePanel.Segment("(no content)", LanternaTheme.welcomeDim())
            ));
            return;
        }

        if (stdout != null && !stdout.trim().isEmpty()) {
            renderIndentedLocalContent(stdout.trim(), panel);
        }
        if (stderr != null && !stderr.trim().isEmpty()) {
            renderIndentedLocalContent(stderr.trim(), panel);
        }
    }

    /**
     * Render a channel message pushed by an MCP server via {@code notifications/claude/channel}.
     */
    private void renderChannelMessage(String text, MessagePanel panel) {
        Matcher m = CHANNEL_RE.matcher(text);
        if (!m.find()) {
            // Unrecognised format — fall through to generic display
            panel.appendMixed(List.of(
                new MessagePanel.Segment(Figures.CHANNEL_ARROW + " ", LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(text.strip(), TextColor.ANSI.DEFAULT)
            ));
            return;
        }
        String source = m.group(1);
        String attrs  = m.group(2) != null ? m.group(2) : "";
        String content = m.group(3) != null ? m.group(3).trim() : "";

        // Extract optional user attribute
        Matcher userM = CHANNEL_USER_ATTR_RE.matcher(attrs);
        String user = userM.find() ? userM.group(1) : null;

        // Plugin servers have names like plugin:slack-channel:slack — show the leaf
        String displayServer = source;
        int colonIdx = source.lastIndexOf(':');
        if (colonIdx != -1) displayServer = source.substring(colonIdx + 1);

        // Collapse whitespace and truncate to ~60 chars for the dim preview
        String body = content.replaceAll("\\s+", " ");
        int truncAt = 60;
        String truncated = FormatUtils.truncate(body, truncAt);

        // Render: "↩ [server] content" or "↩ [server · user] content"
        String label = user != null
            ? Figures.CHANNEL_ARROW + " [" + displayServer + " · " + user + "] "
            : Figures.CHANNEL_ARROW + " [" + displayServer + "] ";
        panel.appendMixed(List.of(
            new MessagePanel.Segment(label, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(truncated, TextColor.ANSI.DEFAULT)
        ));
    }


    private void renderIndentedLocalContent(String content, MessagePanel panel) {
        if (Strings.CS.startsWith(content, Figures.DIAMOND_OPEN + " ")
                || Strings.CS.startsWith(content, Figures.DIAMOND_FILLED + " ")) {
            renderCloudLaunchContent(content, panel);
            return;
        }
        if (renderCurrentPlan(content, panel)) return;

        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        int last = lines.length;
        while (last > 0 && lines[last - 1].isEmpty()) last--;
        for (int i = 0; i < last; i++) {
            String prefix = (i == 0) ? INDENT_PREFIX : INDENT_CONT;
            panel.appendMixed(List.of(
                new MessagePanel.Segment(prefix, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(lines[i], TextColor.ANSI.DEFAULT)
            ));
        }
    }


    private boolean renderCurrentPlan(String content, MessagePanel panel) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String prefix = "Current Plan\n";
        if (!Strings.CS.startsWith(normalized, prefix)) return false;
        int bodySeparator = normalized.indexOf("\n\n", prefix.length());
        if (bodySeparator < 0) return false;
        String path = normalized.substring(prefix.length(), bodySeparator);
        if (StringUtils.isBlank(path) || Strings.CS.contains(path, "\n")) return false;

        String bodyAndHint = normalized.substring(bodySeparator + 2);
        String hintPrefix = "\n\n\"/plan open\" to edit this plan in ";
        int hintAt = bodyAndHint.lastIndexOf(hintPrefix);
        String body = hintAt >= 0 ? bodyAndHint.substring(0, hintAt) : bodyAndHint;
        String editor = hintAt >= 0
            ? bodyAndHint.substring(hintAt + hintPrefix.length()) : null;

        panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("Current Plan", TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD))));
        panel.appendLine(INDENT_CONT + path, LanternaTheme.welcomeDim());
        if (!body.isEmpty()) {
            panel.appendLine("", TextColor.ANSI.DEFAULT);
            String[] bodyLines = body.split("\n", -1);
            for (String line : bodyLines) {
                panel.appendLine(line.isEmpty() ? "" : INDENT_CONT + line,
                    TextColor.ANSI.DEFAULT);
            }
        }
        if (editor != null) {
            panel.appendLine("", TextColor.ANSI.DEFAULT);
            panel.appendMixed(List.of(
                new MessagePanel.Segment(INDENT_CONT + "\"/plan open\" to edit this plan in ",
                    LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(editor, LanternaTheme.welcomeDim(),
                    null, null, Set.of(SGR.BOLD))));
        }
        return true;
    }


    private void renderCloudLaunchContent(String content, MessagePanel panel) {
        // diamond = first char (◆ or ◇); skip "◆ " prefix (slice(2))
        String diamond = content.substring(0, 1);
        String body = content.substring(2);  // after "◆ " or "◇ "

        int nl = body.indexOf('\n');
        String header = nl == -1 ? body : body.substring(0, nl);
        String rest = nl == -1 ? "" : body.substring(nl + 1).trim();


        int sep = header.indexOf(" · ");
        String label = sep == -1 ? header : header.substring(0, sep);
        String suffix = sep == -1 ? "" : header.substring(sep);


        List<MessagePanel.Segment> line1 = new ArrayList<>();
        line1.add(new MessagePanel.Segment(diamond + " ", LanternaTheme.welcomeDim()));
        line1.add(new MessagePanel.Segment(label, LanternaTheme.inputText()));  // bold via theme
        if (!suffix.isEmpty()) {
            line1.add(new MessagePanel.Segment(suffix, LanternaTheme.welcomeDim()));
        }
        panel.appendMixed(line1);

        // Line 2: RESULT_PREFIX dim + rest dim
        if (!rest.isEmpty()) {
            panel.appendMixed(List.of(
                new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(rest, LanternaTheme.welcomeDim())
            ));
        }
    }

    // ──────────────────────────────────────────────────────────────────────

    private void renderAssistant(SDKMessage.Assistant msg, MessagePanel panel, RenderingContext ctx) {
        if (msg.message() == null || msg.message().message() == null) return;
        AssistantContent content = msg.message().message();
        if (content.content() == null) return;

        if (log.isDebugEnabled()) {
            log.debug("[DISPATCHER] renderAssistant: streamedThisTurn={} snapshot={} lines={}",
                streamedThisTurn, streamStartSnapshot, panel.snapshotLineCount());
        }

        int blockIndex = 0;
        int retractionAnchor = -1;
        for (ContentBlock block : content.content()) {
            int currentBlockIndex = blockIndex++;
            String logicalMessageId = msg.message().uuid() + ":" + currentBlockIndex;
            int logicalStart = block instanceof TextBlock && streamedThisTurn
                    && streamStartSnapshot >= 0
                ? streamStartSnapshot : panel.snapshotLineCount();
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
                    if (isRateLimitError(textContent)) {
                        panel.appendMixed(List.of(
                            new MessagePanel.Segment("  ⚠ Rate limit: ", LanternaTheme.toolError()),
                            new MessagePanel.Segment(textContent, LanternaTheme.welcomeDim())
                        ));
                        break;
                    }

                    if (renderSpecialAssistantText(textContent, panel)) break;
                    boolean matchesStreamingProjection = streamedThisTurn
                        && Strings.CS.equals(textContent, streamingMarkdownText.toString());
                    if (!matchesStreamingProjection) {
                        if (streamedThisTurn && streamStartSnapshot >= 0) {
                            log.debug("[DISPATCHER] truncateLinesTo({}) from {} lines",
                                streamStartSnapshot, panel.snapshotLineCount());
                            panel.truncateLinesTo(streamStartSnapshot);
                        }
                        panel.appendMarkdown(textContent, MARKDOWN_RENDERER, true);
                    }
                    // Reset streaming markers so the NEXT round of streaming
                    // (subsequent Assistant messages in the same turn — multistep
                    // tool use loops) starts a fresh snapshot AFTER this rendered
// text. Otherwise, the next truncateLinesTo would roll the
                    // buffer back to the original snapshot and erase this round.
                    closeStreamingWindow();
                }
                case ToolUseBlock toolUse -> {
                    String inputJson = toolUse.input() == null ? "{}" : toolUse.input().toString();
                    if (toolUse.id() != null) {
                        toolInvocations.put(toolUse.id(), new ToolInvocation(
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
                        if (pendingToolExact(toolUse.id()) == null) {
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
                            renderVerboseAgentTranscript(toolUse.id(), null, panel);
                        }
                    }
                }
                case ThinkingBlock thinking -> {
                    if (ctx.isInQueuedPreview() || shouldRenderCompletedThinking(logicalMessageId)) {
                        renderThinking(thinking, panel, ctx);
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
        PendingTool pending = pendingToolExact(toolUse.id());
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
                // (truncateLinesTo(streamTailSnapshot/streamStartSnapshot)) would
                // erase any non-text row drawn after the snapshot — e.g. a tool
                // header appended by tool_streaming_start while the window was open.
                // Providers do emit stray "\n" deltas around tool_use blocks (seen
                // with OpenAI-compatible streams), and the final Assistant commit
                // repaints authoritative text anyway, so dropping these costs
                // nothing. Mid-text newlines (window already open) are unaffected.
                if (!streamedThisTurn && StringUtils.isBlank(evData)) return;
                if (!streamedThisTurn) {
                    if (toolResultRenderedThisTurn) {
                        panel.appendLine("", TextColor.ANSI.DEFAULT);
                        toolResultRenderedThisTurn = false;
                    }
                    streamStartSnapshot = panel.snapshotLineCount();
                    log.debug("[DISPATCHER] content_block_delta first: took snapshot={} lines", streamStartSnapshot);
                    streamedThisTurn = true;
                }
                // 197 accumulates streamingText on every text_delta, but a tool_use
                // content_block_start clears it — and while that tool is unresolved no
                // further text can stream (the next assistant message only comes after
                // the tool result, i.e. once pendingTools is empty again). Some
                // providers emit straggler text deltas (a trailing newline, a late
                // batched flush) AFTER tool_streaming_start; treating those as visible
                // streaming text would hide the spinner for the whole tool execution.
                setStreamTextVisible(pendingTools.isEmpty());
                streamingMarkdownText.append(evData);
                renderStreamingMarkdown(panel);
            }
            case "content_block_stop" -> { /* final Assistant event commits the projection */ }
            case "tool_streaming_start" -> {
                // Stage 1 (QUEUED): tool_use block arrived in stream — show dim static dot.
                //
                // 197 clears streamingText on EVERY content_block_start (2.1.197 bundle:
                // a?.(()=>null) ahead of the content_block.type dispatch), so a tool_use
                // block start ends the visible-streaming-text phase — the spinner comes
                // back for the whole tool-input/streaming + execution window, blocking
                // Bash included. The rollback window (streamedThisTurn) deliberately
                // stays open until the tool RESULT commits; only the spinner-facing
                // visibility closes here.
                setStreamTextVisible(false);
                String[] startParts = evData.split("\\|", 3);
                String toolName = startParts[0];
                String toolUseId = startParts.length > 1 ? startParts[1] : null;
                String groupMessageId = startParts.length > 2 ? startParts[2] : null;
                toolResultRenderedThisTurn = false;
                if (ToolVisualContractRegistry.hidesUse(toolName)) {
                    pendingTools.addLast(new PendingTool(-1, false, "", toolName, -1,
                        "tool:" + (++logicalToolSequence), toolUseId, groupMessageId));
                    break;
                }
                if (Boolean.TRUE.equals(transparentWrapperLookup.apply(toolName))) {
                    // Transparent wrapper: suppress header. Push a sentinel so downstream
                    // tool_call_start / tool_result_* events know to skip this slot.
                    pendingTools.addLast(new PendingTool(-1, true, "", toolName, -1,
                        "tool:" + (++logicalToolSequence), toolUseId, groupMessageId));
                    break;
                }
                if (streamedThisTurn || toolEmittedThisTurn || textRenderedThisTurn || !pendingTools.isEmpty()) {
                    panel.appendLine("", TextColor.ANSI.DEFAULT);
                }
                List<MessagePanel.Segment> dimSegs = dimToolSegs(toolName);
                int lineIdx = panel.snapshotLineCount();
                panel.appendMixed(dimSegs);
                pendingTools.addLast(new PendingTool(lineIdx, false, "", toolName, -1,
                    "tool:" + (++logicalToolSequence), toolUseId, groupMessageId));
                toolEmittedThisTurn = true;
            }
            case "tool_streaming_done" -> {
                // Stage 1b: full input now known — update arg summary in the dim line.
                String[] parts = evData.split("\\|", 3);
                String toolName = parts[0];
                String eventToolUseId = parts.length >= 2 ? parts[1] : null;
                // Skip transparent wrapper slots.
                PendingTool pending = pendingToolExact(eventToolUseId);
                if (pending != null && pending.transparent()) {
                    break;
                }
                String argsJson = parts.length >= 3 ? parts[2] : "";
                String argsPart = toolArgsPart(toolName, argsJson);
                String tag = toolTagPart(toolName, argsJson, eventToolUseId);
                if (pending != null) {
                    ToolVisualContractRegistry.UseView useView =
                        ToolVisualContractRegistry.useView(toolName, argsJson, verbose);
                    int lineIdx = pending.lineIdx();
                    if (lineIdx < 0 && !useView.hidden()) {
                        if (streamedThisTurn || toolEmittedThisTurn || textRenderedThisTurn
                                || pendingTools.size() > 1) {
                            panel.appendLine("", TextColor.ANSI.DEFAULT);
                        }
                        lineIdx = panel.snapshotLineCount();
                        panel.appendMixed(dimToolSegs(toolName, argsPart, tag, argsJson));
                        toolEmittedThisTurn = true;
                    }
                    // Store input JSON for renderToolUseTag/result time and any newly revealed line.
                    PendingTool updated = pending.withInputJson(argsJson).withLineIdx(lineIdx);
                    replacePendingTool(pending, updated);
                    if (lineIdx >= 0) {
                        panel.updateLine(lineIdx, dimToolSegs(toolName, argsPart, tag, argsJson));
                    }
                }
                if (eventToolUseId != null) {
                    toolInvocations.put(eventToolUseId, new ToolInvocation(toolName, argsJson));
                }
                if (Strings.CS.equals("Agent", toolName)) {
                    maybeCreateAgentGroup(panel);
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
                PendingTool first = pendingTool(eventToolUseId);
                if (first != null && first.transparent()) {
                    break;
                }
                String callArgsJson = parts.length >= 3 ? parts[2] : "";
                if ((first != null && first.lineIdx() < 0)
                        || (first == null && ToolVisualContractRegistry
                            .useView(toolName, callArgsJson, verbose).hidden())) {
                    if (first == null) {
                        pendingTools.addLast(new PendingTool(-1, false, callArgsJson, toolName, -1,
                            "tool:" + (++logicalToolSequence), eventToolUseId, null));
                    }
                    break;
                }
                if (eventToolUseId != null && agentGroupsByToolUseId.containsKey(eventToolUseId)) {
                    renderAgentGroup(agentGroupsByToolUseId.get(eventToolUseId), panel);
                    break;
                }
                String argsPart = parts.length >= 3 ? toolArgsPart(toolName, parts[2]) : "";
                String tag = toolTagPart(toolName, callArgsJson, eventToolUseId);
                List<MessagePanel.Segment> dimSegs =
                    dimToolSegs(toolName, argsPart, tag, callArgsJson);
                if (first != null) {
                    panel.startBlinkLine(first.lineIdx(), dimSegs);
                } else {
                    // Fallback (no streaming phase): render dim + start blinking
                    int lineIdx = panel.snapshotLineCount();
                    panel.appendMixed(dimSegs);
                    pendingTools.addLast(new PendingTool(lineIdx, false, callArgsJson, toolName, -1,
                        "tool:" + (++logicalToolSequence), null, null));
                    panel.startBlinkLine(lineIdx, dimSegs);
                }
                PendingTool active = pendingTools.peekFirst();
                if (eventToolUseId != null) active = pendingTool(eventToolUseId);
                if (Strings.CS.equals("Agent", toolName) && active != null
                        && active.toolUseId() != null
                        && !agentProgressBlocks.containsKey(active.toolUseId())) {
                    int progressStart = panel.snapshotLineCount();
                    panel.appendLine("  ⎿  Initializing…", LanternaTheme.welcomeDim());
                    agentProgressBlocks.put(active.toolUseId(), new AgentProgressBlock(progressStart));
                    reflowAgentProgressBlocks(panel);
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
                PendingTool pending = pendingTools.pollFirst();
                if (pending != null && pending.transparent()) {
                    // Transparent wrapper: discard sentinel, do not render.
                    break;
                }
                if (pending != null && pending.toolUseId() != null) {
                    AgentGroupBlock group = agentGroupsByToolUseId.get(pending.toolUseId());
                    if (group != null) {
                        AgentGroupMember member = agentGroupMember(group, pending.toolUseId());
                        if (member != null) {
                            member.resolved = true;
                            member.error = isError;
                            member.async = member.launchedAsync;
                        }
                        renderAgentGroup(group, panel);
                        toolResultRenderedThisTurn = true;
                        // Result committed as rows — recompute the streaming baseline
                        // after it, never roll back over it.
                        if (streamedThisTurn) closeStreamingWindow();
                        break;
                    }
                }
                if (pending != null && pending.toolUseId() != null) {
                    removeAgentProgressBlock(pending.toolUseId(), panel);
                }
                ToolVisualContractRegistry.ResultMode resultMode =
                    ToolVisualContractRegistry.resultMode(toolName);
                if (!isError && resultMode != ToolVisualContractRegistry.ResultMode.DEFAULT) {
                    completePendingToolHeader(pending, LanternaTheme.toolSuccess(), panel);
                    toolResultRenderedThisTurn = true;
                    if (streamedThisTurn) closeStreamingWindow();
                    break;
                }
                // Hoist the nullable fields once; below we only touch these scalars,
                // so a replay-path null pending needs no repeated `pending != null`.
                String inputJson = pending != null ? pending.inputJson() : null;
                Integer pendingIdx = pending != null ? pending.lineIdx() : null;
                String logicalMessageId = pending != null ? pending.logicalMessageId()
                    : "tool:" + (++logicalToolSequence);
                TextColor dotColor = isError ? LanternaTheme.toolError() : LanternaTheme.toolSuccess();
                String tag = toolTagPart(toolName, inputJson != null ? inputJson : "",
                    pending != null ? pending.toolUseId() : null);

                // renderToolUseMessage visible after the tool resolves.
                String argsPart = toolArgsPart(toolName, inputJson);
                List<MessagePanel.Segment> doneSegs = buildDoneSegs(
                    toolName, dotColor, tag, argsPart, inputJson);
                if (pendingIdx != null) {
                    panel.stopBlinkLine(pendingIdx, doneSegs);
                } else {
                    // Replay/fallback: no blink state — render directly.
// match the blank-line separator that tool_streaming_start normally adds.
                    if (streamedThisTurn) {
                        panel.appendLine("", TextColor.ANSI.DEFAULT);
                    }
                    panel.appendMixed(doneSegs);
                }
                if (!StringUtils.isBlank(resultText)) {
                    renderToolResultText(resultText, isError, panel);
                }
                PrimaryInput primary = extractPrimaryInput(toolName, inputJson);
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
                if (streamedThisTurn) closeStreamingWindow();
            }
            case "hook_call_start" -> {
                // evData = "toolName|PreToolUse" or "toolName|PostToolUse"
                String hookEvent = Strings.CS.contains(evData, "|") ? evData.substring(evData.indexOf('|') + 1) : "hook";
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Running " + hookEvent + " hook…", LanternaTheme.welcomeDim())
                ));
            }
            case "hook_call_done" -> {
                // Hook progress line stays as dim historical record
            }
            case "permission_waiting" -> { // evData = toolName
                PendingTool pending = pendingTools.peekFirst();
                if (pending == null || pending.transparent() || pending.lineIdx() < 0) break;
                List<MessagePanel.Segment> waiting = List.of(
                    new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Waiting…", LanternaTheme.welcomeDim()));
                if (pending.statusLineIdx() >= 0) {
                    panel.updateLine(pending.statusLineIdx(), waiting);
                } else {
                    int statusLineIdx = panel.snapshotLineCount();
                    panel.appendMixed(waiting);
                    pendingTools.removeFirst();
                    pendingTools.addFirst(pending.withStatusLineIdx(statusLineIdx));
                }
            }
            case "permission_resolved", "permission_denied" -> {
                // State transitions — no additional line needed; tool_result_* will follow
            }
        }
    }

/**
     * Builds dim (queued/in-progress) tool call segments — no args yet (stage 1: tool_streaming_start).
     */
    private List<MessagePanel.Segment> dimToolSegs(String toolName) {
        return dimToolSegs(toolName, "", "");
    }

    /** Builds dim tool call segments with an optional tag (e.g. task ID). */
    private List<MessagePanel.Segment> dimToolSegs(String toolName, String argsPart, String tag) {
        return dimToolSegs(toolName, argsPart, tag, "");
    }

    private List<MessagePanel.Segment> dimToolSegs(String toolName, String argsPart,
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
    private List<MessagePanel.Segment> buildDoneSegs(String toolName, TextColor dotColor,
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

    private MessagePanel.Segment toolNameSegment(String toolName, String inputJson,
                                                 TextColor fallback) {
        String visibleName = ToolVisualContractRegistry
            .useView(toolName, inputJson, verbose).displayName();
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
     * Renders tool result text with ⎿ prefix, matching the renderToolResult format.
     */
    private void renderToolResultText(String text, boolean isError, MessagePanel panel) {
        String prefixedPlan = isError ? planFromRejectionResult(text) : null;
        if (prefixedPlan != null) {
            panel.appendMixed(List.of(new MessagePanel.Segment(
                INDENT_PREFIX + "User rejected Claude's plan:", LanternaTheme.welcomeDim())));
            panel.appendMarkdown(prefixedPlan, MARKDOWN_RENDERER, false);
            return;
        }
        // UserToolResultMessage catches REJECT_MESSAGE before its generic error
        // branch and delegates to the tool's rejection renderer. Bash has no
        // custom renderer, so FallbackToolUseRejectedMessage paints InterruptedByUser.
        if (isError) {
            if (Strings.CS.contains(text, MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE)
                    || Strings.CS.startsWith(text, MessageConstants.REJECT_MESSAGE)) {
                renderInterruptedToolResult(panel);
                return;
            }
            if (MessageConstants.isClassifierDenial(text)) {
                renderClassifierDenial(panel);
                return;
            }
            text = fallbackToolErrorText(text);
        } else if (Strings.CS.equals(text, "(Bash completed with no output)")) {
            // BashToolResultMessage uses structured noOutputExpected to render
            // "Done" while preserving the longer model-facing placeholder.
            text = "Done";
        }

        // Keep the complete formatted value in MessagePanel. Its physical rows are
        // projected from the current terminal width, so a single huge JSONL record
        // cannot bypass folding merely because it contains no newline.
        String formatted = ShellOutputFormatter.tryJsonFormatContent(text);
        boolean showAll = verbose || transcriptMode;
        TextColor color = isError ? LanternaTheme.toolError() : LanternaTheme.welcomeDim();
        panel.appendToolOutput(formatted, color, showAll);
    }

    /**
     * Extracts a concise display summary from a raw JSON string.
     * Looks for the first short string value as the primary argument.
     */
    private static final int SUMMARY_MAX_LEN = 100;

    private record PrimaryInput(String label, String value) {}


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

    private static PrimaryInput extractPrimaryInput(String toolName, String json) {
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

    private static String summarizeInputJson(String toolName, String json) {
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

    private String toolArgsPart(String toolName, String inputJson) {
        ToolVisualContractRegistry.UseView view =
            ToolVisualContractRegistry.useView(toolName, inputJson, verbose);
        if (view.argumentText() != null) return view.argsPart();
        return argsPart(toolName, inputJson);
    }

    private String toolTagPart(String toolName, String inputJson, String toolUseId) {
        List<ProgressMessage> progress = toolUseId == null ? List.of()
            : toolProgressByToolUseId.getOrDefault(toolUseId,
                transcriptAgentProgress.getOrDefault(toolUseId, List.of()));
        Optional<ToolUseTag> external = toolTagLookup.resolve(new ToolTagRequest(
            toolName, inputJson == null ? "" : inputJson, toolUseId,
            toolUseId == null ? null : toolResultsByToolUseId.get(toolUseId), progress));
        return external.map(tag -> " " + tag.text()).orElseGet(
          () -> ToolVisualContractRegistry.useView(toolName, inputJson, verbose).tagPart());
    }

    private static String argsPart(String toolName, String inputJson) {
        String summary = summarizeInputJson(toolName, inputJson);
        return StringUtils.isBlank(summary) ? "" : "(" + summary + ")";
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
        } catch (RuntimeException _) {
            // Fall through to the original path at the UI boundary.
        }
        return filePath;
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

    private void renderSystem(SDKMessage.System msg, MessagePanel panel) {
        if (msg.message() == null) return;
        SystemMessage message = msg.message();
        String subtype = message.subtype() != null ? message.subtype() : "";
        if (Strings.CS.equals("turn_duration", subtype) && message.durationMs() != null) {
            Integer pendingAgents = message.pendingBackgroundAgentCount();
            Integer pendingWorkflows = message.pendingWorkflowCount();
            PendingBackgroundWork current = currentPendingBackgroundWork();
            if (current != null) {
                if (current.pendingAgents() <= 0) pendingAgents = null;
                if (current.pendingWorkflows() <= 0) pendingWorkflows = null;
            }
            renderTurnSummary(panel, message.durationMs(), pendingAgents, pendingWorkflows,
                currentBackgroundTaskSummary(),
                message.budgetTokens(), message.budgetLimit(), message.budgetNudges(),
                message.briefHiddenCount());
            return;
        }
        if (message.content() == null) return;
        String content = message.content();
        String level = message.level() != null ? message.level() : "info";

        // Suppress session-init metadata noise:
        if (Strings.CS.startsWith(content, "Session:") || Strings.CS.contains(content, "| Tools:")) {
            return;
        }
        if (Strings.CS.equals("api_metrics", subtype)) {
            return;
        }

        if (Strings.CS.equals("thinking", subtype)) {
            return;
        }
// A refusal the CLI could not retry on a fallback model is silent.
        if (Strings.CS.equals("model_refusal_no_fallback", subtype)) {
            return;
        }

        // if (!isStopHookSummary && !verbose && message.level === "info") return null;
        // Exception: local_command carries user-visible command echo + result tags

        //  bypassing SystemTextMessage's level filter entirely).
        if (!Strings.CS.equals("stop_hook_summary", subtype) && !Strings.CS.equals("turn_duration", subtype)
                && !Strings.CS.equals("memory_saved", subtype) && !Strings.CS.equals("api_error", subtype)
                && !Strings.CS.equals("compact_boundary", subtype) && !Strings.CS.equals("local_command", subtype)
                && !Strings.CS.equals("scheduled_task_fire", subtype)
                && !verbose && Strings.CS.equals("info", level)) {
            return;
        }

        switch (subtype) {
            case "api_error" -> {
                String truncated = content.length() > 1000
                    ? FormatUtils.truncate(content, 1000) : content;
                panel.appendLine("✗ " + truncated, LanternaTheme.toolError());
            }

            case "memory_saved" ->
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Saved " + content, LanternaTheme.inputText())
                ));


            case "permission_retry" ->
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(Figures.TEARDROP_ASTERISK + " ", LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment("Allowed ", TextColor.ANSI.DEFAULT),
                    new MessagePanel.Segment(content, LanternaTheme.inputText())
                ));

            case "bridge_status" ->

                panel.appendLine("ℹ " + content, LanternaTheme.agentCyan());

            case "stop_hook_summary" ->

                panel.appendMixed(List.of(
                    new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment(content, LanternaTheme.welcomeDim())
                ));

            case "turn_duration" ->
                panel.appendLine(
                    Figures.TEARDROP_ASTERISK + " " + SpinnerVerbs.randomCompleted() + " for " + content,
                    LanternaTheme.welcomeDim());

            case "away_summary" ->

                panel.appendMixed(List.of(
                    new MessagePanel.Segment(Figures.REFERENCE_MARK + " ", LanternaTheme.welcomeDim()),
                    new MessagePanel.Segment(content, LanternaTheme.welcomeDim())
                ));

            case "scheduled_task_fire" -> {

                if (panel.snapshotLineCount() > 0) {
                    panel.appendLine("", TextColor.ANSI.DEFAULT);
                }
                panel.appendLine(Figures.TEARDROP_ASTERISK + " " + content, LanternaTheme.welcomeDim());
            }

            case "informational" -> {
// matches SystemTextMessageInner. Verbose info has no dot and is
                // dim; notice/warning rows have a BLACK_CIRCLE gutter. Only the
                // warning level applies warning color — notice uses terminal text.
                if (Strings.CS.equals("info", level)) {
                    panel.appendLine(content, LanternaTheme.welcomeDim(), 10);
                } else {
                    TextColor color = Strings.CS.equals("warning", level)
                        ? LanternaTheme.statusCost() : TextColor.ANSI.DEFAULT;
                    panel.appendMixed(List.of(
                        new MessagePanel.Segment(BLACK_CIRCLE, color),
                        new MessagePanel.Segment(content, color)
                    ), 10);
                }
            }

            case "agents_killed" ->
                panel.appendLine("⚠ " + content, LanternaTheme.modeAuto());

            case "microcompact_boundary" ->
                panel.appendLine("  ─── microcompact ───", LanternaTheme.welcomeDim());

            case "compact_boundary" ->

                panel.appendLine("  ✻ Conversation compacted (ctrl+o for history)",
                    LanternaTheme.welcomeDim());

// local_command system messages carry one of: (1) <command-name>...
            case "local_command" -> renderUserTextBlock(content, panel);

            // The announcement that a refused turn was retried on a fallback
            // model. Two rows: a bold warning banner and a dim tip pointing at
            // /config. The banner survives the retraction filter that drops the
            // messages it took back (see RetractedMessages).
            case "model_refusal_fallback" -> {
                if (RefusalFallbackFeature.enabled()) {
                    List<MessagePanel.Segment> banner = new ArrayList<>();
                    banner.add(new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.statusCost()));
                    banner.addAll(refusalBodySegments(content));
                    panel.appendMixed(banner);
                    panel.appendLine("  " + Figures.RESULT_BRANCH
                            + "  Tip: You can configure model switch behavior in /config",
                        LanternaTheme.welcomeDim());
                }
            }

            default -> {

                boolean isWarning = Strings.CS.equals("warning", level);
                TextColor msgColor = isWarning ? LanternaTheme.statusCost() : LanternaTheme.toolError();
                panel.appendMixed(List.of(
                    new MessagePanel.Segment(BLACK_CIRCLE, msgColor),
                    new MessagePanel.Segment(content, msgColor)
                ), 10);
            }
        }
    }

    private PendingBackgroundWork currentPendingBackgroundWork() {
        Supplier<PendingBackgroundWork> supplier = pendingBackgroundWorkSupplier;
        if (supplier == null) return null;
        try {
            return supplier.get();
        } catch (RuntimeException failure) {
            log.debug("Failed to sample pending background work", failure);
            return PendingBackgroundWork.NONE;
        }
    }

    private String currentBackgroundTaskSummary() {
        Supplier<String> supplier = backgroundTaskSummarySupplier;
        if (supplier == null) return null;
        try {
            return supplier.get();
        } catch (RuntimeException failure) {
            log.debug("Failed to sample background task summary", failure);
            return null;
        }
    }

    /**
     * Splits the trailing {@code learn more: <url>} run out of a refusal
     * announcement body into an underlined hyperlink whose visible label is just
     * {@code learn more}. Falls back to a single bold run when the terminal has
     * no hyperlink support or the marker is absent — see
     * {@link RefusalLearnMoreLink}, which the pause dialog shares.
     */
    private List<MessagePanel.Segment> refusalBodySegments(String content) {
        RefusalLearnMoreLink.Split split =
            RefusalLearnMoreLink.split(content, hyperlinkSupport.getAsBoolean());
        if (!split.linked()) {
            return List.of(new MessagePanel.Segment(content, LanternaTheme.statusCost(),
                null, null, Set.of(SGR.BOLD)));
        }
        List<MessagePanel.Segment> segments = new ArrayList<>(3);
        if (!split.head().isEmpty()) {
            segments.add(new MessagePanel.Segment(split.head(), LanternaTheme.statusCost(),
                null, null, Set.of(SGR.BOLD)));
        }
        segments.add(MessagePanel.Segment.hyperlink(RefusalLearnMoreLink.LINK_TEXT,
            LanternaTheme.statusCost(), split.url(), Set.of(SGR.BOLD, SGR.UNDERLINE)));
        if (!split.tail().isEmpty()) {
            segments.add(new MessagePanel.Segment(split.tail(), LanternaTheme.statusCost(),
                null, null, Set.of(SGR.BOLD)));
        }
        return segments;
    }

    /** Test seam for {@link #hyperlinkSupport}; production keeps the environment probe. */
    void setHyperlinkSupport(BooleanSupplier support) {
        this.hyperlinkSupport = support;
    }

    private void renderError(SDKMessage.Error msg, MessagePanel panel) {


        // it never produces an Error message, it just resets loading state.
        if (msg.exception() instanceof AbortException) {
            return;
        }
        String friendly = msg.exception() instanceof FriendlyApiError fae ? fae.friendlyMessage() : null;
        String text = friendly != null
            ? "✗ " + friendly
            : msg.exception() != null
                ? "✗ Error: " + msg.exception().getMessage()
                : "✗ Unknown error";
        panel.appendLine(text, LanternaTheme.toolError());
    }

    /**
     * Turn-summary line that also reports what the turn is still waiting on.
     */
    public void renderTurnSummary(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary) {
        renderTurnSummary(panel, elapsedMs, pendingAgentCount, pendingWorkflowCount,
            backgroundTaskSummary, null, null, null, null);
    }

    public void renderTurnSummary(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary,
                                  Long budgetTokens, Long budgetLimit,
                                  Integer budgetNudges, Integer briefHiddenCount) {
        renderTurnSummaryWithVisibility(panel, elapsedMs, pendingAgentCount,
            pendingWorkflowCount, backgroundTaskSummary, budgetTokens, budgetLimit,
            budgetNudges, briefHiddenCount,
            UiSettings.readGlobalBoolean("showTurnDuration", true));
    }

    void renderTurnSummaryWithVisibility(MessagePanel panel, long elapsedMs,
                                  Integer pendingAgentCount, Integer pendingWorkflowCount,
                                  String backgroundTaskSummary,
                                  Long budgetTokens, Long budgetLimit,
                                  Integer budgetNudges, Integer briefHiddenCount,
                                  boolean showDuration) {
        boolean hasBudget = budgetLimit != null;
        int hiddenCount = briefHiddenCount == null ? 0 : briefHiddenCount;
        if (!showDuration && !hasBudget && hiddenCount <= 0) return;
        int agents    = pendingAgentCount    == null ? 0 : pendingAgentCount;
        int workflows = pendingWorkflowCount == null ? 0 : pendingWorkflowCount;
        boolean waiting = agents > 0 || workflows > 0;
        TextColor dim = LanternaTheme.welcomeDim();
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment("✻ ", dim));
        if (showDuration && waiting) {
            line.add(new MessagePanel.Segment("Waiting for", dim));
            if (agents > 0) {
                line.add(new MessagePanel.Segment(" ", dim));
                line.add(new MessagePanel.Segment(String.valueOf(agents), dim, null, null,
                    Set.of(SGR.BOLD)));
                line.add(new MessagePanel.Segment(
                    agents == 1 ? " background agent" : " background agents", dim));
            }
            if (agents > 0 && workflows > 0) {
                line.add(new MessagePanel.Segment(" and", dim));
            }
            if (workflows > 0) {
                line.add(new MessagePanel.Segment(" ", dim));
                line.add(new MessagePanel.Segment(String.valueOf(workflows), dim, null, null,
                    Set.of(SGR.BOLD)));
                line.add(new MessagePanel.Segment(
                    workflows == 1 ? " dynamic workflow" : " dynamic workflows", dim));
            }
            line.add(new MessagePanel.Segment(" to finish", dim));
        } else if (showDuration) {
            String verb    = SpinnerVerbs.randomCompleted();
            String elapsed = FormatUtils.formatDuration(elapsedMs);
            line.add(new MessagePanel.Segment(verb + " for " + elapsed, dim));
        }
        if (hasBudget) {
            long tokens = budgetTokens == null ? 0L : budgetTokens;
            long limit = budgetLimit;
            String usage = tokens >= limit
                ? FormatUtils.formatNumber(tokens) + " used ("
                    + FormatUtils.formatNumber(limit) + " min ✔)"
                : FormatUtils.formatNumber(tokens) + " / "
                    + FormatUtils.formatNumber(limit) + " ("
                    + Math.round(limit == 0L ? 0D : tokens * 100D / limit) + "%)";
            String separator = showDuration ? " · " : "";
            line.add(new MessagePanel.Segment(separator + usage, dim));
            int nudges = budgetNudges == null ? 0 : budgetNudges;
            if (nudges > 0) {
                line.add(new MessagePanel.Segment(" · " + nudges + " "
                    + (nudges == 1 ? "nudge" : "nudges"), dim));
            }
        }
        if (hiddenCount > 0) {
            String separator = showDuration || hasBudget ? " · " : "";
            line.add(new MessagePanel.Segment(separator + hiddenCount + " "
                + (hiddenCount == 1 ? "message" : "messages")
                + " hidden (/focus to show)", dim));
        }
        if (!(showDuration && waiting) && StringUtils.isNotBlank(backgroundTaskSummary)) {
            line.add(new MessagePanel.Segment(" · " + backgroundTaskSummary + " still running", dim));
        }
        panel.appendLine("", dim);                                                        // spacer
        panel.appendMixed(line);
    }

    /**
     * Renders a progress message inline in the message panel.
     */
    private void renderProgress(SDKMessage.Progress msg, MessagePanel panel) {
        if (msg.message() == null) return;
        String progressToolUseId = msg.message().toolUseId();
        if (progressToolUseId != null) {
            toolProgressByToolUseId.computeIfAbsent(progressToolUseId, _ -> new ArrayList<>())
                .add(msg.message());
            PendingTool pending = pendingTool(progressToolUseId);
            if (pending != null && !pending.transparent() && pending.lineIdx() >= 0) {
                String argsPart = toolArgsPart(pending.toolName(), pending.inputJson());
                String tag = toolTagPart(
                    pending.toolName(), pending.inputJson(), progressToolUseId);
                panel.updateLine(pending.lineIdx(), dimToolSegs(
                    pending.toolName(), argsPart, tag, pending.inputJson()));
            }
        }
        ProgressMessage.ProgressData data = msg.message().data();
        ToolInvocation invocation = msg.message().toolUseId() == null ? null
            : toolInvocations.get(msg.message().toolUseId());
        if (data != null && Strings.CS.equals("waiting_for_task", data.type())
                && invocation != null
                && ToolVisualContractRegistry.resultMode(invocation.toolName())
                    == ToolVisualContractRegistry.ResultMode.TASK_OUTPUT) {
            String content = msg.message().content();
            String description = content == null ? ""
                : Strings.CS.removeStart(content, "Waiting for task ").strip();
            if (!StringUtils.isBlank(description)) {
                panel.appendLine("  " + description, TextColor.ANSI.DEFAULT);
            }
            panel.appendMixed(List.of(
                new MessagePanel.Segment("     Waiting for task ", TextColor.ANSI.DEFAULT),
                new MessagePanel.Segment("(esc to give additional instructions)",
                    LanternaTheme.welcomeDim())));
            return;
        }
        if (data != null && Strings.CS.equals("agent_progress", data.type())) {
            renderAgentProgress(msg.message().toolUseId(), data, panel);
            return;
        }
        if (data != null && Strings.CS.equals("mcp_progress", data.type())) {
            renderMcpProgress(data, panel);
            return;
        }
        if (data != null && Strings.CS.equalsAny(data.type(),
                "query_update", "search_results_received")) {
            renderWebSearchProgress(data, panel);
            return;
        }
        if (data != null && data.type() != null) {
            // Structured progress — build a ShellProgressMessage-like summary.
            StringBuilder sb = new StringBuilder("⟳ ");
            if (data.totalLines() != null && data.totalLines() > 0) {
                sb.append(data.totalLines()).append(" lines");
            }
            if (data.totalBytes() != null && data.totalBytes() > 0) {
                if (sb.length() > 2) sb.append(" · ");
                sb.append(FormatUtils.formatFileSize(data.totalBytes()));
            }
            if (data.elapsedTimeSeconds() != null && data.elapsedTimeSeconds() > 0) {
                if (sb.length() > 2) sb.append(" · ");
                sb.append(data.elapsedTimeSeconds().longValue()).append("s");
            }
            if (Boolean.FALSE.equals(data.isIncomplete())) {
                sb.append(" ✓");
            }
            panel.appendLine(sb.toString(), LanternaTheme.agentCyan());
        } else if (msg.message().content() != null) {
            panel.appendLine("⟳ " + msg.message().content(), LanternaTheme.agentCyan());
        }
    }

    private void renderMcpProgress(ProgressMessage.ProgressData data, MessagePanel panel) {
        Double progress = data.progress();
        Double total = data.total();
        String message = data.progressMessage();
        if (progress == null) {
            appendDimResult(panel, -1, "Running…");
            return;
        }
        if (total != null && total > 0) {
            double ratio = Math.max(0.0, Math.min(1.0, progress / total));
            int percentage = (int) Math.round(ratio * 100);
            if (StringUtils.isNotBlank(message)) appendDimResult(panel, -1, message);
            int filled = (int) Math.round(ratio * 20);
            String bar = "█".repeat(filled) + "░".repeat(Math.max(0, 20 - filled));
            appendDimResult(panel, -1, bar + " " + percentage + "%");
            return;
        }
        appendDimResult(panel, -1, StringUtils.isBlank(message)
            ? "Processing… " + formatProgressNumber(progress) : message);
    }

    private static String formatProgressNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private void renderWebSearchProgress(ProgressMessage.ProgressData data, MessagePanel panel) {
        String query = data.query() == null ? "" : data.query();
        if (Strings.CS.equals("query_update", data.type())) {
            appendDimResult(panel, -1, "Searching: " + query);
            return;
        }
        long count = data.resultCount() == null ? 0L : data.resultCount();
        appendDimResult(panel, -1, "Found " + count + " results for \"" + query + "\"");
    }

    private void renderAgentProgress(String toolUseId, ProgressMessage.ProgressData data,
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
        if (transcriptMode) return;
        String activity = agentActivity(data.message(), block);
        if (activity != null) block.activity.add(activity);

        if (created) reflowAgentProgressBlocks(panel);
        else replaceAgentProgressRows(block, agentProgressRows(block, panel), panel);
    }

    private PendingTool pendingTool(String toolUseId) {
        if (toolUseId == null) return pendingTools.peekFirst();
        return pendingTools.stream()
            .filter(tool -> Strings.CS.equals(toolUseId, tool.toolUseId()))
            .findFirst().orElse(pendingTools.peekFirst());
    }

    private PendingTool pendingToolExact(String toolUseId) {
        if (toolUseId == null) return pendingTools.peekLast();
        PendingTool exact = pendingTools.stream()
            .filter(tool -> Strings.CS.equals(toolUseId, tool.toolUseId()))
            .findFirst().orElse(null);
        if (exact != null) return exact;
        List<PendingTool> legacyWithoutIds = pendingTools.stream()
            .filter(tool -> tool.toolUseId() == null)
            .toList();
        return legacyWithoutIds.size() == 1 ? legacyWithoutIds.getFirst() : null;
    }

    private void replacePendingTool(PendingTool expected, PendingTool replacement) {
        List<PendingTool> reordered = pendingTools.stream()
            .map(tool -> tool == expected ? replacement : tool)
            .toList();
        pendingTools.clear();
        pendingTools.addAll(reordered);
    }


    /**
     * Renders the background affordance inside its owning tool card.
     *
     * @return {@code false} when this tool use has no card of its own (a plain Bash call, or an
     *     Agent folded into a group), so the caller can fall back to the status line instead of
     *     silently dropping the affordance.
     */
    public boolean showAgentBackgroundHint(String toolUseId, MessagePanel panel) {
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
    public void clearAgentProgress(String toolUseId, MessagePanel panel) {
        AgentGroupBlock group = agentGroupsByToolUseId.get(toolUseId);
        if (group != null) {
            AgentGroupMember member = agentGroupMember(group, toolUseId);
            if (member != null) member.resolved = true;
            pendingTools.removeIf(tool -> Strings.CS.equals(toolUseId, tool.toolUseId()));
            renderAgentGroup(group, panel);
            return;
        }
        removeAgentProgressBlock(toolUseId, panel);
    }

    private void maybeCreateAgentGroup(MessagePanel panel) {
        if (verbose || transcriptMode) return;
        List<PendingTool> pending = new ArrayList<>(pendingTools);
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
            panel.stopBlinkLine(tool.lineIdx(), dimToolSegs("Agent", "", "", tool.inputJson()));
            var input = parseJsonObject(tool.inputJson());
            String type = ToolVisualContractRegistry
                .useView("Agent", tool.inputJson(), verbose).displayName();
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

        List<PendingTool> reanchored = pendingTools.stream().map(tool -> {
            boolean grouped = candidates.stream().anyMatch(candidate ->
                Strings.CS.equals(candidate.toolUseId(), tool.toolUseId()));
            if (grouped) return tool.withLineIdx(start);
            return tool.shiftedAfter(groupEnd, delta);
        }).toList();
        pendingTools.clear();
        pendingTools.addAll(reanchored);
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

    private boolean renderGroupedAgentResult(Object payload, ToolResultBlock result,
                                             MessagePanel panel) {
        AgentGroupBlock group = agentGroupsByToolUseId.get(result.toolUseId());
        if (group == null) return false;
        AgentGroupMember member = agentGroupMember(group, result.toolUseId());
        if (member == null) return false;

        pendingTools.removeIf(tool -> Strings.CS.equals(result.toolUseId(), tool.toolUseId()));
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
        toolResultRenderedThisTurn = true;
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
        shiftPendingToolLines(group.start + oldCount, delta);
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
                header.add(new MessagePanel.Segment(" " + expandHint(),
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
            header.add(new MessagePanel.Segment(expandHint(), LanternaTheme.welcomeDim()));
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

    private static JsonNode parseJsonObject(String json) {
        try {
            return JsonUtils.getMapper().readTree(json);
        } catch (Exception _) {
            return JsonUtils.getMapper().createObjectNode();
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
                    + tokenText + " · " + expandHint(),
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
                "  ⎿  +" + hidden + " more tool uses " + expandHint(),
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
                    String summary = summarizeInputJson(toolUse.name(), toolUse.input().toString());
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
            shiftPendingToolLines(block.start + oldCount, delta);
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

    private void removeAgentProgressBlock(String toolUseId, MessagePanel panel) {
        AgentProgressBlock block = agentProgressBlocks.remove(toolUseId);
        if (block == null) return;
        int removed = block.rowCount;
        panel.replaceLines(block.start, removed, List.of());
        shiftPendingToolLines(block.start + removed, -removed);
        agentProgressBlocks.values().stream()
            .filter(other -> other.start > block.start)
            .forEach(other -> other.start -= removed);
        new HashSet<>(agentGroupsByToolUseId.values()).stream()
            .filter(group -> group.start > block.start)
            .forEach(group -> group.start -= removed);
        reflowAgentProgressBlocks(panel);
    }

    private void shiftPendingToolLines(int start, int delta) {
        if (delta == 0 || pendingTools.isEmpty()) return;
        List<PendingTool> shifted = pendingTools.stream()
            .map(tool -> tool.shiftedAfter(start, delta)).toList();
        pendingTools.clear();
        pendingTools.addAll(shifted);
    }

    private void renderRetry(SDKMessage.ApiRetry retry, MessagePanel panel) {
        panel.appendLine(
            "⟳ Retry: " + retry.error() + " (attempt " + retry.attempt() + ")",
            LanternaTheme.modeAuto());
    }

    private void renderCompactBoundary(MessagePanel panel) {

        panel.appendLine("", LanternaTheme.welcomeDim()); // top margin
        panel.appendLine("✻ Conversation compacted (ctrl+o for history)",
            LanternaTheme.welcomeDim());
        panel.appendLine("", LanternaTheme.welcomeDim()); // bottom margin
    }

    private void renderAttachment(SDKMessage.Attachment attachment, MessagePanel panel) {
        switch (attachment.attachmentType()) {
            case "max_turns_reached" ->
                panel.appendLine("⚠ Maximum turns reached — conversation ended.", LanternaTheme.modeAuto());
            case "hook_success", "hook_output" ->
                panel.appendLine("  [hook] " + FormatUtils.truncate(attachment.content(), 120), LanternaTheme.welcomeDim());
            case "hook_error" ->
                panel.appendLine("✗ [hook error] " + FormatUtils.truncate(attachment.content(), 120), LanternaTheme.toolError());
            case "hook_system_message" -> renderHookSystemMessage(attachment.content(), panel);
            case "queued_command" ->
                panel.appendLine("➳ Queued: " + FormatUtils.truncate(attachment.content(), 80), LanternaTheme.agentCyan());
            case "goal_status" -> renderGoalStatus(attachment.content(), panel);
            case "structured_output", "hook_non_blocking_error" -> {
                /* transcript metadata — not displayed inline */
            }
            default -> {
                // Show unknown attachments as dim metadata
                if (StringUtils.isNotBlank(attachment.content())) {
                    panel.appendLine(
                        "[" + attachment.attachmentType() + "] " + FormatUtils.truncate(attachment.content(), 100),
                        LanternaTheme.welcomeDim());
                }
            }
        }
    }

    private void renderHookSystemMessage(String content, MessagePanel panel) {
        try {
            JsonNode payload = JsonUtils.getMapper().readTree(content);
            String hookName = payload.path("hookName").asText("Hook");
            String message = payload.path("content").asText("");
            if (StringUtils.isNotBlank(message)) {
                panel.appendLine(hookName + " says: " + message, LanternaTheme.inputText());
            }
        } catch (Exception _) {
            if (StringUtils.isNotBlank(content)) {
                panel.appendLine(content, LanternaTheme.inputText());
            }
        }
    }


    private void renderGoalStatus(String content, MessagePanel panel) {
        if (StringUtils.isBlank(content)) return;
        try {
            GoalStatusAttachment status = JsonUtils.getMapper()
                .readValue(content, GoalStatusAttachment.class);
            if (status.hasSentinelMarker()) return;
            panel.appendLine("", TextColor.ANSI.DEFAULT);

            if (!status.met() && !status.hasFailedMarker()) {
                panel.appendLine("✶ Goal not yet met… continuing", LanternaTheme.welcomeDim());
                if (verbose || transcriptMode) {
                    if (StringUtils.isNotBlank(status.condition())) {
                        panel.appendLine("  Goal: " + status.condition(), LanternaTheme.welcomeDim());
                    }
                    if (StringUtils.isNotBlank(status.reason())) {
                        panel.appendLine("  Reason: " + status.reason(), LanternaTheme.welcomeDim());
                    }
                }
                return;
            }

            String stats = goalStats(status);
            if (status.hasFailedMarker()) {
                panel.appendLine("✗ Goal could not be achieved" + stats, LanternaTheme.toolError());
                if (StringUtils.isNotBlank(status.reason())) {
                    panel.appendLine("  " + status.reason(), LanternaTheme.welcomeDim());
                }
            } else {
                panel.appendLine("✓ Goal achieved" + stats, LanternaTheme.toolSuccess());
            }

            if (verbose || transcriptMode) {
                if (StringUtils.isNotBlank(status.condition())) {
                    panel.appendLine("  Goal: " + status.condition(), LanternaTheme.welcomeDim());
                }
                if (!status.hasFailedMarker() && status.reason() != null && !StringUtils.isBlank(status.reason())) {
                    panel.appendLine("  Reason: " + status.reason(), LanternaTheme.welcomeDim());
                }
            }

        } catch (Exception e) {
            log.warn("Unable to render goal_status attachment: {}", e.getMessage());
        }
    }

    private static String goalStats(GoalStatusAttachment status) {
        if (status.durationMs() == null && status.iterations() == null && status.tokens() == null) {
            return "";
        }
        long duration = status.durationMs() != null ? status.durationMs() : 0L;
        int turns = status.iterations() != null ? status.iterations() : 0;
        long tokens = status.tokens() != null ? status.tokens() : 0L;
        return " (" + FormatUtils.formatDuration(duration, true, true)
            + " · " + turns + " " + (turns == 1 ? "turn" : "turns")
            + " · " + FormatUtils.formatTokens(tokens) + " tokens)";
    }


    private static String agentSubtype(String inputJson) {
        if (StringUtils.isBlank(inputJson)) return "";
        var input = JsonUtils.safeParseJson(inputJson);
        return input == null ? "" : input.path("subagent_type").asText("");
    }









    private static final String INDENT_PREFIX     = Figures.RESULT_PREFIX;
    private static final String INDENT_CONT       = Figures.RESULT_INDENT;

    /**
     * Handle special assistant error/status text.
     */
    private boolean renderSpecialAssistantText(String text, MessagePanel panel) {

        if (Strings.CS.equals("No response requested.", text)) return true;

        TextColor err = LanternaTheme.toolError();
        switch (text) {
            case "Prompt is too long" -> {
                panel.appendLine("Context limit reached · /compact or /clear to continue", err);
                return true;
            }
            case "Credit balance is too low" -> {
                panel.appendLine(
                    "Credit balance too low · Add funds: https://platform.claude.com/settings/billing", err);
                return true;
            }
// INVALID_API_KEY (both variants).
            case "Not logged in · Please run /login",
                 "Invalid API key · Fix external API key" -> {
                panel.appendLine(text, err);
                return true;
            }
            // ORG_DISABLED (both variants)
            case "Your ANTHROPIC_API_KEY belongs to a disabled organization · Unset the environment variable to use your subscription instead",
                 "Your ANTHROPIC_API_KEY belongs to a disabled organization · Update or unset the environment variable" -> {
                panel.appendLine(text, err);
                return true;
            }
            case "OAuth token revoked · Please run /login" -> {
                panel.appendLine(text, err);
                return true;
            }
            case "Request timed out" -> {
                panel.appendLine(text, err);
                return true;
            }
            case "Opus is experiencing high load, please use /model to switch to Sonnet" -> {
                panel.appendLine(text, err);
                return true;
            }

            case "API Error: Request was aborted." -> {
                panel.appendLine("Interrupted by user", LanternaTheme.welcomeDim());
                return true;
            }
        }

        if (Strings.CS.startsWith(text, "API Error") || Strings.CS.startsWith(text, "Please run /login · API Error")) {
            if (!verbose && !transcriptMode && text.length() > 1000) {
                panel.appendLine(FormatUtils.truncate(text, 1000), err);
                panel.appendMixed(List.of(
                    new MessagePanel.Segment("  " + expandHint(), LanternaTheme.welcomeDim())
                ));
            } else {
                panel.appendLine(text, err);
            }
            return true;
        }
        return false;
    }


    private static boolean isRateLimitError(String text) {
        if (StringUtils.isEmpty(text)) return false;
        String[] prefixes = {
            "You've hit your",
            "You've used",
            "You're now using extra usage",
            "You're close to",
            "You're out of extra usage"
        };
        for (String prefix : prefixes) {
            if (Strings.CS.startsWith(text, prefix)) return true;
        }
        return false;
    }

    private record ToolResultPlacement(PendingTool pending, int replaceLine) {}

    /**
     * Completes the shared tool-card state before a generic or specialized result body renders.
     * Structured Edit/Write/Notebook payloads must pass through this path too; otherwise their
     * tool header keeps blinking and a permission {@code Waiting…} row remains stranded.
     */
    private ToolResultPlacement beginToolResult(ToolResultBlock result, MessagePanel panel) {
        toolResultRenderedThisTurn = true;
        // A tool result is a committed row: any still-open live-stream window must
        // not be allowed to roll back past it. Close the window so the next text
        // block re-snapshots AFTER this result (197 never rolls back a committed
        // tool result; see closeStreamingWindow()).
        if (streamedThisTurn) closeStreamingWindow();
        PendingTool pending = removePendingTool(result.toolUseId());
        int replaceLine = -1;
        if (pending != null && !pending.transparent()) {
            replaceLine = pending.statusLineIdx();
            String argsPart = toolArgsPart(pending.toolName(), pending.inputJson());
            String tag = toolTagPart(
                pending.toolName(), pending.inputJson(), pending.toolUseId());
            panel.stopBlinkLine(pending.lineIdx(), buildDoneSegs(
                pending.toolName(), result.isError() ? LanternaTheme.toolError()
                    : LanternaTheme.toolSuccess(), tag, argsPart, pending.inputJson()));
        }
        return new ToolResultPlacement(pending, replaceLine);
    }

    private PendingTool removePendingTool(String toolUseId) {
        if (toolUseId == null) return pendingTools.pollFirst();
        for (var iterator = pendingTools.iterator(); iterator.hasNext();) {
            PendingTool pending = iterator.next();
            if (Strings.CS.equals(toolUseId, pending.toolUseId())) {
                iterator.remove();
                return pending;
            }
        }
        List<PendingTool> legacy = pendingTools.stream()
            .filter(tool -> tool.toolUseId() == null)
            .toList();
        if (legacy.size() != 1) return null;
        PendingTool pending = legacy.getFirst();
        pendingTools.remove(pending);
        return pending;
    }

    private void completePendingToolHeader(PendingTool pending, TextColor color,
                                           MessagePanel panel) {
        if (pending == null || pending.transparent() || pending.lineIdx() < 0) return;
        panel.stopBlinkLine(pending.lineIdx(), buildDoneSegs(
            pending.toolName(), color,
            toolTagPart(pending.toolName(), pending.inputJson(), pending.toolUseId()),
            toolArgsPart(pending.toolName(), pending.inputJson()), pending.inputJson()));
    }

    private boolean renderRegisteredToolResult(Object payload, ToolResultBlock result,
                                               MessagePanel panel) {
        ToolInvocation invocation = result.toolUseId() == null
            ? null : toolInvocations.get(result.toolUseId());
        PendingTool pending = pendingTool(result.toolUseId());
        String toolName = invocation != null ? invocation.toolName()
            : pending != null ? pending.toolName() : "";
        ToolVisualContractRegistry.ResultMode mode =
            ToolVisualContractRegistry.resultMode(toolName);
        if (mode == ToolVisualContractRegistry.ResultMode.DEFAULT || result.isError()) return false;

        if (mode == ToolVisualContractRegistry.ResultMode.HIDDEN) {
            beginToolResult(result, panel);
            if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.SEARCH) {
            renderSearchResult(invocation, result, panel);
            return true;
        }
        if (payload == null) return false;
        JsonNode node = JsonUtils.getMapper().valueToTree(payload);
        if (mode == ToolVisualContractRegistry.ResultMode.TASK_OUTPUT
                && node.has("retrieval_status") && node.has("task")) {
            renderTaskOutputResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.SKILL
                && (node.path("success").asBoolean(false)
                    || Strings.CS.equals("forked", node.path("status").asText()))) {
            renderSkillResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.ASK_USER_QUESTION
                && node.path("answers").isObject()) {
            renderAskUserQuestionResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.TASK_STOP
                && node.has("command")) {
            renderTaskStopResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.SEND_MESSAGE
                && node.has("message")) {
            renderSendMessageResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.ENTER_WORKTREE
                && node.has("worktreePath")) {
            renderEnterWorktreeResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.EXIT_WORKTREE
                && node.has("action") && node.has("originalCwd")) {
            renderExitWorktreeResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.LSP
                && node.has("operation") && node.has("result")) {
            renderLspResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.JSON_OUTPUT) {
            renderJsonOutputResult(toolName, node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.MCP) {
            renderMcpResult(node, result, panel, invocation);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.WEB_FETCH
                && node.has("bytes") && node.has("code")) {
            renderWebFetchResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.WEB_SEARCH
                && node.has("results") && node.has("durationSeconds")) {
            renderWebSearchResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.ENTER_PLAN_MODE
                && node.has("message")) {
            renderEnterPlanModeResult(result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.EXIT_PLAN_MODE
                && node.has("plan")) {
            renderExitPlanModeResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.CRON_CREATE
                && node.has("id") && node.has("humanSchedule")) {
            renderCronCreateResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.CRON_DELETE && node.has("id")) {
            renderCronDeleteResult(node, result, panel);
            return true;
        }
        if (mode == ToolVisualContractRegistry.ResultMode.CRON_LIST
                && node.path("jobs").isArray()) {
            renderCronListResult(node, result, panel);
            return true;
        }
        return false;
    }

    private void renderSearchResult(ToolInvocation invocation, ToolResultBlock result,
                                    MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        String content = toolResultText(result);
        JsonNode input = invocation == null ? JsonUtils.getMapper().createObjectNode()
            : parseJsonObject(invocation.inputJson());
        String mode = Strings.CS.equals("Glob", invocation == null ? "" : invocation.toolName())
            ? "files_with_matches" : input.path("output_mode").asText("files_with_matches");
        List<String> lines = new ArrayList<>(content.lines().filter(line -> !StringUtils.isBlank(line)
            && !Strings.CS.startsWith(line.strip(), "(Results are truncated")
            && !Strings.CS.startsWith(line.strip(), "[Showing results with pagination"))
            .toList());
        if (Strings.CS.equalsAny(content.strip(), "No files found", "No matches found")) {
            lines.clear();
        } else if (Strings.CS.equals("files_with_matches", mode) && !lines.isEmpty()
                && Strings.CS.startsWith(lines.getFirst(), "Found ")) {
            lines.removeFirst();
        } else if (Strings.CS.equals("count", mode)) {
            lines.removeIf(line -> Strings.CS.startsWith(line, "Found ")
                && Strings.CS.contains(line, " across "));
        }
        int primary = lines.size();
        int secondary = 0;
        String primaryLabel = Strings.CS.equals("content", mode) ? "lines" : "files";
        if (Strings.CS.equals("count", mode)) {
            primary = 0;
            secondary = lines.size();
            primaryLabel = "matches";
            for (String line : lines) {
                int colon = line.lastIndexOf(':');
                if (colon >= 0) {
                    try {
                        primary += Integer.parseInt(line.substring(colon + 1).strip());
                    } catch (NumberFormatException _) {
                        // Keep counting the files even if one adapter returned a non-numeric suffix.
                    }
                }
            }
        }
        List<MessagePanel.Segment> summary = new ArrayList<>();
        summary.add(new MessagePanel.Segment(INDENT_PREFIX + "Found ", TextColor.ANSI.DEFAULT));
        summary.add(new MessagePanel.Segment(Integer.toString(primary), TextColor.ANSI.DEFAULT,
            null, null, Set.of(SGR.BOLD)));
        summary.add(new MessagePanel.Segment(" " + singular(primary, primaryLabel),
            TextColor.ANSI.DEFAULT));
        if (Strings.CS.equals("count", mode)) {
            summary.add(new MessagePanel.Segment(" across ", TextColor.ANSI.DEFAULT));
            summary.add(new MessagePanel.Segment(Integer.toString(secondary), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            summary.add(new MessagePanel.Segment(" " + singular(secondary, "files"),
                TextColor.ANSI.DEFAULT));
        }
        if (!verbose && primary > 0) {
            summary.add(new MessagePanel.Segment(" " + expandHint(),
                LanternaTheme.welcomeDim()));
        }
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), summary);
        if (verbose && !lines.isEmpty()) {
            for (String line : lines) {
                panel.appendLine(INDENT_CONT + line, TextColor.ANSI.DEFAULT);
            }
        }
    }

    private void renderTaskOutputResult(JsonNode output, ToolResultBlock result,
                                        MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        JsonNode task = output.path("task");
        if (task.isMissingNode() || task.isNull()) {
            appendDimResult(panel, placement.replaceLine(), "No task output available");
            return;
        }
        String type = task.path("task_type").asText("");
        String retrieval = output.path("retrieval_status").asText("");
        String taskOutput = task.path("output").asText("");
        if (Strings.CS.equals("local_bash", type)) {
            String body = StringUtils.isBlank(taskOutput) ? task.path("error").asText("Done") : taskOutput;
            panel.updateToolOutputOrAppend(placement.replaceLine(), body,
                LanternaTheme.welcomeDim(), verbose || transcriptMode);
            return;
        }
        if (Strings.CS.equals("local_agent", type)) {
            if (Strings.CS.equals("success", retrieval)) {
                if (!verbose && !transcriptMode) {
                    appendDimResult(panel, placement.replaceLine(),
                        "Read output " + expandHint());
                    return;
                }
                String response = task.path("result").asText(taskOutput);
                int lines = StringUtils.isBlank(response) ? 0 : response.split("\\n", -1).length;
                appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                    new MessagePanel.Segment(INDENT_PREFIX + task.path("description").asText("")
                        + " (" + lines + " lines)", TextColor.ANSI.DEFAULT)));
                String prompt = task.path("prompt").asText("");
                if (!StringUtils.isBlank(prompt)) panel.appendMarkdown(prompt, MARKDOWN_RENDERER, false);
                if (!StringUtils.isBlank(response)) panel.appendMarkdown(response, MARKDOWN_RENDERER, false);
                String error = task.path("error").asText("");
                if (!StringUtils.isBlank(error)) panel.appendLine("Error: " + error, LanternaTheme.toolError());
                return;
            }
            if (Strings.CS.equalsAny(retrieval, "timeout", "not_ready")
                    || Strings.CS.equals("running", task.path("status").asText())) {
                appendDimResult(panel, placement.replaceLine(), "Task is still running…");
            } else {
                appendDimResult(panel, placement.replaceLine(), "Task not ready");
            }
            return;
        }
        String description = task.path("description").asText("");
        String status = task.path("status").asText("");
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + description + " [" + status + "]",
                TextColor.ANSI.DEFAULT)));
        if (Strings.CS.equals("remote_agent", type)) {
            if (verbose && !StringUtils.isBlank(taskOutput)) {
                panel.appendLine(INDENT_CONT + taskOutput, TextColor.ANSI.DEFAULT);
            } else if (!StringUtils.isBlank(taskOutput)) {
                panel.appendLine(INDENT_CONT + expandHint(), LanternaTheme.welcomeDim());
            }
        } else if (!StringUtils.isBlank(taskOutput)) {
            panel.appendLine(INDENT_CONT + FormatUtils.truncate(taskOutput, 500),
                TextColor.ANSI.DEFAULT);
        }
    }

    private void renderSkillResult(JsonNode output, ToolResultBlock result, MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        if (Strings.CS.equals("forked", output.path("status").asText())) {
            appendDimResult(panel, placement.replaceLine(), "Done");
            return;
        }
        List<String> parts = new ArrayList<>();
        parts.add("Successfully loaded skill");
        JsonNode allowed = output.path("allowedTools");
        if (allowed.isArray() && !allowed.isEmpty()) {
            parts.add(allowed.size() + " " + singular(allowed.size(), "tools") + " allowed");
        }
        String model = output.path("model").asText("");
        if (!StringUtils.isBlank(model)) parts.add(model);
        appendDimResult(panel, placement.replaceLine(), String.join(" · ", parts));
    }

    private void renderAskUserQuestionResult(JsonNode output, ToolResultBlock result,
                                             MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        int replaceLine = placement.replaceLine();
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.permission()),
            new MessagePanel.Segment("User answered Claude's questions:",
                TextColor.ANSI.DEFAULT)));
        output.path("answers").fields().forEachRemaining(entry -> panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "· " + entry.getKey() + " → "
                + entry.getValue().asText(), LanternaTheme.welcomeDim()))));
    }

    private void renderTaskStopResult(JsonNode output, ToolResultBlock result,
                                      MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        String raw = output.path("command").asText("");
        String command = raw;
        if (!verbose && !transcriptMode) {
            String[] lines = raw.split("\\n", -1);
            if (lines.length > 2) command = String.join("\n", Arrays.copyOf(lines, 2));
            if (command.length() > 160) command = command.substring(0, 160);
            command = command.strip();
        }
        String suffix = Strings.CS.equals(command, raw) ? " · stopped" : "… · stopped";
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + command + suffix, TextColor.ANSI.DEFAULT)));
    }

    private void renderSendMessageResult(JsonNode output, ToolResultBlock result,
                                         MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        if ((output.has("routing") && !output.path("routing").isNull()
                && (!output.path("routing").isBoolean() || output.path("routing").asBoolean()))
                || (output.has("request_id") && output.has("target"))) {
            return;
        }
        appendDimResult(panel, placement.replaceLine(), output.path("message").asText(""));
    }

    private void renderEnterWorktreeResult(JsonNode output, ToolResultBlock result,
                                           MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment(INDENT_PREFIX + "Switched to worktree on branch ",
            TextColor.ANSI.DEFAULT));
        line.add(new MessagePanel.Segment(output.path("worktreeBranch").asText(""),
            TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
        panel.appendLine(INDENT_CONT + output.path("worktreePath").asText(""),
            LanternaTheme.welcomeDim());
    }

    private void renderExitWorktreeResult(JsonNode output, ToolResultBlock result,
                                          MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        String action = output.path("action").asText("");
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment(INDENT_PREFIX
            + (Strings.CS.equals("keep", action) ? "Kept worktree" : "Removed worktree"),
            TextColor.ANSI.DEFAULT));
        String branch = output.path("worktreeBranch").asText("");
        if (!StringUtils.isBlank(branch)) {
            line.add(new MessagePanel.Segment(" (branch ", TextColor.ANSI.DEFAULT));
            line.add(new MessagePanel.Segment(branch, TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            line.add(new MessagePanel.Segment(")", TextColor.ANSI.DEFAULT));
        }
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
        panel.appendLine(INDENT_CONT + "Returned to " + output.path("originalCwd").asText(""),
            LanternaTheme.welcomeDim());
    }

    private void renderLspResult(JsonNode output, ToolResultBlock result,
                                 MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        if (!output.has("resultCount") || !output.has("fileCount")) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + output.path("result").asText(""),
                    TextColor.ANSI.DEFAULT)));
            return;
        }
        int count = output.path("resultCount").asInt();
        int files = output.path("fileCount").asInt();
        String operation = output.path("operation").asText("");
        String singular = switch (operation) {
            case "goToDefinition" -> "definition";
            case "findReferences" -> "reference";
            case "documentSymbol", "workspaceSymbol" -> "symbol";
            case "hover" -> "hover info";
            case "goToImplementation" -> "implementation";
            case "prepareCallHierarchy" -> "call item";
            case "incomingCalls" -> "caller";
            case "outgoingCalls" -> "callee";
            default -> "result";
        };
        String plural = switch (singular) {
            case "hover info" -> singular;
            case "call item" -> "call items";
            default -> singular + "s";
        };
        List<MessagePanel.Segment> summary = new ArrayList<>();
        if (Strings.CS.equals("hover", operation) && count > 0) {
            summary.add(new MessagePanel.Segment(INDENT_PREFIX + "Hover info available",
                TextColor.ANSI.DEFAULT));
        } else {
            summary.add(new MessagePanel.Segment(INDENT_PREFIX + "Found ", TextColor.ANSI.DEFAULT));
            summary.add(new MessagePanel.Segment(Integer.toString(count), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            summary.add(new MessagePanel.Segment(" " + (count == 1 ? singular : plural),
                TextColor.ANSI.DEFAULT));
        }
        if (files > 1) {
            summary.add(new MessagePanel.Segment(" across ", TextColor.ANSI.DEFAULT));
            summary.add(new MessagePanel.Segment(Integer.toString(files), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)));
            summary.add(new MessagePanel.Segment(" files", TextColor.ANSI.DEFAULT));
        }
        if (!verbose && count > 0) {
            summary.add(new MessagePanel.Segment(" " + expandHint(),
                LanternaTheme.welcomeDim()));
        }
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), summary);
        if (verbose) {
            String content = output.path("result").asText("");
            if (!StringUtils.isBlank(content)) panel.appendLine(INDENT_CONT + content, TextColor.ANSI.DEFAULT);
        }
    }

    private void renderJsonOutputResult(String toolName, JsonNode output,
                                        ToolResultBlock result, MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        if (Strings.CS.equals("ListMcpResourcesTool", toolName)
                && (!output.isArray() || output.isEmpty())) {
            appendDimResult(panel, placement.replaceLine(), "(No resources found)");
            return;
        }
        if (Strings.CS.equals("ReadMcpResourceTool", toolName)
                && (!output.path("contents").isArray() || output.path("contents").isEmpty())) {
            appendDimResult(panel, placement.replaceLine(), "(No content)");
            return;
        }
        panel.updateToolOutputOrAppend(placement.replaceLine(), output.toPrettyString(),
            TextColor.ANSI.DEFAULT, verbose || transcriptMode);
    }

    private void renderMcpResult(JsonNode output, ToolResultBlock result,
                                 MessagePanel panel, ToolInvocation invocation) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        SlackSend slack = !verbose ? slackSend(output, invocation) : null;
        if (slack != null) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "Sent a message to ",
                    TextColor.ANSI.DEFAULT),
                MessagePanel.Segment.hyperlink(slack.channel(), TextColor.ANSI.DEFAULT,
                    slack.url())));
            return;
        }
        long estimate = estimateMcpTokens(output);
        if (estimate > 10_000) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "⚠ Large MCP response (~"
                    + FormatUtils.formatNumber(estimate)
                    + " tokens), this can fill up context quickly", LanternaTheme.statusCost())));
            placement = new ToolResultPlacement(placement.pending(), -1);
        }
        if (output == null || output.isNull() || (output.isTextual() && output.asText().isEmpty())) {
            appendDimResult(panel, placement.replaceLine(), "(No content)");
            return;
        }
        if (output.isArray()) {
            boolean first = true;
            for (JsonNode block : output) {
                int replace = first ? placement.replaceLine() : -1;
                if (Strings.CS.equals("image", block.path("type").asText())) {
                    appendOrReplaceToolResultLine(panel, replace, List.of(
                        new MessagePanel.Segment(INDENT_PREFIX + "[Image]", TextColor.ANSI.DEFAULT)));
                } else {
                    String text = block.path("text").asText("");
                    panel.updateToolOutputOrAppend(replace, text, TextColor.ANSI.DEFAULT,
                        verbose || transcriptMode);
                }
                first = false;
            }
            if (first) appendDimResult(panel, placement.replaceLine(), "(No content)");
            return;
        }
        String content = output.isTextual() ? output.asText() : output.toPrettyString();
        if (renderRichMcpText(content, placement.replaceLine(), panel)) return;
        panel.updateToolOutputOrAppend(placement.replaceLine(), content, TextColor.ANSI.DEFAULT,
            verbose || transcriptMode);
    }

    private boolean renderRichMcpText(String content, int replaceLine, MessagePanel panel) {
        if (content == null || content.length() > 200_000
            || !Strings.CS.startsWith(content.stripLeading(), "{")) {
            return false;
        }
        JsonNode parsed = JsonUtils.safeParseJson(content);
        if (parsed == null || !parsed.isObject() || parsed.isEmpty()) return false;
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        parsed.fields().forEachRemaining(entries::add);
        if (entries.size() <= 4) {
            Map.Entry<String, JsonNode> dominant = null;
            List<String> extras = new ArrayList<>();
            for (Map.Entry<String, JsonNode> entry : entries) {
                JsonNode value = entry.getValue();
                if (value.isTextual()) {
                    String text = value.asText().stripTrailing();
                    boolean large = text.length() > 200
                        || (text.length() > 50 && Strings.CS.contains(text, "\n"));
                    if (large) {
                        if (dominant != null) return false;
                        dominant = entry;
                    } else if (text.length() <= 150) {
                        extras.add(entry.getKey() + ": " + text.replaceAll("\\s+", " "));
                    } else return false;
                } else if (value.isValueNode()) {
                    extras.add(entry.getKey() + ": " + value.asText());
                } else return false;
            }
            if (dominant != null) {
                if (!extras.isEmpty()) appendDimResult(panel, replaceLine, String.join(" · ", extras));
                panel.updateToolOutputOrAppend(extras.isEmpty() ? replaceLine : -1,
                    dominant.getValue().asText().stripTrailing(), TextColor.ANSI.DEFAULT,
                    verbose || transcriptMode);
                return true;
            }
        }
        if (content.length() > 5_000 || entries.size() > 12) return false;
        List<String> rows = new ArrayList<>();
        int maxKey = entries.stream().mapToInt(entry -> entry.getKey().length()).max().orElse(0);
        for (Map.Entry<String, JsonNode> entry : entries) {
            JsonNode value = entry.getValue();
            String display = value.isTextual() ? value.asText()
                : value.isValueNode() ? value.asText() : value.toString();
            if (!value.isValueNode() && display.length() > 120) return false;
            rows.add(entry.getKey() + " ".repeat(maxKey - entry.getKey().length()) + ": " + display);
        }
        panel.updateToolOutputOrAppend(replaceLine, String.join("\n", rows),
            TextColor.ANSI.DEFAULT, true);
        return true;
    }

    private static SlackSend slackSend(JsonNode output, ToolInvocation invocation) {
        String text = null;
        if (output != null && output.isTextual()) text = output.asText();
        if (output != null && output.isArray()) {
            for (JsonNode block : output) {
                if (Strings.CS.equals("text", block.path("type").asText())) {
                    text = block.path("text").asText();
                    break;
                }
            }
        }
        if (text == null || !Strings.CS.contains(text, "\"message_link\"")) return null;
        JsonNode parsed = JsonUtils.safeParseJson(text);
        String url = parsed == null ? "" : parsed.path("message_link").asText("");
        Matcher matcher = Pattern.compile(
            "^https://[a-z0-9-]+\\.slack\\.com/archives/([A-Z0-9]+)/p\\d+$").matcher(url);
        if (!matcher.matches()) return null;
        JsonNode input = invocation == null ? null : parseJsonObject(invocation.inputJson());
        String channel = input == null ? "" : input.path("channel_id").asText("");
        if (StringUtils.isBlank(channel) && input != null) channel = input.path("channel").asText("");
        if (StringUtils.isBlank(channel)) channel = matcher.group(1);
        if (!Strings.CS.startsWith(channel, "#")) channel = "#" + channel;
        return new SlackSend(channel, url);
    }

    private record SlackSend(String channel, String url) {}

    private static long estimateMcpTokens(JsonNode output) {
        if (output == null || output.isNull()) return 0L;
        if (output.isArray()) {
            long total = 0L;
            for (JsonNode block : output) {
                total += Strings.CS.equals("image", block.path("type").asText())
                    ? 1600L : Math.max(1L, block.path("text").asText("").length() / 4L);
            }
            return total;
        }
        String text = output.isTextual() ? output.asText() : output.toString();
        return Math.max(1L, text.length() / 4L);
    }

    private void renderWebFetchResult(JsonNode output, ToolResultBlock result,
                                      MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        List<MessagePanel.Segment> line = new ArrayList<>();
        line.add(new MessagePanel.Segment(INDENT_PREFIX + "Received ", TextColor.ANSI.DEFAULT));
        line.add(new MessagePanel.Segment(FormatUtils.formatFileSize(output.path("bytes").asLong()),
            TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
        line.add(new MessagePanel.Segment(" (" + output.path("code").asInt() + " "
            + output.path("codeText").asText("") + ")", TextColor.ANSI.DEFAULT));
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
        if (verbose) {
            String body = output.path("result").asText("");
            if (!StringUtils.isBlank(body)) panel.appendLine(body, TextColor.ANSI.DEFAULT);
        }
    }

    private void renderWebSearchResult(JsonNode output, ToolResultBlock result,
                                       MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        int searches = 0;
        for (JsonNode item : output.path("results")) {
            if (item != null && item.isObject()) searches++;
        }
        double seconds = output.path("durationSeconds").asDouble();
        String duration = seconds >= 1
            ? Math.round(seconds) + "s" : Math.round(seconds * 1000) + "ms";
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Did " + searches + " search"
                + (searches == 1 ? "" : "es") + " in " + duration,
                TextColor.ANSI.DEFAULT)));
    }


    private void renderEnterPlanModeResult(ToolResultBlock result, MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.modePlan()),
            new MessagePanel.Segment("Entered plan mode", TextColor.ANSI.DEFAULT)));
        panel.appendLine("  Claude is now exploring and designing an implementation approach.",
            LanternaTheme.welcomeDim());
    }


    private void renderExitPlanModeResult(JsonNode output, ToolResultBlock result,
                                          MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        String plan = output.path("plan").isNull() ? null : output.path("plan").asText(null);
        boolean empty = StringUtils.isBlank(plan);
        boolean awaiting = output.path("awaitingLeaderApproval").asBoolean(false);
        String filePath = output.path("filePath").asText("");
        String title = empty ? "Exited plan mode"
            : awaiting ? "Plan submitted for team lead approval"
            : "User approved Claude's plan";
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(BLACK_CIRCLE, LanternaTheme.modePlan()),
            new MessagePanel.Segment(title, TextColor.ANSI.DEFAULT)));
        if (empty) return;
        String shownPath = displayPath(filePath);
        if (awaiting) {
            if (StringUtils.isNotBlank(filePath)) {
                panel.appendLine(INDENT_CONT + "Plan file: " + shownPath,
                    LanternaTheme.welcomeDim());
            }
            panel.appendLine(INDENT_CONT + "Waiting for team lead to review and approve...",
                LanternaTheme.welcomeDim());
            return;
        }
        if (StringUtils.isNotBlank(filePath)) {
            panel.appendLine(INDENT_CONT + "Plan saved to: " + shownPath + " · /plan to edit",
                LanternaTheme.welcomeDim());
        }
        panel.appendMarkdown(plan, MARKDOWN_RENDERER, false);
    }

    private void renderCronCreateResult(JsonNode output, ToolResultBlock result,
                                        MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Scheduled ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(output.path("id").asText(""), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(" (" + output.path("humanSchedule").asText("") + ")",
                LanternaTheme.welcomeDim())));
    }

    private void renderCronDeleteResult(JsonNode output, ToolResultBlock result,
                                        MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "Cancelled ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(output.path("id").asText(""), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD))));
    }

    private void renderCronListResult(JsonNode output, ToolResultBlock result,
                                      MessagePanel panel) {
        ToolResultPlacement placement = beginToolResult(result, panel);
        if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
        JsonNode jobs = output.path("jobs");
        if (jobs.isEmpty()) {
            appendDimResult(panel, placement.replaceLine(), "No scheduled jobs");
            return;
        }
        boolean first = true;
        for (JsonNode job : jobs) {
            List<MessagePanel.Segment> line = List.of(
                new MessagePanel.Segment(INDENT_PREFIX, TextColor.ANSI.DEFAULT),
                new MessagePanel.Segment(job.path("id").asText(""), TextColor.ANSI.DEFAULT,
                    null, null, Set.of(SGR.BOLD)),
                new MessagePanel.Segment(" " + job.path("humanSchedule").asText(""),
                    LanternaTheme.welcomeDim()));
            if (first) {
                appendOrReplaceToolResultLine(panel, placement.replaceLine(), line);
                first = false;
            } else {
                panel.appendMixed(line);
            }
        }
    }

    private static String toolResultText(ToolResultBlock result) {
        StringBuilder body = new StringBuilder();
        if (result.content() != null) {
            for (ContentBlock block : result.content()) {
                if (block instanceof TextBlock(String text1)) body.append(text1);
            }
        }
        return body.toString();
    }

    private static void appendDimResult(MessagePanel panel, int replaceLine, String text) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX + text, LanternaTheme.welcomeDim())));
    }

    private static String singular(int count, String plural) {
        return count == 1 && Strings.CS.endsWith(plural, "s")
            ? plural.substring(0, plural.length() - 1) : plural;
    }

    private boolean renderStructuredAgentResult(Object payload, ToolResultBlock result,
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

        ToolResultPlacement placement = beginToolResult(result, panel);
        int replaceLine = placement.replaceLine();
        boolean renderedTranscript = renderVerboseAgentTranscript(
            result.toolUseId(), node, panel);
        if (!transcriptMode) removeAgentProgressBlock(result.toolUseId(), panel);

        if (backgrounded) {
            if (transcriptMode && !renderedTranscript) {
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
                    "Backgrounded agent (↓ manage · " + expandShortcut() + " to expand)",
                    LanternaTheme.welcomeDim())));
            return true;
        }

        if (transcriptMode) {
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
        if (!transcriptMode) {
            panel.appendLine("     " + expandHint(), LanternaTheme.welcomeDim());
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

    private void renderToolResult(ToolResultBlock result, MessagePanel panel) {
        String toolUseId = result.toolUseId();
        ToolPresentationSnapshotStore.Snapshot presentation =
            presentationSnapshots.consume(toolUseId);
        String rememberedPlan = presentation.plan();
        RejectedFileChangePreview rejectedFilePreview = presentation.filePreview();
        ToolInvocation resultInvocation = result.toolUseId() == null
            ? null : toolInvocations.get(result.toolUseId());
        PendingTool pendingResult = pendingTool(result.toolUseId());
        String resultToolName = resultInvocation != null ? resultInvocation.toolName()
            : pendingResult != null ? pendingResult.toolName() : "";
        if (!result.isError() && ToolVisualContractRegistry.resultMode(resultToolName)
                == ToolVisualContractRegistry.ResultMode.HIDDEN) {
            beginToolResult(result, panel);
            if (result.toolUseId() != null) toolInvocations.remove(result.toolUseId());
            return;
        }
        ToolResultPlacement placement = beginToolResult(result, panel);
        PendingTool pending = placement.pending();
        int replaceLine = placement.replaceLine();
        if (transcriptMode && Strings.CS.equals("Agent", resultToolName)) {
            renderVerboseAgentTranscript(result.toolUseId(), null, panel);
        }
        // Extract textual content (most tools produce text blocks)
        StringBuilder body = new StringBuilder();
        if (result.content() != null) {
            for (ContentBlock cb : result.content()) {
                if (cb instanceof TextBlock(String text)) body.append(text);
            }
        }
        if (body.isEmpty()) {
            String marker = result.isError() ? "(error)" : "(no output)";
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(INDENT_PREFIX,  LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(marker,    LanternaTheme.welcomeDim())
            ));
            return;
        }

        String bodyText = body.toString();
        String prefixedPlan = planFromRejectionResult(bodyText);
        if (result.isError() && prefixedPlan != null) {
            renderRejectedPlan(panel, replaceLine, prefixedPlan);
            return;
        }
        if (result.isError() && Strings.CS.equals("EnterPlanMode", resultToolName)
                && Strings.CS.startsWith(bodyText, MessageConstants.REJECT_MESSAGE)) {
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(BLACK_CIRCLE,
                    LanternaTheme.colorFor(PermissionMode.DEFAULT)),
                new MessagePanel.Segment("User declined to enter plan mode",
                    TextColor.ANSI.DEFAULT)));
            return;
        }
        boolean exitPlanRejection = result.isError()
            && Strings.CS.equals("ExitPlanMode", resultToolName)
            && (rememberedPlan != null
                || Strings.CS.startsWith(bodyText, MessageConstants.REJECT_MESSAGE)
                || Strings.CS.startsWith(
                    bodyText, MessageConstants.REJECT_MESSAGE_WITH_REASON_PREFIX));
        if (exitPlanRejection) {
            String plan = rememberedPlan != null ? rememberedPlan : persistedPlan();
            renderRejectedPlan(panel, replaceLine, plan != null ? plan : "No plan found");
            return;
        }
        if (result.isError()
                && (Strings.CS.startsWith(bodyText, MessageConstants.REJECT_MESSAGE)
                    || Strings.CS.contains(bodyText,
                        MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE))) {
            ToolInvocation invocation = result.toolUseId() == null ? null
                : toolInvocations.remove(result.toolUseId());
            if (invocation == null && pending != null && pending.inputJson() != null
                    && !StringUtils.isBlank(pending.inputJson())) {
                invocation = new ToolInvocation(pending.toolName(), pending.inputJson());
            }
            if (renderRejectedFileChange(
                    rejectedFilePreview, invocation, panel, replaceLine)) return;
            renderInterruptedToolResult(panel, replaceLine);
            return;
        }
        if (result.isError() && MessageConstants.isClassifierDenial(bodyText)) {
            renderClassifierDenial(panel, replaceLine);
            return;
        }
        if (result.isError()) {
            bodyText = fallbackToolErrorText(bodyText);
        } else if (Strings.CS.equals(bodyText, "(Bash completed with no output)")) {
            bodyText = "Done";
        }

        String formatted = ShellOutputFormatter.tryJsonFormatContent(bodyText);
        TextColor color = result.isError() ? LanternaTheme.toolError() : LanternaTheme.welcomeDim();
        panel.updateToolOutputOrAppend(replaceLine, formatted, color, verbose || transcriptMode);
    }

    private boolean renderVerboseAgentTranscript(String toolUseId, JsonNode result,
            MessagePanel panel) {
        if (!transcriptMode || toolUseId == null
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
        removeAgentProgressBlock(toolUseId, panel);
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
        ToolInvocation invocation = toolInvocations.get(toolUseId);
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

    private static String planFromRejectionResult(String text) {
        if (text == null || !Strings.CS.startsWith(text, MessageConstants.PLAN_REJECTION_PREFIX)) {
            return null;
        }
        String plan = text.substring(MessageConstants.PLAN_REJECTION_PREFIX.length());
        return StringUtils.isBlank(plan) ? "No plan found" : plan.stripTrailing();
    }

    private String persistedPlan() {
        try {
            return persistedPlanSupplier.get();
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static void renderRejectedPlan(MessagePanel panel, int replaceLine, String plan) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "User rejected Claude's plan:",
                LanternaTheme.welcomeDim())));
        panel.appendMarkdown(plan, MARKDOWN_RENDERER, false);
    }

    private boolean renderRejectedFileChange(RejectedFileChangePreview prepared,
                                             ToolInvocation invocation, MessagePanel panel,
                                             int replaceLine) {
        RejectedFileChangePreview preview = prepared != null
            ? prepared : fallbackRejectedFileChange(invocation);
        if (preview == null || StringUtils.isBlank(preview.filePath())) return false;
        String shownPath = verbose ? preview.filePath() : relativeToCwd(preview.filePath());
        if (preview.kind() == RejectedFileChangePreview.Kind.NOTEBOOK) {
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(
                    INDENT_PREFIX + "User rejected " + preview.operation() + " ",
                    LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(shownPath, LanternaTheme.welcomeDim(), null, null,
                    Set.of(SGR.BOLD)),
                new MessagePanel.Segment(
                    " at cell " + preview.cellId(), LanternaTheme.welcomeDim())));
        } else {
            appendRejectedHeader(panel, replaceLine, preview.operation(), shownPath);
        }
        if (!preview.hunks().isEmpty()) {
            appendRejectedDiff(panel, preview.hunks(), preview.language());
        } else if (!StringUtils.isEmpty(preview.content())) {
            appendRejectedSourcePreview(panel, preview.content(), preview.language());
        }
        return true;
    }

    /** Input-only replay/remote fallback; deliberately performs no filesystem access. */
    private static RejectedFileChangePreview fallbackRejectedFileChange(
            ToolInvocation invocation) {
        if (invocation == null || invocation.inputJson() == null) return null;
        if (!Strings.CS.equalsAny(invocation.toolName(), "Edit", "FileEdit", "Write", "FileWrite",
                "NotebookEdit")) return null;
        JsonNode input;
        try {
            input = JsonUtils.getMapper().readTree(invocation.inputJson());
        } catch (JsonProcessingException _) {
            return null;
        }
        boolean notebook = Strings.CS.equals("NotebookEdit", invocation.toolName());
        String rawPath = notebook ? input.path("notebook_path").asText("")
            : input.path("file_path").asText(input.path("path").asText(""));
        if (StringUtils.isBlank(rawPath)) return null;
        String path = PathUtils.expandPath(
            rawPath, System.getProperty("user.dir", ".")).toString();
        if (notebook) {
            String mode = input.path("edit_mode").asText("replace");
            String operation = Strings.CS.equals("delete", mode)
                ? "delete" : mode + " cell in";
            String content = Strings.CS.equals("delete", mode)
                ? "" : input.path("new_source").asText("");
            return RejectedFileChangePreview.notebook(
                operation, path, input.path("cell_id").asText(""), List.of(), content,
                Strings.CS.equals("markdown", input.path("cell_type").asText())
                    ? "file.md" : "notebook.py");
        }
        if (Strings.CS.equalsAny(invocation.toolName(), "Edit", "FileEdit")) {
            String oldString = input.path("old_string").asText(input.path("old_str").asText(""));
            String newString = input.path("new_string").asText(input.path("new_str").asText(""));
            return RejectedFileChangePreview.inputEdit(path, oldString, newString);
        }
        return RejectedFileChangePreview.source(
            "write", path, input.path("content").asText(""), path);
    }

    private static void appendRejectedHeader(MessagePanel panel, int replaceLine,
                                             String operation, String shownPath) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX + "User rejected " + operation + " to ",
                LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(shownPath, LanternaTheme.welcomeDim(), null, null,
                Set.of(SGR.BOLD))));
    }

    private static void appendRejectedDiff(MessagePanel panel, List<StructuredPatchHunk> hunks,
                                           String filePath) {
        for (int i = 0; i < hunks.size(); i++) {
            if (i > 0) appendDiffHunkSeparator(panel);
            appendInlineDiffHunk(panel, hunks.get(i), diffLanguageForPath(filePath), true);
        }
    }

    private static void appendRejectedSourcePreview(MessagePanel panel, String content,
                                                    String filePath) {
        String preview = StringUtils.isEmpty(content) ? "(No content)" : content;
        String[] lines = preview.split("\\n", -1);
        int available = lines.length;
        int visible = Math.min(available, 10);
        TmTokenizer.TokenizedCode tokenized = tokenizeCode(preview, diffLanguageForPath(filePath));
        for (int i = 0; i < visible; i++) {
            appendHighlightedCodeLine(panel, INDENT_CONT, lines[i],
                tokenLine(tokenized, i), true);
        }
        if (available > visible) {
            panel.appendLine(INDENT_CONT + "… +" + (available - visible) + " lines",
                LanternaTheme.welcomeDim());
        }
    }

    private static void appendOrReplaceToolResultLine(MessagePanel panel, int lineIdx,
                                                       List<MessagePanel.Segment> segments) {
        if (lineIdx >= 0) {
            panel.updateLine(lineIdx, segments);
        } else {
            panel.appendMixed(segments);
        }
    }

    private boolean renderStructuredFileChangeResult(Object payload, ToolResultBlock result,
                                                     MessagePanel panel) {
        if (payload == null) return false;
        var node = JsonUtils.getMapper().valueToTree(payload);
        if (node.path("filePath").isTextual()
                && node.path("structuredPatch").isArray()
                && !node.path("structuredPatch").isEmpty()
                && !Strings.CS.equals("create", node.path("type").asText())) {
            List<StructuredPatchHunk> hunks = decodeStructuredHunks(node);
            if (hunks == null || hunks.isEmpty()) return false;
            ToolResultPlacement placement = beginToolResult(result, panel);
            toolInvocations.remove(result.toolUseId());
            renderStructuredEditResult(node, hunks, panel, placement.replaceLine());
            return true;
        }
        String type = node.path("type").asText();
        // Accept Write results that carry full content for both {@code create}
        // and {@code update}. An {@code update} with an empty {@code structuredPatch}
        // (identical rewrite) previously bailed here and fell through to the generic
        // folded output; 197's isResultTruncated never folds {@code update}.
        if (!isWriteContentType(type)
                || !node.path("filePath").isTextual()
                || !node.path("content").isTextual()) {
            return false;
        }

        ToolResultPlacement placement = beginToolResult(result, panel);
        toolInvocations.remove(result.toolUseId());

        String filePath = node.path("filePath").asText();
        String content = node.path("content").asText();
        if (!verbose && isPlanFile(filePath)) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "/plan to preview",
                    LanternaTheme.welcomeDim())));
            return true;
        }
        int lineCount = countVisibleLines(content);
        boolean update = Strings.CS.equals("update", type);
        String shownPath = verbose ? filePath : relativeToCwd(filePath);
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(update ? "Updated " : "Wrote ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(Integer.toString(lineCount), TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(" lines to ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(shownPath, TextColor.ANSI.DEFAULT,
                null, null, Set.of(SGR.BOLD))
        ));

        String preview = content.isEmpty() ? "(No content)" : content;
        String[] lines = preview.split("\n", -1);
        int available = lines.length;
        // preview is always non-empty (the empty case maps to "(No content)"),
        // so only the trailing-newline adjustment remains.
        if (Strings.CS.endsWith(preview, "\n")) available--;
        // create truncates to 10 lines; update renders the full content because
        // 197's isResultTruncated only ever folds {@code create}.
        int visible = verbose || update ? available : Math.min(available, 10);
        int numberWidth = Math.max(1, Integer.toString(Math.max(lineCount, visible)).length());
        TmTokenizer.TokenizedCode tokenized = tokenizeCode(preview, diffLanguageForPath(filePath));
        for (int i = 0; i < visible; i++) {
            String num = Integer.toString(i + 1);
            String number = " ".repeat(Math.max(0, numberWidth - num.length())) + num + " ";
            appendHighlightedCodeLine(panel, INDENT_CONT + number, lines[i],
                tokenLine(tokenized, i), false);
        }
        if (!update && !verbose && lineCount > 10) {
            int hidden = lineCount - 10;
            panel.appendMixed(List.of(new MessagePanel.Segment(
                INDENT_CONT + "… +" + hidden + " " + (hidden == 1 ? "line" : "lines")
                    + " " + expandHint(),
                LanternaTheme.welcomeDim())));
        }
        return true;
    }

    private boolean renderStructuredNotebookEditResult(Object payload, ToolResultBlock result,
                                                       MessagePanel panel) {
        if (payload == null) return false;
        var node = JsonUtils.getMapper().valueToTree(payload);
        if (!node.path("notebook_path").isTextual()
                || !node.path("new_source").isTextual()
                || !node.path("edit_mode").isTextual()) {
            return false;
        }
        ToolResultPlacement placement = beginToolResult(result, panel);
        toolInvocations.remove(result.toolUseId());
        String error = node.path("error").asText("");
        if (!StringUtils.isBlank(error)) {
            appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
                new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
                new MessagePanel.Segment(error, LanternaTheme.toolError())));
            return true;
        }
        String cellId = node.path("cell_id").asText("");
        appendOrReplaceToolResultLine(panel, placement.replaceLine(), List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("Updated cell ", TextColor.ANSI.DEFAULT),
            new MessagePanel.Segment(cellId, TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)),
            new MessagePanel.Segment(":", TextColor.ANSI.DEFAULT)));
        appendNotebookSourcePreview(panel, node.path("new_source").asText(""));
        return true;
    }

    private static void appendNotebookSourcePreview(MessagePanel panel, String source) {
        String preview = StringUtils.isEmpty(source) ? "(No content)" : source;
        String[] lines = preview.split("\\n", -1);
        int available = Strings.CS.endsWith(preview, "\n") ? lines.length - 1 : lines.length;
        TmTokenizer.TokenizedCode tokenized = tokenizeCode(preview, "python");
        for (int i = 0; i < available; i++) {
            appendHighlightedCodeLine(panel, INDENT_CONT + "  ", lines[i],
                tokenLine(tokenized, i), false);
        }
    }

    /** Write results that carry a full {@code content} payload: new-file {@code create} and
     * overwrite {@code update}. Both belong to the FileWriteTool UI family and must never
     * fold to the generic collapsed output (197 folds only {@code create}, to 10 lines). */
    private static boolean isWriteContentType(String type) {
        return Strings.CS.equals("create", type) || Strings.CS.equals("update", type);
    }

    private static List<StructuredPatchHunk> decodeStructuredHunks(
            JsonNode node) {
        try {
            List<StructuredPatchHunk> hunks = new ArrayList<>();
            for (var hunkNode : node.path("structuredPatch")) {
                hunks.add(JsonUtils.getMapper().treeToValue(hunkNode, StructuredPatchHunk.class));
            }
            return hunks;
        } catch (JsonProcessingException e) {
            log.debug("Unable to render structured Edit result", e);
            return null;
        }
    }

    private void renderStructuredEditResult(JsonNode node,
                                            List<StructuredPatchHunk> hunks,
                                            MessagePanel panel, int replaceLine) {
        String filePath = node.path("filePath").asText();
        if (!verbose && isPlanFile(filePath)) {
            appendOrReplaceToolResultLine(panel, replaceLine, List.of(
                new MessagePanel.Segment(INDENT_PREFIX + "/plan to preview",
                    LanternaTheme.welcomeDim())));
            return;
        }

        int additions = hunks.stream().mapToInt(StructuredPatchHunk::addedCount).sum();
        int removals = hunks.stream().mapToInt(StructuredPatchHunk::removedCount).sum();
        appendOrReplaceToolResultLine(panel, replaceLine,
            editSummarySegments(additions, removals));

        String language = diffLanguageForPath(filePath);
        for (int i = 0; i < hunks.size(); i++) {
            if (i > 0) appendDiffHunkSeparator(panel);
            appendInlineDiffHunk(panel, hunks.get(i), language, false);
        }
    }

    private static List<MessagePanel.Segment> editSummarySegments(int additions, int removals) {
        List<MessagePanel.Segment> segments = new ArrayList<>();
        segments.add(new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()));
        if (additions > 0) {
            segments.add(new MessagePanel.Segment("Added ", TextColor.ANSI.DEFAULT));
            segments.add(new MessagePanel.Segment(Integer.toString(additions),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            segments.add(new MessagePanel.Segment(
                " " + (additions == 1 ? "line" : "lines"), TextColor.ANSI.DEFAULT));
        }
        if (removals > 0) {
            segments.add(new MessagePanel.Segment(
                additions > 0 ? ", removed " : "Removed ", TextColor.ANSI.DEFAULT));
            segments.add(new MessagePanel.Segment(Integer.toString(removals),
                TextColor.ANSI.DEFAULT, null, null, Set.of(SGR.BOLD)));
            segments.add(new MessagePanel.Segment(
                " " + (removals == 1 ? "line" : "lines"), TextColor.ANSI.DEFAULT));
        }
        return List.copyOf(segments);
    }

    private static void appendDiffHunkSeparator(MessagePanel panel) {
        panel.appendMixed(List.of(
            new MessagePanel.Segment(INDENT_CONT + "...", LanternaTheme.welcomeDim())));
    }

    private static void appendInlineDiffHunk(MessagePanel panel, StructuredPatchHunk hunk,
                                             String language, boolean dim) {
        List<DiffRenderer.DiffLineView> views = DiffRenderer.renderHunk(hunk, language);
        int maxLine = views.stream()
            .filter(view -> view.lineNo() != null)
            .mapToInt(DiffRenderer.DiffLineView::lineNo)
            .max().orElse(0);
        int digits = Math.max(1, Integer.toString(maxLine).length());
        LanternaTheme.DiffRenderPalette palette = LanternaTheme.diffRenderPalette();

        for (DiffRenderer.DiffLineView view : views) {
            char marker = view.marker();
            TextColor lineBg = switch (marker) {
                // 197 dim=true (FileEditToolUseRejectedMessage) selects the dimmed theme
                // background directly (diffAddedDimmed/diffRemovedDimmed) rather than
                // dimming the normal line background. Keep that two-way lookup here.
                case '+' -> dim ? LanternaTheme.diffAddedDimmed() : palette.addedLineBackground();
                case '-' -> dim ? LanternaTheme.diffRemovedDimmed() : palette.removedLineBackground();
                default -> null;
            };
            TextColor wordBg = switch (marker) {
                case '+' -> palette.addedWordBackground();
                case '-' -> palette.removedWordBackground();
                default -> null;
            };
            TextColor decoration = switch (marker) {
                case '+' -> palette.addedDecoration();
                case '-' -> palette.removedDecoration();
                default -> LanternaTheme.welcomeDim();
            };
            // lineBg is already the theme's dim variant when dim=true; only word/decoration/
            // foreground material gets dimmed here (mirrors Ink dimColor on the diff Text),
            // so lineBg must never be re-dimmed. Dimming happens once at each use site below
            // to avoid stacking blends on wordBg.
            String number = view.lineNo() == null ? "" : Integer.toString(view.lineNo());
            String gutter = marker == '@'
                ? " ".repeat(digits) + "   "
                : " ".repeat(Math.max(0, digits - number.length())) + number + " " + marker + " ";
            TextColor gutterDecoration = dim ? dimColor(decoration) : decoration;
            List<MessagePanel.Segment> segments = new ArrayList<>();
            segments.add(new MessagePanel.Segment(INDENT_CONT + gutter, gutterDecoration, lineBg));
            for (DiffRenderer.Segment segment : view.segments()) {
                TextColor foreground = segment.foreground() != null
                    ? LanternaTheme.toLC(segment.foreground())
                    : segment.kind() == DiffRenderer.SegKind.HUNK
                        ? LanternaTheme.subtle() : LanternaTheme.inputText();
                TextColor background = switch (segment.kind()) {
                    case ADDED, REMOVED -> wordBg;
                    case COMMON -> lineBg;
                    case HUNK -> null;
                };
                if (dim) {
                    foreground = dimColor(foreground);
                    // COMMON segments reuse lineBg, already the theme's dimmed variant, so
                    // only the added/removed word background (wordBg) still needs dimming.
                    if (segment.kind() != DiffRenderer.SegKind.COMMON) {
                        background = dimColor(background);
                    }
                }
                segments.add(new MessagePanel.Segment(segment.text(), foreground, background));
            }
            panel.appendMixed(segments);
        }
    }

    private static TmTokenizer.TokenizedCode tokenizeCode(String content, String language) {
        if (UiSettings.readSyntaxHighlightingDisabled()
                || !TmTokenizer.isSupported(language)) {
            return null;
        }
        return TmTokenizer.tokenize(content, language);
    }

    private static List<TmTokenizer.TmToken> tokenLine(
            TmTokenizer.TokenizedCode tokenized, int lineIndex) {
        return tokenized != null && lineIndex >= 0 && lineIndex < tokenized.lines().size()
            ? tokenized.lines().get(lineIndex) : List.of();
    }

    private static void appendHighlightedCodeLine(MessagePanel panel, String prefix, String line,
                                                   List<TmTokenizer.TmToken> tokens,
                                                   boolean dim) {
        List<MessagePanel.Segment> segments = new ArrayList<>();
        segments.add(new MessagePanel.Segment(prefix,
            dim ? dimColor(LanternaTheme.welcomeDim()) : LanternaTheme.welcomeDim()));
        if (tokens == null || tokens.isEmpty()) {
            segments.add(new MessagePanel.Segment(line,
                dim ? dimColor(TextColor.ANSI.DEFAULT) : TextColor.ANSI.DEFAULT));
            panel.appendMixed(segments);
            return;
        }
        int cursor = 0;
        for (TmTokenizer.TmToken token : tokens) {
            int start = Math.max(cursor, Math.min(token.start(), line.length()));
            int end = Math.max(start, Math.min(token.end(), line.length()));
            if (start > cursor) {
                TextColor plain = dim ? dimColor(TextColor.ANSI.DEFAULT) : TextColor.ANSI.DEFAULT;
                segments.add(new MessagePanel.Segment(line.substring(cursor, start), plain));
            }
            if (end > start) {
                String text = line.substring(start, end);
                TextColor color = LanternaTheme.toLC(ScopeColorMap.scopeColor(
                    token.scopes(), text, LanternaTheme.activeThemeName()));
                if (dim) color = dimColor(color);
                segments.add(new MessagePanel.Segment(text, color, null, null,
                    toLanternaStyles(ScopeColorMap.scopeStyle(token.scopes()))));
                cursor = end;
            }
        }
        if (cursor < line.length()) {
            TextColor plain = dim ? dimColor(TextColor.ANSI.DEFAULT) : TextColor.ANSI.DEFAULT;
            segments.add(new MessagePanel.Segment(line.substring(cursor), plain));
        }
        panel.appendMixed(segments);
    }

    private static Set<SGR> toLanternaStyles(Set<AnsiStyle> styles) {
        if (styles == null || styles.isEmpty()) return Set.of();
        Set<SGR> result = new HashSet<>();
        if (styles.contains(AnsiStyle.BOLD)) result.add(SGR.BOLD);
        if (styles.contains(AnsiStyle.ITALIC)) result.add(SGR.ITALIC);
        if (styles.contains(AnsiStyle.UNDERLINE)) result.add(SGR.UNDERLINE);
        return Set.copyOf(result);
    }

    private static TextColor dimColor(TextColor color) {
        if (color == null) return null;
        TextColor background = LanternaTheme.clawdBackground();
        return new TextColor.RGB(
            blend(color.getRed(), background.getRed()),
            blend(color.getGreen(), background.getGreen()),
            blend(color.getBlue(), background.getBlue()));
    }

    private static int blend(int foreground, int background) {
        return Math.clamp((foreground * 55L + background * 45L) / 100, 0, 255);
    }

    private static boolean isPlanFile(String filePath) {
        if (StringUtils.isBlank(filePath)) return false;
        return Strings.CS.startsWith(filePath, PlanFiles.getPlansDirectory().toString());
    }

    private static String relativeToCwd(String filePath) {
        if (StringUtils.isBlank(filePath)) return "";
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
            Path path = Path.of(filePath).toAbsolutePath().normalize();
            return cwd.relativize(path).toString();
        } catch (RuntimeException _) {
            return filePath;
        }
    }

    private static String diffLanguageForPath(String path) {
        if (StringUtils.isBlank(path)) return null;
        String name = Path.of(path).getFileName().toString().toLowerCase(Locale.ROOT);
        if (Strings.CS.equals(name, "dockerfile")) return "dockerfile";
        if (Strings.CS.equals(name, "makefile")) return "makefile";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return switch (name.substring(dot + 1)) {
            case "mjs", "cjs" -> "javascript";
            case "mts", "cts" -> "typescript";
            case "yml" -> "yaml";
            case "sh", "bash", "zsh" -> "shell";
            default -> name.substring(dot + 1);
        };
    }

    private static int countVisibleLines(String content) {
        if (content == null) return 0;
        if (content.isEmpty()) return 1;
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') lines++;
        }
        return Strings.CS.endsWith( content, "\n") ? lines - 1 : lines;
    }

    private static void renderInterruptedToolResult(MessagePanel panel) {
        renderInterruptedToolResult(panel, -1);
    }

    private static void renderInterruptedToolResult(MessagePanel panel, int replaceLine) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("Interrupted ", LanternaTheme.welcomeDim()),
            new MessagePanel.Segment("· What should Claude do instead?",
                LanternaTheme.welcomeDim())
        ));
    }

    private static void renderClassifierDenial(MessagePanel panel) {
        renderClassifierDenial(panel, -1);
    }

    private static void renderClassifierDenial(MessagePanel panel, int replaceLine) {
        appendOrReplaceToolResultLine(panel, replaceLine, List.of(
            new MessagePanel.Segment(INDENT_PREFIX, LanternaTheme.welcomeDim()),
            new MessagePanel.Segment(
                "Denied by auto mode classifier · /feedback if incorrect",
                LanternaTheme.welcomeDim())
        ));
    }

/** matches {@code FallbackToolUseErrorMessage}: generic errors visibly carry an Error prefix. */
    private static String fallbackToolErrorText(String text) {
        String trimmed = text != null ? text.trim() : "";
        if (Strings.CS.startsWith(trimmed, "Error: ")
                || Strings.CS.startsWith(trimmed, "Cancelled: ")) {
            return trimmed;
        }
        return "Error: " + trimmed;
    }

    /**
     * Renders a completed thinking block, adapting glyph/token colors based on {@code ctx}.
     *
     * <p>In queued-preview context, delegates to {@link HighlightedThinkingRenderer}
     * which uses {@link com.claudecode.ui.render.ThinkingStyle#forContext(RenderingContext)}
     * to pick dim/subtle styling. Outside that context the body is hidden entirely unless
     * {@code verbose} or {@code transcriptMode} is on, and is then laid out as a
     * {@link #THINKING_GUTTER} column followed by dim Markdown.
     */
    void renderThinking(ThinkingBlock thinking, MessagePanel panel, RenderingContext ctx) {

        // completed thinking is hidden in the normal non-verbose view;
        // verbose/transcript renders the full dimmed Markdown body.
        String thinkingText = thinking.thinking();
        if (StringUtils.isEmpty(thinkingText)) return;

        // Queued-preview: use HighlightedThinkingRenderer with context-driven color selection.
        if (ctx.isInQueuedPreview()) {
            HighlightedThinkingRenderer.INSTANCE.render(thinkingText, panel, ctx);
            return;
        }

        if (!verbose && !transcriptMode) return;
        // Verbose/transcript: a 2-column dim+italic "∴" gutter on the FIRST body row,
        // then the dim Markdown column. No label word and no gap row — the "Thinking"
        // wording belongs to the spinner and the queued-preview renderer, not here.
        String rendered = MARKDOWN_RENDERER.renderDimmed(
            thinkingText.trim(), markdownWidth(panel) - 2);
        List<List<MessagePanel.Segment>> mdLines = AnsiToSegments.ansiToLines(rendered, LanternaTheme.welcomeDim());
        int last = mdLines.size();
        while (last > 0 && mdLines.get(last - 1).isEmpty()) last--;
        MessagePanel.Segment gutter = new MessagePanel.Segment(THINKING_GUTTER,
            LanternaTheme.welcomeDim(), null, null, Set.of(SGR.ITALIC));
        if (last == 0) {
            // An all-whitespace body still renders the gutter: the glyph is an
            // unconditional column, not a decoration on the first text row.
            panel.appendMixed(List.of(gutter));
            return;
        }
        for (int i = 0; i < last; i++) {
            List<MessagePanel.Segment> line = mdLines.get(i);
            List<MessagePanel.Segment> indented = new ArrayList<>(line.size() + 1);
            indented.add(i == 0 ? gutter
                : new MessagePanel.Segment(THINKING_INDENT, LanternaTheme.welcomeDim()));
            for (MessagePanel.Segment segment : line) {
                indented.add(new MessagePanel.Segment(segment.text(),
                    LanternaTheme.welcomeDim(), segment.bgColor(),
                    segment.hyperlinkUrl(), segment.modifiers()));
            }
            panel.appendMixed(indented);
        }
    }

    private boolean shouldRenderCompletedThinking(String blockId) {
        if (!verbose && !transcriptMode) return false;
        return !transcriptMode
            || !hidePastThinking
            || Objects.equals(visibleTranscriptThinkingBlockId, blockId);
    }

    // ── Markdown helpers ──────────────────────────────────────────────────

    /**
     * Append ANSI-parsed markdown lines to the panel.
     */
    private void renderStreamingMarkdown(MessagePanel panel) {
        String stripped = MARKDOWN_RENDERER.stripPromptXmlTags(streamingMarkdownText.toString());
        if (!Strings.CS.startsWith(stripped, streamingStrippedMarkdownText.substring(
                0, Math.min(streamingStablePrefixLength, streamingStrippedMarkdownText.length())))) {
            panel.truncateLinesTo(streamStartSnapshot);
            streamingStablePrefixLength = 0;
            streamingHasStableContent = false;
            streamTailSnapshot = -1;
        }
        streamingStrippedMarkdownText = stripped;
        String unstableCandidate = stripped.substring(streamingStablePrefixLength);
        streamingBoundaryParseInputLengths.add(unstableCandidate.length());
        int stableAdvance = MARKDOWN_RENDERER.stablePrefixLength(unstableCandidate);
        if (streamTailSnapshot >= 0) {
            panel.truncateLinesTo(streamTailSnapshot);
        }

        if (stableAdvance > 0) {
            String newlyStable = unstableCandidate.substring(0, stableAdvance);
            if (streamingHasStableContent) {
                panel.appendLine("", TextColor.ANSI.DEFAULT);
            }
            appendMarkdownFragment(newlyStable, panel, !streamingHasStableContent);
            streamingStablePrefixLength += stableAdvance;
            streamingHasStableContent = true;
        }
        streamTailSnapshot = panel.snapshotLineCount();

        String unstableSuffix = unstableCandidate.substring(stableAdvance);
        if (!StringUtils.isBlank(unstableSuffix)) {
            if (streamingHasStableContent) {
                panel.appendLine("", TextColor.ANSI.DEFAULT);
            }
            appendMarkdownFragment(unstableSuffix, panel, !streamingHasStableContent);
        }
    }

    private void appendMarkdownFragment(String markdown, MessagePanel panel, boolean showBullet) {
        panel.appendMarkdown(markdown, MARKDOWN_RENDERER, showBullet);
    }

    private static int markdownWidth(MessagePanel panel) {
        var size = panel.getSize();
        int panelWidth = size == null || size.getColumns() <= 0 ? 80 : size.getColumns();
        return Math.max(1, panelWidth - 2); // assistant dot/hanging gutter
    }

    // ──────────────────────────────────────────────────────────────────────

}
