package com.claudecode.tools.validation;

import com.claudecode.core.validation.JsonSchemaValidator;

/** Compatibility facade for the validator shared by tools and the public SDK. */
public class SchemaValidator extends JsonSchemaValidator {
    public static SchemaValidator shared() {
        return SharedHolder.INSTANCE;
    }

    private static final class SharedHolder {
        private static final SchemaValidator INSTANCE = new SchemaValidator();
    }
}
