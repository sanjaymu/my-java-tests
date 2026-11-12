package com.sanjaymu.schemaregistry.local;

/** An error that maps directly to a registry HTTP error response. */
final class RegistryError extends RuntimeException {
    final int httpStatus;
    final int errorCode;

    RegistryError(int httpStatus, int errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    static RegistryError subjectNotFound(String subject) {
        return new RegistryError(404, 40401, "Subject '" + subject + "' not found.");
    }

    static RegistryError versionNotFound(String subject, String version) {
        return new RegistryError(404, 40402, "Version " + version + " not found for subject '" + subject + "'.");
    }

    static RegistryError schemaNotFound() {
        return new RegistryError(404, 40403, "Schema not found");
    }

    static RegistryError invalidSchema(String detail) {
        return new RegistryError(422, 42201, "Invalid schema: " + detail);
    }

    static RegistryError invalidVersion(String version) {
        return new RegistryError(422, 42202,
                "The specified version '" + version + "' is not a valid version id. "
                        + "Allowed values are between [1, 2^31-1] and the string \"latest\"");
    }
}
