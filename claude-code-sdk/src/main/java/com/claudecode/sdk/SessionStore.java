package com.claudecode.sdk;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * External transcript persistence boundary used by SDK resume and matching.
{@code SessionStore}.</li></ul>
 */
public interface SessionStore {
    List<JsonNode> load(SessionStoreKey key) throws Exception;
    void append(SessionStoreKey key, List<JsonNode> entries) throws Exception;
    default List<StoredSession> listSessions(String projectKey) throws Exception {
        throw new UnsupportedOperationException(
            "SessionStore.listSessions is required when continueConversation is enabled");
    }
    default List<String> listSubkeys(SessionStoreKey key) throws Exception { return List.of(); }
}
