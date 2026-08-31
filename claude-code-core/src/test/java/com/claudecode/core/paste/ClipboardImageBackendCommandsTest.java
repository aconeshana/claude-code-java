package com.claudecode.core.paste;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Platform clipboard backend command contract tests.
 */
class ClipboardImageBackendCommandsTest {

    @TempDir
    Path tempDir;

    @Test
    void windowsUsesStaFormsClipboardAndEnvironmentPathWithoutShellInterpolation() {
        Path path = Path.of("C:\\Temp\\a'; Remove-Item C:\\important; '.png");

        List<String> command = WindowsClipboardImageBackend.saveCommand(path);

        assertEquals(List.of("powershell", "-NoProfile", "-NonInteractive", "-Sta", "-Command"),
            command.subList(0, 5));
        String script = command.get(5);
        assertTrue(script.contains("[System.Windows.Forms.Clipboard]::ContainsImage()"));
        assertTrue(script.contains("[System.Windows.Forms.Clipboard]::GetImage()"));
        assertTrue(script.contains("[System.Drawing.Imaging.ImageFormat]::Png"));
        assertTrue(script.contains("$env:CLAUDE_CODE_CLIPBOARD_IMAGE_PATH"));
        assertFalse(script.contains(path.toString()));
        assertEquals(6, command.size());
        assertEquals(path.toString(), WindowsClipboardImageBackend.environment(path)
            .get("CLAUDE_CODE_CLIPBOARD_IMAGE_PATH"));
    }

    @Test
    void appleScriptReceivesPathAsArgumentInsteadOfEmbeddingItInSource() {
        Path path = Path.of("/tmp/a\"; do shell script \"touch /tmp/nope\"");

        List<String> command = AppleScriptClipboardImageBackend.saveCommand(path);

        assertEquals("osascript", command.getFirst());
        assertTrue(command.contains("on run argv"));
        assertFalse(command.stream().anyMatch(part -> part.contains(path.toString()) && !part.equals(path.toString())));
        assertEquals(path.toString(), command.getLast());
    }

    @Test
    void linuxBackendsUseArgumentizedCommandsWithoutShell() {
        Path path = Path.of("/tmp/a;touch nope.png");

        List<List<String>> commands = LinuxClipboardImageBackend.readCommands(path);

        assertEquals(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-o"),
            commands.getFirst());
        assertEquals(List.of("wl-paste", "--type", "image/png"), commands.get(1));
        assertFalse(commands.stream().flatMap(List::stream).anyMatch("sh"::equals));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void bmpClipboardOutputIsConvertedToPngBeforeReturning() throws Exception {
        Path bmp = tempDir.resolve("clipboard.bmp");
        Path output = tempDir.resolve("clipboard-output.bin");
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, 0x00ff_0000);
        assertTrue(ImageIO.write(source, "bmp", bmp.toFile()));

        ClipboardReadResult result = ClipboardImageProcesses.readCommandToFile(
            List.of("sh", "-c", "cat \"$1\"", "sh", bmp.toString()), output);
        ImagePaste.ImageWithDimensions image = ((ClipboardReadResult.Image) result).image();
        byte[] bytes = Base64.getDecoder().decode(image.base64());

        assertEquals("image/png", image.mediaType());
        assertTrue(bytes.length >= 8 && bytes[0] == (byte) 0x89
            && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G');
        assertEquals(3, ImageIO.read(new ByteArrayInputStream(bytes)).getWidth());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void successfulCommandStderrDoesNotPolluteImageBytes() throws Exception {
        Path png = tempDir.resolve("clipboard.png");
        Path output = tempDir.resolve("clipboard-output.bin");
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", encoded));
        Files.write(png, encoded.toByteArray());

        ClipboardReadResult result = ClipboardImageProcesses.readCommandToFile(
            List.of("sh", "-c", "printf warning >&2; cat \"$1\"", "sh", png.toString()), output);
        byte[] bytes = Base64.getDecoder().decode(
            ((ClipboardReadResult.Image) result).image().base64());

        assertTrue(bytes.length >= 8 && bytes[0] == (byte) 0x89
            && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G');
    }
}
