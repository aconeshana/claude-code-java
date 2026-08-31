package com.claudecode.tools;

import java.util.List;

/**
 * Resolves and caches class-level definitions for statically declared tools.
 */
final class BuiltInToolResolver {

    private static final ClassValue<ResolvedDefinition> CACHE = new ClassValue<>() {
        @Override
        protected ResolvedDefinition computeValue(Class<?> type) {
            BuiltInTool annotation = type.getAnnotation(BuiltInTool.class);
            if (annotation == null) {
                throw new IllegalStateException(
                    type.getName() + " extends AnnotatedTool but does not declare @BuiltInTool"
                );
            }
            return new ResolvedDefinition(
                annotation,
                new ToolIdentity(annotation.name(), List.of(annotation.aliases()))
            );
        }
    };

    private BuiltInToolResolver() {}

    static ToolIdentity identity(Class<?> type) {
        return CACHE.get(type).identity();
    }

    static BuiltInTool metadata(Class<?> type) {
        return CACHE.get(type).metadata();
    }

    private record ResolvedDefinition(BuiltInTool metadata, ToolIdentity identity) {}
}
