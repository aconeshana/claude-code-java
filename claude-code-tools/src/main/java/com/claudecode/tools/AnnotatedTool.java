package com.claudecode.tools;

/**
 * Tool whose immutable class-level definition is declared with {@link BuiltInTool}.
 */
public abstract class AnnotatedTool<I, O> extends Tool<I, O> {

    @Override
    public final ToolIdentity identity() {
        return BuiltInToolResolver.identity(getClass());
    }

    @Override
    public boolean shouldDefer() {
        return metadata().shouldDefer();
    }

    @Override
    public boolean strict() {
        return metadata().strict();
    }

    @Override
    public int maxResultSizeChars() {
        return metadata().maxResultSizeChars();
    }

    @Override
    public boolean isReadOnly() {
        return metadata().readOnly();
    }

    @Override
    public boolean isConcurrencySafe() {
        return metadata().concurrencySafe();
    }

    private BuiltInTool metadata() {
        return BuiltInToolResolver.metadata(getClass());
    }
}
