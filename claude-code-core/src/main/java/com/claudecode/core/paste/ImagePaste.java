package com.claudecode.core.paste;

import com.claudecode.core.imagestore.ImageResizer;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;













public final class ImagePaste {

    private ImagePaste() {}

    private static final Platform PLATFORM = Platform.CURRENT;

    private static final String SCREENSHOT_PREFIX = "claude_cli_screenshot_";
    private static final Duration CLIPBOARD_PROCESS_TIMEOUT = Duration.ofSeconds(5);

/**
     * Regex for image file extensions.
     */
    public static final String IMAGE_EXTENSION_REGEX = "(?i)\\.(png|jpe?g|gif|webp)$";
    private static final Pattern IMAGE_EXT_PATTERN = Pattern.compile(IMAGE_EXTENSION_REGEX);
    private static final ClipboardImageService CLIPBOARD_IMAGES = clipboardImageService();

    /** Result of reading an image from the clipboard. */
    public record ImageWithDimensions(
        String base64,
        String mediaType,
        PastedContent.ImageDimensions dimensions
    ) {}

    /**
     * Read the clipboard as plain text.
     */
    public static String getClipboardText() {
        String text = switch (PLATFORM) {
            case DARWIN -> runAndCapture("pbpaste");
            case LINUX  -> runAndCapture("sh", "-c",
                "xclip -selection clipboard -o 2>/dev/null || wl-paste 2>/dev/null");
            case WIN32  -> runAndCapture(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", "Get-Clipboard");
            case OTHER  -> null;
        };
        return StringUtils.isEmpty(text) ? null : text;
    }

    // ── hasImageInClipboard ────────────────────────────────────────────────

    /**
     * Check if clipboard contains an image without retrieving it.
     * Returns {@code false} on unsupported platforms.
     */
    public static boolean hasImageInClipboard() {
        return switch (PLATFORM) {
            case DARWIN -> hasImageDarwin();
            case LINUX  -> hasImageLinux();
            case WIN32  -> hasImageWin32();
            case OTHER  -> false;
        };
    }

    private static boolean hasImageDarwin() {
        return run0("osascript", "-e", "the clipboard as «class PNGf»") == 0;
    }

    /** {@code xclip ... -t TARGETS -o | grep image/...} || {@code wl-paste -l | grep image/...}. */
    private static boolean hasImageLinux() {
        String out = runAndCapture("sh", "-c",
            "xclip -selection clipboard -t TARGETS -o 2>/dev/null | grep -E \"image/(png|jpeg|jpg|gif|webp|bmp)\""
            + " || wl-paste -l 2>/dev/null | grep -E \"image/(png|jpeg|jpg|gif|webp|bmp)\"");
        return StringUtils.isNotEmpty(out);
    }

    private static boolean hasImageWin32() {
        String out = runAndCapture(
            "powershell", "-NoProfile", "-NonInteractive", "-Sta", "-Command",
                "Add-Type -AssemblyName System.Windows.Forms; "
                + "[System.Windows.Forms.Clipboard]::ContainsImage()");
        return out != null && Strings.CI.equals(out.trim(), "true");
    }

    // ── getImageFromClipboard ──────────────────────────────────────────────

    /**
     * Read an image from the clipboard and return its base64-encoded bytes.
     */
    public static ImageWithDimensions getImageFromClipboard() {
        ClipboardReadResult result = CLIPBOARD_IMAGES.read();
        return result instanceof ClipboardReadResult.Image image ? image.image() : null;
    }

    private static ClipboardImageService clipboardImageService() {
        List<ClipboardImageBackend> backends = switch (PLATFORM) {
            case DARWIN -> List.of(
                new MacNativeClipboardImageBackend(),
                new AppleScriptClipboardImageBackend());
            case LINUX -> List.of(new LinuxClipboardImageBackend());
            case WIN32 -> List.of(new WindowsClipboardImageBackend());
            case OTHER -> List.of();
        };
        return new ClipboardImageService(backends);
    }



    /**
     * Read the clipboard as text and return it if it's a path — used to detect drag-dropped file paths.
     */
    public static String getImagePathFromClipboard() {
        String text = switch (PLATFORM) {
            case DARWIN -> runAndCapture("osascript", "-e",
                "get POSIX path of (the clipboard as «class furl»)");
            case LINUX  -> runAndCapture("sh", "-c",
                "xclip -selection clipboard -t text/plain -o 2>/dev/null || wl-paste 2>/dev/null");
            case WIN32  -> runAndCapture(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", "Get-Clipboard");
            case OTHER  -> null;
        };
        if (text == null) return null;
        text = text.trim();
        return text.isEmpty() ? null : text;
    }



    /**
     * Try to find and read an image file from a pasted text path.
     */
    public static ImageWithDimensions tryReadImageFromPath(String text) {
        String cleaned = asImageFilePath(text);
        if (cleaned == null) return null;
        try {
            byte[] bytes;
            if (Path.of(cleaned).isAbsolute()) {
                bytes = Files.readAllBytes(Path.of(cleaned));
            } else {
// VSCode Terminal pastes just the filename; check if clipboard holds a full path
// whose basename matches.
                String clipPath = getImagePathFromClipboard();
                if (clipPath == null) return null;
                Path clipP = Path.of(clipPath);
                if (!cleaned.equals(clipP.getFileName().toString())) return null;
                bytes = Files.readAllBytes(clipP);
            }
            if (bytes.length == 0) return null;
            String mediaType = detectSupportedMediaType(bytes);
            if (mediaType == null) return null;
            String ext = mediaType.substring("image/".length());
            ImageResizer.ResizeResult resized =
                ImageResizer.maybeResizeAndDownsample(bytes, ext);
            return fromResizeResult(resized);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Strip outer quotes + shell backslash escapes, then verify the result looks like an image file
     * path.
     */
    public static String asImageFilePath(String text) {
        if (text == null) return null;
        String cleaned = removeOuterQuotes(text.trim());
        String unescaped = stripBackslashEscapes(cleaned);
        return IMAGE_EXT_PATTERN.matcher(unescaped).find() ? unescaped : null;
    }

    /**
     * Check if a given text represents an image file path.
     */
    public static boolean isImageFilePath(String text) {
        return asImageFilePath(text) != null;
    }

/**
     * Remove outer single/double quotes.
     */
    static String removeOuterQuotes(String text) {
        if (text.length() >= 2 &&
            ((text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') ||
             (text.charAt(0) == '\'' && text.charAt(text.length() - 1) == '\''))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    /**
     * Remove shell escape backslashes on macOS/Linux/WSL.
     */
    static String stripBackslashEscapes(String path) {
        if (PLATFORM == Platform.WIN32) return path;
        // Replace \\ first with a placeholder, strip single-backslash escapes,
        // then restore placeholders to single backslashes.
        String salt = Integer.toHexString(path.hashCode());
        String placeholder = "__DBLBS_" + salt + "__";
        String withPlaceholder = path.replace("\\\\", placeholder);
        // Strip single-backslash escapes: \X → X
        StringBuilder sb = new StringBuilder(withPlaceholder.length());
        for (int i = 0; i < withPlaceholder.length(); i++) {
            char c = withPlaceholder.charAt(i);
            if (c == '\\' && i + 1 < withPlaceholder.length()) {
                sb.append(withPlaceholder.charAt(i + 1));
                i++;
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replace(placeholder, "\\");
    }

    // ── Shared helpers ─────────────────────────────────────────────────────

    /** Build an Anthropic API {@code image} content block source JsonNode. */
    public static JsonNode toImageSource(ImageWithDimensions img, ObjectMapper mapper) {
        ObjectNode src = mapper.createObjectNode();
        src.put("type", "base64");
        src.put("media_type", img.mediaType());
        src.put("data", img.base64());
        return src;
    }

/**
     * Detect image media type from magic bytes.
     */
    static String detectMediaType(byte[] bytes) {
        String detected = detectSupportedMediaType(bytes);
        return detected != null ? detected : "image/png";
    }

    private static String detectSupportedMediaType(byte[] bytes) {
        if (bytes.length >= 8 && bytes[0] == (byte) 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4E && bytes[3] == 0x47) return "image/png";
        if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8
                && bytes[2] == (byte) 0xFF) return "image/jpeg";
        if (bytes.length >= 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F') return "image/gif";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "image/webp";
        return null;
    }

    private static ImageWithDimensions fromResizeResult(ImageResizer.ResizeResult resized) {
        ImageResizer.PastedDims dims = resized.dimensions();
        PastedContent.ImageDimensions pastedDims = dims == null ? null
            : new PastedContent.ImageDimensions(
                dims.originalWidth(), dims.originalHeight(),
                dims.displayWidth(), dims.displayHeight());
        return new ImageWithDimensions(
            Base64.getEncoder().encodeToString(resized.buffer()),
            resized.mediaType(),
            pastedDims);
    }

    static String quoteAppleScriptString(String value) {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n") + "\"";
    }

    static String quoteShellArgument(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static Path createScreenshotPath() {
        String base = SubprocessEnvironment.get("CLAUDE_CODE_TMPDIR");
        if (StringUtils.isEmpty(base)) {
            base = switch (PLATFORM) {
                case WIN32 -> SubprocessEnvironment.get("TEMP");
                default -> "/tmp";
            };
            if (StringUtils.isEmpty(base)) base = "/tmp";
        }
        return Path.of(base, SCREENSHOT_PREFIX + UUID.randomUUID() + ".png");
    }

    /** Run a command, return exit code, and terminate clipboard helpers that hang. */
    private static int run0(String... cmd) {
        return runWithTimeout(CLIPBOARD_PROCESS_TIMEOUT, cmd);
    }

    static int runWithTimeout(Duration timeout, String... cmd) {
        try {
            Process process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
            terminate(process);
            return -1;
        } catch (Exception _) {
            return -1;
        }
    }

    /** Run a command and capture stdout as a UTF-8 string. Returns null on failure. */
    private static String runAndCapture(String... cmd) {
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try { process.getOutputStream().close(); } catch (IOException _) {}
            FutureTask<byte[]> output = new FutureTask<>(process.getInputStream()::readAllBytes);
            Thread.ofVirtual().name("clipboard-output").start(output);
            if (!process.waitFor(CLIPBOARD_PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                output.cancel(true);
                return null;
            }
            return new String(output.get(1, TimeUnit.SECONDS), UTF_8);
        } catch (Exception _) {
            return null;
        }
    }

    private static void terminate(Process process) throws InterruptedException {
        var descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        process.waitFor(200, TimeUnit.MILLISECONDS);
        descendants.stream()
            .filter(ProcessHandle::isAlive)
            .forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        process.waitFor(1, TimeUnit.SECONDS);
        try { process.getInputStream().close(); } catch (IOException _) {}
        try { process.getErrorStream().close(); } catch (IOException _) {}
        try { process.getOutputStream().close(); } catch (IOException _) {}
    }

}
