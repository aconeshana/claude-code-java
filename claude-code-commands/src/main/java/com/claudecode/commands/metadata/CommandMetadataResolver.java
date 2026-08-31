package com.claudecode.commands.metadata;

import org.apache.commons.lang3.StringUtils;
import java.util.List;

/**
 * Resolves and caches class-level slash-command metadata.
 */
public final class CommandMetadataResolver {

    private static final ClassValue<CommandMetadata> CACHE = new ClassValue<>() {
        @Override
        protected CommandMetadata computeValue(Class<?> type) {
            SlashCommand annotation = type.getAnnotation(SlashCommand.class);
            if (annotation == null) {
                throw new IllegalStateException(
                    type.getName() + " implements AnnotatedCommand but does not declare @SlashCommand"
                );
            }
            if (StringUtils.isBlank(annotation.description())) {
                throw new IllegalStateException(
                    type.getName() + " declares a blank @SlashCommand description"
                );
            }
            return new CommandMetadata(
                annotation.name(),
                annotation.description(),
                List.of(annotation.aliases())
            );
        }
    };

    private CommandMetadataResolver() {}

    public static CommandMetadata resolve(Class<?> type) {
        return CACHE.get(type);
    }
}
