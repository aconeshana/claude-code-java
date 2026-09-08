package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.*;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.gateway.GatewayHeadlessSessions;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.session.SessionManager;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskStore;
import com.claudecode.tools.tasks.TaskType;
import org.assertj.core.api.Assertions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the gateway headless-session supervisor over the real gateway
 * port types: open/close lifecycle, project-path validation, the cross-project
 * permission gate, and the TaskRegistry registration the TUI pill reads.
 */
class CliHeadlessGatewaySessionsTest {

    @TempDir
    Path mainProject;
    @TempDir
    Path otherProject;

    @BeforeEach
    void isolateGlobalRegistry() {
        TaskRegistry.setGlobalForTest(new TaskRegistry(TaskStore.inMemory()));
    }

    @AfterEach
    void restoreGlobalRegistry() {
        TaskRegistry.resetGlobalForTest();
    }

    /** A minimal streaming client: the assembled engine never submits a turn here. */
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
            return "claude-sonnet-5";
        }
    }

    private CliHeadlessGatewaySessions sessions() {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(), new DefaultQuerySessionFactory(),
            new PermissionGate(
                ToolPermissionContext.of(mainProject)),
            "claude-sonnet-5", mainProject.toString());
        return new CliHeadlessGatewaySessions(factory, mainProject.toString());
    }

    @Test
    void openRegistersWebSessionTaskAndCloseCompletesIt() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened opened = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("ws-1", mainProject.toString()));

        assertThat(opened.session().info().id()).isEqualTo("ws-1");
        assertThat(opened.projectPath()).isEqualTo(mainProject.toString());
        assertThat(sessions.find("ws-1")).isPresent();

        var tasks = TaskRegistry.global().store().list().stream()
            .filter(task -> task.type() == TaskType.WEB_SESSION)
            .toList();
        assertThat(tasks).hasSize(1);
        String taskId = tasks.getFirst().id();
        assertThat(sessions.transcriptPathForTask(taskId)).isNotNull();
        assertThat(sessions.isWebSessionTask(taskId)).isTrue();

        assertThat(sessions.close("ws-1")).isTrue();
        assertThat(sessions.find("ws-1")).isEmpty();
        assertThat(sessions.isWebSessionTask(taskId)).isFalse();
        assertThat(TaskRegistry.global().store().get(taskId))
            .hasValueSatisfying(task -> assertThat(task.status())
                .isEqualTo(TaskStatus.COMPLETED));
        assertThat(sessions.close("ws-1")).isFalse();
    }

    @Test
    void listShowsOpenSessionsWithTheirProjects() {
        CliHeadlessGatewaySessions sessions = sessions();
        assertThat(sessions.list()).isEmpty();
        sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-1", mainProject.toString()));
        sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-2", mainProject.toString()));

        var listing = sessions.list();
        assertThat(listing).hasSize(2);
        assertThat(listing.getFirst().sessionId()).isEqualTo("ws-1");
        assertThat(listing.getFirst().projectPath()).isEqualTo(mainProject.toString());

        sessions.close("ws-1");
        assertThat(sessions.list()).singleElement()
            .satisfies(entry -> assertThat(entry.sessionId()).isEqualTo("ws-2"));
    }

    @Test
    void openWithoutSessionIdMintsOne() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened opened = sessions.open(
            new GatewayHeadlessSessions.OpenRequest(null, mainProject.toString()));
        assertThat(opened.session().info().id()).isNotBlank();
    }

    @Test
    void openSameIdTwiceReturnsSameSession() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened first = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("ws-dup", mainProject.toString()));
        GatewayHeadlessSessions.Opened second = sessions.open(
            new GatewayHeadlessSessions.OpenRequest("ws-dup", mainProject.toString()));
        assertThat(second.session()).isSameAs(first.session());
        assertThat(TaskRegistry.global().store().list().stream()
            .filter(task -> task.type() == TaskType.WEB_SESSION))
            .hasSize(1);
    }

    @Test
    void openWithPriorDiskTranscriptResumesMessageLevel() throws IOException {
        // Persist a conversation under the session id first.
        SessionManager manager =
            new SessionManager(mainProject.toString());
        Path transcript = manager.getSessionFile("ws-resume");
        Files.createDirectories(transcript.getParent());
        List<String> lines = new ArrayList<>();
        UserMessage user =
            new UserMessage("u-1",
                new MessageContent("prior prompt", null));
        AssistantMessage assistant =
            new AssistantMessage("a-1",
                new AssistantContent(
                    null,
                    List.of(new TextBlock("prior answer")),
                    null));
        lines.add(JsonUtils.getMapper()
            .writeValueAsString(user));
        lines.add(JsonUtils.getMapper()
            .writeValueAsString(assistant));
        Files.write(transcript, lines,
            StandardCharsets.UTF_8);

        CliHeadlessGatewaySessions sessions = sessions();
        // No explicit project_path: the resume path reads the disk transcript.
        GatewayHeadlessSessions.Opened opened =
            sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-resume", null));
        assertThat(opened.resumed()).isTrue();
    }

    @Test
    void openWithoutDiskTranscriptIsFresh() {
        CliHeadlessGatewaySessions sessions = sessions();
        GatewayHeadlessSessions.Opened opened =
            sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-fresh", null));
        assertThat(opened.resumed()).isFalse();
    }

    @Test
    void openRejectsMissingProjectDirectory() {
        CliHeadlessGatewaySessions sessions = sessions();
        String missing = otherProject.resolve("gone").toString();
        Assertions.assertThatThrownBy(() ->
            sessions.open(new GatewayHeadlessSessions.OpenRequest("ws-x", missing)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("project_path");
    }

    @Test
    void crossProjectSessionGetsItsOwnGate() throws IOException {
        CliHeadlessSessionFactory factory = new CliHeadlessSessionFactory(
            new EmptyStreamingClient(), new ToolRegistry(),
            new DefaultQuerySessionFactory(),
            new PermissionGate(
                ToolPermissionContext.of(mainProject)),
            "m", mainProject.toString());
        PermissionGate crossGate =
            factory.gateFor(otherProject.toString());
        // toRealPath resolves the macOS /var → /private/var symlink divergence.
        assertThat(crossGate.currentContext().workingDirectory().toRealPath())
            .isEqualTo(otherProject.toRealPath());
    }
}
