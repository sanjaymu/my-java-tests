package com.sanjaymu.schemaregistry.client.model;

/** Compatibility levels supported by the registry. */
public enum CompatibilityLevel {
    NONE,
    BACKWARD,
    BACKWARD_TRANSITIVE,
    FORWARD,
    FORWARD_TRANSITIVE,
    FULL,
    FULL_TRANSITIVE;

    public boolean isBackward() {
        return this == BACKWARD || this == BACKWARD_TRANSITIVE || isFull();
    }

    public boolean isForward() {
        return this == FORWARD || this == FORWARD_TRANSITIVE || isFull();
    }

    public boolean isFull() {
        return this == FULL || this == FULL_TRANSITIVE;
    }

    public boolean isTransitive() {
        return name().endsWith("_TRANSITIVE");
    }

    public static CompatibilityLevel parse(String value) {
        return CompatibilityLevel.valueOf(value.trim().toUpperCase());
    }
}
