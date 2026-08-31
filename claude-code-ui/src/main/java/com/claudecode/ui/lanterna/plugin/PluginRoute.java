package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import java.util.Arrays;
import java.util.Locale;

/**
 * Parsed {@code /plugin} subcommand route — how {@link PluginSettingsPanel} decides which view to
 * open on.
 */
public record PluginRoute(Type type, String plugin, String marketplace, String action, String path) {

    public enum Type { MENU, HELP, INSTALL, MANAGE, UNINSTALL, ENABLE, DISABLE, VALIDATE, MARKETPLACE }

    static PluginRoute menu() {
        return new PluginRoute(Type.MENU, null, null, null, null);
    }


    public static PluginRoute parse(String args) {
        if (StringUtils.isBlank(args)) {
            return menu();
        }
        String[] parts = args.trim().split("\\s+");
        String command = parts[0].toLowerCase(Locale.ROOT);

        return switch (command) {
            case "help", "--help", "-h" -> new PluginRoute(Type.HELP, null, null, null, null);
            case "install", "i" -> parseInstall(parts);
            case "manage" -> new PluginRoute(Type.MANAGE, null, null, null, null);
            case "uninstall" -> new PluginRoute(Type.UNINSTALL, arg(parts, 1), null, null, null);
            case "enable" -> new PluginRoute(Type.ENABLE, arg(parts, 1), null, null, null);
            case "disable" -> new PluginRoute(Type.DISABLE, arg(parts, 1), null, null, null);
            case "validate" -> {
                String target = joinFrom(parts, 1);
                yield new PluginRoute(Type.VALIDATE, null, null, null,
                    target.isEmpty() ? null : target);
            }
            case "marketplace", "market" -> parseMarketplace(parts);
            default -> menu();
        };
    }

    private static PluginRoute parseInstall(String[] parts) {
        String target = arg(parts, 1);
        if (target == null) {
            return new PluginRoute(Type.INSTALL, null, null, null, null);
        }
        if (Strings.CS.contains(target, "@")) {
            String[] split = target.split("@", 2);
            return new PluginRoute(Type.INSTALL, emptyToNull(split[0]),
                split.length > 1 ? emptyToNull(split[1]) : null, null, null);
        }
        boolean isMarketplace = Strings.CS.startsWith(target, "http://")
            || Strings.CS.startsWith(target, "https://")
            || Strings.CS.startsWith(target, "file://")
            || Strings.CS.contains(target, "/")
            || Strings.CS.contains(target, "\\");
        if (isMarketplace) {
            return new PluginRoute(Type.INSTALL, null, target, null, null);
        }
        return new PluginRoute(Type.INSTALL, target, null, null, null);
    }

    private static PluginRoute parseMarketplace(String[] parts) {
        String action = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : null;
        String target = joinFrom(parts, 2);
        return switch (action == null ? "" : action) {
            case "add" -> new PluginRoute(Type.MARKETPLACE, null, emptyToNull(target), "add", null);
            case "remove", "rm" ->
                new PluginRoute(Type.MARKETPLACE, null, emptyToNull(target), "remove", null);
            case "update" ->
                new PluginRoute(Type.MARKETPLACE, null, emptyToNull(target), "update", null);
            case "list" -> new PluginRoute(Type.MARKETPLACE, null, null, "list", null);
            default -> new PluginRoute(Type.MARKETPLACE, null, null, null, null);
        };
    }

    private static String arg(String[] parts, int index) {
        return parts.length > index ? parts[index] : null;
    }

    private static String joinFrom(String[] parts, int start) {
        if (parts.length <= start) {
            return "";
        }
        return String.join(" ", Arrays.copyOfRange(parts, start, parts.length)).trim();
    }

    private static String emptyToNull(String value) {
        return StringUtils.isEmpty(value) ? null : value;
    }
}
