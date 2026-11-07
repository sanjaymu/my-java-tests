package com.sanjaymu.schemaregistry.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Error body returned by the registry, e.g. {@code {"error_code":40401,"message":"Subject not found."}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorMessage(@JsonProperty("error_code") int errorCode, String message) {
}
