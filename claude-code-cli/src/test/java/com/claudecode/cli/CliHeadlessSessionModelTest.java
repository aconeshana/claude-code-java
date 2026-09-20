package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.runtime.sessionhost.SessionHostModelOption;
import com.claudecode.runtime.sessionhost.SessionHostModelState;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The model projection the gateway and webui read from a headless session.
 *
 * <p>Regression cover for the webui model list disagreeing with the TUI's: this
 * factory used to call the {@code includeBuiltIns}-blind overload of
 * {@code SessionHostModelOptions.build}, so a session on a custom endpoint
 * advertised the official Fable/Opus/Sonnet/Haiku families <em>and</em> a
 * {@code Default (recommended)} row beside the custom catalogue — the same
 * model rendered twice in the composer's picker. The TUI path
 * ({@code SessionHostPublisher}) passed the gate all along; the two projections
 * must stay identical.
 */
class CliHeadlessSessionModelTest {

    private static final String MODEL = "claude-sonnet-5";

    /** A custom endpoint whose name shadows an official family, as a relaying gateway's does. */
    private static final CustomModelConfig SHADOWING_CUSTOM = new CustomModelConfig(
        "anthropic.claude-sonnet-5", ModelApiProtocol.ANTHROPIC,
        "https://gateway.example.test", null, Map.of());

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

    /** A read-only catalogue over a fixed list; the projection never writes. */
    private record FixedCatalog(List<CustomModelConfig> models) implements CustomModelCatalog {
        @Override public List<CustomModelConfig> list() { return models; }

        @Override public Optional<CustomModelConfig> find(String modelName) {
            return models.stream().filter(m -> m.modelName().equals(modelName)).findFirst();
        }

        @Override public void save(CustomModelConfig model) {
            throw new UnsupportedOperationException();
        }

        @Override public boolean remove(String modelName) {
            throw new UnsupportedOperationException();
        }
    }

    private GatewayHeadlessSessions.Opened openSession(
            boolean showBuiltInModelFamilies, List<CustomModelConfig> custom) {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(), new DefaultQuerySessionFactory(),
            new PermissionGate(ToolPermissionContext.of(project)),
            MODEL, project.toString(), new FixedCatalog(custom), showBuiltInModelFamilies);
        return new CliHeadlessGatewaySessions(factory, project.toString()).open(
            new GatewayHeadlessSessions.OpenRequest("model-session", project.toString()));
    }

    private static List<String> names(SessionHostModelState state) {
        return state.models().stream().map(SessionHostModelOption::name).toList();
    }

    @Test
    void aCustomEndpointOffersOnlyItsOwnCatalogue() {
        SessionHostModelState state =
            openSession(false, List.of(SHADOWING_CUSTOM)).session().models().get();

        assertThat(names(state)).containsExactly("anthropic.claude-sonnet-5");
        // No official family alias, and no resolved official id sneaking in as
        // the current-model fallback row.
        assertThat(names(state)).doesNotContain("sonnet", "opus", "haiku", "fable", MODEL);
    }

    @Test
    void noProjectionOffersADefaultRecommendedRow() {
        for (boolean showBuiltIns : List.of(true, false)) {
            SessionHostModelState state =
                openSession(showBuiltIns, List.of(SHADOWING_CUSTOM)).session().models().get();

            assertThat(names(state)).doesNotContain("default");
            assertThat(state.models()).noneMatch(SessionHostModelOption::defaultOption);
        }
    }

    @Test
    void theSeatIsAConcreteModelIdNotTheDefaultSentinel() {
        SessionHostModelState state =
            openSession(true, List.of()).session().models().get();

        // Nothing picked yet: the seat reports the model that actually reaches
        // the wire, so the composer can name it instead of saying "Default".
        assertThat(state.current()).isEqualTo(MODEL);
    }

    @Test
    void pickingACustomModelSeatsItVerbatim() {
        GatewayHeadlessSessions.Opened opened = openSession(false, List.of(SHADOWING_CUSTOM));

        SessionHostModelState state =
            opened.session().models().set("anthropic.claude-sonnet-5");

        assertThat(state.current()).isEqualTo("anthropic.claude-sonnet-5");
        assertThat(opened.session().models().get().current())
            .isEqualTo("anthropic.claude-sonnet-5");
    }

    /**
     * {@code "default"} is no longer a listed choice, but a Session Link client
     * round-tripping an older {@code current} still sends it and must keep
     * clearing the preference rather than being rejected.
     */
    @Test
    void theLegacyDefaultInputStillClearsThePreference() {
        GatewayHeadlessSessions.Opened opened = openSession(true, List.of(SHADOWING_CUSTOM));
        opened.session().models().set("anthropic.claude-sonnet-5");

        SessionHostModelState cleared = opened.session().models().set("default");

        assertThat(cleared.current()).isEqualTo(MODEL);
    }

    @Test
    void anUnlistedModelIsStillRejected() {
        GatewayHeadlessSessions.Opened opened = openSession(false, List.of(SHADOWING_CUSTOM));

        assertThatThrownBy(() -> opened.session().models().set("sonnet"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("not available");
    }
}
