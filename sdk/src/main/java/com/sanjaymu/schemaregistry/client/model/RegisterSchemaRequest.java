package com.sanjaymu.schemaregistry.client.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Objects;

/**
 * Request body used to register, look up or test the compatibility of a schema.
 *
 * @param schema     the schema definition as a string
 * @param schemaType the schema type; {@code null} means AVRO
 * @param references references to other schemas (may be empty)
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record RegisterSchemaRequest(String schema, String schemaType, List<SchemaReference> references) {

    public RegisterSchemaRequest {
        Objects.requireNonNull(schema, "schema must not be null");
        references = references == null ? List.of() : List.copyOf(references);
        // The registry omits schemaType for AVRO; mirror that on the wire.
        if (schemaType != null && SchemaType.parse(schemaType) == SchemaType.AVRO) {
            schemaType = null;
        }
    }

    public static RegisterSchemaRequest avro(String schema) {
        return new RegisterSchemaRequest(schema, null, List.of());
    }

    public static RegisterSchemaRequest of(String schema, SchemaType type) {
        return new RegisterSchemaRequest(schema, type.name(), List.of());
    }

    public static RegisterSchemaRequest of(String schema, SchemaType type, List<SchemaReference> references) {
        return new RegisterSchemaRequest(schema, type.name(), references);
    }

    public SchemaType type() {
        return SchemaType.parse(schemaType);
    }
}
