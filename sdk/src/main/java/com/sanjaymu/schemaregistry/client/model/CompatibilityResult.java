package com.sanjaymu.schemaregistry.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Result of a compatibility test. {@code messages} is populated when verbose output was requested. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record CompatibilityResult(@JsonProperty("is_compatible") boolean compatible, List<String> messages) {

    public CompatibilityResult {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
