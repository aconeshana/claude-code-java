package com.claudecode.tools;

/**
 * Result of {@link Tool#validateInput}.
 */
public sealed interface ValidationResult {

    record Valid() implements ValidationResult {}

    record Invalid(String message) implements ValidationResult {}

    ValidationResult VALID = new Valid();

    static ValidationResult valid() {
        return VALID;
    }

    static ValidationResult invalid(String message) {
        return new Invalid(message);
    }
}
