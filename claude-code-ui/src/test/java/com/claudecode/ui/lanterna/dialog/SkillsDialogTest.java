package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.skills.Skill;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives {@link SkillsDialog} through its state machine without a real GUI thread — asserts by
 * rendered line content, not by position (per the codebase convention that dialog.
 */
class SkillsDialogTest {

    private static final Path HOME = Path.of("/home/tester");

    private static Skill skill(String name, String description, Skill.SkillSource source, Path sourceFile) {
        return new Skill(name, description, List.of(), "body", sourceFile, source, null, null, null, null);
    }

    private static KeyStroke key(KeyType t) { return new KeyStroke(t); }

    @Test
    void hiddenUntilShown() {
        SkillsDialog d = new SkillsDialog(List::of, HOME);
        assertFalse(d.isActive());
        assertFalse(d.isShown());
    }

    @Test
    void emptyState_showsCreateHint() {
        SkillsDialog d = new SkillsDialog(List::of, HOME);
        d.show(() -> {});
        assertTrue(d.isActive());
        List<String> texts = d.lineTexts();
        assertTrue(texts.contains("No skills found"), texts.toString());
        assertTrue(texts.stream().anyMatch(s -> Strings.CS.contains(s, "Create skills in .claude/skills/")), texts.toString());
    }

    @Test
    void groupsBySource_inTsOrder_withCount() {
        List<Skill> skills = List.of(
            skill("zeta", "user skill", Skill.SkillSource.USER, HOME.resolve(".claude/skills/user/zeta.md")),
            skill("alpha", "project skill", Skill.SkillSource.PROJECT, Path.of("/proj/.claude/skills/alpha.md")),
            skill("mid", "mcp skill", Skill.SkillSource.MCP, Path.of("/tmp/mid.md")));
        SkillsDialog d = new SkillsDialog(() -> skills, HOME);
        d.show(() -> {});

        List<String> texts = d.lineTexts();
        assertEquals("Skills", texts.getFirst(), texts.toString());
        assertTrue(texts.contains("3 skills"), texts.toString());

        int project = indexOfContains(texts, "Project skills");
        int user = indexOfContains(texts, "User skills");
        int mcp = indexOfContains(texts, "MCP skills");
        assertTrue(project >= 0 && user >= 0 && mcp >= 0, texts.toString());

        assertTrue(project < user && user < mcp, "group order project<user<mcp; " + texts);
    }

    @Test
    void perSkillLine_hasTokenEstimate() {
        // "beta" + " " + "hi" = 7 chars → round(7/4)=2 tokens
        Skill s = skill("beta", "hi", Skill.SkillSource.PROJECT, Path.of("/proj/.claude/skills/beta.md"));
        SkillsDialog d = new SkillsDialog(() -> List.of(s), HOME);
        d.show(() -> {});

        String line = d.lineTexts().stream().filter(x -> Strings.CS.contains(x, "beta ·")).findFirst().orElse("");
        assertTrue(Strings.CS.contains(line, "~2 description tokens"), "token estimate wrong; got: " + line);
    }

    @Test
    void homePrefixCollapsedToTilde_inGroupSubtitle() {
        Skill s = skill("u", "d", Skill.SkillSource.USER, HOME.resolve(".claude/skills/user/u.md"));
        SkillsDialog d = new SkillsDialog(() -> List.of(s), HOME);
        d.show(() -> {});
        String header = d.lineTexts().stream().filter(x -> Strings.CS.startsWith(x, "User skills")).findFirst().orElse("");
        assertTrue(Strings.CS.contains(header, "(~/.claude/skills/user)"), "home should collapse to ~; got: " + header);
    }

    @Test
    void escapeDismisses_firesCallback_consumesKey() {
        AtomicInteger dismissed = new AtomicInteger();
        SkillsDialog d = new SkillsDialog(List::of, HOME);
        d.show(dismissed::incrementAndGet);
        assertTrue(d.isActive());

        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(key(KeyType.ESCAPE), deliver);

        assertFalse(d.isActive(), "Escape must hide the dialog");
        assertEquals(1, dismissed.get(), "dismiss callback must fire once");
        assertFalse(deliver.get(), "Escape must be consumed");
    }

    @Test
    void confirmationCancelCanBeReboundAndEscapeUnbound(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [{"context":"Confirmation","bindings":{
              "x":"confirm:no",
              "escape":null
            }}]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicInteger dismissed = new AtomicInteger();
            SkillsDialog d = new SkillsDialog(List::of, HOME);
            d.setKeybindingsStore(store);
            d.show(dismissed::incrementAndGet);

            d.handleKey(key(KeyType.ESCAPE), new AtomicBoolean(true));
            assertTrue(d.isActive(), "null-unbound Escape must not use the hard-coded fallback");
            assertEquals(0, dismissed.get());

            d.handleKey(new KeyStroke('x', false, false), new AtomicBoolean(true));
            assertFalse(d.isActive());
            assertEquals(1, dismissed.get());
        } finally {
            store.dispose();
        }
    }

    @Test
    void enterDoesNotDismissBecauseSkillsOnlySubscribesToConfirmNo() {
        AtomicInteger dismissed = new AtomicInteger();
        SkillsDialog d = new SkillsDialog(List::of, HOME);
        d.show(dismissed::incrementAndGet);

        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(key(KeyType.ENTER), deliver);

        assertTrue(d.isActive());
        assertEquals(0, dismissed.get());
        assertFalse(deliver.get(), "the active overlay must still consume the unhandled Confirmation action");
    }

    @Test
    void arrowScrollClampedAndConsumed() {
        // 30 skills → more than MAX_VISIBLE_ROWS, so scrolling is meaningful.
        List<Skill> many = IntStream.range(0, 30)
            .mapToObj(i -> skill("s" + i, "d", Skill.SkillSource.PROJECT, Path.of("/proj/.claude/skills/s" + i + ".md")))
            .toList();
        SkillsDialog d = new SkillsDialog(() -> many, HOME);
        d.show(() -> {});

        AtomicBoolean deliver = new AtomicBoolean(true);
        d.handleKey(key(KeyType.ARROW_UP), deliver);
        assertEquals(0, d.scrollOffset(), "cannot scroll above the top");
        assertFalse(deliver.get());

        d.handleKey(key(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        assertEquals(1, d.scrollOffset());

        d.handleKey(key(KeyType.END), new AtomicBoolean(true));
        assertEquals(Math.max(0, d.lineCount() - 18), d.scrollOffset(), "End jumps to max scroll");
    }

    private static int indexOfContains(List<String> texts, String needle) {
        for (int i = 0; i < texts.size(); i++) if (Strings.CS.contains(texts.get(i), needle)) return i;
        return -1;
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }
}
