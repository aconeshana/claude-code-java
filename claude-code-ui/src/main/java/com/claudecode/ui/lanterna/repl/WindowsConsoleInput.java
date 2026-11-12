package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.Union;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows console input that preserves IME characters.
 *
 * <p>With virtual-terminal input enabled, PowerShell delivers ASCII as escape
 * sequences but leaves a committed CJK character in the console key event's
 * Unicode field. A plain {@code System.in} reader never sees that field, so
 * the prompt accepts Latin text and silently drops Chinese.
 */
@Explanation("Reads Windows console input records so IME characters reach the prompt as UTF-8")
final class WindowsConsoleInput extends InputStream {

    private static final Logger log = LoggerFactory.getLogger(WindowsConsoleInput.class);
    private static final int STD_INPUT_HANDLE = -10;
    private static final int ENABLE_PROCESSED_INPUT = 0x0001;
    private static final int ENABLE_LINE_INPUT = 0x0002;
    private static final int ENABLE_ECHO_INPUT = 0x0004;
    private static final int ENABLE_WINDOW_INPUT = 0x0008;
    private static final int ENABLE_MOUSE_INPUT = 0x0010;
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;
    private static final short KEY_EVENT = 1;
    private static final short MOUSE_EVENT = 2;
    private static final short WINDOW_BUFFER_SIZE_EVENT = 4;
    private static final int FROM_LEFT_1ST_BUTTON_PRESSED = 0x0001;
    private static final int RIGHTMOST_BUTTON_PRESSED = 0x0002;
    private static final int MOUSE_WHEELED = 0x0004;
    private static final int DOUBLE_CLICK = 0x0002;
    private static final int MOUSE_MOVED = 0x0001;

    private final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private ByteBuffer pending = ByteBuffer.allocate(0);

    private WindowsConsoleInput() {}

    static InputStream open() {
        if (!Platform.IS_WINDOWS) return null;
        try {
            Console console = Console.INSTANCE;
            WinNT.HANDLE input = console.GetStdHandle(STD_INPUT_HANDLE);
            int[] mode = new int[1];
            if (input == null || !console.GetConsoleMode(input, mode)) return null;
            int updated = mode[0]
                & ~ENABLE_LINE_INPUT
                & ~ENABLE_ECHO_INPUT
                | ENABLE_PROCESSED_INPUT
                | ENABLE_WINDOW_INPUT
                | ENABLE_MOUSE_INPUT
                | ENABLE_VIRTUAL_TERMINAL_INPUT;
            if (!console.SetConsoleMode(input, updated)) return null;
            console.SetConsoleCP(65001);
            console.SetConsoleOutputCP(65001);
            log.info("[LANTERNA] console input mode {} -> {}; code page UTF-8", mode[0], updated);
            return new WindowsConsoleInput();
        } catch (RuntimeException exception) {
            log.warn("[LANTERNA] console input setup failed: {}", exception.toString());
            return null;
        }
    }

    @Override
    public synchronized int read() throws IOException {
        if (!pending.hasRemaining()) pending = readRecords(true);
        return pending.hasRemaining() ? pending.get() & 0xff : -1;
    }

    @Override
    public synchronized int read(byte[] target, int offset, int length) throws IOException {
        if (length <= 0) return 0;
        if (!pending.hasRemaining()) pending = readRecords(true);
        int count = Math.min(length, pending.remaining());
        pending.get(target, offset, count);
        return count;
    }

    @Override
    public synchronized int available() throws IOException {
        if (!pending.hasRemaining()) pending = readRecords(false);
        return pending.remaining();
    }

    private ByteBuffer readRecords(boolean blocking) throws IOException {
        IntByReference queued = new IntByReference();
        WinNT.HANDLE input = Console.INSTANCE.GetStdHandle(STD_INPUT_HANDLE);
        if (Console.INSTANCE.GetNumberOfConsoleInputEvents(input, queued) == 0) {
            throw new IOException("GetNumberOfConsoleInputEvents failed");
        }
        if (!blocking && queued.getValue() == 0) return ByteBuffer.allocate(0);
        InputRecord[] records = new InputRecord[64];
        IntByReference read = new IntByReference();
        if (!Console.INSTANCE.ReadConsoleInput(input, records, records.length, read)) {
            throw new IOException("ReadConsoleInput failed");
        }
        StringBuilder characters = new StringBuilder();
        int count = read.getValue();
        for (int index = 0; index < count; index++) {
            InputRecord record = records[index];
            record.read();
            switch (record.eventType) {
                case KEY_EVENT -> appendKey(record.event.key, characters);
                case MOUSE_EVENT -> appendMouse(record.event.mouse, characters);
                case WINDOW_BUFFER_SIZE_EVENT -> {}
                default -> {}
            }
        }
        return characters.isEmpty() ? ByteBuffer.allocate(0) : encode(characters);
    }

    private static void appendKey(KeyEvent key, StringBuilder characters) {
        if (!key.keyDown) return;
        if (key.character == 0) return;
        int repeat = Math.max(1, key.repeatCount);
        for (int index = 0; index < repeat; index++) characters.append(key.character);
    }

    private static void appendMouse(MouseEvent mouse, StringBuilder characters) {
        if ((mouse.eventFlags & MOUSE_WHEELED) != 0) {
            characters.append((mouse.buttonState >>> 16) > 0 ? "\033[<64;" : "\033[<65;");
        } else if ((mouse.eventFlags & (MOUSE_MOVED | DOUBLE_CLICK)) != 0) {
            return;
        } else if ((mouse.buttonState & FROM_LEFT_1ST_BUTTON_PRESSED) != 0) {
            characters.append("\033[<0;");
        } else if ((mouse.buttonState & RIGHTMOST_BUTTON_PRESSED) != 0) {
            characters.append("\033[<2;");
        } else {
            characters.append("\033[<0;");
        }
        characters.append(mouse.position.x + 1).append(';')
            .append(mouse.position.y + 1).append('M');
    }

    private ByteBuffer encode(CharSequence characters) throws IOException {
        ByteBuffer encoded = ByteBuffer.allocate(
            (int) encoder.maxBytesPerChar() * characters.length());
        CoderResult result = encoder.encode(java.nio.CharBuffer.wrap(characters), encoded, false);
        if (result.isError()) result.throwException();
        encoded.flip();
        return encoded;
    }

    private interface Console extends StdCallLibrary {
        Console INSTANCE = Native.load("kernel32", Console.class, W32APIOptions.DEFAULT_OPTIONS);

        WinNT.HANDLE GetStdHandle(int handle);

        boolean GetConsoleMode(WinNT.HANDLE handle, int[] mode);

        boolean SetConsoleMode(WinNT.HANDLE handle, int mode);

        boolean SetConsoleCP(int codePage);

        boolean SetConsoleOutputCP(int codePage);

        int GetNumberOfConsoleInputEvents(WinNT.HANDLE handle, IntByReference count);

        boolean ReadConsoleInput(WinNT.HANDLE handle, InputRecord[] records,
                                 int length, IntByReference read);
    }

    @SuppressWarnings("unused")
    public static final class InputRecord extends Structure {
        public short eventType;
        public Event event;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return Arrays.asList("eventType", "event");
        }

        @Override
        public void read() {
            super.read();
            switch (eventType) {
                case KEY_EVENT -> event.setType(KeyEvent.class);
                case MOUSE_EVENT -> event.setType(MouseEvent.class);
                case WINDOW_BUFFER_SIZE_EVENT -> event.setType(WindowEvent.class);
                default -> {}
            }
            event.read();
        }

        public static final class Event extends Union {
            public KeyEvent key;
            public MouseEvent mouse;
            public WindowEvent window;
        }
    }

    @SuppressWarnings("unused")
    public static final class KeyEvent extends Structure {
        public boolean keyDown;
        public short repeatCount;
        public short virtualKeyCode;
        public short virtualScanCode;
        public char character;
        public int controlKeyState;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return Arrays.asList("keyDown", "repeatCount", "virtualKeyCode",
                "virtualScanCode", "character", "controlKeyState");
        }
    }

    @SuppressWarnings("unused")
    public static final class MouseEvent extends Structure {
        public Coordinate position = new Coordinate();
        public int buttonState;
        public int controlKeyState;
        public int eventFlags;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return Arrays.asList("position", "buttonState", "controlKeyState", "eventFlags");
        }
    }

    @SuppressWarnings("unused")
    public static final class WindowEvent extends Structure {
        public Coordinate size = new Coordinate();

        @Override
        protected java.util.List<String> getFieldOrder() {
            return java.util.List.of("size");
        }
    }

    @SuppressWarnings("unused")
    public static final class Coordinate extends Structure {
        public short x;
        public short y;

        @Override
        protected java.util.List<String> getFieldOrder() {
            return Arrays.asList("x", "y");
        }
    }
}
