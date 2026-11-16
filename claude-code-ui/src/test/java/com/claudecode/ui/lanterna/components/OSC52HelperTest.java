package com.claudecode.ui.lanterna.components;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OSC52HelperTest {

    private final OSC52Helper.Platform originalPlatform = OSC52Helper.platform;
    private final OSC52Helper.Environment originalEnvironment = OSC52Helper.environment;
    private final OSC52Helper.NativeCommandRunner originalRunner = OSC52Helper.nativeCommandRunner;
    private final OSC52Helper.WindowsClipboard originalWindowsClipboard = OSC52Helper.windowsClipboard;

    @AfterEach
    void restoreSeams() {
        OSC52Helper.platform = originalPlatform;
        OSC52Helper.environment = originalEnvironment;
        OSC52Helper.nativeCommandRunner = originalRunner;
        OSC52Helper.windowsClipboard = originalWindowsClipboard;
        OSC52Helper.resetNativeClipboardCache();
    }

    @Test
    void localMacCopySendsChineseToPbcopyAsUtf8() {
        List<String[]> commands = new ArrayList<>();
        List<byte[]> inputs = new ArrayList<>();
        OSC52Helper.platform = () -> "Mac OS X";
        OSC52Helper.environment = _ -> null;
        OSC52Helper.nativeCommandRunner = (command, input) -> {
            commands.add(command);
            inputs.add(input);
            return true;
        };

        OSC52Helper.copyNative("中文划词复制");

        assertEquals(1, commands.size());
        assertArrayEquals(new String[]{"pbcopy"}, commands.getFirst());
        assertArrayEquals("中文划词复制".getBytes(StandardCharsets.UTF_8), inputs.getFirst());
    }

    @Test
    void sshSessionDoesNotWriteRemoteNativeClipboard() {
        List<String[]> commands = new ArrayList<>();
        OSC52Helper.platform = () -> "Mac OS X";
        Map<String, String> env = Map.of("SSH_CONNECTION", "client server");
        OSC52Helper.environment = env::get;
        OSC52Helper.nativeCommandRunner = (command, _) -> {
            commands.add(command);
            return true;
        };

        OSC52Helper.copyNative("中文");

        assertEquals(0, commands.size());
    }

    @Test
    void windowsCopyUsesUnicodeClipboardInsteadOfClip() {
        List<String> copied = new ArrayList<>();
        List<String[]> commands = new ArrayList<>();
        OSC52Helper.platform = () -> "Windows 11";
        OSC52Helper.environment = _ -> null;
        OSC52Helper.windowsClipboard = text -> {
            copied.add(text);
            return true;
        };
        OSC52Helper.nativeCommandRunner = (command, _) -> {
            commands.add(command);
            return true;
        };

        OSC52Helper.copyNative("中文划词复制");

        assertEquals(List.of("中文划词复制"), copied);
        assertEquals(0, commands.size());
        assertArrayEquals(new byte[] {
            (byte) 0x2d, (byte) 0x4e, (byte) 0x87, (byte) 0x65, 0, 0
        }, WindowsUnicodeClipboard.utf16("中文"));
    }
}
