package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Producer side: converts objects to JSON, validates them against the subject's JSON schema and
 * prefixes the schema id ({@link WireFormat}).
 *
 * <pre>{@code
 * JsonSchemaSerializer serializer = JsonSchemaSerializer.builder(client)
 *         .subject("orders-value")
 *         .forType(Order.class)      // schema generated from the Java model
 *         .autoRegister(true)        // register it if needed (otherwise it must already exist)
 *         .build();
 * byte[] message = serializer.serialize(order);
 * }</pre>
 *
 * Instead of {@link Builder#forType}, a hand-written schema can be supplied with {@link Builder#schema}, or an
 * already-registered version selected with {@link Builder#useVersion}/{@link Builder#useLatestVersion}.
 *
 * The schema id is resolved once, on first use, and then reused. Thread-safe.
 */
public final class JsonSchemaSerializer {

    private final SchemaRegistryClient client;
    private final ObjectMapper mapper;
    private final String subject;
    private final String schema;
    private final Class<?> type;
    private final boolean autoRegister;
    private final String version;
    private final boolean validate;

    private volatile Resolved resolved;

    private record Resolved(int schemaId, int version, JsonSchemaValidator validator) {
    }

    private JsonSchemaSerializer(Builder b) {
        this.client = b.client;
        this.mapper = b.mapper;
        this.subject = Objects.requireNonNull(b.subject, "subject is required");
        this.type = b.type;
        this.schema = b.type != null ? JavaTypeSchemaGenerator.generate(b.type) : b.schema;
        if (b.type != null && b.schema != null) {
            throw new IllegalArgumentException("forType(...) and schema(...) are mutually exclusive");
        }
        this.autoRegister = b.autoRegister;
        this.version = b.version;
        this.validate = b.validate;
        if (schema == null && version == null) {
            throw new IllegalArgumentException("Provide forType(...), schema(...) or useVersion(...)/useLatestVersion()");
        }
        if (schema != null && version != null) {
            throw new IllegalArgumentException("schema(...) and useVersion(...) are mutually exclusive");
        }
    }

    public static Builder builder(SchemaRegistryClient client) {
        return new Builder(client);
    }

    /** Serializes an object (POJO, record, Map or {@link JsonNode}) into a framed, validated message. */
    public byte[] serialize(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        if (type != null && !type.isInstance(value) && !(value instanceof JsonNode)) {
            throw new IllegalArgumentException("This serializer writes " + type.getName() + " but got "
                    + value.getClass().getName());
        }
        JsonNode node = value instanceof JsonNode n ? n : mapper.valueToTree(value);
        Resolved r = resolve();
        if (validate) {
            r.validator().validateOrThrow(node, "schema id " + r.schemaId() + " (" + subject + " v" + r.version() + ")");
        }
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new SerdeException("Could not write JSON payload", e);
        }
        return WireFormat.encode(r.schemaId(), json);
    }

    /** Serializes raw JSON text (validated like any other value). */
    public byte[] serializeJson(String json) {
        try {
            return serialize(mapper.readTree(json));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SerdeException("Payload is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /** The JSON schema this serializer registers/looks up ({@code null} when using an existing version). */
    public String schema() {
        return schema;
    }

    /** The schema id used in message headers (resolving it if necessary). */
    public int schemaId() {
        return resolve().schemaId();
    }

    /** The subject version the serializer writes with. */
    public int schemaVersion() {
        return resolve().version();
    }

    private Resolved resolve() {
        Resolved r = resolved;
        if (r != null) {
            return r;
        }
        synchronized (this) {
            if (resolved == null) {
                SchemaVersion sv;
                if (schema != null) {
                    RegisterSchemaRequest req = RegisterSchemaRequest.of(schema, SchemaType.JSON);
                    if (autoRegister) {
                        client.register(subject, req);
                    }
                    sv = client.lookup(subject, req);
                } else {
                    sv = client.getSchemaVersion(subject, version);
                }
                if (sv.type() != SchemaType.JSON) {
                    throw new SerdeException("Subject '" + subject + "' version " + sv.version() + " is "
                            + sv.type() + ", expected JSON");
                }
                resolved = new Resolved(sv.id(), sv.version(), JsonSchemaValidator.compile(sv.schema()));
            }
            return resolved;
        }
    }

    public static final class Builder {
        private final SchemaRegistryClient client;
        private ObjectMapper mapper = SerdeMappers.defaultMapper();
        private String subject;
        private String schema;
        private Class<?> type;
        private boolean autoRegister;
        private String version;
        private boolean validate = true;

        private Builder(SchemaRegistryClient client) {
            this.client = Objects.requireNonNull(client, "client is required");
        }

        /** Subject to write under, e.g. {@code orders-value}. */
        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        /** The producer's JSON schema. It is looked up (or registered, see {@link #autoRegister}) on first use. */
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        /**
         * Generates the schema from a Java class (see {@link JavaTypeSchemaGenerator}), so the registered schema
         * always matches the producer's model. Only instances of this type are accepted by {@code serialize}.
         */
        public Builder forType(Class<?> type) {
            this.type = Objects.requireNonNull(type);
            return this;
        }

        /** Register {@link #schema} if it is not registered yet (default {@code false}). */
        public Builder autoRegister(boolean autoRegister) {
            this.autoRegister = autoRegister;
            return this;
        }

        /** Write with an already-registered version instead of supplying a schema. */
        public Builder useVersion(int version) {
            this.version = Integer.toString(version);
            return this;
        }

        /** Write with the subject's latest version instead of supplying a schema. */
        public Builder useLatestVersion() {
            this.version = SchemaRegistryClient.LATEST;
            return this;
        }

        /** Validate payloads before sending (default {@code true}). */
        public Builder validate(boolean validate) {
            this.validate = validate;
            return this;
        }

        /** Jackson mapper used to convert objects to JSON. */
        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper);
            return this;
        }

        public JsonSchemaSerializer build() {
            return new JsonSchemaSerializer(this);
        }
    }
}
