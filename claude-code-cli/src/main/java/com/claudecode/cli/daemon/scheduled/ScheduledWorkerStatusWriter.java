package com.claudecode.cli.daemon.scheduled;

import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class ScheduledWorkerStatusWriter {

    private final Path target;
    private final LongSupplier pid;
    private final Supplier<String> processStart;
    private final LongSupplier now;

    ScheduledWorkerStatusWriter(Path target) {
        this(target, () -> ProcessHandle.current().pid(),
            () -> ProcessHandle.current().info().startInstant()
                .map(Object::toString).orElse(null),
            System::currentTimeMillis);
    }

    ScheduledWorkerStatusWriter(Path target, LongSupplier pid,
                                Supplier<String> processStart, LongSupplier now) {
        this.target = target.toAbsolutePath().normalize();
        this.pid = pid;
        this.processStart = processStart;
        this.now = now;
    }

    synchronized void write(ScheduledWorkerSnapshot snapshot) throws IOException {
        ObjectNode root = JsonUtils.getMapper().createObjectNode();
        root.put("pid", pid.getAsLong());
        String token = processStart.get();
        if (token != null) root.put("procStart", token);
        root.put("timestamp", now.getAsLong());
        root.put("running", snapshot.running());
        root.put("queued", snapshot.queued());
        ObjectNode fired = root.putObject("lastFiredAt");
        snapshot.lastFiredAt().forEach(fired::put);
        FileUtils.atomicReplace(target,
            temporary -> JsonUtils.getMapper().writeValue(temporary.toFile(), root));
    }
}
