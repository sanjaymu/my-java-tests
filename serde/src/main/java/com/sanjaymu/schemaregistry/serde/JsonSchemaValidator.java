package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * A compiled JSON Schema. The draft is taken from the schema's {@code $schema} keyword,
 * defaulting to draft-07 (the registry's default for JSON schemas).
 */
public final class JsonSchemaValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String schemaString;
    private final JsonSchema compiled;

    private JsonSchemaValidator(String schemaString, JsonSchema compiled) {
        this.schemaString = schemaString;
        this.compiled = compiled;
    }

    public static JsonSchemaValidator compile(String schema) {
        JsonNode node;
        try {
            node = MAPPER.readTree(schema);
        } catch (JsonProcessingException e) {
            throw new SerdeException("Schema is not valid JSON: " + e.getOriginalMessage(), e);
        }
        SpecVersion.VersionFlag version = SpecVersionDetector.detectOptionalVersion(node, false)
                .orElse(SpecVersion.VersionFlag.V7);
        try {
            return new JsonSchemaValidator(schema, JsonSchemaFactory.getInstance(version).getSchema(node));
        } catch (RuntimeException e) {
            throw new SerdeException("Could not compile JSON schema: " + e.getMessage(), e);
        }
    }

    /** Returns validation errors, sorted for stable output; empty when the payload is valid. */
    public List<String> validate(JsonNode payload) {
        Set<ValidationMessage> messages = compiled.validate(payload);
        return messages.stream()
                .sorted(Comparator.comparing(ValidationMessage::getMessage))
                .map(ValidationMessage::getMessage)
                .toList();
    }

    /** Throws {@link SerdeException} if the payload does not conform. */
    public void validateOrThrow(JsonNode payload, String context) {
        List<String> errors = validate(payload);
        if (!errors.isEmpty()) {
            throw new SerdeException("Payload does not conform to " + context, errors);
        }
    }

    public String schema() {
        return schemaString;
    }
}
