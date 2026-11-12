package com.sanjaymu.schemaregistry.serde;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanjaymu.schemaregistry.client.SchemaRegistryClient;
import com.sanjaymu.schemaregistry.client.SchemaRegistryException;
import com.sanjaymu.schemaregistry.client.model.Schema;
import com.sanjaymu.schemaregistry.client.model.SchemaType;
import com.sanjaymu.schemaregistry.client.model.SchemaVersion;
import com.sanjaymu.schemaregistry.client.model.SubjectVersion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Consumer side: reads the schema id from the message header, resolves the schema in the registry and maps the
 * payload into a Java model — enforcing the schema in code rather than with a JSON Schema validator:
 * <ul>
 *   <li><b>payload → model</b>: required properties must be present, JSON is mapped strictly into the Java types,
 *       and Jakarta Bean Validation constraints are checked ({@link JavaContract});</li>
 *   <li><b>model ↔ registered schema</b>: with {@link Builder#targetType}, the class is checked against every
 *       registered version of {@link Builder#expectedSubject} at {@code build()}, and against each new schema id
 *       the first time it arrives ({@link SchemaTypeChecker}). A mismatch throws
 *       {@link SchemaTypeMismatchException} instead of silently losing data.</li>
 * </ul>
 * Producers on different schema versions (v1, v2, …) can write to the same consumer. Unknown properties are
 * ignored, so an older model can read newer payloads that add optional fields.
 *
 * <pre>{@code
 * JsonSchemaDeserializer deserializer = JsonSchemaDeserializer.builder(client)
 *         .expectedSubject("orders-value")   // reject payloads written with another subject's schema
 *         .targetType(Order.class)            // fail at startup if Order doesn't match the schemas
 *         .build();
 * Order order = deserializer.deserialize(message, Order.class);
 * }</pre>
 *
 * Deserializing to {@link JsonNode}/{@code Map} returns the raw payload without enforcement. Thread-safe.
 */
public final class JsonSchemaDeserializer {

    private final SchemaRegistryClient client;
    private final ObjectMapper mapper;
    private final JavaContract contract;
    private final boolean validate;
    private final String expectedSubject;
    private final Class<?> targetType;
    private final boolean failOnTypeWarnings;
    private final SchemaTypeChecker typeChecker;
    private final Map<Integer, Schema> schemas = new ConcurrentHashMap<>();
    private final Map<Integer, SubjectVersion> subjectVersions = new ConcurrentHashMap<>();
    private final Map<Integer, TypeCheckReport> typeReportsById = new ConcurrentHashMap<>();
    private final Map<Integer, TypeCheckReport> startupReports;

    private JsonSchemaDeserializer(Builder b) {
        this.client = b.client;
        this.mapper = b.mapper;
        this.contract = new JavaContract(mapper);
        this.validate = b.validate;
        this.expectedSubject = b.expectedSubject;
        this.targetType = b.targetType;
        this.failOnTypeWarnings = b.failOnTypeWarnings;
        this.typeChecker = new SchemaTypeChecker(mapper);
        this.startupReports = targetType != null && expectedSubject != null ? checkAllVersions() : Map.of();
    }

    public static Builder builder(SchemaRegistryClient client) {
        return new Builder(client);
    }

    /** Deserializes into {@code type}, enforcing its contract (see class docs). */
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
        Schema schema = schemaFor(id);
        SubjectVersion sv = expectedSubject != null ? subjectVersionFor(id) : null;
        if (targetType != null && type == targetType) {
            checkType(id, schema, type);
        }
        T value;
        if (JavaContract.isUntyped(type)) {
            value = type.isInstance(node) ? type.cast(node) : mapper.convertValue(node, type);
        } else if (validate) {
            value = contract.read(node, type);
        } else {
            try {
                value = mapper.treeToValue(node, type);
            } catch (IOException e) {
                throw new SerdeException("Could not map payload to " + type.getName() + ": " + e.getMessage(), e);
            }
        }
        return new DeserializedMessage<>(id, sv == null ? null : sv.subject(), sv == null ? null : sv.version(), value);
    }

    /**
     * Type-check results per subject version from {@code build()} (empty unless both
     * {@code targetType} and {@code expectedSubject} are set). Useful for logging warnings.
     */
    public Map<Integer, TypeCheckReport> startupTypeReports() {
        return startupReports;
    }

    private Map<Integer, TypeCheckReport> checkAllVersions() {
        List<Integer> versions;
        try {
            versions = client.getVersions(expectedSubject);
        } catch (SchemaRegistryException e) {
            if (e.errorCode() == SchemaRegistryException.SUBJECT_NOT_FOUND) {
                return Map.of(); // nothing registered yet; schemas are checked as they arrive
            }
            throw new SerdeException("Could not list versions of '" + expectedSubject + "': " + e.getMessage(), e);
        }
        Map<Integer, TypeCheckReport> reports = new TreeMap<>();
        StringBuilder failures = new StringBuilder();
        List<String> allErrors = new ArrayList<>();
        List<String> allWarnings = new ArrayList<>();
        for (int version : versions) {
            SchemaVersion sv = client.getSchemaVersion(expectedSubject, version);
            if (sv.type() != SchemaType.JSON) {
                continue;
            }
            TypeCheckReport report = typeChecker.check(sv.schema(), targetType);
            reports.put(version, report);
            typeReportsById.put(sv.id(), report);
            if (fails(report)) {
                failures.append(failures.isEmpty() ? "" : ", ").append("v").append(version);
                report.errors().forEach(e -> allErrors.add("v" + version + " " + e));
                report.warnings().forEach(w -> allWarnings.add("v" + version + " " + w));
            }
        }
        if (!failures.isEmpty()) {
            throw new SchemaTypeMismatchException(targetType.getName() + " does not match subject '" + expectedSubject
                    + "' " + failures, new TypeCheckReport(allErrors, allWarnings));
        }
        return Collections.unmodifiableMap(reports);
    }

    private boolean fails(TypeCheckReport report) {
        return report.hasErrors() || (failOnTypeWarnings && !report.warnings().isEmpty());
    }

    private void checkType(int id, Schema schema, Class<?> type) {
        TypeCheckReport report = typeReportsById.computeIfAbsent(id, key -> typeChecker.check(schema.schema(), targetType));
        if (fails(report)) {
            throw new SchemaTypeMismatchException(type.getName() + " does not match schema id " + id, report);
        }
    }

    private Schema schemaFor(int id) {
        return schemas.computeIfAbsent(id, key -> {
            Schema schema;
            try {
                schema = client.getSchemaById(key);
            } catch (SchemaRegistryException e) {
                throw new SerdeException("Could not fetch schema id " + key + " from the registry: " + e.getMessage(), e);
            }
            if (schema.type() != SchemaType.JSON) {
                throw new SerdeException("Schema id " + key + " is " + schema.type() + ", expected JSON");
            }
            return schema;
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
        private ObjectMapper mapper = SerdeMappers.defaultMapper();
        private boolean validate = true;
        private String expectedSubject;
        private Class<?> targetType;
        private boolean failOnTypeWarnings;

        private Builder(SchemaRegistryClient client) {
            this.client = Objects.requireNonNull(client, "client is required");
        }

        /** Enforce required properties and Bean Validation constraints on read (default {@code true}). */
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

        /**
         * The consumer's Java model. It is checked against the schemas of {@link #expectedSubject} when the
         * deserializer is built, and against each new schema id on first use when deserializing to this type.
         */
        public Builder targetType(Class<?> type) {
            this.targetType = Objects.requireNonNull(type);
            return this;
        }

        /** Treat type-check warnings (e.g. optional schema properties the class lacks) as failures. */
        public Builder failOnTypeWarnings(boolean fail) {
            this.failOnTypeWarnings = fail;
            return this;
        }

        /** Jackson mapper used to map JSON to objects. Defaults to {@link SerdeMappers#defaultMapper()}. */
        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = Objects.requireNonNull(mapper);
            return this;
        }

        public JsonSchemaDeserializer build() {
            return new JsonSchemaDeserializer(this);
        }
    }
}
