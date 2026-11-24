package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.interaction.InteractionCoordinator;
import com.claudecode.runtime.interaction.InteractionEndpoint;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.interaction.InteractionPresenter;
import com.claudecode.runtime.interaction.InteractionRequest;
import com.claudecode.runtime.interaction.InteractionSupport;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies Web Daemon headless session parity and multi-session concurrency:
 * <ul>
 *   <li>M3 cross-project inactive sessions open as locally owned and runnable;</li>
 *   <li>Concurrent headless sessions in the same workspace get isolated
 *       {@link PermissionGate} instances;</li>
 *   <li>Concurrent permission asks across parallel headless sessions carry
 *       their own {@code sessionId} via {@link ScopedValue} and resolve
 *       independently without cross-talk.</li>
 * </ul>
 */
@Timeout(20)
class HeadlessConcurrencyAndParityTest {

    @TempDir
    Path mainProject;
    @TempDir
    Path otherProject;

    @BeforeEach
    void isolateTaskRegistry() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
    }

    @AfterEach
    void restoreTaskRegistry() {
        TaskRegistry.resetGlobalForTest();
    }

    private static final class EmptyStreamingClient implements StreamingClient {
        @Override
        public Iterator<StreamingEvent> createStream(StreamRequest request) {
            return new Iterator<>() {
                @Override public boolean hasNext() { return false; }
                @Override public StreamingEvent next() { throw new NoSuchElementException(); }
            };
        }

        @Override
        public String getModel() {
            return "claude-sonnet-5";
        }
    }

    private CliHeadlessGatewaySessions newSessions(InteractionCoordinator interactions) {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(), new DefaultQuerySessionFactory(),
            new PermissionGate(ToolPermissionContext.of(mainProject)),
            "claude-sonnet-5", mainProject.toString(), null, true, interactions);
        return new CliHeadlessGatewaySessions(factory, mainProject.toString());
    }

    @Test
    void m3_inactiveCrossProjectSessionIsLocallyOwnedAndRunnable() throws IOException {
        CliHeadlessGatewaySessions sessions = newSessions(null);
        GatewayHeadlessSessions.Opened opened = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("cross-1", otherProject.toString()));

        assertThat(opened.session().info().id()).isEqualTo("cross-1");
        assertThat(Path.of(opened.projectPath()).toRealPath())
            .isEqualTo(otherProject.toRealPath());
        assertThat(sessions.find("cross-1")).isPresent();
        assertThat(sessions.liveEngine("cross-1")).isPresent();
    }

    @Test
    void concurrentHeadlessSessionsHaveIndependentPermissionGates() {
        CliHeadlessGatewaySessions sessions = newSessions(null);
        GatewayHeadlessSessions.Opened s1 = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("hl-iso-1", mainProject.toString()));
        GatewayHeadlessSessions.Opened s2 = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("hl-iso-2", mainProject.toString()));

        assertThat(s1.session().permissions().get().current()).isEqualTo("default");
        assertThat(s2.session().permissions().get().current()).isEqualTo("default");

        // Switching s1 to plan mode must NOT mutate concurrent session s2.
        s1.session().permissions().set("plan");
        assertThat(s1.session().permissions().get().current()).isEqualTo("plan");
        assertThat(s2.session().permissions().get().current()).isEqualTo("default");
    }

    @Test
    void concurrentHeadlessPermissionAsksResolveIndependently() throws Exception {
        try (InteractionCoordinator coordinator =
                 new InteractionCoordinator(CliHeadlessSessionFactory::currentSessionIdOrEmpty)) {
            var requestsBySession =
                new ConcurrentHashMap<String, InteractionRequest<PermissionAskContext, PermissionAskCallback.Result>>();
            CountDownLatch bothArrived = new CountDownLatch(2);

            InteractionPresenter<PermissionAskContext, PermissionAskCallback.Result> presenter =
                new InteractionPresenter<>() {
                    @Override public InteractionEndpoint endpoint() { return InteractionEndpoint.REMOTE; }
                    @Override public InteractionSupport support() { return InteractionSupport.SUPPORTED; }
                    @Override public boolean available(String sessionId) { return true; }
                    @Override public void present(
                            InteractionRequest<PermissionAskContext, PermissionAskCallback.Result> request) {
                        requestsBySession.put(request.descriptor().sessionId(), request);
                        bothArrived.countDown();
                    }
                };
            try (AutoCloseable ignored = coordinator.register(
                    InteractionFeatures.PERMISSION, presenter)) {
                CliHeadlessGatewaySessions sessions = newSessions(coordinator);
                sessions.open(new GatewayHeadlessSessions.OpenRequest("hl-perm-1", mainProject.toString()));
                sessions.open(new GatewayHeadlessSessions.OpenRequest("hl-perm-2", mainProject.toString()));

                PermissionAskCallback cb1 = sessions.liveEngine("hl-perm-1").orElseThrow()
                    .execution().getPermissionAskCallback();
                PermissionAskCallback cb2 = sessions.liveEngine("hl-perm-2").orElseThrow()
                    .execution().getPermissionAskCallback();
                assertThat(cb1).isNotNull();
                assertThat(cb2).isNotNull();

                PermissionAskContext ctx1 = new PermissionAskContext(
                    "Bash", JsonUtils.getMapper().createObjectNode().put("command", "echo one"),
                    "tu-1", null, null, null, null, null, null, null, null, null, List.of(), null);
                PermissionAskContext ctx2 = new PermissionAskContext(
                    "Bash", JsonUtils.getMapper().createObjectNode().put("command", "echo two"),
                    "tu-2", null, null, null, null, null, null, null, null, null, List.of(), null);

                CompletableFuture<PermissionAskCallback.Result> res1 =
                    CompletableFuture.supplyAsync(() -> cb1.ask(ctx1));
                CompletableFuture<PermissionAskCallback.Result> res2 =
                    CompletableFuture.supplyAsync(() -> cb2.ask(ctx2));

                assertThat(bothArrived.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(requestsBySession).containsKeys("hl-perm-1", "hl-perm-2");

                // Resolve out of order: deny session 2 first, allow session 1 second.
                boolean responded2 = coordinator.respond(
                    InteractionFeatures.PERMISSION,
                    requestsBySession.get("hl-perm-2").descriptor().id(),
                    "hl-perm-2",
                    PermissionAskCallback.Result.deny(),
                    InteractionEndpoint.REMOTE);
                boolean responded1 = coordinator.respond(
                    InteractionFeatures.PERMISSION,
                    requestsBySession.get("hl-perm-1").descriptor().id(),
                    "hl-perm-1",
                    PermissionAskCallback.Result.allow(),
                    InteractionEndpoint.REMOTE);

                assertThat(responded2).isTrue();
                assertThat(responded1).isTrue();
                assertThat(res2.get(5, TimeUnit.SECONDS).allowed()).isFalse();
                assertThat(res1.get(5, TimeUnit.SECONDS).allowed()).isTrue();
            }
        }
    }
}
