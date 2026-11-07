package com.sanjaymu.schemaregistry.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A (subject, version) pair, e.g. as returned by {@code GET /schemas/ids/{id}/versions}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubjectVersion(String subject, int version) {
}
