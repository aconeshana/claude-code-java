package com.claudecode.cli.daemon;

import com.claudecode.cli.daemon.scheduled.ScheduledDaemonWorker;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.OptionalInt;
import org.apache.commons.lang3.Strings;

/** Hidden configured-worker dispatcher executed before ordinary Picocli parsing. */
public final class DaemonWorkerDispatcher {

    private DaemonWorkerDispatcher() {}

    public static OptionalInt tryRun(
            String[] args, InputStream input, PrintStream output, PrintStream error) {
        if (args == null || args.length < 2
                || (!Strings.CS.equals("--daemon-worker", args[0])
                    && !Strings.CS.equals("--daemon-supervisor", args[0]))) {
            return OptionalInt.empty();
        }
        InputStream workerInput = Objects.requireNonNull(input, "input");
        PrintStream workerOutput = Objects.requireNonNull(output, "output");
        PrintStream err = Objects.requireNonNull(error, "error");
        boolean supervisorMode = Strings.CS.equals("--daemon-supervisor", args[0]);
        String kind = args[1];
        if (!Strings.CS.equals("scheduled", kind)) {
            err.println("unknown daemon " + (supervisorMode ? "supervisor" : "worker")
                + " kind: " + kind);
            return OptionalInt.of(2);
        }
        try {
            String config = new String(workerInput.readAllBytes(), StandardCharsets.UTF_8);
            ScheduledDaemonWorker.validate(config);
            if (supervisorMode) {
                DaemonSupervisor supervisor = new DaemonSupervisor();
                Thread shutdown = Thread.ofPlatform().name("daemon-supervisor-shutdown")
                    .unstarted(supervisor::close);
                Runtime.getRuntime().addShutdownHook(shutdown);
                try {
                    supervisor.run(kind, config);
                    return OptionalInt.of(0);
                } finally {
                    supervisor.close();
                    try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException _) { }
                }
            }
            return OptionalInt.of(ScheduledDaemonWorker.run(config, workerOutput, err));
        } catch (IllegalArgumentException failure) {
            err.println(failure.getMessage());
            return OptionalInt.of(2);
        } catch (Exception failure) {
            err.println("failed to read daemon worker config: " + failure.getMessage());
            return OptionalInt.of(2);
        }
    }
}
