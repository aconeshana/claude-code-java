package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.cli.CliInteractiveSessionAdapter;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.session.stats.SessionFileEnumerator;
import com.claudecode.session.stats.StatsAggregator;
import com.claudecode.session.stats.StatsCacheStore;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


class StatsDialogTest {

    @TempDir Path tmp;

    private StatsAggregator aggregator(boolean withData) throws Exception {
        Path projects = tmp.resolve("projects");
        Path proj = projects.resolve("-Users-x-p");
        Files.createDirectories(proj);
        if (withData) {
            String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
            Files.writeString(proj.resolve("s1.jsonl"),
                "{\"type\":\"user\",\"timestamp\":\"" + ts + "\",\"isSidechain\":false,\"message\":{}}\n"
                + "{\"type\":\"assistant\",\"timestamp\":\"" + ts + "\",\"isSidechain\":false,"
                + "\"message\":{\"model\":\"claude-opus-4-8\",\"content\":[],"
                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50}}}\n");
        }
        return new StatsAggregator(new SessionFileEnumerator(projects),
            new StatsCacheStore(tmp.resolve("stats-cache.json")), ZoneOffset.UTC);
    }

    private StatsDialog dialog(StatsAggregator agg) {
        // guiInvoker runs inline — the virtual thread's callback lands synchronously.
        return new StatsDialog(new CliInteractiveSessionAdapter(agg),
            Runnable::run, () -> 100, ZoneOffset.UTC);
    }

    /** Polls until the dialog leaves LOADING (async aggregate on a virtual thread). */
    private static void awaitLoaded(StatsDialog d) throws Exception {
        for (int i = 0; i < 300; i++) {
            if (!Strings.CS.contains(dump(d), "Loading")) return;
            Thread.sleep(10);
        }
    }

    /** Text projection of the current lines via preferred-size render probe. */
    private static String dump(StatsDialog d) {
        // Render into a scratch text graphics via the private body is overkill;
        // the span lines are reflected in preferred size + we can reuse toString
        // of draw — simplest reliable probe: reflect over the 'lines' field.
        try {
            var f = StatsDialog.class.getDeclaredField("lines");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var lines = (List<List<?>>) f.get(d);
            StringBuilder sb = new StringBuilder();
            for (var line : lines) {
                for (Object span : line) {
                    var t = span.getClass().getDeclaredMethod("text");
                    t.setAccessible(true);
                    sb.append(t.invoke(span));
                }
                sb.append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void press(StatsDialog d, KeyStroke key) {
        d.handleKey(key, new AtomicBoolean(true));
    }

    @Test
    void loadsAndShowsOverview() throws Exception {
        StatsDialog d = dialog(aggregator(true));
        d.show(() -> {});
        awaitLoaded(d);

        String text = dump(d);
        assertTrue(d.isActive());
        assertTrue(Strings.CS.contains(text, "Overview"), text);
        assertTrue(Strings.CS.contains(text, "Favorite model: "), text);
        assertTrue(Strings.CS.contains(text, "Opus 4.8"), text);
        assertTrue(Strings.CS.contains(text, "Total tokens: 150"), text);
        assertTrue(Strings.CS.contains(text, "Sessions: 1"), text);
        assertTrue(Strings.CS.contains(text, "All time"), text);
        assertTrue(Strings.CS.contains(text, "Esc to cancel"), text);
    }

    @Test
    void tabTogglesToModels() throws Exception {
        StatsDialog d = dialog(aggregator(true));
        d.show(() -> {});
        awaitLoaded(d);

        press(d, new KeyStroke(KeyType.TAB));
        String text = dump(d);
        // Models tab: model entry with share percentage + In/Out detail.
        assertTrue(Strings.CS.contains(text, "(100.0%)"), text);
        assertTrue(Strings.CS.contains(text, "In: 100 · Out: 50"), text);

        press(d, new KeyStroke(KeyType.TAB));
        assertTrue(Strings.CS.contains(dump(d), "Favorite model: "), "tab toggles back to Overview");
    }

    @Test
    void rCyclesDateRanges() throws Exception {
        StatsDialog d = dialog(aggregator(true));
        d.show(() -> {});
        awaitLoaded(d);

        press(d, new KeyStroke('r', false, false));
        // 7d loads async; wait for it to land in the cache and re-render.
        for (int i = 0; i < 300 && Strings.CS.contains(dump(d), "…"); i++) Thread.sleep(10);
        String text = dump(d);
        assertTrue(Strings.CS.contains(text, "Active days: 1/7"), "range days follow the 7d selection: " + text);

        press(d, new KeyStroke('r', false, false));
        for (int i = 0; i < 300 && Strings.CS.contains(dump(d), "…"); i++) Thread.sleep(10);
        assertTrue(Strings.CS.contains(dump(d), "Active days: 1/30"), "then 30d");

        press(d, new KeyStroke('r', false, false));
        assertTrue(Strings.CS.contains(dump(d), "All time"), "wraps back to all");
    }

    @Test
    void escClosesAndFiresDismiss() throws Exception {
        AtomicInteger dismissed = new AtomicInteger();
        StatsDialog d = dialog(aggregator(true));
        d.show(dismissed::incrementAndGet);
        awaitLoaded(d);

        press(d, new KeyStroke(KeyType.ESCAPE));
        assertFalse(d.isActive());
        assertEquals(1, dismissed.get());
    }

    @Test
    void closeUsesReboundConfirmationAndHonorsEscapeUnbind() throws Exception {
        AtomicInteger dismissed = new AtomicInteger();
        StatsDialog d = dialog(aggregator(true));
        var store = createStore(tmp.resolve("stats-keybindings.json"), """
            [{"context":"Confirmation","bindings":{
              "ctrl+g":"confirm:no",
              "escape":null
            }}]
            """);
        try {
            d.setKeybindingsStore(store);
            d.show(dismissed::incrementAndGet);
            awaitLoaded(d);
            press(d, new KeyStroke(KeyType.ESCAPE));
            assertTrue(d.isActive());

            press(d, new KeyStroke('g', true, false));
            assertFalse(d.isActive());
            assertEquals(1, dismissed.get());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(
            Path file, String json) throws Exception {
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    @Test
    void emptyProjectsShowsEmptyState() throws Exception {
        StatsDialog d = dialog(aggregator(false));
        d.show(() -> {});
        awaitLoaded(d);
        assertTrue(Strings.CS.contains(dump(d), "No stats available yet. Start using Claude Code!"));
    }
}
