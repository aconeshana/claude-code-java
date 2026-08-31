package com.claudecode.keybindings;

import com.claudecode.core.platform.Platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.function.Consumer;

public final class DefaultBindings {

    private DefaultBindings() {}

    /** One keybinding block — {@code context} → ordered map of key → action. */
    public record Block(String context, Map<String, String> bindings) {}

    /** True when running on macOS — used by callers building display strings. */
    public static final boolean IS_DARWIN = Platform.IS_DARWIN;

    /** True when running on Windows. */
    public static final boolean IS_WINDOWS = Platform.IS_WINDOWS;


    public static final String EXPAND_HINT = "(ctrl+o to expand)";

    /**
     * Platform-specific image paste shortcut.
     */
    public static final String IMAGE_PASTE_KEY = IS_WINDOWS ? "alt+v" : "ctrl+v";

    /**
     * Platform-specific mode-cycle key.
     */
    public static final String MODE_CYCLE_KEY = IS_WINDOWS ? "meta+m" : "shift+tab";

    public static final List<Block> BLOCKS = buildBlocks();

    private static List<Block> buildBlocks() {
        return List.of(
            block("Global", m -> {
                m.put("ctrl+c", "app:interrupt");
                m.put("ctrl+d", "app:exit");
                m.put("ctrl+l", "app:redraw");
                m.put("ctrl+t", "app:toggleTodos");
                m.put("ctrl+o", "app:toggleTranscript");
                m.put("ctrl+shift+o", "app:toggleTeammatePreview");
                m.put("ctrl+r", "history:search");
            }),
            block("Chat", m -> {
                m.put("escape", "chat:cancel");
                m.put("ctrl+x ctrl+k", "chat:killAgents");
                m.put(MODE_CYCLE_KEY, "chat:cycleMode");
                m.put("meta+p", "chat:modelPicker");
                m.put("meta+o", "chat:fastMode");
                m.put("meta+t", "chat:thinkingToggle");
                m.put("enter", "chat:submit");
                m.put("up", "history:previous");
                m.put("down", "history:next");
                m.put("ctrl+_", "chat:undo");
                m.put("ctrl+shift+-", "chat:undo");
                m.put("ctrl+g", "chat:externalEditor");
                m.put("ctrl+x ctrl+e", "chat:externalEditor");
                m.put("ctrl+s", "chat:stash");
                m.put(IMAGE_PASTE_KEY, "chat:imagePaste");
            }),
            block("Autocomplete", m -> {
                m.put("tab", "autocomplete:accept");
                m.put("escape", "autocomplete:dismiss");
                m.put("up", "autocomplete:previous");
                m.put("down", "autocomplete:next");
            }),
            block("Settings", m -> {
                m.put("escape", "confirm:no");
                m.put("up", "select:previous");
                m.put("down", "select:next");
                m.put("k", "select:previous");
                m.put("j", "select:next");
                m.put("ctrl+p", "select:previous");
                m.put("ctrl+n", "select:next");
                m.put("space", "select:accept");
                m.put("enter", "select:accept");
                m.put("/", "settings:search");
                m.put("r", "settings:retry");
            }),
            block("Confirmation", m -> {
                m.put("y", "confirm:yes");
                m.put("n", "confirm:no");
                m.put("enter", "confirm:yes");
                m.put("escape", "confirm:no");
                m.put("up", "confirm:previous");
                m.put("down", "confirm:next");
                m.put("tab", "confirm:nextField");
                m.put("space", "confirm:toggle");
                m.put("shift+tab", "confirm:cycleMode");
                m.put("ctrl+e", "confirm:toggleExplanation");
                m.put("ctrl+d", "permission:toggleDebug");
            }),
            block("Tabs", m -> {
                m.put("tab", "tabs:next");
                m.put("shift+tab", "tabs:previous");
                m.put("right", "tabs:next");
                m.put("left", "tabs:previous");
            }),
            block("Transcript", m -> {
                m.put("ctrl+e", "transcript:toggleShowAll");
                m.put("ctrl+c", "transcript:exit");
                m.put("escape", "transcript:exit");
                m.put("q", "transcript:exit");
            }),
            block("HistorySearch", m -> {
                m.put("ctrl+r", "historySearch:next");
                m.put("escape", "historySearch:accept");
                m.put("tab", "historySearch:accept");
                m.put("ctrl+c", "historySearch:cancel");
                m.put("enter", "historySearch:execute");
                m.put("ctrl+s", "historySearch:cycleScope");
            }),
            block("Task", m -> {
                // ctrl+b in tmux must be pressed twice (tmux prefix escape).
                m.put("ctrl+b", "task:background");
            }),
            block("ThemePicker", m -> m.put("ctrl+t", "theme:toggleSyntaxHighlighting")),
            block("Scroll", m -> {
                m.put("pageup", "scroll:pageUp");
                m.put("pagedown", "scroll:pageDown");
                m.put("wheelup", "scroll:lineUp");
                m.put("wheeldown", "scroll:lineDown");
                m.put("ctrl+home", "scroll:top");
                m.put("ctrl+end", "scroll:bottom");
                m.put("ctrl+shift+c", "selection:copy");
                m.put("cmd+c", "selection:copy");
            }),
            block("Help", m -> m.put("escape", "help:dismiss")),
            block("Attachments", m -> {
                m.put("right", "attachments:next");
                m.put("left", "attachments:previous");
                m.put("backspace", "attachments:remove");
                m.put("delete", "attachments:remove");
                m.put("down", "attachments:exit");
                m.put("escape", "attachments:exit");
            }),
            block("Footer", m -> {
                m.put("up", "footer:up");
                m.put("ctrl+p", "footer:up");
                m.put("down", "footer:down");
                m.put("ctrl+n", "footer:down");
                m.put("right", "footer:next");
                m.put("left", "footer:previous");
                m.put("enter", "footer:openSelected");
                m.put("escape", "footer:clearSelection");
            }),
            block("MessageSelector", m -> {
                m.put("up", "messageSelector:up");
                m.put("down", "messageSelector:down");
                m.put("k", "messageSelector:up");
                m.put("j", "messageSelector:down");
                m.put("ctrl+p", "messageSelector:up");
                m.put("ctrl+n", "messageSelector:down");
                m.put("ctrl+up", "messageSelector:top");
                m.put("shift+up", "messageSelector:top");
                m.put("meta+up", "messageSelector:top");
                m.put("shift+k", "messageSelector:top");
                m.put("ctrl+down", "messageSelector:bottom");
                m.put("shift+down", "messageSelector:bottom");
                m.put("meta+down", "messageSelector:bottom");
                m.put("shift+j", "messageSelector:bottom");
                m.put("enter", "messageSelector:select");
            }),
            block("MessageActions", m -> {
                m.put("up", "messageActions:prev");
                m.put("down", "messageActions:next");
                m.put("k", "messageActions:prev");
                m.put("j", "messageActions:next");
                m.put("meta+up", "messageActions:top");
                m.put("meta+down", "messageActions:bottom");
                m.put("super+up", "messageActions:top");
                m.put("super+down", "messageActions:bottom");
                m.put("shift+up", "messageActions:prevUser");
                m.put("shift+down", "messageActions:nextUser");
                m.put("escape", "messageActions:escape");
                m.put("ctrl+c", "messageActions:ctrlc");
                m.put("enter", "messageActions:enter");
                m.put("c", "messageActions:c");
                m.put("p", "messageActions:p");
            }),
            block("DiffDialog", m -> {
                m.put("escape", "diff:dismiss");
                m.put("left", "diff:previousSource");
                m.put("right", "diff:nextSource");
                m.put("up", "diff:previousFile");
                m.put("down", "diff:nextFile");
                m.put("enter", "diff:viewDetails");
            }),
            block("ModelPicker", m -> {
                m.put("left", "modelPicker:decreaseEffort");
                m.put("right", "modelPicker:increaseEffort");
                m.put("x", "modelPicker:deleteCustomModel");
            }),
            block("Select", m -> {
                m.put("up", "select:previous");
                m.put("down", "select:next");
                m.put("j", "select:next");
                m.put("k", "select:previous");
                m.put("ctrl+n", "select:next");
                m.put("ctrl+p", "select:previous");
                m.put("pageup", "select:pageUp");
                m.put("pagedown", "select:pageDown");
                m.put("home", "select:first");
                m.put("end", "select:last");
                m.put("enter", "select:accept");
                m.put("escape", "select:cancel");
            }),
            block("Plugin", m -> {
                m.put("space", "plugin:toggle");
                m.put("i", "plugin:install");
            })
        );
    }

    /**
     * Convenience accessor — returns the action mapped to {@code key} in the named context, or {@code
     * null} if absent.
     */
    public static String actionFor(String context, String key) {
        for (Block b : BLOCKS) {
            if (b.context.equals(context)) {
                return b.bindings.get(key);
            }
        }
        return null;
    }

    private static Block block(String context, Consumer<Map<String, String>> filler) {
        Map<String, String> m = new LinkedHashMap<>();
        filler.accept(m);
        return new Block(context,
            Collections.unmodifiableMap(new LinkedHashMap<>(m)));
    }
}
