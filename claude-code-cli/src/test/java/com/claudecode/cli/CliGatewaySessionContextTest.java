package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.commands.context.ContextData;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewaySessionContextPort;
import com.claudecode.runtime.sessionhost.*;
import com.claudecode.runtime.turn.SessionEventHub;
import com.claudecode.runtime.turn.SessionSink;
import com.claudecode.runtime.turn.TurnOutcome;
import com.claudecode.runtime.turn.UserInput;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The gateway session-context adapter's routing: a blank session id means the
 * registry's active TUI session (its live controllers — the same control path
 * the TUI /model picker and Session Link endpoints drive); an open headless id
 * routes to that session; any other known id is a transcript-only target.
 */
class CliGatewaySessionContextTest {

    /** The active TUI session's published id — what the webui addresses it by. */
    private static final String ACTIVE_ID = "sess-1";

    /** Mutable controller double: one current model over a fixed catalogue. */
    private static final class SwitchableModels implements SessionHostModelController {
        final AtomicReference<String> current = new AtomicReference<>("sonnet");

        @Override public SessionHostModelState get() {
            return new SessionHostModelState(current.get(), List.of(
                new SessionHostModelOption("default", "Default", "the launch model", true),
                new SessionHostModelOption("sonnet", "Sonnet", "balanced", false),
                new SessionHostModelOption("opus", "Opus", "deepest reasoning", false)));
        }

        @Override public SessionHostModelState set(String selected) {
            if (get().models().stream()
                    .noneMatch(option -> option.name().equals(selected))) {
                throw new IllegalArgumentException("model is not available for this session");
            }
            current.set(selected);
            return get();
        }
    }

    /** Mutable effort double: auto/low/high with a settable current. */
    private static final class SwitchableEfforts implements SessionHostEffortController {
        final AtomicReference<String> current = new AtomicReference<>("auto");

        @Override public SessionHostEffortState get() {
            return new SessionHostEffortState(current.get(), current.get(),
                List.of("auto", "low", "high"));
        }

        @Override public SessionHostEffortState set(String selected) {
            if (!get().efforts().contains(selected)) {
                throw new IllegalArgumentException("effort is not available for this session");
            }
            current.set(selected);
            return get();
        }
    }

    /** Headless-session double: a controller-backed host per open id. */
    private static final class RecordingHeadlessSessions
            implements GatewayHeadlessSessions {
        final Map<String, SessionHostSession> open = new ConcurrentHashMap<>();

        void open(String sessionId, SessionHostModelController models,
                SessionHostEffortController efforts) {
            open.put(sessionId, new SessionHostSession(
                new SessionHostInfo(sessionId, "/work", "", 0, Instant.now(), "main"),
                new SessionEventHub(new NoopSink(), _ -> {}),
                _ -> CompletableFuture.completedFuture(null),
                models, efforts));
        }

        @Override public Optional<SessionHostSession> find(String sessionId) {
            return Optional.ofNullable(sessionId == null ? null : open.get(sessionId));
        }
    }

    private static SessionHostRegistry registryWith(
            SwitchableModels models, SwitchableEfforts efforts) {
        SessionHostRegistry registry = new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override public CompletableFuture<SessionHostSession> activate(
                        SessionOpenRequest request) {
                    throw new UnsupportedOperationException("not used by these tests");
                }
                @Override public List<SessionHostInfo> list() { return List.of(); }
            });
        registry.activateLocal(new SessionHostSession(
            new SessionHostInfo(ACTIVE_ID, "/work", "", 0, Instant.now(), "main"),
            new SessionEventHub(new NoopSink(), _ -> {}),
            _ -> CompletableFuture.completedFuture(null),
            models, efforts));
        return registry;
    }

    private static SessionHostRegistry emptyRegistry() {
        return new SessionHostRegistry(
            new SessionHostRegistry.Activator() {
                @Override public CompletableFuture<SessionHostSession> activate(
                        SessionOpenRequest request) {
                    throw new UnsupportedOperationException("not used by these tests");
                }
                @Override public List<SessionHostInfo> list() { return List.of(); }
            });
    }

    @Test
    void selectionProjectsActiveSessionControllers() {
        SwitchableModels models = new SwitchableModels();
        SwitchableEfforts efforts = new SwitchableEfforts();
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(models, efforts), null, null, "/work", null);

        GatewaySessionContextPort.ModelSelection selection =
            port.selection(null).orElseThrow();
        assertThat(selection.current()).isEqualTo("sonnet");
        assertThat(selection.models())
            .extracting(GatewaySessionContextPort.ModelChoice::name)
            .containsExactly("default", "sonnet", "opus");
        assertThat(selection.effortChoices()).containsExactly("auto", "low", "high");
    }

    /**
     * The webui addresses its conversation by the active session's own id
     * (the registry's published {@link SessionHostInfo#id()}), never by a
     * blank id. Both spellings must resolve to the same live controllers, or
     * the model seat renders on the blank-id answer and then vanishes the
     * moment the sidebar selection lands.
     */
    @Test
    void activeSessionIdResolvesLikeABlankIdForSelectionAndSelections() {
        SwitchableModels models = new SwitchableModels();
        SwitchableEfforts efforts = new SwitchableEfforts();
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(models, efforts), null, null, "/work", null);

        GatewaySessionContextPort.ModelSelection selection =
            port.selection(ACTIVE_ID).orElseThrow();
        assertThat(selection.current()).isEqualTo("sonnet");
        assertThat(selection.models())
            .extracting(GatewaySessionContextPort.ModelChoice::name)
            .containsExactly("default", "sonnet", "opus");

        GatewaySessionContextPort.SelectionResult applied =
            port.selectModel(ACTIVE_ID, "opus");
        assertThat(applied.accepted()).isTrue();
        assertThat(models.current.get()).isEqualTo("opus");

        GatewaySessionContextPort.SelectionResult effort =
            port.selectEffort(ACTIVE_ID, "high");
        assertThat(effort.accepted()).isTrue();
        assertThat(efforts.current.get()).isEqualTo("high");
    }

    /** An id that is neither open nor active stays transcript-only. */
    @Test
    void unknownSessionIdStillReportsNoLiveSelection() {
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(new SwitchableModels(), new SwitchableEfforts()),
                null, null, "/work", null);

        assertThat(port.selection("sess-2")).isEmpty();
        GatewaySessionContextPort.SelectionResult rejected = port.selectModel("sess-2", "opus");
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.error()).contains("session is not open");
    }

    /**
     * Without a live engine there is no durable fold to project: a null
     * engine serves no blank-id metrics, and an unknown id (transcript-only)
     * never re-derives one from the visible rows (spec §6).
     */
    @Test
    void metricsStayEmptyWithoutALiveEngineOrForTranscriptOnlyIds() {
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(new SwitchableModels(), new SwitchableEfforts()),
                null, null, "/work", null);
        assertThat(port.metrics(null)).isEmpty();
        assertThat(port.metrics("sess-2")).isEmpty();
    }

    /**
     * A headless double that is not the concrete CLI supervisor cannot
     * expose its engine — the concrete-cast seam degrades to empty instead
     * of guessing at the fold.
     */
    @Test
    void metricsOverANonCliHeadlessDoubleStayEmpty() {
        RecordingHeadlessSessions headless = new RecordingHeadlessSessions();
        headless.open("web-1", new SwitchableModels(), new SwitchableEfforts());
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(new SwitchableModels(), new SwitchableEfforts()),
                null, headless, "/work", null);
        assertThat(port.metrics("web-1")).isEmpty();
    }

    @Test
    void selectModelDrivesTheLiveControllerAndUnknownNamesAreRejected() {
        SwitchableModels models = new SwitchableModels();
        SwitchableEfforts efforts = new SwitchableEfforts();
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(models, efforts), null, null, "/work", null);

        GatewaySessionContextPort.SelectionResult applied = port.selectModel(null, "opus");
        assertThat(applied.accepted()).isTrue();
        assertThat(applied.selection().current()).isEqualTo("opus");
        assertThat(models.current.get()).isEqualTo("opus");

        GatewaySessionContextPort.SelectionResult rejected = port.selectModel(null, "nope");
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.error()).contains("model is not available");
        assertThat(models.current.get()).isEqualTo("opus");
    }

    @Test
    void selectEffortDrivesTheLiveController() {
        SwitchableModels models = new SwitchableModels();
        SwitchableEfforts efforts = new SwitchableEfforts();
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(models, efforts), null, null, "/work", null);

        GatewaySessionContextPort.SelectionResult applied = port.selectEffort(null, "high");
        assertThat(applied.accepted()).isTrue();
        assertThat(applied.selection().effortCurrent()).isEqualTo("high");
        assertThat(efforts.current.get()).isEqualTo("high");

        GatewaySessionContextPort.SelectionResult rejected = port.selectEffort(null, "turbo");
        assertThat(rejected.accepted()).isFalse();
        assertThat(rejected.error()).contains("effort is not available");
    }

    @Test
    void emptyRegistryLeavesSelectionEmptyAndSelectionsRejected() {
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                emptyRegistry(), null, null, "/work", null);

        assertThat(port.selection(null)).isEmpty();
        assertThat(port.selectModel(null, "sonnet").accepted()).isFalse();
        assertThat(port.selectEffort(null, "high").accepted()).isFalse();
    }

    @Test
    void openHeadlessSessionRoutesSelectionAndSelectionsToItsControllers() {
        SwitchableModels tuiModels = new SwitchableModels();
        SwitchableEfforts tuiEfforts = new SwitchableEfforts();
        SwitchableModels headlessModels = new SwitchableModels();
        SwitchableEfforts headlessEfforts = new SwitchableEfforts();
        RecordingHeadlessSessions headless = new RecordingHeadlessSessions();
        headless.open("web-1", headlessModels, headlessEfforts);
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(tuiModels, tuiEfforts), null, headless, "/work", null);

        GatewaySessionContextPort.SelectionResult applied =
            port.selectModel("web-1", "opus");
        assertThat(applied.accepted()).isTrue();
        assertThat(headlessModels.current.get()).isEqualTo("opus");
        // The TUI session keeps its own model: one session's selection never
        // leaks into its sibling.
        assertThat(tuiModels.current.get()).isEqualTo("sonnet");

        GatewaySessionContextPort.ModelSelection selection =
            port.selection("web-1").orElseThrow();
        assertThat(selection.current()).isEqualTo("opus");

        // A closed headless id is transcript-only: no live selection.
        assertThat(port.selection("web-2")).isEmpty();
        GatewaySessionContextPort.SelectionResult closed =
            port.selectModel("web-2", "opus");
        assertThat(closed.accepted()).isFalse();
        assertThat(closed.error()).contains("session is not open");
    }

    @Test
    void breakdownMapsContextDataCategoriesOntoTheDshSplit() throws Exception {
        SwitchableModels models = new SwitchableModels();
        SwitchableEfforts efforts = new SwitchableEfforts();
        ContextData data = new ContextData(
            List.of(
                new ContextData.Category("System prompt", 3_000, ContextData.ContextColor.PROMPT_BORDER),
                new ContextData.Category("System tools", 4_000, ContextData.ContextColor.INACTIVE),
                new ContextData.Category("MCP tools", 1_000, ContextData.ContextColor.CYAN),
                new ContextData.Category("Custom agents", 500, ContextData.ContextColor.PERMISSION),
                new ContextData.Category("Memory files", 1_200, ContextData.ContextColor.CLAUDE),
                new ContextData.Category("Skills", 300, ContextData.ContextColor.WARNING),
                new ContextData.Category("Messages", 47_000, ContextData.ContextColor.PURPLE),
                new ContextData.Category(ContextData.AUTOCOMPACT_BUFFER, 13_000, ContextData.ContextColor.INACTIVE),
                new ContextData.Category(ContextData.FREE_SPACE, 130_000, ContextData.ContextColor.PROMPT_BORDER)),
            50_000, 200_000, 25, "claude-sonnet-5",
            List.of(), List.of(), List.of(), null, null,
            167_000L, "model-default", true,
            new ContextData.MessageBreakdown(47_000, 100, 200, 300, 400, List.of()),
            null);
        GatewaySessionContextPort port =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(models, efforts), null, null, "/work", () -> data);

        // The collector runs on a background single-flight pass: the first
        // GET starts it and serves the single-reading form, later GETs serve
        // the landed snapshot. The analyzer makes real count-tokens calls,
        // so the request path itself must never wait on it.
        assertThat(port.breakdown(null)).isEmpty();
        awaitAtMost5s(() -> assertThat(port.breakdown(null)).isPresent());
        GatewaySessionContextPort.ContextBreakdown breakdown =
            port.breakdown(null).orElseThrow();
        // System side: prompt + memory + agents + skills = 3000+1200+500+300.
        assertThat(breakdown.systemTokens()).isEqualTo(5_000);
        // Tools: built-in + MCP = 4000+1000.
        assertThat(breakdown.toolsTokens()).isEqualTo(5_000);
        // Messages carry over unchanged; reserved buffers never enter the split.
        assertThat(breakdown.messageTokens()).isEqualTo(47_000);
        assertThat(breakdown.total()).isEqualTo(57_000);
    }

    @Test
    void breakdownIsEmptyWithoutACollectorOnAnalyzerFailureOrForHeadlessSessions() {
        SwitchableModels models = new SwitchableModels();
        SwitchableEfforts efforts = new SwitchableEfforts();
        GatewaySessionContextPort noCollector =
            CliInteractiveSessionRunner.gatewaySessionContext(
                registryWith(models, efforts), null, null, "/work", null);
        assertThat(noCollector.breakdown(null)).isEmpty();

        GatewaySessionContextPort failing = CliInteractiveSessionRunner.gatewaySessionContext(
            registryWith(models, efforts), null, null, "/work", () -> {
                throw new IllegalStateException("analyzer is not wired");
            });
        // A failing analyzer never lands a snapshot: the GET above stays
        // non-blocking and keeps serving the single-reading meter form.
        assertThat(failing.breakdown(null)).isEmpty();

        // Only the TUI session has a wired analyzer; headless targets render
        // the meter's single-reading form.
        RecordingHeadlessSessions headless = new RecordingHeadlessSessions();
        headless.open("web-1", new SwitchableModels(), new SwitchableEfforts());
        GatewaySessionContextPort withHeadless = CliInteractiveSessionRunner.gatewaySessionContext(
            registryWith(models, efforts), null, headless, "/work", () -> {
                throw new IllegalStateException("must not be consulted for headless");
            });
        assertThat(withHeadless.breakdown("web-1")).isEmpty();
    }

    /** Polls {@code assertion} until it passes or 5 seconds elapse (background refresh). */
    private static void awaitAtMost5s(Runnable assertion) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError pending) {
                if (System.nanoTime() >= deadline) throw pending;
            }
            Thread.sleep(20);
        }
    }

    private record NoopSink() implements SessionSink {
        @Override public void onTurnStart(UserInput input) {}
        @Override public void onMessage(SDKMessage msg) {}
        @Override public void onError(Throwable error, boolean userCancel) {}
        @Override public void onTurnComplete(TurnOutcome outcome) {}
        @Override public void onIdle() {}
    }
}
