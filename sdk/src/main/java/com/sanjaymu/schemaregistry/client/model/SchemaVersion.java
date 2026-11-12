package com.sanjaymu.schemaregistry.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A schema registered under a subject at a specific version.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SchemaVersion(String subject,
                            Integer id,
                            Integer version,
                            String schemaType,
                            String schema,
                            List<SchemaReference> references) {

    public SchemaVersion {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public SchemaType type() {
        return SchemaType.parse(schemaType);
    }
}
