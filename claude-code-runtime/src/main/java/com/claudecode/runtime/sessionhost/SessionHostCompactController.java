package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Runs manual conversation compaction against one application-owned session.
 */
@Explanation("Session-scoped compact operation exposed to semantic endpoints")
public interface SessionHostCompactController {

    CompletionStage<SessionHostCompactResult> compact(String instructions);

    static SessionHostCompactController unsupported() {
        return _ -> CompletableFuture.failedFuture(
            new UnsupportedOperationException("compaction is not supported"));
    }
}
