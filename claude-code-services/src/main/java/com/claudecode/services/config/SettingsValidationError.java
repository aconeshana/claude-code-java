package com.claudecode.services.config;

/**
 * One diagnostic produced while validating a settings source.
 */
public record SettingsValidationError(String file, String path, String message) {}
