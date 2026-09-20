package com.claudecode.gateway;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AttachmentMessage;
import com.claudecode.core.message.AttachmentPayload;
import com.claudecode.core.message.AttachmentRenderer;
import com.claudecode.core.message.AttachmentTypeNames;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.DynamicSkillAttachment;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.InvokedSkillsAttachment;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.PlanModeExitAttachment;
import com.claudecode.core.message.PlanModeReentryAttachment;
import com.claudecode.core.message.PlanModeReminderAttachment;
import com.claudecode.core.message.SkillListingAttachment;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * One session's incremental context-timeline fold: the per-message surface
 * ledger behind the webui's Context tab, {@code /context} modal, and Context
 * Dashboard (vendored from dsh-context; see {@code webui/UPSTREAM.md}).
 *
 * <p>Covers the host half of dsh-context's projection units, re-expressed
 * over this product's conversation model:
 * <ul>
 *   <li>{@code src/host/fold.ts} — surface nodes per model-visible message
 *       (category, heuristic price, preview text, tool/skill identity),
 *       request records settled per answered model call (composition
 *       snapshot as dispatched + billed usage), context events (compaction,
 *       prune, inject, model switch, plan-mode toggles), the removed-node
 *       archive with {@code gone} stamps, cost buckets, and the retention
 *       bounds ({@code trimState}).</li>
 *   <li>{@code src/host/activity.ts} — the per-local-day billed-token /
 *       request ledger behind the dashboard heatmap.</li>
 *   <li>{@code src/shared/fileOps.ts} — the file-operation derivation from a
 *       settled file-tool call (kind, path, line deltas, search detail),
 *       mapped onto this product's {@code Read/Write/Edit/MultiEdit/
 *       NotebookEdit/Glob/Grep} tools.</li>
 *   <li>{@code src/host/config.ts} — {@code DEFAULT_BOUNDS}.</li>
 * </ul>
 *
 * <p>dsh folds a durable event log ({@code user/message}, {@code tool/result},
 * {@code compaction/summary}…). This product has no such log at the gateway
 * boundary: the live engine's {@code List<Message>} is the truth and
 * compaction rewrites it in place. The fold therefore <b>diffs successive
 * snapshots</b> of that list by message uuid — new rows fold in order, rows
 * that vanished are archived under the compaction boundary that replaced
 * them (or a {@code prune} event when none was logged). Sequence numbers are
 * fold-assigned and monotonic, so they survive the rewrite exactly like
 * dsh's event seqs.
 *
 * <p>Additive extension over the dsh wire: {@code agents[]} records the
 * session's {@code Task}/{@code Agent} tool calls (this product's subagents
 * are tool calls inside the parent session, not sibling sessions), feeding
 * the Agent Network card.
 */
@Explanation("Snapshot-diff fold over the live message list; dsh folds a durable event log")
final class ContextTimelineFold {

    /** dsh-context {@code host/config.ts} DEFAULT_BOUNDS. */
    static final int MAX_REQUEST_STEPS = 1_500;
    static final int MAX_KEPT_TURNS = 300;
    static final int MAX_EVENTS = 400;
    static final int MAX_NODES = 2_000;
    static final int MAX_ARCHIVE_NODES = 400;
    static final int MAX_FILE_OPS = 400;
    /** dsh-context {@code host/activity.ts} MAX_KEPT_DAYS. */
    static final int MAX_KEPT_DAYS = 400;
    /** Per-node stored content bound, matching the mirror's result excerpt. */
    static final int MAX_CONTENT_CHARS = 20_000;
    /**
     * Surface nodes that keep their content for the content endpoint (the
     * newest). Older live nodes stay on the surface for the token ledger but
     * drop their text: dsh keeps node text in the harness log, not the fold,
     * and an unbounded fold of 20k-char excerpts would grow with the session.
     */
    static final int MAX_CONTENT_NODES = 600;
    /** Tool calls whose result never arrived (an interrupted turn) are forgotten past this many. */
    private static final int MAX_PENDING_CALLS = 500;
    /** Settled model response ids remembered for usage de-duplication. */
    private static final int MAX_SETTLED_RESPONSES = 2_000;
    private static final String TRUNCATION_MARKER = "\n…[truncated]";
    private static final int MAX_AGENTS = 200;
    private static final int PREVIEW_CHARS = 80;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern REMINDER_TAGS =
        Pattern.compile("</?system-reminder>", Pattern.CASE_INSENSITIVE);

    enum Category { USER, INJECT, SKILL, ASSISTANT, TOOL;
        String wire() { return name().toLowerCase(Locale.ROOT); }
    }

    /** One model-visible message on the surface (dsh {@code SurfaceNode}). */
    static final class Node {
        final long seq;
        final Long time;
        Category cat;
        long tokens;
        int imgs;
        Long gone;
        String form;
        String name;
        String text;
        String tool;
        boolean err;
        String skill;
        List<String> calls;
        /** Full content for the on-demand content endpoint (bounded). */
        String content;

        Node(long seq, Long time, Category cat, long tokens) {
            this.seq = seq;
            this.time = time;
            this.cat = cat;
            this.tokens = tokens;
        }
    }

    /** One answered model call (dsh {@code RequestRecord}). */
    record RequestRecord(long turn, long step, long time, long seq,
                         long system, long tools, long user, long inject, long skill,
                         long assistant, long tool, long total,
                         Long prompt, Long cacheRead, Long output) {}

    /** One notable context event (dsh {@code ContextEventRecord}). */
    static final class EventRecord {
        final long seq;
        final long time;
        final String kind;
        String form;
        Long tokens;
        Integer count;
        String sub;
        String name;
        String detail;
        String from;
        String to;

        EventRecord(long seq, long time, String kind) {
            this.seq = seq;
            this.time = time;
            this.kind = kind;
        }
    }

    /** One executed file operation (dsh {@code FileOpRecord}). */
    record FileOp(long seq, String path, String kind, String tool, Long time, boolean err,
                  long added, long removed, String detail, boolean pattern) {}

    /** One subagent call of this session (this product's {@code agents[]} extension). */
    static final class AgentRecord {
        final String id;
        final long seq;
        final Long time;
        String agentId;
        String type;
        String description;
        String prompt;
        String status = "running";
        Long endSeq;
        Long endTime;
        Long tokens;
        Long durationMs;
        Long toolUses;
        String model;
        Usage usage;

        AgentRecord(String id, long seq, Long time) {
            this.id = id;
            this.seq = seq;
            this.time = time;
        }
    }

    /** Whole-session billed-token totals for one model (dsh {@code CostBucketTotals}). */
    static final class CostBucket {
        long uncached;
        long cacheRead;
        long cacheWrite;
        long output;
    }

    /** One local day's ledger row (dsh {@code ActivityDay}). */
    static final class ActivityDay {
        long tokens;
        long requests;
    }

    /** Per-tool-name completed-call tally (dsh {@code ToolTimingTotals}). */
    static final class ToolTotals {
        long calls;
        long ms;
    }

    /** An armed tool call awaiting its result. */
    private record PendingCall(String name, JsonNode input, Long time, long seq) {}

    private final ZoneId zone;
    private long nextSeq = 1;
    private long detailRev;
    private final Map<String, List<Node>> nodesByKey = new HashMap<>();
    private final List<Node> surface = new ArrayList<>();
    private final Map<Category, Long> sums = new EnumMap<>(Category.class);
    private final List<Node> archive = new ArrayList<>();
    private Long archiveFloor;
    private final List<RequestRecord> requests = new ArrayList<>();
    private final List<EventRecord> events = new ArrayList<>();
    private final List<FileOp> fileOps = new ArrayList<>();
    private Long fileOpsFloor;
    private final Map<String, PendingCall> pendingCalls = new LinkedHashMap<>();
    private final Map<String, AgentRecord> agentsByCall = new LinkedHashMap<>();
    private final TreeMap<String, ActivityDay> days = new TreeMap<>();
    private final Map<String, CostBucket> costByModel = new LinkedHashMap<>();
    private final Map<String, ToolTotals> toolTotals = new LinkedHashMap<>();
    private final Set<String> settledResponseIds = new LinkedHashSet<>();
    private long toolsMs;
    private long toolCalls;
    private long turn;
    private long step;
    private long humanInputs;
    private String lastUser;
    private String lastModel;
    private String model;
    private long systemTokens;
    private long toolsTokens;
    private Long pendingBoundarySeq;
    private Long pendingBoundaryTime;

    ContextTimelineFold() {
        this(ZoneId.systemDefault());
    }

    ContextTimelineFold(ZoneId zone) {
        this.zone = zone;
        for (Category category : Category.values()) sums.put(category, 0L);
    }

    long detailRev() {
        return detailRev;
    }

    /** The envelope prices the request records snapshot (from the breakdown port). */
    void envelope(long systemTokens, long toolsTokens) {
        this.systemTokens = Math.max(0, systemTokens);
        this.toolsTokens = Math.max(0, toolsTokens);
    }

    /** The currently selected model id, for the head's route display. */
    void model(String model) {
        if (StringUtils.isNotBlank(model)) this.model = model;
    }

    String model() {
        return model;
    }

    /**
     * Folds the current message list: new uuids fold in list order, vanished
     * uuids are archived under the compaction boundary (or a prune event).
     * Returns true when the fold state changed.
     */
    boolean sync(List<Message> messages) {
        long before = detailRev;
        Set<String> present = new HashSet<>();
        Message last = messages.isEmpty() ? null : messages.getLast();
        for (int index = 0; index < messages.size(); index++) {
            Message message = messages.get(index);
            String key = keyOf(message, index);
            present.add(key);
            if (nodesByKey.containsKey(key)) {
                if (message == last) reprice(key, message);
                continue;
            }
            nodesByKey.put(key, fold(message));
        }
        // Rows that left the list: compaction replaced them (the boundary row
        // folded above armed pendingBoundarySeq) or a rewind/prune dropped
        // them. Either way they leave the surface under one gone stamp.
        List<Node> removed = new ArrayList<>();
        for (var iterator = nodesByKey.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            if (present.contains(entry.getKey())) continue;
            removed.addAll(entry.getValue());
            iterator.remove();
        }
        if (!removed.isEmpty()) {
            removed.sort(Comparator.comparingLong((ContextTimelineFold.Node a) -> a.seq));
            long goneSeq = pendingBoundarySeq != null ? pendingBoundarySeq : allocate();
            long goneTime = pendingBoundaryTime != null ? pendingBoundaryTime
                : System.currentTimeMillis();
            long freed = 0;
            Set<Long> removedSeqs = new HashSet<>();
            for (Node node : removed) {
                removedSeqs.add(node.seq);
                freed += node.tokens;
                sums.merge(node.cat, -node.tokens, Long::sum);
                node.gone = goneSeq;
                archive.add(node);
            }
            surface.removeIf(node -> removedSeqs.contains(node.seq));
            EventRecord event = findEvent(goneSeq);
            if (event == null) {
                event = new EventRecord(goneSeq, goneTime, "prune");
                event.tokens = freed;
                event.count = removed.size();
                events.add(event);
            } else {
                // The boundary's own row was written with the engine's
                // preTokens estimate; the fold's freed sum is what the trend
                // chart actually drops by.
                event.tokens = freed;
                event.count = removed.size();
            }
            bump();
            // The boundary is spent once rows left under it. Left armed
            // otherwise: the compact_boundary row and the rewrite it
            // announces can land in two successive snapshots.
            pendingBoundarySeq = null;
            pendingBoundaryTime = null;
        }
        trim();
        return detailRev != before;
    }

    /** True before any row was folded (a fresh ledger). */
    boolean isEmpty() {
        return nodesByKey.isEmpty();
    }

    /** The surface node whose seq matches, live or archived. */
    Node nodeAt(long seq) {
        for (Node node : surface) if (node.seq == seq) return node;
        for (Node node : archive) if (node.seq == seq) return node;
        return null;
    }

    // ---------------------------------------------------------------- fold

    private List<Node> fold(Message message) {
        return switch (message) {
            case UserMessage user -> foldUser(user);
            case AssistantMessage assistant -> foldAssistant(assistant);
            case AttachmentMessage attachment -> foldAttachment(attachment);
            case SystemMessage system -> foldSystem(system);
            // Progress, hook results, tombstones, tool-use summaries, and
            // grouped rows are render bookkeeping — never model-visible.
            default -> List.of();
        };
    }

    private List<Node> foldUser(UserMessage user) {
        MessageContent content = user.message();
        Long time = timeOf(user);
        List<ContentBlock> blocks = content == null ? null : content.blocks();
        List<ToolResultBlock> results = new ArrayList<>();
        if (blocks != null) {
            for (ContentBlock block : blocks) {
                if (block instanceof ToolResultBlock result) results.add(result);
            }
        }
        if (!results.isEmpty()) {
            List<Node> nodes = new ArrayList<>(results.size());
            for (ToolResultBlock result : results) nodes.add(foldToolResult(user, result, time));
            return nodes;
        }
        long tokens = estimate(user);
        if (user.isCompactSummary()) {
            Node node = push(new Node(allocate(), time, Category.INJECT, tokens));
            node.form = "compaction";
            node.name = "compaction summary";
            String text = textOf(content);
            node.text = preview(text);
            node.content = bounded(text);
            node.imgs = imageCount(blocks);
            EventRecord event = new EventRecord(node.seq, timeOr(time), "inject");
            event.form = node.form;
            event.name = node.name;
            event.tokens = tokens;
            events.add(event);
            bump();
            return List.of(node);
        }
        if (user.isMeta()) {
            String text = textOf(content);
            Node node = push(new Node(allocate(), time, Category.INJECT, tokens));
            node.form = "reminder";
            node.name = reminderName(text);
            node.text = preview(stripReminder(text));
            node.content = bounded(text);
            node.imgs = imageCount(blocks);
            EventRecord event = new EventRecord(node.seq, timeOr(time), "inject");
            event.form = node.form;
            event.name = node.name;
            event.tokens = tokens;
            events.add(event);
            bump();
            return List.of(node);
        }
        Node node = push(new Node(allocate(), time, Category.USER, tokens));
        String text = textOf(content);
        node.text = preview(text);
        node.content = bounded(text);
        node.imgs = imageCount(blocks);
        // Human-prompted rows open the next turn — the metrics tracker's
        // opener rule (GatewayMessagesSnapshotHandler.opensTurn).
        turn += 1;
        step = 0;
        humanInputs += 1;
        if (StringUtils.isNotBlank(node.text)) lastUser = node.text;
        bump();
        return List.of(node);
    }

    private Node foldToolResult(UserMessage user, ToolResultBlock result, Long time) {
        UserMessage single = new UserMessage(user.uuid(),
            MessageContent.ofBlocks(List.of(result)));
        long tokens = estimate(single);
        PendingCall call = result.toolUseId() == null ? null
            : pendingCalls.remove(result.toolUseId());
        Node node = new Node(allocate(), time, Category.TOOL, tokens);
        node.err = result.isError();
        node.imgs = imageCount(result.content());
        String text = resultText(result);
        node.text = preview(text);
        node.content = bounded(text);
        if (call != null) {
            node.tool = call.name();
            long duration = call.time() != null && time != null
                ? Math.max(0, time - call.time()) : 0;
            toolsMs += duration;
            toolCalls += 1;
            ToolTotals totals = toolTotals.computeIfAbsent(call.name(), _ -> new ToolTotals());
            totals.calls += 1;
            totals.ms += duration;
            fileOps.addAll(fileOpsOf(node.seq, time, call.name(), call.input(), result.isError()));
            AgentRecord agent = agentsByCall.get(result.toolUseId());
            if (agent != null) settleAgent(agent, node, user.toolUseResult(), time, result.isError());
            if (Strings.CS.equals(call.name(), "Skill")) {
                // A skill load returns the skill's instructions as a tool
                // result — content injected into the model's context; it
                // lives in the skill bucket (dsh issue #66).
                node.cat = Category.SKILL;
                node.skill = skillNameOf(call.input(), text);
                EventRecord event = new EventRecord(node.seq, timeOr(time), "inject");
                event.form = "instructions";
                event.sub = "skill";
                event.name = node.skill;
                event.tokens = tokens;
                events.add(event);
            }
        }
        push(node);
        bump();
        return node;
    }

    private List<Node> foldAssistant(AssistantMessage assistant) {
        AssistantContent envelope = assistant.message();
        if (envelope == null || assistant.isApiErrorMessage()
                || Strings.CS.equals(MessageConstants.SYNTHETIC_MODEL, envelope.model())) {
            return List.of();
        }
        Long time = timeOf(assistant);
        // One API response lands as one row per content block, all sharing
        // the envelope id and usage: settle one request record per response.
        String responseId = StringUtils.isNotBlank(envelope.id()) ? envelope.id()
            : assistant.uuid();
        long seq = allocate();
        if (responseId != null && settledResponseIds.add(responseId)) {
            settle(seq, timeOr(time), envelope);
        }
        if (StringUtils.isNotBlank(envelope.model())
                && lastModel != null && !Strings.CS.equals(lastModel, envelope.model())) {
            EventRecord event = new EventRecord(seq, timeOr(time), "model");
            event.from = lastModel;
            event.to = envelope.model();
            events.add(event);
        }
        if (StringUtils.isNotBlank(envelope.model())) {
            lastModel = envelope.model();
            model = envelope.model();
        }
        Node node = new Node(seq, time, Category.ASSISTANT, estimate(assistant));
        List<ContentBlock> blocks = envelope.content();
        String text = firstText(blocks);
        if (!text.isEmpty()) {
            node.text = preview(text);
        }
        List<String> calls = new ArrayList<>();
        if (blocks != null) {
            for (ContentBlock block : blocks) {
                if (!(block instanceof ToolUseBlock use)) continue;
                calls.add(use.name());
                if (use.id() != null) {
                    pendingCalls.put(use.id(), new PendingCall(use.name(), use.input(), time, seq));
                    if (Strings.CS.equalsAny(use.name(), "Task", "Agent")) openAgent(use, seq, time);
                }
            }
        }
        if (node.text == null && !calls.isEmpty()) node.calls = calls.subList(0, Math.min(3, calls.size()));
        node.content = bounded(assistantContent(blocks));
        push(node);
        bump();
        return List.of(node);
    }

    private void settle(long seq, long time, AssistantContent envelope) {
        step += 1;
        long total = systemTokens + toolsTokens + sum(Category.USER) + sum(Category.INJECT)
            + sum(Category.SKILL) + sum(Category.ASSISTANT) + sum(Category.TOOL);
        Usage usage = envelope.usage();
        Long prompt = null;
        Long cacheRead = null;
        Long output = null;
        if (usage != null && usage != Usage.EMPTY) {
            prompt = usage.inputTokens() + usage.cacheReadInputTokens()
                + usage.cacheCreationInputTokens();
            cacheRead = usage.cacheReadInputTokens();
            output = usage.outputTokens();
            String billed = StringUtils.defaultIfBlank(envelope.model(), model);
            if (billed != null) {
                CostBucket bucket = costByModel.computeIfAbsent(billed, _ -> new CostBucket());
                bucket.uncached += usage.inputTokens();
                bucket.cacheRead += usage.cacheReadInputTokens();
                bucket.cacheWrite += usage.cacheCreationInputTokens();
                bucket.output += usage.outputTokens();
            }
            ActivityDay day = days.computeIfAbsent(dayKey(time), _ -> new ActivityDay());
            day.tokens += prompt + output;
            day.requests += 1;
        } else {
            days.computeIfAbsent(dayKey(time), _ -> new ActivityDay()).requests += 1;
        }
        requests.add(new RequestRecord(turn, step, time, seq, systemTokens, toolsTokens,
            sum(Category.USER), sum(Category.INJECT), sum(Category.SKILL),
            sum(Category.ASSISTANT), sum(Category.TOOL), total, prompt, cacheRead, output));
    }

    private List<Node> foldAttachment(AttachmentMessage attachment) {
        AttachmentPayload payload = attachment.payload();
        if (payload == null) return List.of();
        Long time = timeOf(attachment);
        long tokens = TokenEstimator.getInstance().estimatePostCompactTokenCount(List.of(attachment));
        boolean skill = payload instanceof SkillListingAttachment
            || payload instanceof InvokedSkillsAttachment
            || payload instanceof DynamicSkillAttachment;
        Node node = new Node(allocate(), time, skill ? Category.SKILL : Category.INJECT, tokens);
        String typeName = attachmentType(payload);
        node.form = "attachment";
        node.name = typeName;
        String text = renderedText(payload);
        node.text = preview(stripReminder(text));
        node.content = bounded(text);
        if (skill) node.skill = typeName;
        push(node);
        EventRecord event = new EventRecord(node.seq, timeOr(time), "inject");
        event.form = skill ? "instructions" : "attachment";
        if (skill) event.sub = "skill";
        event.name = typeName;
        event.tokens = tokens;
        events.add(event);
        if (payload instanceof PlanModeReminderAttachment
                || payload instanceof PlanModeReentryAttachment) {
            EventRecord mode = new EventRecord(node.seq, timeOr(time), "mode");
            mode.name = "plan.on";
            events.add(mode);
        } else if (payload instanceof PlanModeExitAttachment) {
            EventRecord mode = new EventRecord(node.seq, timeOr(time), "mode");
            mode.name = "plan.off";
            events.add(mode);
        }
        bump();
        return List.of(node);
    }

    private List<Node> foldSystem(SystemMessage system) {
        if (!Strings.CS.equals(system.subtype(), "compact_boundary")) return List.of();
        Long time = timeOf(system);
        long seq = allocate();
        EventRecord event = new EventRecord(seq, timeOr(time), "compaction");
        if (system.compactMetadata() != null) {
            event.form = system.compactMetadata().trigger();
            if (system.compactMetadata().preTokens() != null
                    && system.compactMetadata().postTokens() != null) {
                event.tokens = Math.max(0, system.compactMetadata().preTokens()
                    - system.compactMetadata().postTokens());
            }
        }
        events.add(event);
        pendingBoundarySeq = seq;
        pendingBoundaryTime = timeOr(time);
        bump();
        // The boundary itself is not a surface node; the key still maps so
        // the row is recognized on the next sync.
        return List.of();
    }

    /** Re-prices the newest assistant row when a still-streaming envelope grew. */
    private void reprice(String key, Message message) {
        if (!(message instanceof AssistantMessage assistant)) return;
        List<Node> nodes = nodesByKey.get(key);
        if (nodes == null || nodes.size() != 1) return;
        Node node = nodes.getFirst();
        if (node.gone != null) return;
        long tokens = estimate(assistant);
        if (tokens == node.tokens) return;
        sums.merge(node.cat, tokens - node.tokens, Long::sum);
        node.tokens = tokens;
        AssistantContent envelope = assistant.message();
        if (envelope != null) {
            String text = firstText(envelope.content());
            if (!text.isEmpty()) node.text = preview(text);
            node.content = bounded(assistantContent(envelope.content()));
        }
        bump();
    }

    // -------------------------------------------------------------- agents

    private void openAgent(ToolUseBlock use, long seq, Long time) {
        if (agentsByCall.size() >= MAX_AGENTS) {
            String oldest = agentsByCall.keySet().iterator().next();
            agentsByCall.remove(oldest);
        }
        AgentRecord agent = new AgentRecord(use.id(), seq, time);
        JsonNode input = use.input();
        if (input != null) {
            agent.type = textField(input, "subagent_type");
            agent.description = textField(input, "description");
            agent.prompt = bounded(textField(input, "prompt"));
            agent.model = textField(input, "model");
        }
        agentsByCall.put(use.id(), agent);
    }

    private void settleAgent(AgentRecord agent, Node node, Object toolUseResult,
                             Long time, boolean error) {
        agent.status = error ? "failed" : "done";
        agent.endSeq = node.seq;
        agent.endTime = time;
        if (agent.time != null && time != null) agent.durationMs = Math.max(0, time - agent.time);
        if (toolUseResult == null) return;
        JsonNode payload;
        try {
            payload = JsonUtils.getMapper().valueToTree(toolUseResult);
        } catch (RuntimeException _) {
            return;
        }
        String agentId = textField(payload, "agentId");
        if (agentId != null) agent.agentId = agentId;
        String type = textField(payload, "agentType");
        if (type != null && agent.type == null) agent.type = type;
        String resolved = textField(payload, "resolvedModel");
        if (resolved != null) agent.model = resolved;
        if (payload.path("totalTokens").isNumber()) agent.tokens = payload.path("totalTokens").asLong();
        if (payload.path("totalDurationMs").isNumber()) {
            agent.durationMs = payload.path("totalDurationMs").asLong();
        }
        if (payload.path("totalToolUseCount").isNumber()) {
            agent.toolUses = payload.path("totalToolUseCount").asLong();
        }
        String status = textField(payload, "status");
        if (Strings.CS.equals(status, "failed") || Strings.CS.equals(status, "killed")) {
            agent.status = "failed";
        }
        JsonNode usage = payload.path("usage");
        if (usage.isObject()) {
            try {
                agent.usage = JsonUtils.getMapper().treeToValue(usage, Usage.class);
            } catch (Exception _) {
                agent.usage = null;
            }
        }
    }

    // ------------------------------------------------------------ file ops

    /**
     * dsh {@code shared/fileOps.ts opsOfCall} over this product's tool names:
     * Read/NotebookEdit-view read, Write/Edit/MultiEdit/NotebookEdit write,
     * Glob/Grep search. Line deltas come off the call arguments only.
     */
    static List<FileOp> fileOpsOf(long seq, Long time, String tool, JsonNode args, boolean error) {
        String kind = switch (tool) {
            case "Read" -> "read";
            case "Write", "Edit", "MultiEdit", "NotebookEdit" -> "write";
            case "Glob", "Grep" -> "search";
            case null, default -> null;
        };
        if (kind == null || args == null || !args.isObject()) return List.of();
        String path;
        boolean pattern = false;
        String detail = null;
        if (Strings.CS.equals(kind, "search")) {
            String scoped = textField(args, "path");
            String needle = textField(args, "pattern");
            if (StringUtils.isNotBlank(scoped)) {
                path = scoped;
                detail = needle;
            } else {
                path = needle;
                pattern = true;
            }
        } else {
            path = firstText(args, "file_path", "filePath", "notebook_path", "path");
        }
        if (StringUtils.isBlank(path)) return List.of();
        long added = 0;
        long removed = 0;
        switch (tool) {
            case "Edit" -> {
                added = lines(textField(args, "new_string"));
                removed = lines(textField(args, "old_string"));
            }
            case "MultiEdit" -> {
                JsonNode edits = args.path("edits");
                if (edits.isArray()) {
                    for (JsonNode edit : edits) {
                        added += lines(textField(edit, "new_string"));
                        removed += lines(textField(edit, "old_string"));
                    }
                }
            }
            case "Write" -> added = lines(textField(args, "content"));
            case "NotebookEdit" -> added = lines(textField(args, "new_source"));
            default -> { /* reads and searches move no lines */ }
        }
        return List.of(new FileOp(seq, path, kind, tool, time, error, added, removed, detail, pattern));
    }

    /** dsh {@code linesOf}: '' is 0, a trailing newline closes its own line. */
    static long lines(String text) {
        if (StringUtils.isEmpty(text)) return 0;
        long count = 0;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return Strings.CS.endsWith(text, "\n") ? count : count + 1;
    }

    // ----------------------------------------------------------------- trim

    /** dsh {@code trimState}: whole turn-runs, then hard caps, with coverage floors. */
    private void trim() {
        if (countTurnRuns() > MAX_KEPT_TURNS) {
            int start = requests.size();
            int runs = 0;
            Long previous = null;
            for (int i = requests.size() - 1; i >= 0; i--) {
                long value = requests.get(i).turn();
                if (previous == null || value != previous) {
                    if (runs >= MAX_KEPT_TURNS) break;
                    runs++;
                    previous = value;
                }
                start = i;
            }
            requests.subList(0, start).clear();
        }
        if (requests.size() > MAX_REQUEST_STEPS) {
            requests.subList(0, requests.size() - MAX_REQUEST_STEPS).clear();
        }
        if (events.size() > MAX_EVENTS) events.subList(0, events.size() - MAX_EVENTS).clear();
        if (fileOps.size() > MAX_FILE_OPS) {
            int drop = fileOps.size() - MAX_FILE_OPS;
            fileOpsFloor = Math.max(fileOpsFloor == null ? 0 : fileOpsFloor,
                fileOps.get(drop - 1).seq());
            fileOps.subList(0, drop).clear();
        }
        if (!archive.isEmpty()) {
            int drop = 0;
            Long oldestRequest = requests.isEmpty() ? null : requests.getFirst().seq();
            if (oldestRequest != null) {
                while (drop < archive.size() && archive.get(drop).gone != null
                        && archive.get(drop).gone <= oldestRequest) {
                    drop++;
                }
            }
            if (archive.size() - drop > MAX_ARCHIVE_NODES) drop = archive.size() - MAX_ARCHIVE_NODES;
            if (drop > 0) {
                Long floor = archive.get(drop - 1).gone;
                if (floor != null) archiveFloor = Math.max(archiveFloor == null ? 0 : archiveFloor, floor);
                archive.subList(0, drop).clear();
            }
        }
        while (days.size() > MAX_KEPT_DAYS) days.pollFirstEntry();
        for (int i = 0; i < surface.size() - MAX_CONTENT_NODES; i++) surface.get(i).content = null;
        while (pendingCalls.size() > MAX_PENDING_CALLS) {
            pendingCalls.remove(pendingCalls.keySet().iterator().next());
        }
        while (settledResponseIds.size() > MAX_SETTLED_RESPONSES) {
            settledResponseIds.remove(settledResponseIds.iterator().next());
        }
    }

    private int countTurnRuns() {
        int runs = 0;
        Long previous = null;
        for (RequestRecord record : requests) {
            if (previous == null || record.turn() != previous) {
                runs++;
                previous = record.turn();
            }
        }
        return runs;
    }

    // ------------------------------------------------------------ projection

    /** The slim head (dsh {@code buildTimelineHead}), plus this product's live fields. */
    ObjectNode head(Long contextWindow, ObjectNode timing) {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        result.put("ok", true);
        if (model != null) result.put("model", model);
        // The provider is not on the wire; a Claude model id is the only
        // evidence, and a custom endpoint serving one still bills like one.
        if (Strings.CS.startsWith(model, "claude")) result.put("provider", "anthropic");
        if (contextWindow != null && contextWindow > 0) result.put("contextWindow", contextWindow);
        ObjectNode current = result.putObject("current");
        long surfaceTotal = sum(Category.USER) + sum(Category.INJECT) + sum(Category.SKILL)
            + sum(Category.ASSISTANT) + sum(Category.TOOL);
        current.put("system", systemTokens);
        current.put("tools", toolsTokens);
        current.put("user", sum(Category.USER));
        current.put("inject", sum(Category.INJECT));
        current.put("skill", sum(Category.SKILL));
        current.put("assistant", sum(Category.ASSISTANT));
        current.put("tool", sum(Category.TOOL));
        current.put("total", surfaceTotal + systemTokens + toolsTokens);
        long images = 0;
        long liveToolCalls = 0;
        for (Node node : surface) {
            images += node.imgs;
            if (node.cat == Category.TOOL || (node.cat == Category.SKILL && node.tool != null)) {
                liveToolCalls++;
            }
        }
        result.put("images", images);
        result.put("toolCalls", liveToolCalls);
        result.put("humanInputs", humanInputs);
        if (lastUser != null) result.put("lastUser", lastUser);
        ObjectNode counts = result.putObject("counts");
        Set<Long> turns = new HashSet<>();
        for (RequestRecord record : requests) turns.add(record.turn());
        int injects = 0;
        int compactions = 0;
        int prunes = 0;
        for (EventRecord event : events) {
            switch (event.kind) {
                case "inject" -> injects++;
                case "compaction" -> compactions++;
                case "prune" -> prunes++;
                default -> { /* model/mode rows are not counted */ }
            }
        }
        counts.put("turns", turns.size());
        counts.put("steps", requests.size());
        counts.put("injects", injects);
        counts.put("compactions", compactions);
        counts.put("prunes", prunes);
        if (!requests.isEmpty()) {
            RequestRecord last = requests.getLast();
            ObjectNode lastNode = result.putObject("last");
            lastNode.put("seq", last.seq());
            lastNode.put("total", last.total());
            if (last.prompt() != null) lastNode.put("prompt", last.prompt());
        }
        result.put("detailRev", detailRev);
        result.putArray("requests");
        result.putArray("events");
        result.putArray("nodes");
        result.put("droppedNodes", 0);
        result.putArray("archive");
        if (!costByModel.isEmpty()) {
            ObjectNode provider = result.putObject("cost").putObject("");
            for (var entry : costByModel.entrySet()) {
                ObjectNode peak = provider.putObject(entry.getKey()).putObject("peak");
                peak.put("uncached", entry.getValue().uncached);
                peak.put("cacheRead", entry.getValue().cacheRead);
                peak.put("cacheWrite", entry.getValue().cacheWrite);
                peak.put("output", entry.getValue().output);
            }
        }
        if (timing != null) result.set("timing", timing);
        if (systemTokens > 0) {
            ObjectNode system = result.putArray("systems").addObject();
            system.put("seq", 0);
            system.put("time", 0);
            system.put("tokens", systemTokens);
        }
        return result;
    }

    /**
     * The timing card's totals (dsh {@code TimingTotals}) from the durable
     * session fold plus this ledger's per-tool tallies. Null without a fold.
     */
    ObjectNode timing(long llmMs, long toolMs, long ttftMs, long steps) {
        ObjectNode node = JsonUtils.getMapper().createObjectNode();
        node.put("wallMs", llmMs + toolMs);
        node.put("ttftMs", ttftMs);
        node.put("genMs", Math.max(0, llmMs - ttftMs));
        node.put("calls", steps);
        node.put("toolsMs", toolsMs);
        node.put("toolCalls", toolCalls);
        ObjectNode tools = node.putObject("tools");
        toolTotals.entrySet().stream()
            .sorted(Comparator.comparingLong((Map.Entry<String, ContextTimelineFold.ToolTotals> a) -> a.getValue().ms).reversed())
            .limit(16)
            .forEach(entry -> {
                ObjectNode row = tools.putObject(entry.getKey());
                row.put("calls", entry.getValue().calls);
                row.put("ms", entry.getValue().ms);
            });
        return node;
    }

    /** The heavy collections (dsh {@code detailCollectionsOf}) plus {@code agents}. */
    void detailInto(ObjectNode result) {
        ArrayNode requestsNode = result.putArray("requests");
        for (RequestRecord record : requests) {
            ObjectNode row = requestsNode.addObject();
            row.put("turn", record.turn());
            row.put("step", record.step());
            row.put("time", record.time());
            row.put("seq", record.seq());
            row.put("system", record.system());
            row.put("tools", record.tools());
            row.put("user", record.user());
            row.put("inject", record.inject());
            row.put("skill", record.skill());
            row.put("assistant", record.assistant());
            row.put("tool", record.tool());
            row.put("total", record.total());
            if (record.prompt() != null) row.put("prompt", record.prompt());
            if (record.cacheRead() != null) row.put("cacheRead", record.cacheRead());
            if (record.output() != null) row.put("output", record.output());
        }
        ArrayNode eventsNode = result.putArray("events");
        int pointer = 0;
        for (EventRecord event : events) {
            ObjectNode row = eventsNode.addObject();
            row.put("seq", event.seq);
            row.put("time", event.time);
            row.put("kind", event.kind);
            if (event.form != null) row.put("form", event.form);
            if (event.tokens != null) row.put("tokens", event.tokens);
            if (event.count != null) row.put("count", event.count);
            if (event.sub != null) row.put("sub", event.sub);
            if (event.name != null) row.put("name", event.name);
            if (event.detail != null) row.put("detail", event.detail);
            if (event.from != null) row.put("from", event.from);
            if (event.to != null) row.put("to", event.to);
            // Attach each event to the requests around it (the chart's ✂ anchor).
            while (pointer < requests.size() && requests.get(pointer).seq() <= event.seq) pointer++;
            if (pointer < requests.size()) {
                row.put("turn", requests.get(pointer).turn());
                row.put("step", requests.get(pointer).step());
            }
            if (pointer > 0) {
                row.put("fromTurn", requests.get(pointer - 1).turn());
                row.put("fromStep", requests.get(pointer - 1).step());
            }
        }
        // The served surface: the newest MAX_NODES tail plus every pinned
        // inject/skill node older than it.
        int overflow = Math.max(0, surface.size() - MAX_NODES);
        ArrayNode nodesNode = result.putArray("nodes");
        int dropped = 0;
        long surfaceFloor = 0;
        for (int i = 0; i < overflow; i++) {
            Node node = surface.get(i);
            if (node.cat == Category.INJECT || node.cat == Category.SKILL) {
                nodesNode.add(nodeBody(node));
            } else {
                dropped++;
                surfaceFloor = Math.max(surfaceFloor, node.seq);
            }
        }
        for (int i = overflow; i < surface.size(); i++) nodesNode.add(nodeBody(surface.get(i)));
        result.put("droppedNodes", dropped);
        if (dropped > 0) result.put("surfaceFloor", surfaceFloor);
        ArrayNode archiveNode = result.putArray("archive");
        for (Node node : archive) archiveNode.add(nodeBody(node));
        if (archiveFloor != null) result.put("archiveFloor", archiveFloor);
        ArrayNode opsNode = result.putArray("fileOps");
        for (FileOp op : fileOps) {
            ObjectNode row = opsNode.addObject();
            row.put("seq", op.seq());
            row.put("path", op.path());
            row.put("kind", op.kind());
            row.put("tool", op.tool());
            if (op.time() != null) row.put("time", op.time());
            row.put("err", op.err());
            row.put("added", op.added());
            row.put("removed", op.removed());
            if (op.detail() != null) row.put("detail", op.detail());
            if (op.pattern()) row.put("pattern", true);
        }
        if (fileOpsFloor != null) result.put("fileOpsFloor", fileOpsFloor);
        ArrayNode agentsNode = result.putArray("agents");
        for (AgentRecord agent : agentsByCall.values()) {
            ObjectNode row = agentsNode.addObject();
            row.put("id", agent.id);
            row.put("seq", agent.seq);
            if (agent.time != null) row.put("time", agent.time);
            if (agent.agentId != null) row.put("agentId", agent.agentId);
            if (agent.type != null) row.put("type", agent.type);
            if (agent.description != null) row.put("description", agent.description);
            if (agent.prompt != null) row.put("prompt", agent.prompt);
            row.put("status", agent.status);
            if (agent.endSeq != null) row.put("endSeq", agent.endSeq);
            if (agent.endTime != null) row.put("endTime", agent.endTime);
            if (agent.tokens != null) row.put("tokens", agent.tokens);
            if (agent.durationMs != null) row.put("durationMs", agent.durationMs);
            if (agent.toolUses != null) row.put("toolUses", agent.toolUses);
            if (agent.model != null) row.put("model", agent.model);
            if (agent.usage != null) {
                ObjectNode usage = row.putObject("usage");
                usage.put("uncachedInputTokens", agent.usage.inputTokens());
                usage.put("outputTokens", agent.usage.outputTokens());
                usage.put("cacheReadTokens", agent.usage.cacheReadInputTokens());
                usage.put("cacheWriteTokens", agent.usage.cacheCreationInputTokens());
            }
        }
    }

    /** The per-day ledger (dsh {@code ContextActivity}). */
    ObjectNode activity() {
        ObjectNode result = JsonUtils.getMapper().createObjectNode();
        ObjectNode daysNode = result.putObject("days");
        for (var entry : days.entrySet()) {
            ObjectNode row = daysNode.putObject(entry.getKey());
            row.put("tokens", entry.getValue().tokens);
            row.put("requests", entry.getValue().requests);
        }
        return result;
    }

    private static ObjectNode nodeBody(Node node) {
        ObjectNode row = JsonUtils.getMapper().createObjectNode();
        row.put("seq", node.seq);
        if (node.time != null) row.put("time", node.time);
        row.put("cat", node.cat.wire());
        row.put("tokens", node.tokens);
        if (node.imgs > 0) row.put("imgs", node.imgs);
        if (node.gone != null) row.put("gone", node.gone);
        if (node.form != null) row.put("form", node.form);
        if (node.name != null) row.put("name", node.name);
        if (node.text != null) row.put("text", node.text);
        if (node.tool != null) row.put("tool", node.tool);
        if (node.err) row.put("err", true);
        if (node.skill != null) row.put("skill", node.skill);
        if (node.calls != null && !node.calls.isEmpty()) {
            ArrayNode calls = row.putArray("calls");
            node.calls.forEach(calls::add);
        }
        return row;
    }

    // -------------------------------------------------------------- helpers

    private Node push(Node node) {
        surface.add(node);
        sums.merge(node.cat, node.tokens, Long::sum);
        return node;
    }

    private long allocate() {
        return nextSeq++;
    }

    private void bump() {
        detailRev++;
    }

    private long sum(Category category) {
        return sums.getOrDefault(category, 0L);
    }

    private EventRecord findEvent(long seq) {
        for (int i = events.size() - 1; i >= 0; i--) {
            if (events.get(i).seq == seq) return events.get(i);
        }
        return null;
    }

    private String dayKey(long epochMillis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(epochMillis), zone).toString();
    }

    private static String keyOf(Message message, int index) {
        return message.uuid() != null ? message.uuid()
            : "anon:" + index + ":" + message.type() + ":" + System.identityHashCode(message);
    }

    private static Long timeOf(Message message) {
        return message.timestamp().map(Instant::toEpochMilli).orElse(null);
    }

    private static long timeOr(Long time) {
        return time != null ? time : System.currentTimeMillis();
    }

    private static long estimate(Message message) {
        return TokenEstimator.getInstance().estimatePostCompactTokenCount(List.of(message));
    }

    private static String textOf(MessageContent content) {
        if (content == null) return "";
        if (content.text() != null) return content.text();
        return firstTextAll(content.blocks());
    }

    private static String firstTextAll(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(String text) && text != null) body.append(text);
        }
        return body.toString();
    }

    private static String assistantContent(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : blocks) {
            switch (block) {
                case TextBlock(String text) -> body.append(text == null ? "" : text);
                case ToolUseBlock use -> body.append("[tool_use ").append(use.name()).append("] ")
                    .append(use.input() == null ? "{}" : use.input().toString());
                default -> { /* thinking and rich blocks are not part of the preview */ }
            }
            body.append('\n');
        }
        return body.toString();
    }

    private static String resultText(ToolResultBlock result) {
        if (result.content() == null) return "";
        StringBuilder body = new StringBuilder();
        for (ContentBlock block : result.content()) {
            if (block instanceof TextBlock(String text) && text != null) body.append(text);
        }
        return body.toString();
    }

    /** dsh {@code firstText}: the first non-blank text block, whitespace collapsed, 80 chars. */
    private static String firstText(List<ContentBlock> blocks) {
        if (blocks == null) return "";
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock(String text) && StringUtils.isNotBlank(text)) {
                return text;
            }
        }
        return "";
    }

    private static String preview(String text) {
        if (StringUtils.isBlank(text)) return null;
        String collapsed = WHITESPACE.matcher(text).replaceAll(" ").strip();
        return collapsed.length() > PREVIEW_CHARS ? collapsed.substring(0, PREVIEW_CHARS) : collapsed;
    }

    private static String bounded(String text) {
        if (StringUtils.isEmpty(text)) return null;
        return text.length() > MAX_CONTENT_CHARS
            ? text.substring(0, MAX_CONTENT_CHARS) + TRUNCATION_MARKER : text;
    }

    private static String stripReminder(String text) {
        return text == null ? "" : REMINDER_TAGS.matcher(text).replaceAll("");
    }

    /**
     * A readable identity for a system-reminder injection, from the markers
     * this product's attachment renderer writes into the reminder text.
     */
    static String reminderName(String text) {
        String body = stripReminder(text).strip();
        String lower = body.toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(lower, "plan mode")) return "plan-mode";
        if (Strings.CS.contains(lower, "claude.md") || Strings.CS.contains(lower, "memory file")) return "memory";
        if (Strings.CS.contains(lower, "todo")) return "todo";
        if (Strings.CS.contains(lower, "<available_skills") || Strings.CS.contains(lower, "skill")) return "skills";
        if (Strings.CS.contains(lower, "hook")) return "hook";
        if (Strings.CS.contains(lower, "task")) return "task";
        if (Strings.CS.contains(lower, "compact")) return "compaction";
        if (Strings.CS.contains(lower, "<command-name>")) return "command";
        return "system-reminder";
    }

    private static String renderedText(AttachmentPayload payload) {
        StringBuilder body = new StringBuilder();
        for (UserMessage rendered : AttachmentRenderer.render(payload)) {
            body.append(textOf(rendered.message())).append('\n');
        }
        return body.toString();
    }

    /** The payload's JSON discriminator ({@code plan_mode}, {@code skill_listing}…). */
    static String attachmentType(AttachmentPayload payload) {
        return AttachmentTypeNames.of(payload);
    }

    private static String skillNameOf(JsonNode input, String text) {
        String named = input == null ? null : firstText(input, "skill", "name", "command");
        if (StringUtils.isNotBlank(named)) return named;
        if (text != null) {
            int at = text.indexOf("<skill_content name=\"");
            if (at >= 0) {
                int start = at + "<skill_content name=\"".length();
                int end = text.indexOf('"', start);
                if (end > start) return text.substring(start, end);
            }
        }
        return "?";
    }

    private static int imageCount(List<ContentBlock> blocks) {
        if (blocks == null) return 0;
        int count = 0;
        for (ContentBlock block : blocks) {
            if (block instanceof ImageBlock) count++;
            else if (block instanceof ToolResultBlock result) count += imageCount(result.content());
        }
        return count;
    }

    private static String textField(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode value = node.path(field);
        return value.isTextual() && StringUtils.isNotBlank(value.asText()) ? value.asText() : null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textField(node, field);
            if (value != null) return value;
        }
        return null;
    }
}
