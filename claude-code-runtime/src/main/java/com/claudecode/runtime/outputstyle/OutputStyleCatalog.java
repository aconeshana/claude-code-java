package com.claudecode.runtime.outputstyle;

import com.claudecode.core.prompt.OutputStyleConfig;
import com.claudecode.core.prompt.OutputStylePresets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Presentation-safe catalogue of output styles available for a working directory.
 */
@FunctionalInterface
public interface OutputStyleCatalog {

    record Entry(String value, String label, String description) {}

    List<Entry> list(Path cwd);

    /** Built-in-only fallback used by isolated UI tests and headless wirings. */
    static OutputStyleCatalog builtIns() {
        return _ -> {
            List<Entry> entries = new ArrayList<>();
            OutputStylePresets.BUILT_IN.forEach((value, config) -> entries.add(toEntry(value, config)));
            return List.copyOf(entries);
        };
    }

    private static Entry toEntry(String value, OutputStyleConfig config) {
        return config == null
            ? new Entry(value, "Default",
                "Claude completes coding tasks efficiently and provides concise responses")
            : new Entry(value, config.name(), config.description());
    }
}
