package com.sanjaymu.schemaregistry.client.model;

/** Schema formats understood by Confluent-compatible registries. */
public enum SchemaType {
    AVRO, JSON, PROTOBUF;

    /** Parses a schema type, treating {@code null}/blank as the registry default (AVRO). */
    public static SchemaType parse(String value) {
        if (value == null || value.isBlank()) {
            return AVRO;
        }
        return SchemaType.valueOf(value.trim().toUpperCase());
    }
}
