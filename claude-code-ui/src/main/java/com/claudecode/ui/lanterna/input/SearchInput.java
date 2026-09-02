package com.claudecode.ui.lanterna.input;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;


public final class SearchInput {


    public interface Listener {


        void onExit();

        /** Query text changed — re-filter and reset the selection. */
        void onChange();
    }

    private final Listener listener;
    private final boolean backspaceExitsOnEmpty;
    private final StringBuilder query = new StringBuilder();
    private int cursorOffset;
    private int lastYankStart = -1;
    private int lastYankLength;

    public SearchInput(Listener listener) {
        this(listener, true);
    }

    public SearchInput(Listener listener, boolean backspaceExitsOnEmpty) {
        this.listener = listener;
        this.backspaceExitsOnEmpty = backspaceExitsOnEmpty;
    }

    public String query() {
        return query.toString();
    }

    public int cursorOffset() {
        return cursorOffset;
    }


    public void reset(String initialQuery) {
        query.setLength(0);
        query.append(initialQuery);
        cursorOffset = query.length();
        lastYankStart = -1;
        lastYankLength = 0;
    }

    /** Handles one keystroke while search is active; every key is consumed. */
    public void handleKey(KeyStroke key) {
        KeyType t = key.getKeyType();

        if (!isKillKey(key)) {
            KillRing.INSTANCE.resetAccumulation();
        }
        if (!isYankKey(key)) {
            KillRing.INSTANCE.resetYankState();
            lastYankStart = -1;
            lastYankLength = 0;
        }
        switch (t) {
            case ENTER, ARROW_DOWN -> listener.onExit();
            case ARROW_UP -> { } // no onExitUp configured — consumed
            case ESCAPE -> {
                if (!query.isEmpty()) {
                    reset("");
                    listener.onChange();
                } else {
                    listener.onExit();
                }
            }
            case BACKSPACE -> handleBackspace(key);
            case DELETE -> {
                if (cursorOffset < query.length()) {
                    query.delete(cursorOffset, nextGraphemeOffset());
                    listener.onChange();
                }
            }
            case ARROW_LEFT -> cursorOffset = (key.isCtrlDown() || key.isAltDown())
                ? prevWordOffset() : previousGraphemeOffset();
            case ARROW_RIGHT -> cursorOffset = (key.isCtrlDown() || key.isAltDown())
                ? nextWordOffset() : nextGraphemeOffset();
            case HOME -> cursorOffset = 0;
            case END -> cursorOffset = query.length();
            case PASTE -> {
                if (key instanceof PasteKeyStroke pks) {
                    String firstLine = pks.getPastedText().split("\\r\\n|\\r|\\n", 2)[0];
                    insert(firstLine);
                }
            }
            case TAB -> { }
            case CHARACTER -> handleCharacter(key);
            default -> { }
        }
    }

    private void handleBackspace(KeyStroke key) {
        if (key.isAltDown()) { // Meta+Backspace: kill word before
            killTo(prevWordOffset(), KillRing.Direction.PREPEND);
            return;
        }
        if (query.isEmpty()) {
            if (backspaceExitsOnEmpty) listener.onExit();
            return;
        }
        if (cursorOffset > 0) {
            int previous = previousGraphemeOffset();
            query.delete(previous, cursorOffset);
            cursorOffset = previous;
            listener.onChange();
        }
    }

    private void handleCharacter(KeyStroke key) {
        Character raw = key.getCharacter();
        if (raw == null) {
            return;
        }
        char ch = raw;
        if (key.isCtrlDown()) {
            handleCtrl(Character.toLowerCase(ch));
            return;
        }
        if (key.isAltDown()) {
            handleMeta(Character.toLowerCase(ch));
            return;
        }
        if (ch >= 0x20) {
            insert(String.valueOf(ch));
        }
    }

    private void handleCtrl(char ch) {
        switch (ch) {
            case 'a' -> cursorOffset = 0;
            case 'e' -> cursorOffset = query.length();
            case 'b' -> cursorOffset = previousGraphemeOffset();
            case 'f' -> cursorOffset = nextGraphemeOffset();
            case 'd' -> {
                if (query.isEmpty()) {
                    listener.onExit();
                } else if (cursorOffset < query.length()) {
                    query.delete(cursorOffset, nextGraphemeOffset());
                    listener.onChange();
                }
            }
            case 'h' -> {
                if (query.isEmpty()) {
                    if (backspaceExitsOnEmpty) listener.onExit();
                } else if (cursorOffset > 0) {
                    int previous = previousGraphemeOffset();
                    query.delete(previous, cursorOffset);
                    cursorOffset = previous;
                    listener.onChange();
                }
            }
            case 'k' -> {
                if (cursorOffset < query.length()) {
                    KillRing.INSTANCE.push(query.substring(cursorOffset),
                        KillRing.Direction.APPEND);
                    query.delete(cursorOffset, query.length());
                    listener.onChange();
                }
            }
            case 'u' -> killTo(0, KillRing.Direction.PREPEND);
            case 'w' -> killTo(prevWordOffset(), KillRing.Direction.PREPEND);
            case 'y' -> {
                String kill = KillRing.INSTANCE.getLast();
                if (!kill.isEmpty()) {
                    int start = cursorOffset;
                    insert(kill);
                    KillRing.INSTANCE.recordYank();
                    lastYankStart = start;
                    lastYankLength = kill.length();
                }
            }
            default -> { }
        }
    }

    private void handleMeta(char ch) {
        switch (ch) {
            case 'b' -> cursorOffset = prevWordOffset();
            case 'f' -> cursorOffset = nextWordOffset();
            case 'd' -> {
                int end = nextWordOffset();
                if (end > cursorOffset) {
                    query.delete(cursorOffset, end);
                    listener.onChange();
                }
            }
            case 'y' -> {
                String replacement = KillRing.INSTANCE.yankPop();
                if (replacement != null && lastYankStart >= 0
                        && lastYankStart + lastYankLength <= query.length()) {
                    query.replace(lastYankStart, lastYankStart + lastYankLength, replacement);
                    cursorOffset = lastYankStart + replacement.length();
                    lastYankLength = replacement.length();
                    KillRing.INSTANCE.continueYank();
                    listener.onChange();
                }
            }
            default -> { }
        }
    }

    private void insert(String text) {
        if (text.isEmpty()) {
            return;
        }
        query.insert(cursorOffset, text);
        cursorOffset += text.length();
        listener.onChange();
    }

    /** Deletes [target, cursor), pushing the killed text; no-op when empty. */
    private void killTo(int target, KillRing.Direction direction) {
        if (target < cursorOffset) {
            KillRing.INSTANCE.push(query.substring(target, cursorOffset), direction);
            query.delete(target, cursorOffset);
            cursorOffset = target;
            listener.onChange();
        }
    }

    private int prevWordOffset() {
        String text = query.toString();
        int previous = -1;
        for (int[] word : wordLikeSegments(text)) {
            int start = word[0];
            int end = word[1];
            if (cursorOffset > start && cursorOffset <= end) return start;
            if (start >= cursorOffset) break;
            previous = start;
        }
        return previous >= 0 ? previous : 0;
    }

    private int nextWordOffset() {
        String text = query.toString();
        for (int[] word : wordLikeSegments(text)) {
            if (word[0] > cursorOffset) return word[0];
        }
        return text.length();
    }

    private static List<int[]> wordLikeSegments(String text) {
        List<int[]> segments = new ArrayList<>();
        BreakIterator words = BreakIterator.getWordInstance();
        words.setText(text);
        for (int start = words.first(), end = words.next();
             end != BreakIterator.DONE;
             start = end, end = words.next()) {
            int runStart = -1;
            for (int offset = start; offset < end;) {
                int codePoint = text.codePointAt(offset);
                int next = offset + Character.charCount(codePoint);
                boolean apostropheInsideWord = (codePoint == '\'' || codePoint == 0x2019)
                    && offset > start && next < end
                    && isWordCore(text.codePointBefore(offset))
                    && isWordCore(text.codePointAt(next));
                boolean wordLike = isWordCore(codePoint) || apostropheInsideWord;
                if (wordLike && runStart < 0) runStart = offset;
                if (!wordLike && runStart >= 0) {
                    segments.add(new int[] {runStart, offset});
                    runStart = -1;
                }
                offset = next;
            }
            if (runStart >= 0) segments.add(new int[] {runStart, end});
        }
        return segments;
    }

    private static boolean isWordCore(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
            || codePoint == '_'
            || Character.getType(codePoint) == Character.NON_SPACING_MARK
            || Character.getType(codePoint) == Character.COMBINING_SPACING_MARK;
    }

    private int previousGraphemeOffset() {
        if (cursorOffset <= 0) return 0;
        BreakIterator graphemes = BreakIterator.getCharacterInstance();
        graphemes.setText(query.toString());
        int previous = graphemes.preceding(cursorOffset);
        return previous == BreakIterator.DONE ? 0 : previous;
    }

    private int nextGraphemeOffset() {
        if (cursorOffset >= query.length()) return query.length();
        BreakIterator graphemes = BreakIterator.getCharacterInstance();
        graphemes.setText(query.toString());
        int next = graphemes.following(cursorOffset);
        return next == BreakIterator.DONE ? query.length() : next;
    }

    private static boolean isKillKey(KeyStroke key) {
        if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
                && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            return ch == 'k' || ch == 'u' || ch == 'w';
        }
        return key.getKeyType() == KeyType.BACKSPACE && key.isAltDown();
    }

    private static boolean isYankKey(KeyStroke key) {
        return key.getKeyType() == KeyType.CHARACTER && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == 'y'
            && (key.isCtrlDown() || key.isAltDown());
    }
}
