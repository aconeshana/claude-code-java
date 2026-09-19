package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.runtime.sessionhost.SessionHostEffortState;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The effort projection the gateway and webui read from a headless session.
 *
 * <p>Regression cover for the front ends disagreeing: the session host used to call the
 * ultracode-blind halves of two helper pairs, so a session set to {@code ultracode} rendered
 * as {@code xhigh} in webui and rejected {@code ultracode} with a hard 400, while the TUI
 * showed and accepted it. The fold to {@code xhigh} on the wire is deliberate and must survive.
 */
class CliHeadlessSessionEffortTest {

    /** An xhigh-capable model, so the ultracode slot is not gated by capabilities. */
    private static final String MODEL = "claude-sonnet-5";

    @TempDir
    Path project;

    @BeforeEach
    void isolateGlobalRegistry() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
    }

    @AfterEach
    void restoreGlobalRegistry() {
        TaskRegistry.resetGlobalForTest();
    }

    /** A minimal streaming client: no turn is ever submitted here. */
    private static final class EmptyStreamingClient implements StreamingClient {
        @Override
        public Iterator<StreamingClient.StreamingEvent> createStream(
                StreamingClient.StreamRequest request) {
            return new Iterator<>() {
                @Override public boolean hasNext() { return false; }
                @Override public StreamingClient.StreamingEvent next() {
                    throw new NoSuchElementException();
                }
            };
        }

        @Override
        public String getModel() {
            return MODEL;
        }
    }

    private SessionHostEffortState openEffortState() {
        return openSession().session().efforts().get();
    }

    private GatewayHeadlessSessions.Opened openSession() {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(), new DefaultQuerySessionFactory(),
            new PermissionGate(ToolPermissionContext.of(project)),
            MODEL, project.toString(), null);
        CliHeadlessGatewaySessions sessions =
            new CliHeadlessGatewaySessions(factory, project.toString());
        return sessions.open(
            new GatewayHeadlessSessions.OpenRequest("effort-session", project.toString()));
    }

    /**
     * Whether this environment's settings/env actually enable dynamic workflows. The ultracode
     * slot is gated on it, so the assertions below branch on the same reading the production
     * projection uses rather than assuming a fixed ambient configuration.
     */
    private static boolean workflowsEnabled() {
        return CliEngineAssembler.workflowsEnabled();
    }

    @Test
    void ordinaryLevelsAreOfferedAlongsideAuto() {
        SessionHostEffortState state = openEffortState();
        assertThat(state.efforts()).startsWith("auto");
        assertThat(state.efforts()).contains("low", "medium", "high", "xhigh");
        // Nothing configured yet: the wire-facing value is the model default, never blank.
        assertThat(state.current()).isEqualTo("auto");
        assertThat(state.effective()).isNotBlank();
    }

    @Test
    void ultracodeIsOfferedWhenWorkflowsBackIt() {
        assumeTrue(workflowsEnabled(), "dynamic workflows are disabled in this environment");
        assertThat(openEffortState().efforts()).contains(EffortHelpers.ULTRACODE);
    }

    @Test
    void ultracodeIsHiddenWhenWorkflowsAreOff() {
        assumeTrue(!workflowsEnabled(), "dynamic workflows are enabled in this environment");
        assertThat(openEffortState().efforts()).doesNotContain(EffortHelpers.ULTRACODE);
    }

    @Test
    void ultracodeIsSettableAndShownAsItselfButFoldsToXhighOnTheWire() {
        assumeTrue(workflowsEnabled(), "dynamic workflows are disabled in this environment");
        GatewayHeadlessSessions.Opened opened = openSession();

        SessionHostEffortState state =
            opened.session().efforts().set(EffortHelpers.ULTRACODE);

        // Accepted rather than rejected with "effort is not available for this session".
        assertThat(state.current()).isEqualTo(EffortHelpers.ULTRACODE);
        // ...and still offered afterwards, so the webui control keeps rendering the slot.
        assertThat(state.efforts()).contains(EffortHelpers.ULTRACODE);
        // The wire-facing level stays folded: ultracode never reaches the API.
        assertThat(state.effective()).isEqualTo("xhigh");
        assertThat(EffortHelpers.resolveAppliedEffort(MODEL, EffortHelpers.ULTRACODE))
            .isEqualTo("xhigh");
    }

    @Test
    void theProjectionMatchesTheOneTheTuiRenders() {
        GatewayHeadlessSessions.Opened opened = openSession();
        opened.session().efforts().set("xhigh");
        SessionHostEffortState state = opened.session().efforts().get();

        EffortHelpers.EffortProjection expected =
            EffortHelpers.projectEffort(MODEL, "xhigh", workflowsEnabled(), null);
        assertThat(state.effective()).isEqualTo(expected.effective());
        // "auto" is the host's own clear-the-override row; the rest is the shared projection.
        assertThat(state.efforts()).containsExactlyElementsOf(
            Stream.concat(
                Stream.of("auto"), expected.choices().stream()).toList());
    }

    @Test
    void anUnknownLevelIsStillRejected() {
        GatewayHeadlessSessions.Opened opened = openSession();
        assertThatThrownBy(() -> opened.session().efforts().set("turbo"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not available");
    }

    @Test
    void autoClearsTheOverride() {
        GatewayHeadlessSessions.Opened opened = openSession();
        opened.session().efforts().set("low");
        assertThat(opened.session().efforts().get().current()).isEqualTo("low");

        SessionHostEffortState cleared = opened.session().efforts().set("auto");
        assertThat(cleared.current()).isEqualTo("auto");
    }
}
