package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.gateway.GatewayPermissionModePort;
import com.claudecode.gateway.GatewaySessionContextPort;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.runtime.sessionhost.SessionHostPermissionMode;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model and permission-mode seats of a session {@code claude web} opened
 * itself.
 *
 * <p>Regression cover for the webui composer of a new web session offering
 * neither a model nor a permission mode: the standalone gateway installed
 * empty context and permission-mode ports on the premise that every session
 * belongs to a TUI, so its own headless sessions answered nothing although
 * each one carries live model and permission controllers.
 */
class WebCliCommandSessionPortsTest {

    private static final String MODEL = "claude-sonnet-5";
    private static final String SESSION = "web-session";

    @TempDir
    Path project;

    private CliHeadlessGatewaySessions headless;

    @BeforeEach
    void openHeadlessSession() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(), new DefaultQuerySessionFactory(),
            new PermissionGate(ToolPermissionContext.of(project)),
            MODEL, new NoCustomModels(), true);
        headless = new CliHeadlessGatewaySessions(factory, project.toString());
        headless.open(new GatewayHeadlessSessions.OpenRequest(SESSION, project.toString()));
    }

    @AfterEach
    void closeHeadlessSession() {
        headless.closeAll();
        TaskRegistry.resetGlobalForTest();
    }

    @Test
    void aWebSessionOffersAndAcceptsAModelSelection() {
        GatewaySessionContextPort context =
            WebCliCommand.sessionContext(headless, project.toString());

        Optional<GatewaySessionContextPort.ModelSelection> selection = context.selection(SESSION);

        assertThat(selection).isPresent();
        assertThat(selection.get().current()).isEqualTo(MODEL);
        assertThat(selection.get().models()).isNotEmpty();

        String other = selection.get().models().stream()
            .map(GatewaySessionContextPort.ModelChoice::name)
            .filter(name -> !name.equals(MODEL))
            .findFirst().orElseThrow();
        GatewaySessionContextPort.SelectionResult picked = context.selectModel(SESSION, other);

        assertThat(picked.error()).isNull();
        assertThat(context.selection(SESSION).orElseThrow().current()).isNotEqualTo(MODEL);
    }

    @Test
    void aWebSessionOffersAndAcceptsAPermissionMode() {
        GatewayPermissionModePort permissions = WebCliCommand.permissionMode(headless);

        assertThat(permissions.state(SESSION)).isPresent();
        assertThat(permissions.state(SESSION).orElseThrow().modes())
            .extracting(SessionHostPermissionMode::value)
            .contains("default", "plan");

        GatewayPermissionModePort.SelectionResult picked = permissions.selectMode(SESSION, "plan");

        assertThat(picked.accepted()).isTrue();
        assertThat(permissions.state(SESSION).orElseThrow().current()).isEqualTo("plan");
    }

    @Test
    void anUnknownSessionStillHasNoSeat() {
        assertThat(WebCliCommand.sessionContext(headless, project.toString())
            .selection("not-open")).isEmpty();
        assertThat(WebCliCommand.permissionMode(headless).state("not-open")).isEmpty();
    }

    /** No turn is ever submitted here. */
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

    private static final class NoCustomModels implements CustomModelCatalog {
        @Override public List<CustomModelConfig> list() { return List.of(); }

        @Override public Optional<CustomModelConfig> find(String modelName) {
            return Optional.empty();
        }

        @Override public void save(CustomModelConfig model) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean remove(String modelName) {
            throw new UnsupportedOperationException();
        }
    }
}
