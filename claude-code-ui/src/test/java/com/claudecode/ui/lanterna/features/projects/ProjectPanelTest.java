package com.claudecode.ui.lanterna.features.projects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectEntry;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectPreferences;
import com.claudecode.ui.lanterna.repl.ProjectCatalogPort.ProjectSessionEntry;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.apache.commons.lang3.Strings;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * {@link ProjectPanel} state-machine and rendering contract: hidden until
 * shown, project rows collapse/expand their sessions, arrow keys walk the
 * flattened visible rows, Enter delegates to the host callbacks, Esc closes.
 * A Java-side extension with no 197 counterpart.
 */
class ProjectPanelTest {

    private static ProjectSessionEntry session(String id, String cwd, long modified,
                                               String customTitle, String summary) {
        return new ProjectSessionEntry(id, modified, Instant.ofEpochMilli(modified), 3,
            summary, "main", cwd, null, customTitle, null, 100,
            Path.of("/transcripts", id + ".jsonl"));
    }

    private static ProjectEntry project(String path, String name, long activity,
                                        ProjectSessionEntry... sessions) {
        return new ProjectEntry(path, name, sessions.length, activity, List.of(sessions));
    }

    private static void route(ProjectPanel panel, KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    private static UserMessage user(String text) {
        return new UserMessage("u-" + text.hashCode(), MessageContent.ofText(text));
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage("a-" + text.hashCode(),
            AssistantContent.of(List.<ContentBlock>of(new TextBlock(text))));
    }

    private static String render(ProjectPanel panel, int columns, int rows) {
        TerminalSize size = new TerminalSize(columns, rows);
        panel.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        panel.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        StringBuilder text = new StringBuilder();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                text.append(image.getCharacterAt(col, row).getCharacterString());
            }
            text.append('\n');
        }
        return text.toString();
    }

    @Test
    void hiddenUntilShown() {
        ProjectPanel panel = new ProjectPanel(() -> 80, () -> 24);
        assertFalse(panel.isActive());
        assertEquals(0, panel.calculatePreferredSize().getRows());
        assertEquals(0, panel.calculatePreferredSize().getColumns());
    }

    @Test
    void showListsProjectsNewestFirst() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/b", "beta", 5000, session("s2", "/p/b", 5000, null, "add tests")),
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "fix bug"))),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, null));

        assertTrue(panel.isActive());
        assertTrue(panel.calculatePreferredSize().getRows() > 0);

        String rendered = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(rendered, "/p/b") && rendered.indexOf("/p/b") < rendered.indexOf("/p/a"),
            "host order is preserved (catalog sorts by activity): " + rendered);
        assertTrue(Strings.CS.contains(rendered, "Projects"), rendered);
    }

    @Test
    void projectsStartCollapsedAndExpandOnRightArrow() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "fix bug"))),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, null));

        String collapsed = render(panel, 100, 30);
        assertFalse(Strings.CS.contains(collapsed, "fix bug"), "collapsed project hides sessions: " + collapsed);

        route(panel, new KeyStroke(KeyType.ARROW_RIGHT));
        String expanded = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(expanded, "fix bug"), "expanded project lists sessions: " + expanded);

        route(panel, new KeyStroke(KeyType.ARROW_LEFT));
        assertFalse(Strings.CS.contains(render(panel, 100, 30), "fix bug"), "left arrow collapses again");
    }

    @Test
    void preferencesControlInitialCollapseState() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "fix bug"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, null));

        assertTrue(Strings.CS.contains(render(panel, 100, 30), "fix bug"),
            "collapsed=false in prefs starts expanded");
    }

    @Test
    void arrowsWalkFlattenedVisibleRows() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/b", "beta", 5000, session("s3", "/p/b", 5000, null, "third")),
            project("/p/a", "alpha", 2000,
                session("s1", "/p/a", 2000, null, "first"),
                session("s2", "/p/a", 1000, null, "second"))),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, null));

        assertEquals("/p/b", panel.focusedProjectPathForTest(), "initial focus on first row");
        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals("/p/a", panel.focusedProjectPathForTest());
        route(panel, new KeyStroke(KeyType.ARROW_RIGHT)); // expand alpha
        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals("s1", panel.focusedSessionIdForTest());
        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals("s2", panel.focusedSessionIdForTest());
        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        assertEquals("/p/b", panel.focusedProjectPathForTest(),
            "past the last visible row wraps to the top (cycleIndex)");
    }

    @Test
    void enterOnSessionFiresResumeCallback() {
        AtomicReference<ProjectSessionEntry> resumed = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "fix bug"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(resumed::set, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN)); // project → first session
        route(panel, new KeyStroke(KeyType.ENTER));

        assertEquals("s1", resumed.get().id());
        assertFalse(panel.isActive(), "resume closes the panel");
    }

    @Test
    void escClosesAndFiresOnClose() {
        AtomicBoolean closed = new AtomicBoolean(false);
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(project("/p/a", "alpha", 2000)),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, () -> closed.set(true)));

        route(panel, new KeyStroke(KeyType.ESCAPE));

        assertFalse(panel.isActive());
        assertTrue(closed.get());
    }

    @Test
    void collapseChangePersistsPreferences() {
        AtomicReference<Map<String, Boolean>> saved = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "fix bug"))),
            ProjectPreferences.empty(),
            new ProjectPanel.Actions(null, null, null,
                (_, collapsed) -> saved.set(collapsed)));

        route(panel, new KeyStroke(KeyType.ARROW_RIGHT)); // expand alpha
        assertEquals(Map.of("/p/a", false), saved.get());

        route(panel, new KeyStroke(KeyType.ARROW_LEFT)); // collapse again
        assertEquals(Map.of("/p/a", true), saved.get());
    }

    @Test
    void pasteAndPlainTypingAreConsumedWithoutEffect() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(project("/p/a", "alpha", 2000)),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, null));

        AtomicBoolean deliver = new AtomicBoolean(true);
        panel.handleKey(new PasteKeyStroke("some pasted text"), deliver);
        assertFalse(deliver.get(), "PASTE must never leak to the input behind the overlay");

        route(panel, new KeyStroke('x', false, false));
        assertTrue(panel.isActive(), "plain keys are swallowed, not treated as commands");
        assertEquals("/p/a", panel.focusedProjectPathForTest());
    }

    @Test
    void leftArrowOnCollapsedProjectClosesPanel() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(project("/p/a", "alpha", 2000)),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_LEFT));
        assertFalse(panel.isActive(), "left at the top level is the keyboard way out");
    }

    @Test
    void narrowTerminalStillPaintsWithinBounds() {
        ProjectPanel panel = new ProjectPanel(() -> 30, () -> 10);
        panel.show(List.of(
            project("/p/very/long/project/name", "a-very-long-project-name", 2000,
                session("s1", "/p/very/long/project/name", 2000, null,
                    "a session summary that is far too long for the strip"))),
            new ProjectPreferences(List.of(), Map.of("/p/very/long/project/name", false)),
            new ProjectPanel.Actions(null, null, null));

        String rendered = render(panel, 30, 10); // must not throw
        assertTrue(Strings.CS.contains(rendered, "Projects"));
    }

    @Test
    void projectRowShowsPathWithHomeAbbreviated() {
        String home = System.getProperty("user.home");
        ProjectEntry inHome = project(home + "/Projects/repo", "repo", 2000);
        assertEquals("▸ ~/Projects/repo (0)",
            ProjectPanel.projectRowLabel(inHome, true, 40));
        assertEquals("▾ /opt/elsewhere (0)",
            ProjectPanel.projectRowLabel(project("/opt/elsewhere", "elsewhere", 2000), false, 40));
    }

    @Test
    void projectRowElidesPathMiddleAndKeepsSessionCount() {
        ProjectEntry deep = project("/p/very/long/nested/checkout", "checkout", 2000,
            session("s1", "/p/very/long/nested/checkout", 2000, null, "x"));
        String label = ProjectPanel.projectRowLabel(deep, true, 22);

        assertEquals(22, FormatUtils.displayWidth(label), label);
        assertTrue(Strings.CS.startsWith(label, "▸ "), label);
        assertTrue(Strings.CS.endsWith(label, "/checkout (1)"),
            "the leaf directory and the count both survive: " + label);
        assertTrue(Strings.CS.contains(label, "…"), "the middle is elided: " + label);
    }

    @Test
    void emptyCatalogShowsHintInsteadOfBlankStrip() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(), ProjectPreferences.empty(), new ProjectPanel.Actions(null, null, null));

        String out = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(out, "No sessions found"), out);
    }

    @Test
    void customTitleWinsOverSummaryForSessionLabel() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, "my title", "fix bug"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, null));

        String rendered = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(rendered, "my title"), rendered);
        assertFalse(Strings.CS.contains(rendered, "fix bug"), rendered);
    }

    @Test
    void sessionLabelFallsBackToFirstPromptThenId() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        ProjectSessionEntry promptOnly = new ProjectSessionEntry(
            "abcdef12-0000-4000-8000-0000000000aa", 2000, Instant.ofEpochMilli(2000), 3,
            null, "main", "/p/a", null, null, "hello world", 100,
            Path.of("/transcripts/x.jsonl"));
        ProjectSessionEntry bare = session("bbbbbb12-0000-4000-8000-0000000000bb", "/p/a", 1000, null, null);
        panel.show(List.of(project("/p/a", "alpha", 2000, promptOnly, bare)),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, null));

        String rendered = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(rendered, "hello world"), rendered);
        assertTrue(Strings.CS.contains(rendered, "bbbbbb12"), "id prefix labels a fully untitled session");
    }

    // ── delete (x two-stage) ─────────────────────────────────────────────────

    @Test
    void xOnSessionArmsDeleteThenSecondXFiresCallbackAndRemovesRow() {
        AtomicReference<ProjectSessionEntry> deleted = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000,
                session("s1", "/p/a", 2000, null, "first"),
                session("s2", "/p/a", 1000, null, "second"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, deleted::set, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN)); // project → s1
        route(panel, new KeyStroke('x', false, false));  // arm
        assertNull(deleted.get(), "first x only arms the confirmation");
        assertTrue(Strings.CS.contains(render(panel, 100, 30), "x to confirm"),
            "armed row advertises the second x");

        route(panel, new KeyStroke('x', false, false));  // confirm
        assertEquals("s1", deleted.get().id());
        String rendered = render(panel, 100, 30);
        assertFalse(Strings.CS.contains(rendered, "first"), "deleted row leaves the tree optimistically");
        assertTrue(Strings.CS.contains(rendered, "second"), "sibling session stays");
        assertTrue(panel.isActive(), "drawer stays open after delete");
    }

    @Test
    void escDisarmsDeleteWithoutFiringOrClosing() {
        AtomicReference<ProjectSessionEntry> deleted = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "first"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, deleted::set, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke('x', false, false));
        route(panel, new KeyStroke(KeyType.ESCAPE)); // disarm, NOT close

        assertNull(deleted.get());
        assertTrue(panel.isActive(), "Esc disarms first; it does not close the drawer");
        assertTrue(Strings.CS.contains(render(panel, 100, 30), "first"), "session survives a disarmed delete");
    }

    @Test
    void emptyingAProjectRemovesItsRow() {
        AtomicReference<ProjectSessionEntry> deleted = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "first")),
            project("/p/b", "beta", 1000, session("s2", "/p/b", 1000, null, "second"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false, "/p/b", false)),
            new ProjectPanel.Actions(null, deleted::set, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN)); // alpha → s1
        route(panel, new KeyStroke('x', false, false));
        route(panel, new KeyStroke('x', false, false));

        String rendered = render(panel, 100, 30);
        assertFalse(Strings.CS.contains(rendered, "/p/a"), "a project with no sessions left disappears");
        assertTrue(Strings.CS.contains(rendered, "/p/b"), rendered);
    }

    @Test
    void xOnProjectRowIsIgnored() {
        AtomicReference<ProjectSessionEntry> deleted = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "first"))),
            ProjectPreferences.empty(), new ProjectPanel.Actions(null, deleted::set, null));

        route(panel, new KeyStroke('x', false, false)); // focus is on the project row
        assertNull(deleted.get(), "delete is a session-level action");
    }

    // ── preview (Space / Ctrl+V, matching the resume picker) ─────────────────

    @Test
    void spaceOnSessionFiresPreviewCallbackAndShowsLoading() {
        AtomicReference<ProjectSessionEntry> previewed = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        ProjectPanel.Actions actions = new ProjectPanel.Actions(
            null, null, previewed::set, null, null);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "first"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)), actions);

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke(' ', false, false));

        assertEquals("s1", previewed.get().id());
        assertTrue(Strings.CS.contains(render(panel, 100, 30), "Loading preview"),
            "preview mode opens immediately with a loading placeholder");
    }

    @Test
    void previewReplaysTheTranscriptAndEscReturnsToTree() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        ProjectSessionEntry s1 = session("s1", "/p/a", 2000, null, "first");
        panel.show(List.of(project("/p/a", "alpha", 2000, s1)),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, _ -> {}, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke(' ', false, false));
        panel.showPreviewMessages(s1, List.of(user("hello"), assistant("hi there")));

        String rendered = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(rendered, "hello"), rendered);
        assertTrue(Strings.CS.contains(rendered, "hi there"), rendered);
        assertTrue(Strings.CS.contains(rendered, "3 messages"),
            "the chrome names the session once its transcript is on screen: " + rendered);

        route(panel, new KeyStroke(KeyType.ESCAPE));
        String tree = render(panel, 100, 30);
        assertTrue(Strings.CS.contains(tree, "first"), "Esc returns to the tree");
        assertFalse(Strings.CS.contains(tree, "hi there"), "preview content is gone");
    }

    @Test
    void previewOpensAtTheTailAndScrollsBack() {
        ProjectPanel panel = new ProjectPanel(() -> 80, () -> 12);
        ProjectSessionEntry s1 = session("s1", "/p/a", 2000, null, "first");
        panel.show(List.of(project("/p/a", "alpha", 2000, s1)),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, _ -> {}, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke(' ', false, false));
        panel.showPreviewMessages(s1,
            IntStream.range(0, 60).mapToObj(i -> (Message) user("turn " + i)).toList());

        // Like the resume picker, the preview lands on the newest turn — the
        // answer to "where did I leave off".
        String tail = render(panel, 80, 12);
        assertTrue(Strings.CS.contains(tail, "turn 59"), tail);
        assertFalse(Strings.CS.contains(tail, "turn 0 "), tail);

        for (int i = 0; i < 200; i++) route(panel, new KeyStroke(KeyType.ARROW_UP));
        assertTrue(Strings.CS.contains(render(panel, 80, 12), "turn 0"),
            "scrolling up reaches the head and clamps there");
    }

    @Test
    void stalePreviewResultForAnotherSessionIsDropped() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        ProjectSessionEntry s1 = session("s1", "/p/a", 2000, null, "first");
        ProjectSessionEntry s2 = session("s2", "/p/a", 1000, null, "second");
        panel.show(List.of(project("/p/a", "alpha", 2000, s1, s2)),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, _ -> {}, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke(' ', false, false)); // preview s1
        panel.showPreviewMessages(s2, List.of(user("STALE CONTENT")));

        assertFalse(Strings.CS.contains(render(panel, 100, 30), "STALE CONTENT"),
            "a late result for a different session must not clobber the open preview");
    }

    @Test
    void reopeningThePreviewDoesNotShowThePreviousSessionsTranscript() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        ProjectSessionEntry s1 = session("s1", "/p/a", 2000, null, "first");
        ProjectSessionEntry s2 = session("s2", "/p/a", 1000, null, "second");
        panel.show(List.of(project("/p/a", "alpha", 2000, s1, s2)),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, _ -> {}, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke(' ', false, false));
        panel.showPreviewMessages(s1, List.of(user("only in s1")));
        route(panel, new KeyStroke(KeyType.ESCAPE));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN)); // now on s2
        route(panel, new KeyStroke(' ', false, false));

        String rendered = render(panel, 100, 30);
        assertFalse(Strings.CS.contains(rendered, "only in s1"),
            "the shared MessagePanel must be cleared between opens: " + rendered);
        assertTrue(Strings.CS.contains(rendered, "Loading preview"), rendered);
    }

    @Test
    void failedTranscriptReadIsReportedInThePreview() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        ProjectSessionEntry s1 = session("s1", "/p/a", 2000, null, "first");
        panel.show(List.of(project("/p/a", "alpha", 2000, s1)),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, _ -> {}, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke(' ', false, false));
        panel.showPreviewMessages(s1, null);

        assertTrue(Strings.CS.contains(render(panel, 100, 30), "failed to read transcript"),
            "a failed read must not look like an empty session");
    }

    @Test
    void ctrlVAlsoOpensThePreview() {
        AtomicReference<ProjectSessionEntry> previewed = new AtomicReference<>();
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "first"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, previewed::set, null, null));

        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        route(panel, new KeyStroke('v', true, false));   // older builds' binding

        assertEquals("s1", previewed.get().id());
    }

    @Test
    void footerAdvertisesPreviewOnlyWhereItApplies() {
        ProjectPanel panel = new ProjectPanel(() -> 100, () -> 30);
        panel.show(List.of(
            project("/p/a", "alpha", 2000, session("s1", "/p/a", 2000, null, "first"))),
            new ProjectPreferences(List.of(), Map.of("/p/a", false)),
            new ProjectPanel.Actions(null, null, _ -> {}, null, null));

        // The strip is 38 columns at most, so a project row cannot afford the
        // session verbs — but a session row must name the preview binding, or
        // the feature is undiscoverable.
        assertFalse(Strings.CS.contains(render(panel, 100, 30), "Space preview"),
            "project row: expand/move verbs only");
        route(panel, new KeyStroke(KeyType.ARROW_DOWN));
        assertTrue(Strings.CS.contains(render(panel, 100, 30), "Space preview"),
            "session row must advertise the preview key");
    }
}
