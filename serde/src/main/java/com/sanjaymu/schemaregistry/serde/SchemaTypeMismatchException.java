package com.sanjaymu.schemaregistry.serde;

/** Thrown when a Java model does not match a registered JSON schema. */
public class SchemaTypeMismatchException extends SerdeException {

    private final TypeCheckReport report;

    public SchemaTypeMismatchException(String message, TypeCheckReport report) {
        super(message + System.lineSeparator() + report.format());
        this.report = report;
    }

    public TypeCheckReport report() {
        return report;
    }
}
