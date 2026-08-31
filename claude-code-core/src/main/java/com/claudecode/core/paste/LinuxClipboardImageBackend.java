package com.claudecode.core.paste;

import java.nio.file.Path;
import java.util.List;

/**
 * Linux xclip/wl-paste clipboard image backend.
 */
final class LinuxClipboardImageBackend implements ClipboardImageBackend {

    @Override
    public String name() {
        return "linux-xclip-wl-paste";
    }

    @Override
    public ClipboardReadResult read() {
        ClipboardReadResult unavailable = ClipboardReadResult.Unavailable.transientFailure(null);
        boolean commandRan = false;
        for (List<String> command : readCommands(ImagePaste.createScreenshotPath())) {
            Path output = ImagePaste.createScreenshotPath();
            ClipboardReadResult result = ClipboardImageProcesses.readCommandToFile(command, output);
            if (result instanceof ClipboardReadResult.Image) return result;
            if (result instanceof ClipboardReadResult.Empty) commandRan = true;
            if (result instanceof ClipboardReadResult.Unavailable failure) unavailable = failure;
        }
        return commandRan ? new ClipboardReadResult.Empty() : unavailable;
    }

    static List<List<String>> readCommands(Path ignoredOutput) {
        return List.of(
            List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-o"),
            List.of("wl-paste", "--type", "image/png"),
            List.of("xclip", "-selection", "clipboard", "-t", "image/bmp", "-o"),
            List.of("wl-paste", "--type", "image/bmp"));
    }
}
