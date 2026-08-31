package com.claudecode.core.paste;

import java.nio.file.Path;
import java.util.List;

/**
 * macOS AppleScript clipboard image fallback.
 */
final class AppleScriptClipboardImageBackend implements ClipboardImageBackend {

    @Override
    public String name() {
        return "macos-applescript";
    }

    @Override
    public ClipboardReadResult read() {
        Path output = ImagePaste.createScreenshotPath();
        return ClipboardImageProcesses.runCommandWritingFile(saveCommand(output), output);
    }

    static List<String> saveCommand(Path output) {
        return List.of(
            "osascript",
            "-e", "on run argv",
            "-e", "set png_data to (the clipboard as «class PNGf»)",
            "-e", "set fp to open for access POSIX file (item 1 of argv) with write permission",
            "-e", "try",
            "-e", "write png_data to fp",
            "-e", "on error errText number errNumber",
            "-e", "close access fp",
            "-e", "error errText number errNumber",
            "-e", "end try",
            "-e", "close access fp",
            "-e", "end run",
            output.toString());
    }
}
