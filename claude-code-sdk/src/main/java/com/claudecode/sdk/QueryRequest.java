package com.claudecode.sdk;

/**
 * Union-like request wrapper for string or structured streaming prompts.
overloaded {@code query} parameter.</li></ul>
 */
public record QueryRequest(String prompt, Iterable<SDKUserMessage> messages,
                           QueryOptions options) {
    public QueryRequest {
        if (prompt != null && messages != null) {
            throw new IllegalArgumentException("Specify prompt or messages, not both");
        }
    }

    public static QueryRequest prompt(String prompt, QueryOptions options) {
        return new QueryRequest(prompt, null, options);
    }

    public static QueryRequest stream(Iterable<SDKUserMessage> messages, QueryOptions options) {
        return new QueryRequest(null, messages, options);
    }
}
