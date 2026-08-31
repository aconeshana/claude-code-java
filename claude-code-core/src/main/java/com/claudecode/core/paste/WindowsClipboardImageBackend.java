package com.claudecode.core.paste;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Windows STA PowerShell clipboard image backend.
 */
final class WindowsClipboardImageBackend implements ClipboardImageBackend {

    private static final String OUTPUT_PATH_ENV = "CLAUDE_CODE_CLIPBOARD_IMAGE_PATH";
    private static final String SCRIPT = "$path = $env:" + OUTPUT_PATH_ENV + "; "
        + "if ([string]::IsNullOrEmpty($path)) { exit 2 }; "
        + "Add-Type -AssemblyName System.Windows.Forms; "
        + "if (-not [System.Windows.Forms.Clipboard]::ContainsImage()) { exit 1 }; "
        + "$img = [System.Windows.Forms.Clipboard]::GetImage(); "
        + "if ($null -eq $img) { exit 1 }; "
        + "try { $img.Save($path, [System.Drawing.Imaging.ImageFormat]::Png) } "
        + "finally { $img.Dispose() }";

    @Override
    public String name() {
        return "windows-powershell-sta";
    }

    @Override
    public ClipboardReadResult read() {
        Path output = ImagePaste.createScreenshotPath();
        return ClipboardImageProcesses.runCommandWritingFile(
            saveCommand(output), output, environment(output));
    }

    static List<String> saveCommand(Path output) {
        return List.of(
            "powershell", "-NoProfile", "-NonInteractive", "-Sta", "-Command", SCRIPT);
    }

    static Map<String, String> environment(Path output) {
        return Map.of(OUTPUT_PATH_ENV, output.toString());
    }
}
