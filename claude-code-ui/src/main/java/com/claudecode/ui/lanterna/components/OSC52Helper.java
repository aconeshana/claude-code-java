package com.claudecode.ui.lanterna.components;

import com.claudecode.ui.lanterna.repl.TuiOutputGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;


public final class OSC52Helper {

    private static final String ESC = "";
    private static final String BEL = "";

    @FunctionalInterface
    interface Platform { String osName(); }

    @FunctionalInterface
    interface Environment { String get(String name); }

    @FunctionalInterface
    interface NativeCommandRunner { boolean run(String[] command, byte[] input); }

    static Platform platform = () -> System.getProperty("os.name", "");
    static Environment environment = System::getenv;
    static NativeCommandRunner nativeCommandRunner = OSC52Helper::runNativeCommand;

    private enum LinuxClipboardTool { WL_COPY, XCLIP, XSEL, NONE }

    private static volatile LinuxClipboardTool linuxClipboardTool;

    private OSC52Helper() {}

    /**
     * Copy {@code text} to the system clipboard via OSC 52. Returns
     * silently on null/empty input. Detects tmux via {@code TMUX} env
     * and wraps the sequence in DCS passthrough.
     *
     * <p>The terminal write is immediate. The optional native clipboard
     * process runs on a virtual thread, so this method does not block the GUI.
     */
    public static void copyToClipboard(String text) {
        if (StringUtils.isEmpty(text)) return;
// setClipboard fires the native write before the OSC/tmux
        // path. Keep it asynchronous so mouse-up never stalls the GUI, while
        // preserving the original UTF-8 bytes for pbcopy/wl-copy/xclip/xsel.
        Thread.startVirtualThread(() -> copyNative(text));
        String b64 = Base64.getEncoder()
            .encodeToString(text.getBytes(StandardCharsets.UTF_8));
        String inner = ESC + "]52;c;" + b64 + BEL;
        String wrapped = wrapForMultiplexer(inner);
        // Use the terminal-control port rather than process stdout so ordinary
        // application code never bypasses the TUI output guard.
        TuiOutputGuard.writeToTerminal(wrapped);
    }


    static void copyNative(String text) {
        if (StringUtils.isEmpty(text)) return;
        if (environment.get("SSH_CONNECTION") != null) return;

        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        String os = platform.osName().toLowerCase(Locale.ROOT);
        if (Strings.CS.contains(os, "mac")) {
            nativeCommandRunner.run(new String[]{"pbcopy"}, utf8);
        } else if (Strings.CS.contains(os, "linux")) {
            copyLinux(utf8);
        } else if (Strings.CS.contains(os, "win")) {
            nativeCommandRunner.run(new String[]{"clip"}, utf8);
        }
    }

    private static void copyLinux(byte[] utf8) {
        LinuxClipboardTool cached = linuxClipboardTool;
        if (cached != null) {
            runLinuxTool(cached, utf8);
            return;
        }
        if (nativeCommandRunner.run(new String[]{"wl-copy"}, utf8)) {
            linuxClipboardTool = LinuxClipboardTool.WL_COPY;
        } else if (nativeCommandRunner.run(
                new String[]{"xclip", "-selection", "clipboard"}, utf8)) {
            linuxClipboardTool = LinuxClipboardTool.XCLIP;
        } else if (nativeCommandRunner.run(
                new String[]{"xsel", "--clipboard", "--input"}, utf8)) {
            linuxClipboardTool = LinuxClipboardTool.XSEL;
        } else {
            linuxClipboardTool = LinuxClipboardTool.NONE;
        }
    }

    private static void runLinuxTool(LinuxClipboardTool tool, byte[] utf8) {
        switch (tool) {
            case WL_COPY -> nativeCommandRunner.run(new String[]{"wl-copy"}, utf8);
            case XCLIP -> nativeCommandRunner.run(
                new String[]{"xclip", "-selection", "clipboard"}, utf8);
            case XSEL -> nativeCommandRunner.run(
                new String[]{"xsel", "--clipboard", "--input"}, utf8);
            case NONE -> { }
        }
    }

    private static boolean runNativeCommand(String[] command, byte[] input) {
        Process process = null;
        try {
            process = new ProcessBuilder(command).start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(input);
            }
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException _) {
            return false;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }

    static void resetNativeClipboardCache() {
        linuxClipboardTool = null;
    }


    public static String wrapForMultiplexer(String sequence) {
        String tmux = System.getenv("TMUX");
        if (StringUtils.isEmpty(tmux)) return sequence;
        // tmux DCS passthrough requires every embedded ESC to be doubled.
        // The inner sequence's ESCs become ESC ESC; the outer wrapper
        // adds ESC P tmux; ESC ... ESC \ around it.
        String escaped = sequence.replace(ESC, ESC + ESC);
        return ESC + "Ptmux;" + ESC + escaped + ESC + "\\";
    }
}
