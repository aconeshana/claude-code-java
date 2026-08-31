package com.claudecode.sdk;

/** Options for reading one stored session's metadata. */
public record GetSessionInfoOptions(String dir, SessionStore sessionStore) {}
