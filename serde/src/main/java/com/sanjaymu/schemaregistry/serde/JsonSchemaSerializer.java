package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.model.RegisterSchemaRequest;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Producer side: writes Java model objects as JSON prefixed with their schema id ({@link WireFormat}).
 * <p>
 * The schema is enforced in Java code, not by validating JSON against a schema:
 * <ul>
 *   <li>the object's class must match the registered schema (checked once per class with
 *       {@link SchemaTypeChecker}; with {@link Builder#forType} the schema is generated from the class);</li>
 *   <li>the object must satisfy its Jakarta Bean Validation constraints and required properties
 *       ({@link JavaContract}).</li>
 * </ul>
 * Untyped values ({@code Map}, {@code JsonNode}, strings, numbers, collections) are rejected because they carry no
 * contract.
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
 * Alternatively write with an already-registered version via {@link Builder#useVersion}/{@link Builder#useLatestVersion},
 * or a hand-written schema via {@link Builder#schema}; the object's class is then checked against that schema.
 * Thread-safe.
 */
public final class JsonSchemaSerializer {

    private final SchemaRegistryClient client;
    private final ObjectMapper mapper;
    private final JavaContract contract;
    private final SchemaTypeChecker typeChecker;
    private final String subject;
    private final String schema;
    private final Class<?> type;
    private final boolean autoRegister;
    private final String version;
    private final boolean validate;
    private final Map<Class<?>, TypeCheckReport> checkedClasses = new ConcurrentHashMap<>();

    private volatile Resolved resolved;

    private record Resolved(int schemaId, int version, String schema) {
    }

    private JsonSchemaSerializer(Builder b) {
        this.client = b.client;
        this.mapper = b.mapper;
        this.contract = new JavaContract(mapper);
        this.typeChecker = new SchemaTypeChecker(mapper);
        this.subject = Objects.requireNonNull(b.subject, "subject is required");
        if (b.type != null && b.schema != null) {
            throw new IllegalArgumentException("forType(...) and schema(...) are mutually exclusive");
        }
        this.type = b.type;
        this.schema = b.type != null ? JavaTypeSchemaGenerator.generate(b.type) : b.schema;
        this.autoRegister = b.autoRegister;
        this.version = b.version;
        this.validate = b.validate;
        if (schema == null && version == null) {
            throw new IllegalArgumentException("Provide forType(...), schema(...) or useVersion(...)/useLatestVersion()");
        }
        if (schema != null && version != null) {
            throw new IllegalArgumentException("forType(...)/schema(...) and useVersion(...) are mutually exclusive");
        }
    }

    public static Builder builder(SchemaRegistryClient client) {
        return new Builder(client);
    }

    /**
     * Serializes a Java model object into a framed message.
     *
     * @throws SerdeException              if the object violates its constraints ({@link SerdeException#validationErrors()})
     * @throws SchemaTypeMismatchException if the object's class does not match the registered schema
     * @throws IllegalArgumentException    for untyped values or objects of the wrong class
     */
    public byte[] serialize(Object value) {
        Objects.requireNonNull(value, "value must not be null");
        if (value instanceof JsonNode || value instanceof Map || value instanceof Collection
                || value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            throw new IllegalArgumentException("Serializer requires a Java model object so the schema can be enforced "
                    + "in code; got " + value.getClass().getName());
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalArgumentException("This serializer writes " + type.getName() + " but got "
                    + value.getClass().getName());
        }
        Resolved r = resolve();
        TypeCheckReport report = checkedClasses.computeIfAbsent(value.getClass(),
                c -> typeChecker.check(r.schema(), c));
        if (report.hasErrors()) {
            throw new SchemaTypeMismatchException(value.getClass().getName() + " does not match schema id "
                    + r.schemaId() + " (" + subject + " v" + r.version() + ")", report);
        }
        if (validate) {
            contract.validateOrThrow(value);
        }
        byte[] json;
        try {
            json = mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new SerdeException("Could not write JSON payload", e);
        }
        return WireFormat.encode(r.schemaId(), json);
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
                resolved = new Resolved(sv.id(), sv.version(), sv.schema());
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

        /**
         * Generates the schema from a Java class (see {@link JavaTypeSchemaGenerator}), so the registered schema
         * always matches the producer's model. Only instances of this type are accepted by {@code serialize}.
         */
        public Builder forType(Class<?> type) {
            this.type = Objects.requireNonNull(type);
            return this;
        }

        /** A hand-written JSON schema. Objects are checked against it with {@link SchemaTypeChecker}. */
        public Builder schema(String schema) {
            this.schema = schema;
            return this;
        }

        /** Register the schema if it is not registered yet (default {@code false}). */
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

        /** Enforce Bean Validation constraints before sending (default {@code true}). */
        public Builder validate(boolean validate) {
            this.validate = validate;
            return this;
        }

        /** Jackson mapper used to write objects. Defaults to {@link SerdeMappers#defaultMapper()}. */
        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper);
            return this;
        }

        public JsonSchemaSerializer build() {
            return new JsonSchemaSerializer(this);
        }
    }
}
