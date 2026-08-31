package com.claudecode.sdk;

/** Authenticated account metadata. All fields are optional for non-first-party providers. */
public record AccountInfo(String email, String organization, String subscriptionType,
                          String tokenSource, String apiKeySource, String apiProvider) {}
