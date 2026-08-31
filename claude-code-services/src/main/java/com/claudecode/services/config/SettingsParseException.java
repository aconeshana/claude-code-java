package com.claudecode.services.config;

import java.nio.file.Path;











public final class SettingsParseException extends RuntimeException {

    private final Path path;

    public SettingsParseException(Path path, Throwable cause) {
        super("Failed to parse settings file " + path + ": " + cause.getMessage(), cause);
        this.path = path;
    }

    public Path path() {
        return path;
    }
}
