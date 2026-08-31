package com.claudecode.api;

import com.claudecode.core.annotation.Explanation;

/** Failure to read or persist the custom-model catalogue. */
@Explanation("Safe model.json persistence failure")
public class CustomModelConfigException extends RuntimeException {
    public CustomModelConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
