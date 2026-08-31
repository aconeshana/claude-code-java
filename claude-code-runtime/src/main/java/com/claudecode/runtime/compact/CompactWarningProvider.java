package com.claudecode.runtime.compact;

import com.claudecode.core.message.Message;

import java.util.List;
import java.util.Optional;

/**
 * Presentation-safe view of the compact subsystem's token warning state.
 */
@FunctionalInterface
public interface CompactWarningProvider {

    record Warning(long percentLeft) {}

    Optional<Warning> warning(List<Message> messages, String model);

    static CompactWarningProvider none() {
        return (_, _) -> Optional.empty();
    }
}
