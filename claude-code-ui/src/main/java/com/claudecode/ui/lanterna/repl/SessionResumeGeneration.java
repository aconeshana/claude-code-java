package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.annotation.Explanation;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Latest-request-wins fence for asynchronous session preparation.
 */
@Explanation("Fences concurrent local and Session Host resume requests")
final class SessionResumeGeneration {
    private final AtomicLong value = new AtomicLong();

    long begin() {
        return value.incrementAndGet();
    }

    void invalidate() {
        value.incrementAndGet();
    }

    boolean isCurrent(long generation) {
        return value.get() == generation;
    }
}
