package com.sanjaymu.schemaregistry.serde;

import java.util.List;

/**
 * Result of comparing a Java type with a JSON schema.
 *
 * @param errors   mismatches that lose or corrupt data (e.g. a required schema property missing from the
 *                 class, or {@code number} mapped to {@code int}); a consumer should refuse to start
 * @param warnings differences that are usually expected during schema evolution (e.g. an optional property the
 *                 class does not have yet, which is ignored when deserializing)
 */
public record TypeCheckReport(List<String> errors, List<String> warnings) {

    public TypeCheckReport {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public boolean isClean() {
        return errors.isEmpty() && warnings.isEmpty();
    }

    /** Human-readable multi-line summary. */
    public String format() {
        StringBuilder sb = new StringBuilder();
        errors.forEach(e -> sb.append("  ERROR   ").append(e).append(System.lineSeparator()));
        warnings.forEach(w -> sb.append("  WARNING ").append(w).append(System.lineSeparator()));
        return sb.toString().stripTrailing();
    }
}
