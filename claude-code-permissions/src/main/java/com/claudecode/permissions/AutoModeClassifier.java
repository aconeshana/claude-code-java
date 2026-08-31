package com.claudecode.permissions;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Port-neutral decision seam for Claude Code's auto-mode transcript classifier.
 *
 * <ul>
 *   <li> —
 *       classifies the compact user/tool transcript and the pending action.</li>
 *   <li>folds the
 *       classifier's allow/block/unavailable result into the final permission
 *       decision for non-fast-path tools in {@code auto} mode.</li>
 * </ul>
 *
 * <p>The permissions/tools modules own only this data contract. The API-backed
 * implementation lives in services and is injected by the CLI composition root,
 * preserving the module dependency direction.</p>
 */
@FunctionalInterface
public interface AutoModeClassifier {

    Decision classify(Request request);

    record Request(
        String model,
        String sessionId,
        String workingDirectory,
        String toolName,
        String toolUseId,
        JsonNode toolInput,
        List<String> compactTranscriptBlocks
    ) {
        public Request {
            compactTranscriptBlocks = compactTranscriptBlocks == null
                ? List.of() : List.copyOf(compactTranscriptBlocks);
        }
    }

    record Decision(boolean shouldBlock, String reason, boolean unavailable) {
        public static Decision allow(String reason) {
            return new Decision(false, reason, false);
        }

        public static Decision block(String reason) {
            return new Decision(true, reason, false);
        }

        public static Decision unavailable(String reason) {
            return new Decision(true, reason, true);
        }
    }
}
