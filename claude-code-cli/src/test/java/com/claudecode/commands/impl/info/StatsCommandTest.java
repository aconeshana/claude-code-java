package com.claudecode.commands.impl.info;


import org.apache.commons.lang3.Strings;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandResult;
import com.claudecode.core.message.Usage;
import com.claudecode.session.stats.SessionFileEnumerator;
import com.claudecode.session.stats.StatsAggregator;
import com.claudecode.session.stats.StatsCacheStore;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.commands.testing.ProviderTestCommandPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;


class StatsCommandTest {

    private static CommandContext.Builder builder(Path cwd) {
        return CommandContext.builder(
            "test-model", List::of, () -> {}, _ -> {},
            () -> Usage.EMPTY, _ -> 0.0,
            cwd.toString(), false);
    }

    private static StatsAggregator tempAggregator(Path tmp) throws Exception {
        Path projects = tmp.resolve("projects");
        Path proj = projects.resolve("-Users-x-p");
        Files.createDirectories(proj);
        String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        Files.writeString(proj.resolve("s1.jsonl"),
            "{\"type\":\"user\",\"timestamp\":\"" + ts + "\",\"isSidechain\":false,\"message\":{}}\n"
            + "{\"type\":\"assistant\",\"timestamp\":\"" + ts + "\",\"isSidechain\":false,"
            + "\"message\":{\"model\":\"claude-opus-4-8\",\"content\":[],"
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50}}}\n");
        return new StatsAggregator(new SessionFileEnumerator(projects),
            new StatsCacheStore(tmp.resolve("stats-cache.json")), ZoneOffset.UTC);
    }

    @Test
    void metadata_matchesTs() {
        StatsCommand command = new StatsCommand();
        assertEquals("stats", command.name());
        assertEquals("Show your Claude Code usage statistics and activity", command.description());
        assertTrue(command.aliases().isEmpty());
    }

    @Test
    void launcherWired_opensDialogAndSkips(@TempDir Path tmp) {
        AtomicInteger opened = new AtomicInteger();
        CommandContext ctx = builder(tmp)
            .statsDialogLauncher(opened::incrementAndGet)
            .build();

        CommandResult r = new StatsCommand().execute(ctx, "");

        assertEquals(1, opened.get(), "must open the interactive panel");
        assertTrue(r.silent(), "dialog owns the UI — no transcript output");
    }

    @Test
    void headless_rendersTextSummary(@TempDir Path tmp) throws Exception {
        StatsAggregator aggregator = tempAggregator(tmp);
        CommandContext ctx = builder(tmp)
            .sessionCommands(ProviderTestCommandPorts.sessions(
                new SessionManager(tmp, tmp.toString()), new SessionStorage(), aggregator))
            .build();   // no launcher

        CommandResult r = new StatsCommand().execute(ctx, "");

        String out = r.output();
        assertTrue(Strings.CS.contains(out, "Sessions:        1"), out);
        assertTrue(Strings.CS.contains(out, "Favorite model:  Opus 4.8"), out);
        assertTrue(Strings.CS.contains(out, "Total tokens:    150"), out);
        assertTrue(Strings.CS.contains(out, "Usage by model:"), out);
        assertTrue(Strings.CS.contains(out, "In: 100 · Out: 50"), out);
    }

    @Test
    void headless_emptyStats(@TempDir Path tmp) {
        StatsAggregator aggregator = new StatsAggregator(
            new SessionFileEnumerator(tmp.resolve("nope")),
            new StatsCacheStore(tmp.resolve("cache.json")), ZoneOffset.UTC);
        CommandContext ctx = builder(tmp)
            .sessionCommands(ProviderTestCommandPorts.sessions(
                new SessionManager(tmp, tmp.toString()), new SessionStorage(), aggregator))
            .build();

        CommandResult r = new StatsCommand().execute(ctx, "");
        assertTrue(Strings.CS.contains(r.output(), "No stats available yet. Start using Claude Code!"));
    }
}
