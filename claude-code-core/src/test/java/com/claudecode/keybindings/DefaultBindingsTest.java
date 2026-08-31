package com.claudecode.keybindings;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DefaultBindingsTest {

    @Test
    void hasAllExpectedContexts() {

        List<String> expected = List.of(
            "Global", "Chat", "Autocomplete", "Settings", "Confirmation",
            "Tabs", "Transcript", "HistorySearch", "Task", "ThemePicker",
            "Scroll", "Help", "Attachments", "Footer", "MessageSelector",
            "DiffDialog", "ModelPicker", "Select", "Plugin"
        );
        Set<String> actual = new HashSet<>();
        DefaultBindings.BLOCKS.forEach(b -> actual.add(b.context()));
        for (String ctx : expected) {
            assertTrue(actual.contains(ctx), "missing context: " + ctx);
        }
    }

    @Test
    void globalBindings_coreShortcuts() {
        assertEquals("app:interrupt", DefaultBindings.actionFor("Global", "ctrl+c"));
        assertEquals("app:exit",      DefaultBindings.actionFor("Global", "ctrl+d"));
        assertEquals("app:toggleTranscript", DefaultBindings.actionFor("Global", "ctrl+o"));
        assertEquals("history:search", DefaultBindings.actionFor("Global", "ctrl+r"));
    }

    @Test
    void chatBindings_includeModeCycleAndImagePaste() {
        // Mode cycle and image paste are platform-dispatched.
        boolean win = DefaultBindings.IS_WINDOWS;
        String expectedCycle = win ? "meta+m" : "shift+tab";
        String expectedPaste = win ? "alt+v" : "ctrl+v";

        assertEquals("chat:cycleMode",  DefaultBindings.actionFor("Chat", expectedCycle));
        assertEquals("chat:imagePaste", DefaultBindings.actionFor("Chat", expectedPaste));
        assertEquals("chat:submit",     DefaultBindings.actionFor("Chat", "enter"));
        assertEquals("history:previous", DefaultBindings.actionFor("Chat", "up"));
        assertEquals("history:next",     DefaultBindings.actionFor("Chat", "down"));
    }

    @Test
    void confirmationBindings_haveBothShortcutsForCommonChoices() {

        assertEquals("confirm:yes", DefaultBindings.actionFor("Confirmation", "y"));
        assertEquals("confirm:yes", DefaultBindings.actionFor("Confirmation", "enter"));
        assertEquals("confirm:no",  DefaultBindings.actionFor("Confirmation", "n"));
        assertEquals("confirm:no",  DefaultBindings.actionFor("Confirmation", "escape"));
        assertEquals("confirm:cycleMode", DefaultBindings.actionFor("Confirmation", "shift+tab"));
    }

    @Test
    void transcript_qAsExit_isPagerConvention() {
        assertEquals("transcript:exit", DefaultBindings.actionFor("Transcript", "q"));
        assertEquals("transcript:exit", DefaultBindings.actionFor("Transcript", "escape"));
        assertEquals("transcript:exit", DefaultBindings.actionFor("Transcript", "ctrl+c"));
    }

    @Test
    void historyPickerScopeCycleMatchesReleased197() {
        assertEquals("historySearch:cycleScope",
            DefaultBindings.actionFor("HistorySearch", "ctrl+s"));
    }

    @Test
    void scroll_hasMouseAndKeyboardBindings() {
        assertEquals("scroll:lineUp",   DefaultBindings.actionFor("Scroll", "wheelup"));
        assertEquals("scroll:lineDown", DefaultBindings.actionFor("Scroll", "wheeldown"));
        assertEquals("scroll:top",      DefaultBindings.actionFor("Scroll", "ctrl+home"));
        assertEquals("selection:copy",  DefaultBindings.actionFor("Scroll", "ctrl+shift+c"));
        assertEquals("selection:copy",  DefaultBindings.actionFor("Scroll", "cmd+c"));
    }

    @Test
    void taskContext_singleBindingCtrlB() {
        // Background-foreground task — historic ctrl+b binding (must press
        // twice in tmux because of prefix escape).
        assertEquals("task:background", DefaultBindings.actionFor("Task", "ctrl+b"));
    }

    @Test
    void modelPickerIncludesCustomModelDeletion() {
        assertEquals("modelPicker:deleteCustomModel",
            DefaultBindings.actionFor("ModelPicker", "x"));
    }

    @Test
    void unknownContextOrKey_returnsNull() {
        assertNull(DefaultBindings.actionFor("Unknown", "ctrl+c"));
        assertNull(DefaultBindings.actionFor("Global", "ctrl+z"));
    }

    @Test
    void messageSelector_includesAllNavigationAliases() {

        for (String key : new String[]{"ctrl+up", "shift+up", "meta+up", "shift+k"}) {
            assertEquals("messageSelector:top", DefaultBindings.actionFor("MessageSelector", key),
                "MessageSelector top should be bound to " + key);
        }
        for (String key : new String[]{"ctrl+down", "shift+down", "meta+down", "shift+j"}) {
            assertEquals("messageSelector:bottom", DefaultBindings.actionFor("MessageSelector", key),
                "MessageSelector bottom should be bound to " + key);
        }
    }

    @Test
    void selectIncludesReleasedPagingAndBoundaryBindings() {
        assertEquals("select:pageUp", DefaultBindings.actionFor("Select", "pageup"));
        assertEquals("select:pageDown", DefaultBindings.actionFor("Select", "pagedown"));
        assertEquals("select:first", DefaultBindings.actionFor("Select", "home"));
        assertEquals("select:last", DefaultBindings.actionFor("Select", "end"));
    }
}
