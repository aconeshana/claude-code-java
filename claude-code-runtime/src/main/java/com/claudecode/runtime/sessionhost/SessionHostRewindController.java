package com.claudecode.runtime.sessionhost;

import com.claudecode.core.annotation.Explanation;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import java.util.Locale;

/**
 * Application-facing rewind port exposed uniformly by both Web headless sessions
 * and live TUI sessions (over SessionLink).
 */
@Explanation("Unified rewind port shared by headless web sessions and remote TUI workers")
public interface SessionHostRewindController {

    enum RewindMode {
        BOTH("both"),
        CONVERSATION_ONLY("conversation_only"),
        CODE_ONLY("code_only");

        private final String wire;

        RewindMode(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static RewindMode parse(String value) {
            if (StringUtils.isBlank(value)) {
                throw new IllegalArgumentException("rewind mode is required");
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (RewindMode mode : values()) {
                if (mode.wire.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("unsupported rewind mode: " + value);
        }
    }

    /** Per-file rollback impact preview for the WebUI confirmation modal. */
    record FileDiff(
            String path,
            int insertions,
            int deletions,
            boolean willDelete,
            String unifiedDiff) {
        public FileDiff {
            path = path != null ? path : "";
            unifiedDiff = unifiedDiff != null ? unifiedDiff : "";
        }
    }

    /** Dry-run rewind preview showing both conversation and code impact. */
    record Preview(
            boolean canRewindConversation,
            boolean canRewindCode,
            String codeUnavailableReason,
            int turnsToDrop,
            int messagesToDrop,
            List<FileDiff> files,
            int insertions,
            int deletions) {
        public Preview {
            files = files != null ? List.copyOf(files) : List.of();
        }
    }

    /** Outcome returned after executing a rewind operation. */
    record Result(
            boolean rewoundConversation,
            boolean rewoundCode,
            List<String> restoredFiles,
            int retainedMessageCount) {
        public Result {
            restoredFiles = restoredFiles != null ? List.copyOf(restoredFiles) : List.of();
        }
    }

    Preview preview(String messageId);

    Result execute(String messageId, RewindMode mode);

    static SessionHostRewindController unsupported() {
        return new SessionHostRewindController() {
            @Override public Preview preview(String messageId) {
                throw new UnsupportedOperationException("rewind is not supported for this session");
            }

            @Override public Result execute(String messageId, RewindMode mode) {
                throw new UnsupportedOperationException("rewind is not supported for this session");
            }
        };
    }
}
