package com.claudecode.gateway;

import com.claudecode.core.message.Message;
import com.claudecode.core.metrics.SessionMetricsSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * Consumer-owned boundary for one session's model selection and context
 * usage, projected for the webui composer's model seat and context meter.
 *
 * <p>The gateway must not depend on the services, commands, or session
 * modules, so the CLI composition root injects both halves: the model
 * choices mirror the TUI {@code /model} catalogue (built-ins, custom
 * models, allowlist), and the context numbers reuse the claude-hud
 * status-line computation — {@code TokenEstimator.contextInputTokens}
 * over the session's messages divided by the model's resolved context
 * window. The port speaks only gateway-owned records plus core
 * {@code Message} rows; the gateway never re-implements token
 * accounting.
 *
 * <p>Every method addresses one session, resolved the way the protocol
 * turn runner routes {@code metadata.session_id}: a blank id means the
 * active TUI session; a known headless id means that open headless
 * session; the active TUI session's own id means the TUI engine; any
 * other known id is its on-disk transcript (a read-only target — the
 * selection halves report empty and reject changes; the usage half
 * still measures the transcript rows).
 */
public interface GatewaySessionContextPort {

    /** The session's current model and every selectable choice. */
    record ModelSelection(
            String current,
            List<ModelChoice> models,
            String effortCurrent,
            String effortEffective,
            List<String> effortChoices) {

        public ModelSelection {
            current = current == null ? "" : current;
            models = List.copyOf(models == null ? List.of() : models);
            effortCurrent = effortCurrent == null ? "" : effortCurrent;
            effortEffective = effortEffective == null ? "" : effortEffective;
            effortChoices = List.copyOf(effortChoices == null ? List.of() : effortChoices);
        }

        /** True when the session's model advertises reasoning effort levels. */
        public boolean supportsEffort() {
            return !effortChoices.isEmpty();
        }
    }

    /** One selectable model row: a built-in family, or a custom model. */
    record ModelChoice(String name, String label, String description, boolean defaultOption) {}

    /**
     * The heuristic composition split the meter's panel proportions its
     * colored bar with (dsh's {@code contextBreakdown}): the system-side
     * injections, the tool definitions, and the conversation messages.
     * Token counts come from the TUI {@code /context} analyzer's category
     * accounting — never re-estimated. Empty for sessions without a wired
     * analyzer (headless and transcript-only targets); the meter then
     * renders the single-reading form.
     */
    record ContextBreakdown(long systemTokens, long toolsTokens, long messageTokens) {

        public long total() {
            return Math.addExact(Math.addExact(systemTokens, toolsTokens), messageTokens);
        }
    }

    /** The context usage the meter renders; fields absent while unknown. */
    record ContextUsage(
            String model,
            long contextWindow,
            Long usedTokens,
            Integer usedPercentage,
            ContextBreakdown breakdown) {

        /** True once a finalized API usage anchor exists to measure from. */
        public boolean measured() {
            return usedTokens != null;
        }
    }

    /**
     * One model-visible tool definition as the request header carries it,
     * for the context browser's tool-schema rows. {@code source} names the
     * provider the way dsh-context's attribution chips do: {@code builtin}
     * for first-party tools, {@code mcp:<server>} for MCP proxies.
     */
    record HeaderTool(String name, String description, Object inputSchema, String source) {}

    /**
     * The request header content in force for a session: the system prompt
     * as the ordered parts the prompt assembler emits, plus every tool
     * definition the model sees. Served on demand only (the content is
     * large; the timeline wire carries token prices, never the text).
     */
    record HeaderContent(List<String> systemPromptParts, List<HeaderTool> tools) {
        public HeaderContent {
            systemPromptParts = List.copyOf(systemPromptParts == null ? List.of() : systemPromptParts);
            tools = List.copyOf(tools == null ? List.of() : tools);
        }
    }

    /** One live (attached) session the context dashboard can list. */
    record LiveSession(String id, String title, String cwd, java.time.Instant updatedAt) {}

    /** Result of one selection change: the refreshed state, or a rejection. */
    record SelectionResult(ModelSelection selection, String error) {

        public static SelectionResult accepted(ModelSelection selection) {
            return new SelectionResult(selection, null);
        }

        public static SelectionResult rejected(String error) {
            return new SelectionResult(null, error);
        }

        public boolean accepted() {
            return error == null;
        }
    }

    /**
     * The authoritative model selection for {@code sessionId}, or empty
     * for a session without live model control (none active, or a
     * transcript-only id).
     */
    default Optional<ModelSelection> selection(String sessionId) {
        return Optional.empty();
    }

    /**
     * The message list backing the usage measurement: the live engine's
     * rows for the active TUI session (blank id or its own id) or an open
     * headless session; the on-disk transcript for any other known
     * session id. The gateway computes the usage numbers itself from
     * these rows; the implementation never re-implements token counting.
     */
    default Optional<List<Message>> messages(String sessionId) {
        return Optional.empty();
    }

    /**
     * The heuristic context composition for the session's model and
     * toolchain — the same category accounting the TUI {@code /context}
     * visualization computes (system prompt, tool definitions, memory,
     * agents, skills, messages). Empty when no analyzer is wired for
     * this session; the meter then renders the single-reading form.
     */
    default Optional<ContextBreakdown> breakdown(String sessionId) {
        return Optional.empty();
    }

    /**
     * The addressed session's durable whole-session HUD fold — the same
     * {@code SessionMetricsTracker} fold the TUI status line renders. The
     * contract mirrors {@link #messages}: the port only projects the fold
     * the engine already maintains; the gateway never recomputes it, and
     * {@code INCOMPLETE} coverage is reported as absent so a partial fold
     * is never displayed as a session total
     * ({@code docs/hud-metrics-specification.md} §6).
     */
    default Optional<SessionMetricsSnapshot> metrics(String sessionId) {
        return Optional.empty();
    }

    /**
     * The request header content (system prompt parts + tool definitions)
     * the addressed live session sends with its next request, for the
     * context browser's System and Tools sections. Empty for sessions
     * without a live engine (transcript-only ids), whose header cannot be
     * reconstructed.
     */
    default Optional<HeaderContent> headerContent(String sessionId) {
        return Optional.empty();
    }

    /**
     * Every session with a live engine in this process — the active TUI
     * session plus the open headless sessions — for the context dashboard's
     * cross-session overview. Never lists transcript-only sessions.
     */
    default List<LiveSession> liveSessions() {
        return List.of();
    }

    /**
     * Applies {@code model} to {@code sessionId}. Implementations
     * validate against the same catalogue the TUI {@code /model} command
     * enforces; a rejected name returns a message suitable for display.
     */
    default SelectionResult selectModel(String sessionId, String model) {
        return SelectionResult.rejected("model selection is not configured");
    }

    /**
     * Applies {@code effort} to {@code sessionId} ({@code "auto"} clears
     * the override). Rejected when the model advertises no effort levels.
     */
    default SelectionResult selectEffort(String sessionId, String effort) {
        return SelectionResult.rejected("effort selection is not configured");
    }
}
