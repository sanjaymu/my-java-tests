package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.SchemaRegistryException;
import com.sanjaymu.schemaregistry.client.model.Schema;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SubjectVersion;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer side: reads the schema id from the message header, fetches that schema from the registry
 * (cached per id), validates the payload against it and maps it to an object.
 * <p>
 * Producers on different schema versions (v1, v2, …) can write to the same consumer: each message is
 * validated against the exact schema it was written with, then mapped to the consumer's class.
 * Unknown properties are ignored by default, so a v1 model can read v2 payloads that add fields.
 *
 * <pre>{@code
 * JsonSchemaDeserializer deserializer = JsonSchemaDeserializer.builder(client)
 *         .expectedSubject("orders-value")   // reject payloads written with another subject's schema
 *         .build();
 * Order order = deserializer.deserialize(message, Order.class);
 * }</pre>
 *
 * Thread-safe.
 */
public final class JsonSchemaDeserializer {

    private final SchemaRegistryClient client;
    private final ObjectMapper mapper;
    private final boolean validate;
    private final String expectedSubject;
    private final Map<Integer, JsonSchemaValidator> validators = new ConcurrentHashMap<>();
    private final Map<Integer, SubjectVersion> subjectVersions = new ConcurrentHashMap<>();

    private JsonSchemaDeserializer(Builder b) {
        this.client = b.client;
        this.mapper = b.mapper;
        this.validate = b.validate;
        this.expectedSubject = b.expectedSubject;
    }

    public static Builder builder(SchemaRegistryClient client) {
        return new Builder(client);
    }

    /** Deserializes into {@code type} (a POJO, record, {@code Map} or {@link JsonNode}). */
    public <T> T deserialize(byte[] message, Class<T> type) {
        return deserializeWithMetadata(message, type).value();
    }

    /** Deserializes and also returns the schema id, subject and version the payload was written with. */
    public <T> DeserializedMessage<T> deserializeWithMetadata(byte[] message, Class<T> type) {
        WireFormat.Framed framed = WireFormat.decode(message);
        int id = framed.schemaId();
        JsonNode node;
        try {
            node = mapper.readTree(framed.payload());
        } catch (IOException e) {
            throw new SerdeException("Payload for schema id " + id + " is not valid JSON", e);
        }
        SubjectVersion sv = expectedSubject != null ? subjectVersionFor(id) : null;
        if (validate) {
            validatorFor(id).validateOrThrow(node, "schema id " + id
                    + (sv != null ? " (" + sv.subject() + " v" + sv.version() + ")" : ""));
        }
        T value;
        try {
            value = type == JsonNode.class ? type.cast(node) : mapper.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new SerdeException("Could not map payload to " + type.getName() + ": " + e.getOriginalMessage(), e);
        }
        return new DeserializedMessage<>(id, sv == null ? null : sv.subject(), sv == null ? null : sv.version(), value);
    }

    private JsonSchemaValidator validatorFor(int id) {
        return validators.computeIfAbsent(id, key -> {
            Schema schema;
            try {
                schema = client.getSchemaById(key);
            } catch (SchemaRegistryException e) {
                throw new SerdeException("Could not fetch schema id " + key + " from the registry: " + e.getMessage(), e);
            }
            if (schema.type() != SchemaType.JSON) {
                throw new SerdeException("Schema id " + key + " is " + schema.type() + ", expected JSON");
            }
            return JsonSchemaValidator.compile(schema.schema());
        });
    }

    private SubjectVersion subjectVersionFor(int id) {
        return subjectVersions.computeIfAbsent(id, key -> {
            List<SubjectVersion> usages;
            try {
                usages = client.getSubjectVersionsById(key);
            } catch (SchemaRegistryException e) {
                throw new SerdeException("Could not resolve schema id " + key + ": " + e.getMessage(), e);
            }
            return usages.stream()
                    .filter(u -> u.subject().equals(expectedSubject))
                    .reduce((a, b) -> b) // latest version if registered more than once
                    .orElseThrow(() -> new SerdeException("Schema id " + key + " is not registered under subject '"
                            + expectedSubject + "' (found: " + usages + ")"));
        });
    }

    public static final class Builder {
        private final SchemaRegistryClient client;
        private ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        private boolean validate = true;
        private String expectedSubject;

        private Builder(SchemaRegistryClient client) {
            this.client = Objects.requireNonNull(client, "client is required");
        }

        /** Validate payloads against their schema (default {@code true}). */
        public Builder validate(boolean validate) {
            this.validate = validate;
            return this;
        }

        /**
         * Only accept payloads whose schema id is registered under this subject, and report the
         * subject version in {@link DeserializedMessage}.
         */
        public Builder expectedSubject(String subject) {
            this.expectedSubject = subject;
            return this;
        }

        /** Jackson mapper used to map JSON to objects. Unknown properties are ignored by the default mapper. */
        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper);
            return this;
        }

        public JsonSchemaDeserializer build() {
            return new JsonSchemaDeserializer(this);
        }
    }
}
