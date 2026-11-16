package com.claudecode.ui.lanterna.components;

import com.claudecode.core.annotation.Explanation;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.nio.charset.StandardCharsets;

/**
 * Writes text to the Windows clipboard as UTF-16.
 *
 * <p>{@code clip.exe} decodes its stdin with the console code page. On a
 * Chinese Windows install that is GBK, so UTF-8 from a selection is stored
 * as the wrong characters and pastes as mojibake.
 */
@Explanation("Windows selection copy must use CF_UNICODETEXT; clip.exe misreads UTF-8 as GBK")
final class WindowsUnicodeClipboard {

    private static final int CF_UNICODETEXT = 13;
    private static final int GMEM_MOVEABLE = 0x0002;

    private WindowsUnicodeClipboard() {}

    static boolean setText(String text) {
        if (text == null) return false;
        byte[] encoded = utf16(text);
        Pointer memory = Kernel.INSTANCE.GlobalAlloc(GMEM_MOVEABLE, encoded.length);
        if (memory == null) return false;
        boolean transferred = false;
        try {
            Pointer locked = Kernel.INSTANCE.GlobalLock(memory);
            if (locked == null) return false;
            locked.write(0, encoded, 0, encoded.length);
            Kernel.INSTANCE.GlobalUnlock(memory);
            for (int attempt = 0; attempt < 5; attempt++) {
                if (!User.INSTANCE.OpenClipboard(null)) {
                    pause();
                    continue;
                }
                try {
                    User.INSTANCE.EmptyClipboard();
                    transferred = User.INSTANCE.SetClipboardData(CF_UNICODETEXT, memory) != null;
                    if (transferred) return true;
                } finally {
                    User.INSTANCE.CloseClipboard();
                }
                pause();
            }
            return false;
        } finally {
            if (!transferred) Kernel.INSTANCE.GlobalFree(memory);
        }
    }

    /** UTF-16LE clipboard payload, including the required trailing NUL. */
    static byte[] utf16(String text) {
        byte[] characters = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] terminated = new byte[characters.length + 2];
        System.arraycopy(characters, 0, terminated, 0, characters.length);
        return terminated;
    }

    private static void pause() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private interface User extends StdCallLibrary {
        User INSTANCE = Native.load("user32", User.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean OpenClipboard(Pointer owner);

        boolean EmptyClipboard();

        Pointer SetClipboardData(int format, Pointer memory);

        boolean CloseClipboard();
    }

    private interface Kernel extends StdCallLibrary {
        Kernel INSTANCE = Native.load("kernel32", Kernel.class, W32APIOptions.DEFAULT_OPTIONS);

        Pointer GlobalAlloc(int flags, long bytes);

        Pointer GlobalLock(Pointer memory);

        boolean GlobalUnlock(Pointer memory);

        Pointer GlobalFree(Pointer memory);
    }
}
