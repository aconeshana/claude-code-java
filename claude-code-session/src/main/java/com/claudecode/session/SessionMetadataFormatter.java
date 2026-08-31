package com.claudecode.session;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.text.FormatUtils;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Session-list metadata display formatter.
 *
 * <ul>
 *   <li>relative time, branch,
 *       file size/message count, tag, and optional cross-project path.</li>
 * </ul>
 */
public final class SessionMetadataFormatter {

    private SessionMetadataFormatter() {}

    /** Formats the metadata row used by session pickers and session suggestions. */
    public static String format(SessionInfo session, long fileSize, boolean includeProjectPath) {
        List<String> parts = new ArrayList<>();
        Instant modified = session.lastModified() > 0
            ? Instant.ofEpochMilli(session.lastModified()) : session.createdAt();
        parts.add(FormatUtils.formatRelativeTimeAgo(modified, FormatUtils.RelativeTimeStyle.SHORT));
        if (StringUtils.isNotBlank(session.gitBranch())) {
            parts.add(session.gitBranch());
        }
        parts.add(fileSize >= 0 ? FormatUtils.formatFileSize(fileSize)
            : session.messageCount() + " messages");
        if (StringUtils.isNotBlank(session.tag())) {
            parts.add("#" + session.tag());
        }
        if (includeProjectPath && session.cwd() != null && !StringUtils.isBlank(session.cwd())) {
            parts.add(session.cwd());
        }
        return String.join(" · ", parts);
    }
}
