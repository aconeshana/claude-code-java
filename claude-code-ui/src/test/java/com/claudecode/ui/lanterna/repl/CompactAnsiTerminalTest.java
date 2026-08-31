package com.claudecode.ui.lanterna.repl;

import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.ExtendedTerminal;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactAnsiTerminalTest {

    @Test
    void longBlankRunDefersPhysicalCursorRestoreUntilMoreTextArrives() throws Exception {
        List<String> calls = new ArrayList<>();
        ExtendedTerminal delegate = fakeTerminal(calls);
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(delegate);

        terminal.setCursorPosition(7, 4);
        terminal.putString(" ".repeat(80));

        assertEquals(List.of("cursor:7,4", "text:\\033[K"), calls);

        terminal.putString("next");

        assertEquals(List.of(
            "cursor:7,4", "text:\\033[K", "cursor:87,4", "text:next"), calls);
    }

    @Test
    void explicitCursorMoveAfterEraseDoesNotEmitTheDeferredPosition() throws Exception {
        List<String> calls = new ArrayList<>();
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(fakeTerminal(calls));

        terminal.setCursorPosition(7, 4);
        terminal.putString(" ".repeat(80));
        terminal.setCursorPosition(2, 8);

        assertEquals(List.of(
            "cursor:7,4", "text:\\033[K", "cursor:2,8"), calls);
    }

    @Test
    void textAndShortSpacingRemainLiteral() throws Exception {
        List<String> calls = new ArrayList<>();
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(fakeTerminal(calls));

        terminal.putString("model  picker");
        terminal.putString("       ");

        assertEquals(List.of("text:model  picker", "text:       "), calls);
    }

    @Test
    void repeatedCursorVisibilityStateDoesNotEmitDuplicateAnsi() throws Exception {
        List<String> calls = new ArrayList<>();
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(fakeTerminal(calls));

        terminal.setCursorVisible(true);
        terminal.setCursorVisible(true);
        terminal.setCursorVisible(false);
        terminal.setCursorVisible(false);

        assertEquals(List.of("visible:true", "visible:false"), calls);
    }

    @Test
    void escapeInputIsDelegatedWithoutPollingForAContinuation() throws Exception {
        ArrayDeque<KeyStroke> input = new ArrayDeque<>();
        input.add(new KeyStroke(KeyType.ESCAPE));
        AtomicInteger emptyPolls = new AtomicInteger(100);
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(
            fakeInputTerminal(input, emptyPolls));

        assertEquals(KeyType.ESCAPE, terminal.readInput().getKeyType());
        assertEquals(100, emptyPolls.get());
    }

    @Test
    void preservesEscapeAndFollowingCharactersWhenTailIsNotMouseInput() throws Exception {
        ArrayDeque<KeyStroke> input = new ArrayDeque<>();
        input.add(new KeyStroke(KeyType.ESCAPE));
        input.add(new KeyStroke('[', false, false));
        input.add(new KeyStroke('A', false, false));
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(
            fakeInputTerminal(input, new AtomicInteger()));

        assertEquals(KeyType.ESCAPE, terminal.readInput().getKeyType());
        assertEquals('[', terminal.readInput().getCharacter());
        assertEquals('A', terminal.readInput().getCharacter());
    }

    @Test
    void windowsFastInputPathPollsNativeSizeAtABoundedRate() throws Exception {
        AtomicInteger sizeQueries = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000_000_000L);
        CompactAnsiTerminal terminal = new CompactAnsiTerminal(
            fakeSizeTerminal(sizeQueries),
            new FastTerminalInputDecoder(new StringReader(""), List.of()),
            true,
            now::get);

        terminal.pollInput();
        terminal.pollInput();
        assertEquals(1, sizeQueries.get(), "tight GUI polls must share one native size query");

        now.addAndGet(100_000_000L);
        terminal.pollInput();
        assertEquals(2, sizeQueries.get(), "a later frame re-queries the ConPTY dimensions");
    }

    private static ExtendedTerminal fakeInputTerminal(
            ArrayDeque<KeyStroke> input, AtomicInteger emptyPolls) {
        return (ExtendedTerminal) Proxy.newProxyInstance(
            CompactAnsiTerminalTest.class.getClassLoader(),
            new Class<?>[] { ExtendedTerminal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "readInput" -> input.pollFirst();
                case "pollInput" -> {
                    if (emptyPolls.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                        yield null;
                    }
                    yield input.pollFirst();
                }
                case "getCursorPosition" -> TerminalPosition.of(0, 0);
                case "getTerminalSize" -> new TerminalSize(120, 40);
                case "enquireTerminal" -> new byte[0];
                case "toString" -> "fake-input-terminal";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    private static ExtendedTerminal fakeTerminal(List<String> calls) {
        return (ExtendedTerminal) Proxy.newProxyInstance(
            CompactAnsiTerminalTest.class.getClassLoader(),
            new Class<?>[] { ExtendedTerminal.class },
            (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "putString" -> {
                        calls.add("text:" + ((String) args[0]).replace("\033", "\\033"));
                        yield null;
                    }
                    case "setCursorPosition" -> {
                        if (args.length == 1) {
                            TerminalPosition p = (TerminalPosition) args[0];
                            calls.add("cursor:" + p.getColumn() + "," + p.getRow());
                        } else {
                            calls.add("cursor:" + args[0] + "," + args[1]);
                        }
                        yield null;
                    }
                    case "getCursorPosition" -> TerminalPosition.of(0, 0);
                    case "setCursorVisible" -> {
                        calls.add("visible:" + args[0]);
                        yield null;
                    }
                    case "getTerminalSize" -> new TerminalSize(120, 40);
                    case "enquireTerminal" -> new byte[0];
                    case "pollInput", "readInput", "newTextGraphics" -> null;
                    case "toString" -> "fake-terminal";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
            });
    }

    private static ExtendedTerminal fakeSizeTerminal(AtomicInteger sizeQueries) {
        return (ExtendedTerminal) Proxy.newProxyInstance(
            CompactAnsiTerminalTest.class.getClassLoader(),
            new Class<?>[] { ExtendedTerminal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getTerminalSize" -> {
                    sizeQueries.incrementAndGet();
                    yield new TerminalSize(120, 40);
                }
                case "getCursorPosition" -> TerminalPosition.of(0, 0);
                case "enquireTerminal" -> new byte[0];
                case "toString" -> "fake-size-terminal";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }
}
