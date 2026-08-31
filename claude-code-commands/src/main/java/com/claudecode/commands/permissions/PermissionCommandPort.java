package com.claudecode.commands.permissions;

import java.nio.file.Path;
import java.util.List;

/**
 * Live permission capabilities consumed by command policies.
 */
public interface PermissionCommandPort {

    record Snapshot(boolean wired, String mode, List<String> allowRules,
                    List<String> askRules, List<String> denyRules,
                    List<Path> workingDirectories, List<Path> additionalDirectories) {
        public Snapshot {
            allowRules = List.copyOf(allowRules);
            askRules = List.copyOf(askRules);
            denyRules = List.copyOf(denyRules);
            workingDirectories = List.copyOf(workingDirectories);
            additionalDirectories = List.copyOf(additionalDirectories);
        }
    }

    Snapshot snapshot();

    boolean isPlanMode();

    void enterPlanMode();

    void addDirectory(Path directory);

    static PermissionCommandPort none() {
        return new PermissionCommandPort() {
            @Override
            public Snapshot snapshot() {
                return new Snapshot(false, "DEFAULT", List.of(), List.of(), List.of(),
                    List.of(), List.of());
            }

            @Override public boolean isPlanMode() { return false; }
            @Override public void enterPlanMode() { }
            @Override public void addDirectory(Path directory) {
                throw new IllegalStateException("Permission command port is not wired");
            }
        };
    }

    static String canonicalMode(String value) {
        if (value == null) return "default";
        return switch (value) {
            case "default", "plan", "acceptEdits", "bypassPermissions", "dontAsk", "auto" -> value;
            default -> "default";
        };
    }

    static String externalMode(String value) {
        String canonical = canonicalMode(value);
        return switch (canonical) {
            case "auto" -> "default";
            default -> canonical;
        };
    }
}
