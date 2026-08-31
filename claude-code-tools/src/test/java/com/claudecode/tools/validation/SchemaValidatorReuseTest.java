package com.claudecode.tools.validation;

import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class SchemaValidatorReuseTest {

    @Test
    void productionValidatorAndSuccessfulResultAreCanonical() {
        assertSame(SchemaValidator.shared(), SchemaValidator.shared());
        assertSame(SchemaValidator.ValidationResult.ok(),
            SchemaValidator.ValidationResult.ok());
    }
}
