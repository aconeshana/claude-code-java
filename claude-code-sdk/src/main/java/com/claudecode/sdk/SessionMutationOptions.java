package com.claudecode.sdk;

/** Storage selection shared by SDK session mutations. */
public record SessionMutationOptions(String dir, SessionStore sessionStore) {}
