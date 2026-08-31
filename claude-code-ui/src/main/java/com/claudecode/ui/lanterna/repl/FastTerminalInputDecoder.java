package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.DefaultKeyDecodingProfile;
import com.googlecode.lanterna.input.KeyDecodingProfile;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.claudecode.ui.lanterna.input.BackspaceRunKeyStroke;
import com.claudecode.ui.lanterna.input.PlainTextKeyStroke;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Lanterna-compatible terminal decoder with an O(1) ordinary-character path.
 *
 * <p>{@code InputDecoder} asks every registered escape/mouse/paste/Kitty
 * pattern to inspect every printable character. That flexibility is useful
 * for ambiguous escape sequences but is unnecessary for the overwhelmingly
 * common one-character case. {@link EscapeSequenceInputStream} already makes
 * complete ESC sequences atomically visible, so this decoder maps ordinary
 * and control characters directly and invokes Lanterna's complete pattern set
 * only for ESC-prefixed input.
 */
final class FastTerminalInputDecoder {

    private static final int NO_INPUT = -2;
    private static final char ESC = KeyDecodingProfile.ESC_CODE;

    private final Reader source;
    private final List<CharacterPattern> escapePatterns;
    private final ArrayDeque<Character> readAhead = new ArrayDeque<>();
    private final ArrayList<Character> matching = new ArrayList<>(16);
    private final char[] plainTextBuffer = new char[256];
    private boolean seenEof;

    FastTerminalInputDecoder(InputStream source, Charset charset) {
        this(new InputStreamReader(
            Objects.requireNonNull(source, "source"),
            Objects.requireNonNull(charset, "charset")),
            new DefaultKeyDecodingProfile().getPatterns());
    }

    FastTerminalInputDecoder(Reader source, Collection<CharacterPattern> patterns) {
        Objects.requireNonNull(source, "source");
        this.source = source instanceof BufferedReader ? source : new BufferedReader(source);
        this.escapePatterns = List.copyOf(Objects.requireNonNull(patterns, "patterns"));
    }

    synchronized KeyStroke pollInput() throws IOException {
        return next(false);
    }

    synchronized KeyStroke readInput() throws IOException {
        return next(true);
    }

    private KeyStroke next(boolean blocking) throws IOException {
        while (true) {
            int value = readCharacter(blocking);
            if (value == NO_INPUT) return null;
            if (value < 0) return new KeyStroke(KeyType.EOF);
            char character = (char) value;
            if (character == ESC) return decodeEscape();
            if (isPrintable(character)) return decodePlainTextRun(character);
            if (isBackspace(character)) return decodeBackspaceRun();
            KeyStroke direct = decodeDirect(character);
            if (direct != null) return direct;
            blocking = false;
        }
    }

    private int readCharacter(boolean blocking) throws IOException {
        Character buffered = readAhead.pollFirst();
        if (buffered != null) return buffered;
        if (seenEof) return -1;
        if (!blocking && !source.ready()) return NO_INPUT;
        int value = source.read();
        if (value < 0) seenEof = true;
        return value;
    }

    private KeyStroke decodeEscape() throws IOException {
        matching.clear();
        matching.add(ESC);
        KeyStroke bestMatch = null;
        int bestLength = 0;

        while (true) {
            CharacterPattern.Matching result = matchEscape(matching);
            if (result.fullMatch != null) {
                bestMatch = result.fullMatch;
                bestLength = matching.size();
            }
            if (!result.partialMatch) break;

            int next = readCharacter(false);
            if (next == NO_INPUT || next < 0) break;
            matching.add((char) next);
        }

        if (bestMatch == null) {
            // Match Lanterna's invalid-input recovery: discard the failed ESC
            // prefix and make any following characters available normally.
            restoreTail(1);
            return next(false);
        }
        restoreTail(bestLength);
        return bestMatch;
    }

    private KeyStroke decodePlainTextRun(char first) throws IOException {
        StringBuilder text = null;
        while (!readAhead.isEmpty()) {
            char character = readAhead.removeFirst();
            if (!isPrintable(character)) {
                readAhead.addFirst(character);
                break;
            }
            if (text == null) text = new StringBuilder(64).append(first);
            text.append(character);
        }
        while (source.ready()) {
            int count = source.read(plainTextBuffer, 0, plainTextBuffer.length);
            if (count < 0) {
                seenEof = true;
                break;
            }
            if (count == 0) break;
            int printable = 0;
            while (printable < count && isPrintable(plainTextBuffer[printable])) printable++;
            if (printable > 0) {
                if (text == null) text = new StringBuilder(64).append(first);
                text.append(plainTextBuffer, 0, printable);
            }
            if (printable == count) continue;
            for (int index = count - 1; index >= printable; index--) {
                readAhead.addFirst(plainTextBuffer[index]);
            }
            break;
        }
        return text == null
            ? new KeyStroke(first, false, false)
            : new PlainTextKeyStroke(text.toString());
    }

    private KeyStroke decodeBackspaceRun() throws IOException {
        int count = 1;
        while (!readAhead.isEmpty()) {
            char character = readAhead.removeFirst();
            if (!isBackspace(character)) {
                readAhead.addFirst(character);
                break;
            }
            count++;
        }
        while (source.ready()) {
            int read = source.read(plainTextBuffer, 0, plainTextBuffer.length);
            if (read < 0) {
                seenEof = true;
                break;
            }
            if (read == 0) break;
            int repeated = 0;
            while (repeated < read && isBackspace(plainTextBuffer[repeated])) repeated++;
            count += repeated;
            if (repeated == read) continue;
            for (int index = read - 1; index >= repeated; index--) {
                readAhead.addFirst(plainTextBuffer[index]);
            }
            break;
        }
        return count == 1
            ? new KeyStroke(KeyType.BACKSPACE)
            : new BackspaceRunKeyStroke(count);
    }

    private CharacterPattern.Matching matchEscape(List<Character> sequence) {
        boolean partial = false;
        KeyStroke full = null;
        for (CharacterPattern pattern : escapePatterns) {
            CharacterPattern.Matching result = pattern.match(sequence);
            if (result == null) continue;
            if (result.partialMatch) partial = true;
            if (result.fullMatch != null) full = result.fullMatch;
        }
        return new CharacterPattern.Matching(partial, full);
    }

    private void restoreTail(int consumed) {
        for (int index = matching.size() - 1; index >= consumed; index--) {
            readAhead.addFirst(matching.get(index));
        }
        matching.clear();
    }

    private static KeyStroke decodeDirect(char character) {
        return switch (character) {
            case '\t' -> new KeyStroke(KeyType.TAB);
            case '\n' -> new KeyStroke(KeyType.ENTER, false, true);
            case '\r' -> new KeyStroke(KeyType.ENTER);
            case 0x08, 0x7f -> new KeyStroke(KeyType.BACKSPACE);
            default -> {
                if (character < 32) {
                    char control = switch (character) {
                        case 0 -> ' ';
                        case 28 -> '\\';
                        case 29 -> ']';
                        case 30 -> '^';
                        case 31 -> '_';
                        default -> (char) ('a' - 1 + character);
                    };
                    yield new KeyStroke(control, true, false);
                }
                yield null;
            }
        };
    }

    private static boolean isPrintable(char character) {
        if (character >= 0x20 && character <= 0x7e) return true;
        if (Character.isISOControl(character)) return false;
        Character.UnicodeBlock block = Character.UnicodeBlock.of(character);
        return block != null && block != Character.UnicodeBlock.SPECIALS;
    }

    private static boolean isBackspace(char character) {
        return character == 0x08 || character == 0x7f;
    }
}
