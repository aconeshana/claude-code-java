package com.claudecode.runtime.query;

/** Standard runtime-owned query-session factory. */
public final class DefaultQuerySessionFactory implements QuerySessionFactory {
    @Override
    public QuerySession create(QuerySessionSpec spec) {
        return new DefaultQuerySession(spec, spec.messageCompactor());
    }
}
