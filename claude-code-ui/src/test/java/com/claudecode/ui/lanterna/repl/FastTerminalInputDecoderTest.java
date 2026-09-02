package com.claudecode.ui.lanterna.repl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.googlecode.lanterna.input.CharacterPattern;
import com.googlecode.lanterna.input.DefaultKeyDecodingProfile;
import com.googlecode.lanterna.input.InputDecoder;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.PasteKeyStroke;
import com.claudecode.ui.lanterna.input.BackspaceRunKeyStroke;
import com.claudecode.ui.lanterna.input.PlainTextKeyStroke;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class FastTerminalInputDecoderTest {

    @Test
    void matchesLanternaForOrdinaryAndControlCharacters() throws Exception {
        assertParity("abcdefghijklmnopqrstuvwxyz012345");
        assertParity("中文🙂");
        for (char control : new char[] {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13,
            14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26,
            28, 29, 30, 31, 0x7f
        }) {
            assertParity(String.valueOf(control));
        }
    }

    @Test
    void matchesLanternaForEscapeProtocols() throws Exception {
        for (String sequence : List.of(
                "\033",
                "\033[A",
                "\033[1;5D",
                "\033x",
                "\033\r",
                "\033[I",
                "\033[O",
                "\033[<0;10;5M",
                "\033[97;5u",
                "\033[200~hello\nworld\033[201~")) {
            assertParity(sequence);
        }
    }

    @Test
    void ordinaryBurstNeverConsultsEscapePatterns() throws Exception {
        AtomicInteger matches = new AtomicInteger();
        CharacterPattern counting = _ -> {
            matches.incrementAndGet();
            return null;
        };
        FastTerminalInputDecoder decoder = new FastTerminalInputDecoder(
            new StringReader("abcdefghijklmnopqrstuvwxyz012345"), List.of(counting));

        PlainTextKeyStroke batch = (PlainTextKeyStroke) decoder.pollInput();

        assertEquals("abcdefghijklmnopqrstuvwxyz012345", batch.text());
        assertEquals(0, matches.get());
        assertEquals(KeyType.EOF, decoder.pollInput().getKeyType());
    }

    private static void assertParity(String input) throws Exception {
        assertEquals(decodeLanterna(input), decodeFast(input), () -> "input=" + printable(input));
    }

    private static List<String> decodeLanterna(String input) throws Exception {
        InputDecoder decoder = new InputDecoder(new StringReader(input));
        decoder.addProfile(new DefaultKeyDecodingProfile());
        List<String> result = new ArrayList<>();
        while (true) {
            KeyStroke key = decoder.getNextCharacter(false);
            if (key == null) continue;
            result.add(semantic(key));
            if (key.getKeyType() == KeyType.EOF) return result;
        }
    }

    private static List<String> decodeFast(String input) throws Exception {
        FastTerminalInputDecoder decoder = new FastTerminalInputDecoder(
            new StringReader(input), new DefaultKeyDecodingProfile().getPatterns());
        List<String> result = new ArrayList<>();
        while (true) {
            KeyStroke key = decoder.pollInput();
            if (key == null) continue;
            if (key instanceof PlainTextKeyStroke batch) {
                for (int index = 0; index < batch.text().length(); index++) {
                    result.add(semantic(new KeyStroke(
                        batch.text().charAt(index), false, false)));
                }
                continue;
            }
            if (key instanceof BackspaceRunKeyStroke batch) {
                for (int index = 0; index < batch.count(); index++) {
                    result.add(semantic(new KeyStroke(KeyType.BACKSPACE)));
                }
                continue;
            }
            result.add(semantic(key));
            if (key.getKeyType() == KeyType.EOF) return result;
        }
    }

    private static String semantic(KeyStroke key) {
        StringBuilder result = new StringBuilder()
            .append(key.getClass().getSimpleName()).append(':')
            .append(key.getKeyType()).append(':')
            .append(key.getCharacter() == null ? "null" : (int) key.getCharacter()).append(':')
            .append(key.isCtrlDown()).append(':')
            .append(key.isAltDown()).append(':')
            .append(key.isShiftDown());
        if (key instanceof PasteKeyStroke paste) {
            result.append(":paste=").append(paste.getPastedText());
        }
        if (key instanceof MouseAction mouse) {
            result.append(":mouse=").append(mouse.getActionType())
                .append(',').append(mouse.getButton())
                .append(',').append(mouse.getPosition());
        }
        return result.toString();
    }

    private static String printable(String value) {
        return value.replace("\033", "\\e")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }
}
