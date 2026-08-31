package com.claudecode.ui.lanterna.theme;

import java.util.Locale;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminal dark/light mode detection for the {@code 'auto'} theme setting.
 */
public final class SystemTheme {

    public enum Mode { DARK, LIGHT }

    private static volatile Mode cachedSystemTheme;

    private SystemTheme() {}

    /**
     * Returns the current terminal theme, cached after first detection.
     * The {@link SystemThemeWatcher} refreshes the cache on a schedule.
     */
    public static Mode getSystemTheme() {
        Mode cached = cachedSystemTheme;
        if (cached != null) return cached;
        synchronized (SystemTheme.class) {
            if (cachedSystemTheme == null) {
                Mode detected = detectFromColorFgBg();
                if (detected == null) detected = detectFromPlatform();
                cachedSystemTheme = detected != null ? detected : Mode.DARK;
            }
            return cachedSystemTheme;
        }
    }

    /**
     * Force-update the cached theme. Called by {@link SystemThemeWatcher}
     * and by external callers (e.g. an explicit {@code /theme refresh}).
     */
    public static void setCachedSystemTheme(Mode mode) {
        cachedSystemTheme = mode;
    }

    /** Reset for tests. */
    static void resetCache() {
        cachedSystemTheme = null;
    }

    /**
     * Read $COLORFGBG for a synchronous best-effort guess. rxvt convention:
     * bg 0–6 or 8 are dark; bg 7 and 9–15 are light. Returns null if the
     * env var is missing/malformed.
     */
    static Mode detectFromColorFgBg() {
        String colorfgbg = System.getenv("COLORFGBG");
        if (StringUtils.isBlank(colorfgbg)) return null;
        String[] parts = colorfgbg.split(";");
        if (parts.length == 0) return null;
        String bg = parts[parts.length - 1].trim();
        if (bg.isEmpty()) return null;
        try {
            int bgNum = Integer.parseInt(bg);
            if (bgNum < 0 || bgNum > 15) return null;
            return (bgNum <= 6 || bgNum == 8) ? Mode.DARK : Mode.LIGHT;
        } catch (NumberFormatException _) {
            return null;
        }
    }

    /**
     * Platform-native query — fallback when $COLORFGBG isn't set.
     * Bounded to 500ms per call so we don't block the UI thread.
     */
    static Mode detectFromPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (Strings.CS.contains(os, "mac")) {
                String out = runCommand(new String[]{"defaults", "read", "-g", "AppleInterfaceStyle"});
                return (out != null && Strings.CI.equals(out.trim(), "Dark")) ? Mode.DARK : Mode.LIGHT;
            }
            if (Strings.CS.contains(os, "linux")) {
                // GNOME 42+
                String out = runCommand(new String[]{"gsettings", "get", "org.gnome.desktop.interface", "color-scheme"});
                if (out != null) {
                    if (Strings.CS.contains(out, "dark")) return Mode.DARK;
                    if (Strings.CS.contains(out, "default") || Strings.CS.contains(out, "light")) return Mode.LIGHT;
                }
                // Legacy GNOME theme name fallback
                out = runCommand(new String[]{"gsettings", "get", "org.gnome.desktop.interface", "gtk-theme"});
                if (out != null && Strings.CI.contains(out, "dark")) return Mode.DARK;
                return null;
            }
            if (Strings.CS.contains(os, "win")) {
                String out = runCommand(new String[]{
                    "reg", "query",
                    "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                    "/v", "AppsUseLightTheme"});
                if (out != null) {
                    Matcher m = Pattern.compile("AppsUseLightTheme\\s+REG_DWORD\\s+0x([0-9a-f]+)",
                        Pattern.CASE_INSENSITIVE).matcher(out);
                    if (m.find()) {
                        return Strings.CS.equals("0", m.group(1)) ? Mode.DARK : Mode.LIGHT;
                    }
                }
                return null;
            }
        } catch (Exception _) {
            return null;
        }
        return null;
    }


    public static Mode themeFromOscColor(String data) {
        if (StringUtils.isBlank(data)) return null;
        Matcher rgbMatch = Pattern.compile(
            "^rgba?:([0-9a-f]{1,4})/([0-9a-f]{1,4})/([0-9a-f]{1,4})",
            Pattern.CASE_INSENSITIVE).matcher(data.trim());
        double r, g, b;
        if (rgbMatch.find()) {
            r = hexComponent(rgbMatch.group(1));
            g = hexComponent(rgbMatch.group(2));
            b = hexComponent(rgbMatch.group(3));
        } else {
            Matcher hashMatch = Pattern.compile("^#([0-9a-f]+)$", Pattern.CASE_INSENSITIVE)
                .matcher(data.trim());
            if (!hashMatch.find() || hashMatch.group(1).length() % 3 != 0) return null;
            String hex = hashMatch.group(1);
            int n = hex.length() / 3;
            r = hexComponent(hex.substring(0, n));
            g = hexComponent(hex.substring(n, 2 * n));
            b = hexComponent(hex.substring(2 * n));
        }
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        return luminance > 0.5 ? Mode.LIGHT : Mode.DARK;
    }

    private static double hexComponent(String hex) {
        long max = (1L << (4 * hex.length())) - 1L;
        return Long.parseLong(hex, 16) / (double) max;
    }

    private static String runCommand(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            try { p.getOutputStream().close(); } catch (Exception _) {}
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(500, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) return null;
            return out.toString();
        } catch (Exception _) {
            return null;
        }
    }
}
