package com.sanjaymu.schemaregistry.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A schema as returned by {@code GET /schemas/ids/{id}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Schema(String schema, String schemaType, List<SchemaReference> references) {

    public Schema {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public SchemaType type() {
        return SchemaType.parse(schemaType);
    }
}
