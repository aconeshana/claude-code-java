package com.claudecode.tools;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.permissions.PermissionDecision;
import com.claudecode.permissions.ToolPermissionContext;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Test-only builder for creating {@link Tool} fixtures from lambdas, so tests (e.g.
 */
public class ToolBuilder<I, O> {

    private String name;
    private String description;
    private JsonNode inputSchema;
    private BiFunction<I, ToolExecutionContext, O> callFn;
    private BiFunction<JsonNode, ToolPermissionContext, PermissionDecision> permissionFn;
    private boolean concurrencySafe = false;
    private boolean readOnly = false;
    private boolean shouldDefer = false;
    private boolean requiresUserInteraction = false;
    private Function<JsonNode, Object> autoClassifierProjection;

    public ToolBuilder<I, O> name(String name) {
        this.name = name;
        return this;
    }

    public ToolBuilder<I, O> description(String description) {
        this.description = description;
        return this;
    }

    public ToolBuilder<I, O> inputSchema(JsonNode inputSchema) {
        this.inputSchema = inputSchema;
        return this;
    }

    public ToolBuilder<I, O> call(BiFunction<I, ToolExecutionContext, O> callFn) {
        this.callFn = callFn;
        return this;
    }

    public ToolBuilder<I, O> permissions(BiFunction<JsonNode, ToolPermissionContext, PermissionDecision> permissionFn) {
        this.permissionFn = permissionFn;
        return this;
    }

    public ToolBuilder<I, O> concurrencySafe(boolean concurrencySafe) {
        this.concurrencySafe = concurrencySafe;
        return this;
    }

    public ToolBuilder<I, O> readOnly(boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    public ToolBuilder<I, O> shouldDefer(boolean shouldDefer) {
        this.shouldDefer = shouldDefer;
        return this;
    }

    public ToolBuilder<I, O> requiresUserInteraction(boolean requiresUserInteraction) {
        this.requiresUserInteraction = requiresUserInteraction;
        return this;
    }

    public ToolBuilder<I, O> autoClassifierProjection(Function<JsonNode, Object> projection) {
        this.autoClassifierProjection = projection;
        return this;
    }

    public Tool<I, O> build() {
        if (name == null) throw new IllegalStateException("Tool name is required");
        if (callFn == null) throw new IllegalStateException("Tool call function is required");

        return new BuiltTool<>(name, description, inputSchema, callFn,
                permissionFn, concurrencySafe, readOnly, shouldDefer, requiresUserInteraction,
                autoClassifierProjection);
    }

    /**
     * Internal Tool implementation backed by builder-provided functions.
     */
    private static final class BuiltTool<I, O> extends Tool<I, O> {
        private final ToolIdentity identity;
        private final String description;
        private final JsonNode inputSchema;
        private final BiFunction<I, ToolExecutionContext, O> callFn;
        private final BiFunction<JsonNode, ToolPermissionContext, PermissionDecision> permissionFn;
        private final boolean concurrencySafe;
        private final boolean readOnly;
        private final boolean shouldDefer;
        private final boolean requiresUserInteraction;
        private final Function<JsonNode, Object> autoClassifierProjection;

        BuiltTool(String name, String description, JsonNode inputSchema,
                  BiFunction<I, ToolExecutionContext, O> callFn,
                  BiFunction<JsonNode, ToolPermissionContext, PermissionDecision> permissionFn,
                  boolean concurrencySafe, boolean readOnly, boolean shouldDefer,
                  boolean requiresUserInteraction,
                  Function<JsonNode, Object> autoClassifierProjection) {
            this.identity = new ToolIdentity(name);
            this.description = description;
            this.inputSchema = inputSchema;
            this.callFn = callFn;
            this.permissionFn = permissionFn;
            this.concurrencySafe = concurrencySafe;
            this.readOnly = readOnly;
            this.shouldDefer = shouldDefer;
            this.requiresUserInteraction = requiresUserInteraction;
            this.autoClassifierProjection = autoClassifierProjection;
        }

        @Override
        public ToolIdentity identity() { return identity; }

        @Override
        public String description() { return description != null ? description : ""; }

        @Override
        public JsonNode inputSchema() { return inputSchema; }

        @Override
        public O call(I input, ToolExecutionContext context) {
            return callFn.apply(input, context);
        }

        @Override
        public PermissionDecision checkPermissions(JsonNode input, ToolPermissionContext permCtx) {
            if (permissionFn != null) return permissionFn.apply(input, permCtx);
            return super.checkPermissions(input, permCtx);
        }

        @Override
        public Object toAutoClassifierInput(JsonNode input) {
            return autoClassifierProjection != null
                ? autoClassifierProjection.apply(input)
                : super.toAutoClassifierInput(input);
        }

        @Override
        public boolean isConcurrencySafe() { return concurrencySafe; }

        @Override
        public boolean isReadOnly() { return readOnly; }

        @Override
        public boolean shouldDefer() { return shouldDefer; }

        @Override
        public boolean requiresUserInteraction() { return requiresUserInteraction; }
    }
}
