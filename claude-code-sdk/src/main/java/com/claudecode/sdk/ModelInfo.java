package com.claudecode.sdk;

import java.util.List;
import java.util.Objects;

/** Model metadata exposed by the Agent SDK. */
public record ModelInfo(String value, String displayName, String description,
                        Boolean supportsEffort, List<String> supportedEffortLevels,
                        Boolean supportsAdaptiveThinking, Boolean supportsFastMode,
                        Boolean supportsAutoMode) {
    public ModelInfo {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        supportedEffortLevels = supportedEffortLevels == null
            ? List.of() : List.copyOf(supportedEffortLevels);
    }
}
