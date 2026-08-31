package com.claudecode.sdk;

import java.time.Instant;

/** Agent SDK projection of stored-session metadata. */
public record SDKSessionInfo(String sessionId, String summary, long lastModified, Long fileSize,
                             String customTitle, String firstPrompt, String gitBranch, String cwd,
                             String tag, Instant createdAt) {}
