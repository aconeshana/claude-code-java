package com.claudecode.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DetachedWebGatewayTest {

    private static final String URL = "http://127.0.0.1:8087/?token=t";

    @TempDir
    Path home;

    @Test
    void reusesALiveGatewayInsteadOfLaunchingAnother() throws Exception {
        Path record = home.resolve("web-gateway.json");
        WebGatewayRecord.write(record, new WebGatewayRecord(ProcessHandle.current().pid(), URL, "0.2.14", 1_700_000_000_000L));
        List<List<String>> launches = new ArrayList<>();

        var started = gateway(record, launches, 1_000).start();

        assertThat(started.url()).isEqualTo(URL);
        assertThat(launches).isEmpty();
    }

    @Test
    void launchesClaudeWebAndReturnsTheURLOnceItPublishes() throws Exception {
        Path record = home.resolve("web-gateway.json");
        List<List<String>> launches = new ArrayList<>();
        List<Path> logs = new ArrayList<>();
        DetachedWebGateway.ProcessLauncher publishing = (command, log) -> {
            launches.add(command);
            logs.add(log);
            WebGatewayRecord.write(record, new WebGatewayRecord(ProcessHandle.current().pid(), URL, "0.2.14", 1_700_000_000_000L));
            return null;
        };

        var started = new DetachedWebGateway(record, home.resolve("web-gateway.log"),
            publishing, 2_000).start();

        assertThat(started.url()).isEqualTo(URL);
        assertThat(launches).hasSize(1);
        assertThat(launches.getFirst().getLast()).isEqualTo("web");
        assertThat(logs).containsExactly(home.resolve("web-gateway.log"));
    }

    @Test
    void failsWithTheLogPathWhenTheGatewayNeverPublishes() {
        Path record = home.resolve("web-gateway.json");
        Path log = home.resolve("web-gateway.log");
        DetachedWebGateway.ProcessLauncher silent = (_, _) -> null;

        assertThatThrownBy(() -> new DetachedWebGateway(record, log, silent, 200).start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(log.toString());
    }

    @Test
    void failsFastWhenTheChildProcessExitsBeforePublishing() {
        Path record = home.resolve("web-gateway.json");
        Path log = home.resolve("web-gateway.log");
        DetachedWebGateway.ProcessLauncher crashing = (_, _) ->
            new ProcessBuilder("/bin/sh", "-c", "exit 42").start();

        long startedNanos = System.nanoTime();
        assertThatThrownBy(() -> new DetachedWebGateway(record, log, crashing, 10_000).start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exited early")
            .hasMessageContaining("42")
            .hasMessageContaining(log.toString());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        assertThat(elapsedMs).isLessThan(3_000L);
    }

    @Test
    void succeedsWhenChildExitsZeroAfterRecordWasPublished() {
        Path record = home.resolve("web-gateway.json");
        Path log = home.resolve("web-gateway.log");
        DetachedWebGateway.ProcessLauncher raced = (_, _) -> {
            Process child = new ProcessBuilder("/bin/sh", "-c", "exit 0").start();
            WebGatewayRecord.write(record, new WebGatewayRecord(
                ProcessHandle.current().pid(), URL, "0.2.14", 1_700_000_000_000L));
            return child;
        };

        var started = new DetachedWebGateway(record, log, raced, 2_000).start();

        assertThat(started.url()).isEqualTo(URL);
    }

    @Test
    void failsWhenTheLauncherCannotStartTheProcess() {
        Path record = home.resolve("web-gateway.json");
        DetachedWebGateway.ProcessLauncher broken = (_, _) -> {
            throw new IOException("no such binary");
        };

        assertThatThrownBy(() -> new DetachedWebGateway(record, home.resolve("web-gateway.log"),
                broken, 200).start())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("failed to launch claude web")
            .hasCauseInstanceOf(IOException.class);
    }

    private static DetachedWebGateway gateway(Path record, List<List<String>> launches,
            long timeoutMillis) {
        return new DetachedWebGateway(record, record.resolveSibling("web-gateway.log"),
            (command, _) -> {
                launches.add(command);
                return null;
            }, timeoutMillis);
    }
}
