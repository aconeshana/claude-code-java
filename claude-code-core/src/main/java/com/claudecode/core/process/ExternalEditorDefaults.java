package com.claudecode.core.process;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.platform.Platform;

/** Platform-safe fallback used when neither {@code VISUAL} nor {@code EDITOR} is configured. */
public final class ExternalEditorDefaults {

    private ExternalEditorDefaults() {}

    @Explanation("Windows installations do not normally provide vi; use the native text editor")
    public static String defaultCommand() {
        return commandFor(Platform.IS_WINDOWS);
    }

    static String commandFor(boolean windows) {
        return windows ? "notepad.exe" : "vi";
    }
}
