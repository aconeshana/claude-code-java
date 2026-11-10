package com.claudecode.cli;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

/**
 * {@code claude web}: runs the single global web gateway as its own process.
 *
 * <p>The gateway lists every running TUI session through their Session Link
 * sockets. Only one instance exists per user: a live discovery record is reused,
 * and an exclusive file lock serializes concurrent starts so two invocations
 * cannot both bind a gateway.
 */
@Command(
    name = "web",
    description = "Run the global web gateway that lists every running Claude Code session",
    mixinStandardHelpOptions = true
)
public class WebCliCommand implements Callable<Integer> {

    private static final long READY_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_MILLIS = 100;

    @Override
    public Integer call() throws Exception {
        Optional<WebGatewayRecord> running = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
        if (running.isPresent()) {
            System.out.println(running.get().url());
            return 0;
        }
        Path lockFile = ClaudePaths.WEB_GATEWAY_JSON.resolveSibling("web-gateway.lock");
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                return reuseWhenPublished();
            }
            Optional<WebGatewayRecord> racing = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
            if (racing.isPresent()) {
                System.out.println(racing.get().url());
                return 0;
            }
            return hostUntilStopped();
        }
    }

    private Integer reuseWhenPublished() throws InterruptedException {
        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<WebGatewayRecord> published = WebGatewayRecord.readLive(ClaudePaths.WEB_GATEWAY_JSON);
            if (published.isPresent()) {
                System.out.println(published.get().url());
                return 0;
            }
            Thread.sleep(POLL_MILLIS);
        }
        System.err.println("Another claude web is starting but did not publish its gateway in time.");
        return 1;
    }

    private Integer hostUntilStopped() throws InterruptedException {
        CliGatewayRuntime runtime = new CliGatewayRuntime(emptyRegistry());
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            runtime.close();
            stopped.countDown();
        }));
        System.out.println(runtime.start().url());
        stopped.await();
        return 0;
    }

    /**
     * The standalone gateway lists TUI sessions through the worker directory. It
     * hosts no sessions of its own: opening one needs the TUI's assembled engine,
     * tools, permission gate and model catalog, which only the interactive runner
     * builds. Creation therefore stays in the TUI and is refused here with a
     * clear message.
     */
    private static SessionHostRegistry emptyRegistry() {
        return new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override
            public CompletableFuture<SessionHostSession> activate(SessionOpenRequest request) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "claude web cannot create sessions yet; start one from a TUI"));
            }

            @Override
            public List<SessionHostInfo> list() {
                return List.of();
            }
        });
    }
}
