package com.sanjaymu.schemaregistry.client;

/**
 * Thrown when the registry returns an error response or cannot be reached.
 * <p>
 * {@link #httpStatus()} is the HTTP status (or {@code -1} for transport failures) and
 * {@link #errorCode()} is the registry-specific error code such as {@code 40401}.
 */
public class SchemaRegistryException extends RuntimeException {

    /** Common registry error codes. */
    public static final int SUBJECT_NOT_FOUND = 40401;
    public static final int VERSION_NOT_FOUND = 40402;
    public static final int SCHEMA_NOT_FOUND = 40403;
    public static final int SUBJECT_SOFT_DELETED = 40404;
    public static final int SUBJECT_NOT_SOFT_DELETED = 40405;
    public static final int VERSION_SOFT_DELETED = 40406;
    public static final int VERSION_NOT_SOFT_DELETED = 40407;
    public static final int SUBJECT_LEVEL_COMPATIBILITY_NOT_CONFIGURED = 40408;
    public static final int SUBJECT_LEVEL_MODE_NOT_CONFIGURED = 40409;
    public static final int INCOMPATIBLE_SCHEMA = 409;
    public static final int INVALID_SCHEMA = 42201;
    public static final int INVALID_VERSION = 42202;
    public static final int INVALID_COMPATIBILITY_LEVEL = 42203;
    public static final int INVALID_MODE = 42204;
    public static final int OPERATION_NOT_PERMITTED = 42205;
    public static final int INTERNAL_ERROR = 50001;

    private final int httpStatus;
    private final int errorCode;

    public SchemaRegistryException(int httpStatus, int errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public SchemaRegistryException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
        this.errorCode = -1;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public int errorCode() {
        return errorCode;
    }

    public boolean isNotFound() {
        return httpStatus == 404;
    }

    @Override
    public String toString() {
        if (httpStatus < 0) {
            return "SchemaRegistryException: " + getMessage();
        }
        return "SchemaRegistryException[http=" + httpStatus + ", code=" + errorCode + "]: " + getMessage();
    }
}
