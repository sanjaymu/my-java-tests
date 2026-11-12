package com.sanjaymu.schemaregistry.serde;

import java.util.List;

/**
 * Thrown when a payload cannot be serialized or deserialized: malformed bytes, unknown schema,
 * or a payload/object that violates its Java contract — missing required properties, wrong types or
 * Bean Validation constraints ({@link #validationErrors()} is then non-empty).
 */
public class SerdeException extends RuntimeException {

    private final List<String> validationErrors;

    public SerdeException(String message) {
        this(message, List.of(), null);
    }

    public SerdeException(String message, Throwable cause) {
        this(message, List.of(), cause);
    }

    public SerdeException(String message, List<String> validationErrors) {
        this(message, validationErrors, null);
    }

    private SerdeException(String message, List<String> validationErrors, Throwable cause) {
        super(validationErrors.isEmpty() ? message : message + ": " + String.join("; ", validationErrors), cause);
        this.validationErrors = List.copyOf(validationErrors);
    }

    /** Contract violations as {@code $.path: message}; empty for other kinds of errors. */
    public List<String> validationErrors() {
        return validationErrors;
    }

    public boolean isValidationFailure() {
        return !validationErrors.isEmpty();
    }
}
