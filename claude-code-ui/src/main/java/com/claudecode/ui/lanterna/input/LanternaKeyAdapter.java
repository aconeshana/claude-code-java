package com.claudecode.ui.lanterna.input;

import com.claudecode.keybindings.KeystrokeParser;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;


public final class LanternaKeyAdapter {

    private LanternaKeyAdapter() {}

    public static KeystrokeParser.Keystroke toKeystroke(KeyStroke key) {
        if (key == null) return null;
        KeyType kt = key.getKeyType();
        boolean ctrl = key.isCtrlDown();
        boolean alt = key.isAltDown();
        boolean shift = key.isShiftDown();

        return switch (kt) {
            case CHARACTER -> {
                Character ch = key.getCharacter();
                if (ch == null) yield new KeystrokeParser.Keystroke("", ctrl, alt, shift, false, false);
                // Lowercase so 'C' and 'c' both normalise to "c"; space stays " ".
                String name = (ch == ' ') ? " " : String.valueOf(Character.toLowerCase(ch));
                yield new KeystrokeParser.Keystroke(name, ctrl, alt, shift, false, false);
            }
            case ENTER       -> new KeystrokeParser.Keystroke("enter", ctrl, alt, shift, false, false);
            case ESCAPE      -> new KeystrokeParser.Keystroke("escape", ctrl, alt, shift, false, false);
            case BACKSPACE   -> new KeystrokeParser.Keystroke("backspace", ctrl, alt, shift, false, false);
            case DELETE      -> new KeystrokeParser.Keystroke("delete", ctrl, alt, shift, false, false);
            case TAB         -> new KeystrokeParser.Keystroke("tab", ctrl, alt, shift, false, false);
            case ARROW_UP    -> new KeystrokeParser.Keystroke("up", ctrl, alt, shift, false, false);
            case ARROW_DOWN  -> new KeystrokeParser.Keystroke("down", ctrl, alt, shift, false, false);
            case ARROW_LEFT  -> new KeystrokeParser.Keystroke("left", ctrl, alt, shift, false, false);
            case ARROW_RIGHT -> new KeystrokeParser.Keystroke("right", ctrl, alt, shift, false, false);
            case HOME        -> new KeystrokeParser.Keystroke("home", ctrl, alt, shift, false, false);
            case END         -> new KeystrokeParser.Keystroke("end", ctrl, alt, shift, false, false);
            case PAGE_UP     -> new KeystrokeParser.Keystroke("pageup", ctrl, alt, shift, false, false);
            case PAGE_DOWN   -> new KeystrokeParser.Keystroke("pagedown", ctrl, alt, shift, false, false);
            // Mouse / paste / focus have no keybinding-equivalent key name.
            case MOUSE_EVENT, PASTE, FOCUS_EVENT -> null;
            default -> null;
        };
    }
}
