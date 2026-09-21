package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.Objects;

/**
 * One selectable permission-mode row: the SDK external wire name plus the
 * display metadata the TUI's own permission-mode chip renders
 * ({@code PermissionMode.Config}). {@code available} is false only for
 * {@code bypassPermissions} when the process was not started with
 * {@code --dangerously-skip-permissions} (or policy disabled it) — every
 * other mode is always available.
 */
@Explanation("Session-scoped permission-mode option for semantic remote endpoints")
public record SessionHostPermissionMode(
        String value,
        String title,
        String shortTitle,
        String symbol,
        String colorKey,
        boolean available) {

    public SessionHostPermissionMode {
        value = Objects.requireNonNull(value, "value").trim();
        title = title == null ? "" : title.trim();
        shortTitle = shortTitle == null ? "" : shortTitle.trim();
        symbol = symbol == null ? "" : symbol.trim();
        colorKey = colorKey == null ? "" : colorKey.trim();
    }
}
