package com.claudecode.session;

import java.nio.file.Path;

/**
 * A session plus the transcript file it was actually found at. The path cannot be
 * re-derived from the session's content {@code cwd}: relocated sessions keep their
 * file in the original sanitized directory, so {@code sessionFile(cwd, id)}-style
 * reconstruction may point at a file that does not exist. Resume flows must use
 * this physical path (197 gh-30217 same root cause).
 */
public record ProjectSessionRef(SessionInfo info, Path transcriptPath) {}
