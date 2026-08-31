package com.claudecode.cli;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Monotonic, write-once interactive startup milestones logged as one line. */
final class CliStartupTimeline {
    private static final Logger log = LoggerFactory.getLogger(CliStartupTimeline.class);
    private static final List<String> ORDER = List.of(
        "plugin", "skills", "commands", "hooks", "setup", "watcher",
        "session-start", "scene", "hot-dialogs", "input-ready", "first-frame");

    private final long startedNanos = System.nanoTime();
    private final Map<String, Long> milestones = new ConcurrentHashMap<>();
    private final AtomicBoolean logged = new AtomicBoolean();

    void mark(String name) {
        if (name == null) return;
        milestones.putIfAbsent(name,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
        if (Strings.CS.equals("first-frame", name)) logOnce();
    }

    private void logOnce() {
        if (!logged.compareAndSet(false, true)) return;
        String rendered = ORDER.stream()
            .filter(milestones::containsKey)
            .map(name -> name + "=" + milestones.get(name) + "ms")
            .collect(Collectors.joining(" "));
        log.info("Interactive startup timeline: {}", rendered);
    }
}
