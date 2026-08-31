package com.claudecode.runtime.query;

/** Creates isolated query sessions from immutable assembly specifications. */
@FunctionalInterface
public interface QuerySessionFactory {
    QuerySession create(QuerySessionSpec spec);
}
