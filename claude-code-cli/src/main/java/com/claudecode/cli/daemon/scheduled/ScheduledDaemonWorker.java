package com.claudecode.cli.daemon.scheduled;

import com.claudecode.cli.daemon.CurrentCliCommand;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.tools.cron.CronJitterConfig;
import org.apache.commons.lang3.StringUtils;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;

@Explanation("Uses existing API-key/Bearer channels until subscriber OAuth identity is implemented")
public final class ScheduledDaemonWorker {

    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(1);
    private static final Duration PARENT_CHECK_INTERVAL = Duration.ofSeconds(30);

    private ScheduledDaemonWorker() {}

    public static void validate(String rawConfig) {
        ScheduledWorkerConfig.parse(rawConfig);
    }

    public static int run(String rawConfig, PrintStream output, PrintStream error) {
        ScheduledWorkerConfig config = ScheduledWorkerConfig.parse(rawConfig);
        PrintStream log = Objects.requireNonNull(output, "output");
        PrintStream err = Objects.requireNonNull(error, "error");
        Path statusPath = statusPath();
        ScheduledAgentQueryRunner queryRunner = new ScheduledAgentQueryRunner(
            CurrentCliCommand.resolve(), log::println);
        try (ScheduledDaemonRuntime runtime = new ScheduledDaemonRuntime(
                new ScheduledWorker(config, queryRunner, CronJitterConfig.DEFAULT,
                    System.currentTimeMillis(), log::println),
                new ScheduledWorkerStatusWriter(statusPath),
                Executors.newSingleThreadScheduledExecutor(r ->
                    Thread.ofVirtual().name("daemon-scheduled-tick").unstarted(r)),
                Executors.newSingleThreadScheduledExecutor(r ->
                    Thread.ofVirtual().name("daemon-scheduled-parent-watchdog").unstarted(r)),
                err::println)) {
            Thread shutdownHook = Thread.ofPlatform()
                .name("daemon-scheduled-shutdown")
                .unstarted(runtime::close);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                runtime.start(ProcessHandle.current().parent().orElse(null),
                    CHECK_INTERVAL, PARENT_CHECK_INTERVAL);
                log.println("scheduled worker started tasks=" + config.tasks().size()
                    + " maxConcurrent=" + config.maxConcurrent());
                runtime.awaitStop();
                return 0;
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return 0;
            } catch (Exception failure) {
                err.println("scheduled worker failed: " + failure.getMessage());
                return 1;
            } finally {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException _) { }
            }
        }
    }

    private static Path statusPath() {
        String configured = System.getenv("CLAUDE_DAEMON_SCHEDULED_STATUS_PATH");
        if (StringUtils.isNotBlank(configured)) return Path.of(configured);
        return Path.of(System.getProperty("user.dir"), ".claude",
            "daemon.scheduled.status.json");
    }
}
